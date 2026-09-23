(ns agent.extensions.refine
  "`/refine` — mine the session for failure patterns, propose memory entries.

   Borrowed from Prime Agent's /refine. The evidence behind it: ACE reports
   +10.6% on agent benchmarks learning from execution feedback alone, and the
   2026 harness survey finds harness benefit is 'non-monotonic, where middle
   tier models benefit the most' — i.e. exactly the MiniMax/Qwen/GLM/DeepSeek
   class, not frontier models.

   Registers a slash command and NO model-facing tools. That is deliberate:
   nyma already exposes ~36 tools against a 30-50 practical ceiling for small
   models, where each extra similar tool costs 1-8% selection accuracy. A user
   command leaves the tool catalogue untouched.

   PROPOSES, never applies. Nothing reaches MEMORY.md without an explicit
   choice — the harness literature is blunt that human involvement should move
   up the stack rather than out of the loop, and lists reward hacking among the
   live failure modes (Prime's own Factorio run found one).

   v1 is deterministic only. The signals below are what actually found real
   bugs by hand; a model step to phrase them more sharply is deliberately
   deferred until the raw report proves insufficient."
  (:require [agent.utils.data :as data]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.debug :as d]
            [agent.utils.ui :refer [ui-prompt-ready?]]
            [agent.extensions.refine.mining :as mining]))

(defn- report-path []
  (let [dir (path/join (js/process.cwd) ".nyma" "refine")]
    (fs/mkdirSync dir #js {:recursive true})
    (path/join dir (str "refine-" (str/replace (.toISOString (js/Date.)) #"[:.]" "-") ".md"))))

(defn- memory-file [scope]
  (if (= scope :global)
    (path/join (or (.. js/process -env -HOME) ".") ".nyma" "memory" "MEMORY.md")
    (path/join (js/process.cwd) ".nyma" "memory" "MEMORY.md")))

(defn render-report
  "The report a human reads and edits. Pure so it can be tested without a
   session — and the file IS the multi-select: editing it before accepting
   beats clicking checkboxes and leaves an audit trail."
  [signals]
  (str "# Session refine — " (.toISOString (js/Date.)) "\n\n"
       "Observed in this session. Delete anything you disagree with before applying;\n"
       "what remains is appended verbatim to MEMORY.md.\n\n"
       (str/join "\n"
                 (map (fn [s]
                        (str "## " (name (:kind s)) "\n"
                             "- " (:detail s) "\n"
                             (str/join "\n"
                                       (map (fn [it]
                                              (str "  - "
                                                   (cond
                                                     (:path it)    (str (:count it) "x  " (:path it))
                                                     (:command it) (str (:count it) "x  " (:command it))
                                                     :else         (str it))))
                                            (:items s)))
                             "\n"))
                      signals))))

(defn- append-memory! [scope body]
  (let [f (memory-file scope)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/appendFileSync f (str "\n" body "\n") "utf8")
    f))

(defn- session-entries [ctx]
  (when-let [sm (.-sessionManager ctx)]
    (when (.-getEntries sm) (vec (.getEntries sm)))))

(defn- entries-from-file [p]
  (try
    (->> (str/split-lines (str (fs/readFileSync p "utf8")))
         (filter seq)
         (keep (fn [l] (data/parse-json l)))
         vec)
    (catch :default _ nil)))

(defn ^:async run-refine
  "Command body. Exposed for tests so the flow can be driven without a TUI."
  [ctx args]
  (let [arg     (first args)
        entries (if (seq (str arg))
                  (entries-from-file (str arg))
                  (session-entries ctx))
        ui      (.-ui ctx)
        notify  (fn [m level] (when (and ui (.-notify ui)) (.notify ui m (or level "info"))))]
    (cond
      (not (seq entries))
      (notify "refine: no session entries to read." "warning")

      :else
      (let [signals (mining/mine entries)]
        (if-not (seq signals)
          ;; Say nothing and stop. No file, no model call, no busywork on a
          ;; healthy session — the fastest way to make a tool like this
          ;; annoying is to always have an opinion.
          (notify "refine: nothing worth refining in this session." "info")
          (let [body (render-report signals)
                p    (report-path)]
            (fs/writeFileSync p body "utf8")
            (d/info "refine" (str "wrote " p) #js {:signals (count signals)})
            (if-not (ui-prompt-ready? ui)
              ;; Non-interactive (-p / json / rpc): leave the artifact, say
              ;; where, never prompt.
              (notify (str "refine: " (count signals) " finding(s) — " p) "info")
              (let [choice (js-await
                            (.select ui
                                     (str (count signals) " finding(s) from this session — apply where?")
                                     #js ["Append to project MEMORY.md"
                                          "Append to global MEMORY.md"
                                          "Keep the report only"
                                          "Cancel"]))]
                (cond
                  (= choice "Append to project MEMORY.md")
                  (notify (str "refine: appended to " (append-memory! :project body)) "info")

                  (= choice "Append to global MEMORY.md")
                  (notify (str "refine: appended to " (append-memory! :global body)) "info")

                  (= choice "Keep the report only")
                  (notify (str "refine: report kept at " p) "info")

                  :else (notify "refine: cancelled." "info"))))))))))

(defn ^:export default [api]
  (.registerCommand api "refine"
                    #js {:description "Mine this session for failure patterns and propose memory entries. Usage: /refine [session-file]"
                         :handler (fn [args ctx] (run-refine ctx args))})
  (fn [] (.unregisterCommand api "refine")))
