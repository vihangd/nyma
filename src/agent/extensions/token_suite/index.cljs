(ns agent.extensions.token-suite.index
  (:require [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions.token-suite.priority-assembly :as priority-assembly]
            [agent.extensions.token-suite.repo-map :as repo-map]
            [agent.extensions.token-suite.diff-edit :as diff-edit]
            [agent.extensions.token-suite.structured-context :as structured-context]
            [agent.extensions.token-suite.smart-compaction :as smart-compaction]
            [agent.extensions.token-suite.anthropic-compaction :as anthropic-compaction]
            [agent.extensions.token-suite.token-preview :as token-preview]
            [agent.token-estimation :as te]
            [clojure.string :as str]))

(defn- format-stats []
  (let [s @shared/suite-stats
        kv  (:kv-cache s)
        rm  (:repo-map s)
        pa  (:priority-assembly s)
        de  (:diff-edit s)
        sc  (:structured-context s)
        sm  (:smart-compaction s)
        total-saved (:tokens-saved pa)]
    (str "Token Optimization Suite — Session Stats\n"
         "─────────────────────────────────────────\n"
         "KV Cache:            " (:cache-hits kv) " cache hits, "
         (or (:cache-misses kv) 0) " misses, "
         (:cached-tokens kv) " cached tokens"
         (let [in (or (:input-tokens kv) 0)]
           (when (pos? in)
             (str " (" (js/Math.round (* 100 (/ (or (:cached-tokens kv) 0) in))) "% of input)")))
         (when (pos? (or (:written-tokens kv) 0))
           (str ", " (:written-tokens kv) " written"))
         (when (:abandoned? kv)
           " — breakpoints OFF: this route never served a read")
         "\n"
         (let [oh @te/observed-overhead]
           (when (seq oh)
             (str "Gateway overhead:\n"
                  (str/join "\n"
                            (map (fn [[k m]]
                                   (str "  " k ": ~" (:tokens m) " tokens/request injected"
                                        " (" (:samples m) " samples)"
                                        (when (>= (:samples m) te/overhead-min-samples)
                                          (str " — pin with \"overheadTokens\": " (:tokens m)))))
                                 oh))
                  "\n")))
         "Repo Map:            " (:files rm) " files indexed, "
         (:symbols rm) " symbols\n"
         "Priority Assembly:   " (:messages-pruned pa) " messages pruned, ~"
         (:tokens-saved pa) " tokens saved\n"
         "Diff Edit:           " (:calls de) " calls, "
         (:hunks-applied de) " hunks (" (:fuzzy-matches de) " fuzzy), "
         (:chars-saved de) " chars saved\n"
         "Structured Context:  " (:files-discovered sc) " files, "
         (:hot-tokens sc) " hot + " (:warm-tokens sc) " warm tokens\n"
         "Smart Compaction:    " (:full-compactions sm) " full compactions\n"
         "─────────────────────────────────────────\n"
         "Total Estimated Token Savings: ~" total-saved " tokens")))

(defn ^:export default [api]
  (let [deactivators (atom [])]

    ;; Activate each sub-extension
    (swap! deactivators conj (kv-cache/activate api))
    (swap! deactivators conj (priority-assembly/activate api))
    (swap! deactivators conj (repo-map/activate api))
    (swap! deactivators conj (diff-edit/activate api))
    (swap! deactivators conj (structured-context/activate api))
    (swap! deactivators conj (smart-compaction/activate api))
    (swap! deactivators conj (anthropic-compaction/activate api))
    (swap! deactivators conj (token-preview/activate api))

    ;; Register /token-stats command
    (.registerCommand api "token-stats"
                      #js {:description "Show token optimization stats"
                           :handler (fn [_args ctx]
                                      (let [text (format-stats)]
                                        (when (and ctx (.-ui ctx) (.-available (.-ui ctx)))
                                          (.notify (.-ui ctx) text "info"))
                                        text))})

    ;; Budget-aware tool filtering — disable expensive tools when context is near full
    (let [budget-handler
          (fn [data]
            (when-let [budget (.getTokenBudget api)]
              (let [used   (or (.-tokensUsed budget) 0)
                    window (or (.-contextWindow budget) 100000)
                    pct    (/ used window)]
                ;; When >80% full, disable expensive tools
                (when (> pct 0.80)
                  (let [tools (vec (.-tools data))
                        cheap (vec (remove #{"web_fetch" "web_search"} tools))]
                    (when (not= (count tools) (count cheap))
                      #js {:allowed (clj->js cheap)}))))))]
      (.on api "tool_access_check" budget-handler)

      ;; Return combined deactivate
      (fn []
        (.off api "tool_access_check" budget-handler)
        (doseq [deactivate @deactivators]
          (when (fn? deactivate)
            (deactivate)))))))
