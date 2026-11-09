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
            [agent.utils.credentials :as creds]
            [agent.utils.home :as home]))

(defn log-path [] (path/join (home/dir) ".nyma" "decisions.jsonl"))

(defn config
  "The user's `judge` settings, or nil when it is off. The global file only:
   a cloned repo must not switch on what approves its own commands.
   `bash-suite.permissions.judge`, where it first lived, still counts."
  []
  (try
    (let [s (js/JSON.parse (fs/readFileSync (path/join (home/dir) ".nyma" "settings.json") "utf8"))]
      (or (aget s "judge") (some-> (aget s "bash-suite") (aget "permissions") (aget "judge"))))
    (catch :default _ nil)))

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

;; Per session: subject → logged verdict awaiting the user's answer; and the
;; breaker. ponytail: process-wide, one session per process.
(defonce ^:private pending (atom {}))
(defonce ^:private breaker (atom {:errors 0 :asks 0}))

(def max-errors 3)
(def max-asks 20)

(defn paused?
  "After 3 errors in a row the service is down; after 20 asks it did not
   avert it is not earning its latency. Either way: off for the session."
  []
  (let [{:keys [errors asks]} @breaker]
    (or (>= errors max-errors) (>= asks max-asks))))

(defn reset-session! []
  (reset! pending {})
  (reset! breaker {:errors 0 :asks 0}))

(defn request
  "Jev request body. `options` is [[name description] …] with the allow
   option last."
  [state options model]
  {:model     (or model "jev-1.13.0")
   :state     state
   :questions {:q {:type "choice"
                   :instructions "Should a coding agent run this action without asking its user?"
                   :criteria (into {} options)}}})

(defn ^:async ask-jev
  "Probability Jev gives `allow`, or nil on any failure."
  [state options allow {:keys [model timeout-ms]}]
  (if-let [key (or (.. js/process -env -TYPESAFE_API_KEY) (creds/read-credential "typesafe"))]
    (try
      (let [r (js-await (js/fetch "https://api.typesafe.ai/v1/systemone"
                                  #js {:method  "POST"
                                       :headers #js {:Authorization (str "Bearer " key)
                                                     :Content-Type  "application/json"}
                                       :body    (js/JSON.stringify (clj->js (request state options model)))
                                       :signal  (js/AbortSignal.timeout (or timeout-ms 1500))}))]
        (if (.-ok r)
          (let [p (some-> (js-await (.json r)) .-answers .-q .-probabilities (aget allow))]
            (when (number? p) p))
          (do (d/warn "judge" (str "TypeSafe answered " (.-status r) "; asking instead")) nil)))
      (catch :default e
        (d/warn "judge" (str "TypeSafe unreachable (" (.-message e) "); asking instead"))
        nil))
    nil))

(defn ^:async decide!
  "Should this ask become an allow? `kind` names the gate (\"bash\", \"mcp\",
   \"web_fetch\"), `subject` the call (hashed for the log), `state` the text
   Jev sees, `options` [[name desc] …] ending with `allow`. True only for
   `allow` at or above the threshold (default 0.9)."
  [{:keys [kind subject state options allow cfg]}]
  (if (paused?)
    false
    (let [p  (js-await (ask-jev state options allow cfg))
          ok (and (number? p) (>= p (or (:threshold cfg) 0.9)))
          h  (subject-hash subject)]
      (if (nil? p)
        (swap! breaker update :errors inc)
        (swap! breaker assoc :errors 0))
      (when (number? p)
        (log! {:kind kind :subject h :p p :verdict (if ok "allow" "ask")})
        (if ok
          (swap! pending dissoc h)
          (do (swap! pending assoc h kind)
              (swap! breaker update :asks inc))))
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
  (let [verdicts (filter :verdict lines)
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
