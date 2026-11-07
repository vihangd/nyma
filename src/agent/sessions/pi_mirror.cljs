(ns agent.sessions.pi-mirror
  "An opt-in copy of each session in pi's file format (`pi.sessions`).

   Usage trackers and session browsers built for pi read its files directly,
   mostly from a hardcoded `~/.pi/agent/sessions`, so no flag reaches them.
   nyma's own file stays the truth. Nothing here is read back, except that
   `nymaSession` in the header leads a pi client's switch_session home."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.debug :as d]
            [agent.pricing :as pricing]
            [agent.utils.home :as home]
            [agent.utils.pi-shape :as shape]))

(defn base-dir
  "Where pi would keep sessions, by pi's own precedence."
  []
  (let [env (.-env js/process)]
    (or (aget env "PI_CODING_AGENT_SESSION_DIR")
        (when-let [a (aget env "PI_CODING_AGENT_DIR")] (path/join a "sessions"))
        (path/join (home/dir) ".pi" "agent" "sessions"))))

(defn cwd-dir
  "pi's folder name for `cwd`: `/a/b:c` → `--a-b-c--`."
  [cwd]
  (str "--" (-> cwd (str/replace #"^[/\\]" "") (str/replace #"[/\\:]" "-")) "--"))

;; Source file → this mirror's `{:file :ms}`, and whether the mirror is on.
(defonce ^:private located (atom {}))
(defonce ^:private enabled? (atom false))

(defn- iso [ms] (.toISOString (js/Date. (or ms (js/Date.now)))))

(defn mirror-file
  "This session's mirror in `dir`, existing or to be created."
  [dir id ms]
  (let [suffix (str "_" id ".jsonl")
        found  (when (fs/existsSync dir)
                 (first (filter #(str/ends-with? % suffix) (vec (fs/readdirSync dir)))))]
    (path/join dir (or found (str (str/replace (iso ms) #"[:.]" "-") suffix)))))

(defn header [id nyma-file cwd ms]
  {:type "session" :version 3 :id id :timestamp (iso ms) :cwd cwd :nymaSession nyma-file})

(defn entry->lines
  "One nyma entry → pi entries, given the mirror id of its parent. `extra` is
   merged into an assistant message: its usage and model, which nyma's entries
   do not carry."
  [e parent extra]
  (let [id   (:id e)
        ts   (iso (:timestamp e))
        base {:id id :parentId parent :timestamp ts}
        msg  (fn [m] (assoc base :type "message" :message m))
        md   (or (:metadata e) {})
        role (:role e)]
    (cond
      (= role "user") [(msg (shape/->pi-message e))]
      (= role "assistant") [(msg (merge (shape/assistant-message {}) (shape/->pi-message e)
                                        {:stopReason "stop"} extra))]
      (= role "tool_call")
      (let [call {:id (or (:tool-call-id md) id "") :name (or (:tool-name md) "")
                  :args (or (:args md) {}) :text (:content e) :ts (:timestamp e)
                  :error? (:is-error md)}]
        [(msg (merge (shape/assistant-message {})
                     {:stopReason "toolUse" :timestamp (:timestamp e)
                      :content [{:type "toolCall" :id (:id call) :name (:name call)
                                 :arguments (:args call)}]}
                     extra))
         (assoc base :id (str id "r") :parentId id :type "message"
                :message (assoc (shape/tool-result-message call) :timestamp (:timestamp e)))])
      (= role "compaction") [(assoc base :type "compaction" :summary (str (:content e))
                                    :firstKeptEntryId (or parent "")
                                    :tokensBefore (or (:tokens-before md) 0))]
      (= role "branch-summary") [(assoc base :type "branch_summary" :fromId (or parent "")
                                        :summary (str (:content e)))]
      (= role "session-name") [(assoc base :type "session_info" :name (str (:content e)))]
      :else [])))

(defn- jsonl [ms] (str (str/join "\n" (map #(js/JSON.stringify (clj->js %)) ms)) "\n"))

(defn add-usage [a b]
  (let [b (select-keys b [:input :output :cacheRead :cacheWrite :totalTokens :cost])]
    (if a
      (-> (reduce (fn [acc k] (update acc k + (or (get b k) 0)))
                  a [:input :output :cacheRead :cacheWrite :totalTokens])
          (update-in [:cost :total] + (or (get-in b [:cost :total]) 0)))
      b)))

(defn- read-entries [file]
  (->> (.split (fs/readFileSync file "utf8") "\n")
       (filter seq)
       (keep #(try (js/JSON.parse %) (catch :default _ nil)))))

(defn nyma-session
  "The nyma file a mirror stands for, or `path` for any other file."
  [path]
  (or (try
        (let [fd  (fs/openSync path "r")
              buf (js/Buffer.alloc 4096)
              n   (fs/readSync fd buf 0 4096 0)
              _   (fs/closeSync fd)
              h   (js/JSON.parse (first (.split (.toString buf "utf8" 0 n) "\n")))
              f   (.-nymaSession h)]
          (when (and (= "session" (.-type h)) f (fs/existsSync f)) f))
        (catch :default _ nil))
      path))

(defn- locate
  "This source's mirror path. Fixed on first ask: pi reports a session's file
   before it exists, and a client holds on to that path."
  [source es]
  (or (get @located source)
      (let [ms   (or (:timestamp (first es)) (js/Date.now))
            cwd  (or (some-> (first es) :metadata :cwd) (js/process.cwd))
            file (mirror-file (path/join (base-dir) (cwd-dir cwd)) (shape/session-id source) ms)
            m    {:file file :ms ms :cwd cwd}]
        (swap! located assoc source m)
        m)))

(defn public-file
  "The session file to tell a client about: the mirror when it is on."
  [source]
  (if (and @enabled? (seq (str source)))
    (:file (locate source (when (fs/existsSync source) (read-entries source))))
    source))

(defn attach!
  "Mirror `session` from here on. As in pi, a session has no file until it has
   a user message. A failed write warns once and stops the mirror: a usage
   tracker never gets to take the session down with it."
  [agent session]
  (let [cur     (atom nil)    ; {:source :file :ids :held}
        pending (atom nil)    ; usage since the last assistant message
        failed? (atom false)
        model   (fn []
                  (let [m (.-model (:config agent))]
                    {:provider (str (or (aget (:config agent) "active-provider-name") ""))
                     :model    (str (or (and m (.-modelId m)) m ""))}))
        step!   (fn [e ids extra]
                  (let [up (get ids (:parent-id e))
                        ls (entry->lines e up extra)]
                    {:lines ls
                     :ids   (assoc ids (:id e) (if (seq ls) (:id (last ls)) up))}))
        write!  (fn [lines user?]
                  (let [{:keys [file source held meta]} @cur
                        all (into held lines)]
                    (cond
                      (fs/existsSync file)
                      (do (when (seq lines) (fs/appendFileSync file (jsonl lines)))
                          (swap! cur assoc :held []))
                      user?
                      (do (fs/mkdirSync (path/dirname file) #js {:recursive true})
                          (fs/writeFileSync file (jsonl (cons (header (shape/session-id source) source
                                                                      (:cwd meta) (:ms meta))
                                                              all)))
                          (swap! cur assoc :held []))
                      :else (swap! cur assoc :held all))))
        open!   (fn [source]
                  ;; First sight of this file: everything already in it counts.
                  (let [es (when (fs/existsSync source) (read-entries source))
                        m  (locate source es)
                        {:keys [lines ids]}
                        (reduce (fn [{:keys [lines ids]} e]
                                  (let [r (step! e ids nil)]
                                    {:lines (into lines (:lines r)) :ids (:ids r)}))
                                {:lines [] :ids {}} es)]
                    (reset! cur {:source source :file (:file m) :ids ids :held [] :meta m})
                    (write! lines (some #(= "user" (:role %)) es))))
        on-entry (fn [e]
                   (when-not @failed?
                     (try
                       (let [source ((:get-file-path session))]
                         (when source
                           (if (= source (:source @cur))
                             (let [usage (when (= "assistant" (:role e)) @pending)
                                   extra (when (contains? #{"assistant" "tool_call"} (:role e))
                                           (cond-> (model) usage (assoc :usage usage)))
                                   {:keys [lines ids]} (step! e (:ids @cur) extra)]
                               (when usage (reset! pending nil))
                               (swap! cur assoc :ids ids)
                               (write! lines (= "user" (:role e))))
                             (open! source))))
                       (catch :default err
                         (reset! failed? true)
                         (d/warn "pi-mirror" (str "stopped: " (.-message err)))))))]
    (reset! enabled? true)
    ((:on (:events agent)) "turn_end"
                           (fn [step]
                             (swap! pending add-usage
                                    (shape/usage step (pricing/model-cost-key (:config agent))))))
    ((:set-on-append session) on-entry)))
