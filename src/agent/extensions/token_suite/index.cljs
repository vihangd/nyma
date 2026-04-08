(ns agent.extensions.token-suite.index
  (:require [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.tool-truncation :as tool-truncation]
            [agent.extensions.token-suite.observation-mask :as observation-mask]
            [agent.extensions.token-suite.expired-context :as expired-context]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions.token-suite.priority-assembly :as priority-assembly]
            [agent.extensions.token-suite.repo-map :as repo-map]
            [agent.extensions.token-suite.diff-edit :as diff-edit]
            [agent.extensions.token-suite.structured-context :as structured-context]
            [agent.extensions.token-suite.smart-compaction :as smart-compaction]
            [agent.extensions.token-suite.context-folding :as context-folding]
            [agent.extensions.token-suite.token-preview :as token-preview]
            [clojure.string :as str]))

(defn- format-stats []
  (let [s @shared/suite-stats
        om  (:observation-mask s)
        kv  (:kv-cache s)
        ec  (:expired-context s)
        tt  (:tool-truncation s)
        rm  (:repo-map s)
        pa  (:priority-assembly s)
        de  (:diff-edit s)
        sc  (:structured-context s)
        sm  (:smart-compaction s)
        cf  (:context-folding s)
        total-saved (+ (:tokens-saved om) (:tokens-saved ec) (:tokens-saved pa)
                       (:tokens-freed cf))]
    (str "Token Optimization Suite — Session Stats\n"
         "─────────────────────────────────────────\n"
         "Observation Masking:  " (:messages-masked om) " messages masked, ~"
         (:tokens-saved om) " tokens saved\n"
         "KV Cache:            " (:cache-hits kv) " cache hits, "
         (:cached-tokens kv) " cached tokens\n"
         "Expired Context:     " (:stale-replaced ec) " stale reads pruned, ~"
         (:tokens-saved ec) " tokens saved\n"
         "Tool Truncation:     " (:calls tt) " results truncated, "
         (:chars-saved tt) " chars saved\n"
         "Repo Map:            " (:files rm) " files indexed, "
         (:symbols rm) " symbols\n"
         "Priority Assembly:   " (:messages-pruned pa) " messages pruned, ~"
         (:tokens-saved pa) " tokens saved\n"
         "Diff Edit:           " (:calls de) " calls, "
         (:hunks-applied de) " hunks (" (:fuzzy-matches de) " fuzzy), "
         (:chars-saved de) " chars saved\n"
         "Structured Context:  " (:files-discovered sc) " files, "
         (:hot-tokens sc) " hot + " (:warm-tokens sc) " warm tokens\n"
         "Smart Compaction:    " (:background-updates sm) " bg updates, "
         (:offloads sm) " offloads, " (:full-compactions sm) " full compactions\n"
         "                     ~" (:tokens-archived sm) " tokens archived, "
         (:re-reads sm) " re-reads detected\n"
         "Context Folding:     " (:foci-completed cf) " foci completed, "
         (:messages-folded cf) " msgs folded, ~" (:tokens-freed cf) " tokens freed\n"
         "─────────────────────────────────────────\n"
         "Total Estimated Token Savings: ~" total-saved " tokens")))

(defn ^:export default [api]
  (let [deactivators (atom [])]

    ;; Activate each sub-extension
    (swap! deactivators conj (tool-truncation/activate api))
    (swap! deactivators conj (observation-mask/activate api))
    (swap! deactivators conj (expired-context/activate api))
    (swap! deactivators conj (kv-cache/activate api))
    (swap! deactivators conj (priority-assembly/activate api))
    (swap! deactivators conj (repo-map/activate api))
    (swap! deactivators conj (diff-edit/activate api))
    (swap! deactivators conj (structured-context/activate api))
    (swap! deactivators conj (smart-compaction/activate api))
    (swap! deactivators conj (context-folding/activate api))
    (swap! deactivators conj (token-preview/activate api))

    ;; Register /token-stats command
    (.registerCommand api "token-stats"
      #js {:description "Show token optimization stats"
           :handler (fn [_args ctx]
                      (let [text (format-stats)]
                        (when (and ctx (.-ui ctx) (.-available (.-ui ctx)))
                          (.notify (.-ui ctx) text "info"))
                        text))})

    ;; Return combined deactivate
    (fn []
      (doseq [deactivate @deactivators]
        (when (fn? deactivate)
          (deactivate))))))
