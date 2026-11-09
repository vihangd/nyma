(ns agent.utils.decide
  "A cheap judge for a permission ask nyma would otherwise put to the user:
   TypeSafe's Jev, asked one choice question, answering with probabilities.

   Used narrowly, because the evidence says so:
   - It only turns an ask into an allow, at a high threshold (0.9). Any doubt,
     error or timeout still asks; a deny never comes from here.
   - It sees what the user asked for and the call itself, never tool output:
     adversarial text in its input shifts its answers (TypeSafe's own notes).
   - The allow option is offered LAST: Jev leans toward the first.
   - Every verdict is logged with the user's eventual answer, in
     ~/.nyma/decisions.jsonl, so `/decisions` can show whether the threshold
     holds on this user's own traffic. No published calibration exists.

   Settings (global only): `judge` {model threshold timeout-ms}; key from
   TYPESAFE_API_KEY or `typesafe` in credentials.json."
  (:require ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.debug :as d]
            [agent.utils.content :as content]
            [agent.utils.credentials :as creds]
            [agent.utils.home :as home]))

(defn log-path [] (path/join (home/dir) ".nyma" "decisions.jsonl"))

;; The user's `judge` settings, or nil when it is off; set once by cli.
(defonce ^:private judge-cfg (atom nil))

(defn configure!
  "Take the judge settings from the user's GLOBAL settings map: a cloned repo
   must not switch on what approves its own commands.
   `bash-suite.permissions.judge`, where it first lived, still counts."
  [global]
  (reset! judge-cfg (or (:judge global) (get-in global [:bash-suite :permissions :judge]))))

(defn config [] @judge-cfg)

(defn user-request
  "The text of the latest user message in `messages`, cut to 2000 characters:
   all the judge is told of what the user wants."
  [messages]
  (let [t (some->> messages vec reverse (filter #(= "user" (:role %))) first :content content/->text)]
    (subs (str t) 0 (min 2000 (count (str t))))))

(defn judge-state
  "What Jev sees: the request and the proposed action, never tool output."
  [request action]
  (str "The user asked the coding agent:\n" request "\n\nThe agent wants to " action))

(defn subject-of
  "What identifies one call across its verdict and the user's answer."
  [tool-name args]
  (if (= "bash" (str tool-name))
    (str (get args :command))
    (str tool-name " " (js/JSON.stringify (clj->js args)))))

(defn subject-hash
  "What the log keeps of a call: a hash, so commands and args never sit on disk."
  [s]
  (subs (.digest (.update (crypto/createHash "sha256") (str s)) "hex") 0 16))

(defn- log! [m]
  (try
    (let [p (log-path)]
      (fs/mkdirSync (path/dirname p) #js {:recursive true})
      (fs/appendFileSync p (str (js/JSON.stringify (clj->js (assoc m :ts (js/Date.now)))) "\n")
                         #js {:mode 384})
      (fs/chmodSync p 384))
    (catch :default _ nil)))

;; Per session: subject → logged verdict awaiting the user's answer; and a
;; breaker per kind, so a failing skill check cannot switch off the permission
;; judge. ponytail: process-wide, one session per process.
(defonce ^:private pending (atom {}))
(defonce ^:private breaker (atom {}))

(def max-errors 3)
(def max-asks 20)
(def default-model "jev-1.14.0")

(defn paused?
  "After 3 errors in a row the service is down; after 20 asks it did not
   avert it is not earning its latency. Either way: `kind` is off for the
   session."
  [kind]
  (let [{:keys [errors asks]} (get @breaker kind)]
    (or (>= (or errors 0) max-errors) (>= (or asks 0) max-asks))))

(defn reset-session! []
  (reset! pending {})
  (reset! breaker {}))

(defn- failed! [kind ok?]
  (if ok?
    (swap! breaker assoc-in [kind :errors] 0)
    (swap! breaker update-in [kind :errors] (fnil inc 0))))

(defn request
  "Jev request body: `questions` about `state`."
  [state questions model]
  {:model (or model default-model) :state state :questions questions})

(defn ^:async ask-jev
  "Jev's `answers` object for `questions` about `state`, or nil on any failure."
  [state questions {:keys [model timeout-ms]}]
  (if-let [key (or (.. js/process -env -TYPESAFE_API_KEY) (creds/read-credential "typesafe"))]
    (try
      (let [r (js-await (js/fetch "https://api.typesafe.ai/v1/systemone"
                                  #js {:method  "POST"
                                       :headers #js {:Authorization (str "Bearer " key)
                                                     :Content-Type  "application/json"}
                                       :body    (js/JSON.stringify (clj->js (request state questions model)))
                                       :signal  (js/AbortSignal.timeout (or timeout-ms 1500))}))]
        (if (.-ok r)
          (some-> (js-await (.json r)) .-answers)
          (do (d/warn "judge" (str "TypeSafe answered " (.-status r) "; asking instead")) nil)))
      (catch :default e
        (d/warn "judge" (str "TypeSafe unreachable (" (.-message e) "); asking instead"))
        nil))
    nil))

(defn ^:async ask!
  "Probability of yes for each yes/no question in `questions`
   ({id {:instructions … :criteria {:true … :false …}}}) about `state`, as
   {id p}; nil when `kind` is paused or Jev fails. Each p is logged, hashed,
   under `kind`."
  [{:keys [kind state questions cfg]}]
  (when-not (paused? kind)
    (let [a (js-await (ask-jev state (into {} (map (fn [[id q]] [id (assoc q :type "noul")]) questions)) cfg))]
      (failed! kind (some? a))
      (when a
        (into {} (keep (fn [id]
                         (let [p (some-> (aget a id) .-noul)]
                           (when (number? p)
                             (log! {:kind kind :subject (subject-hash (str kind ":" id)) :p p})
                             [id p])))
                       (keys questions)))))))

(defn ^:async decide!
  "Should this ask become an allow? `kind` names the gate (\"bash\", \"mcp\",
   \"web_fetch\"), `subject` the call (hashed for the log), `state` the text
   Jev sees, `options` [[name desc] …] ending with `allow`. True only for
   `allow` at or above the threshold (default 0.9)."
  [{:keys [kind subject state options allow cfg]}]
  (if (paused? kind)
    false
    (let [a  (js-await (ask-jev state {:q {:type "choice"
                                           :instructions "Should a coding agent run this action without asking its user?"
                                           :criteria (into {} options)}}
                                cfg))
          p  (some-> a .-q .-probabilities (aget allow))
          p  (when (number? p) p)
          ok (and (number? p) (>= p (or (:threshold cfg) 0.9)))
          h  (subject-hash subject)]
      (failed! kind (some? p))
      (when (number? p)
        (log! {:kind kind :subject h :p p :verdict (if ok "allow" "ask")})
        (if ok
          (swap! pending dissoc h)
          (do (swap! pending assoc h kind)
              (swap! breaker update-in [kind :asks] (fnil inc 0)))))
      ok)))

(defn answered!
  "The user answered a prompt for `subject`: log it beside the verdict that
   sent it to them, if the judge saw it."
  [subject allowed?]
  (let [h (subject-hash subject)]
    (when-let [kind (get @pending h)]
      (swap! pending dissoc h)
      (log! {:kind kind :subject h :answer (if allowed? "allow" "deny")}))))

(defn report
  "Agreement by probability band from the log's lines: how often the user
   allowed what the judge sent them. A band where the user always allows is
   one the threshold could move into."
  [lines]
  (let [verdicts (filter #(number? (:p %)) lines)
        answers  (into {} (map (juxt :subject :answer) (filter :answer lines)))
        band     (fn [p] (cond (>= p 0.9) "0.9-1" (>= p 0.7) "0.7-0.9" (>= p 0.5) "0.5-0.7" :else "0-0.5"))]
    (->> verdicts
         ;; a string key: squint stringifies a vector one
         (group-by #(str (:kind %) "|" (band (:p %))))
         (map (fn [[_ vs]]
                (let [k     (:kind (first vs))
                      b     (band (:p (first vs)))
                      asked (filter #(= "ask" (:verdict %)) vs)
                      ans   (keep #(get answers (:subject %)) asked)]
                  {:kind k :band b :n (count vs)
                   :auto (count (filter #(= "allow" (:verdict %)) vs))
                   :answered (count ans)
                   :user-allowed (count (filter #(= "allow" %) ans))})))
         (sort-by (juxt :kind :band))
         vec)))

(defn read-log []
  (try
    (->> (.split (fs/readFileSync (log-path) "utf8") "\n")
         (filter seq)
         (keep #(try (js/JSON.parse %) (catch :default _ nil)))
         vec)
    (catch :default _ [])))
