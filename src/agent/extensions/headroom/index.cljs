(ns agent.extensions.headroom.index
  "Headroom — ML context compression via the Headroom proxy.

   Adds a context_assembly hook at priority 10 (runs after all token_suite
   handlers at 95→70). Receives already-pruned messages and applies
   ML compression (SmartCrusher, CodeCompressor, Kompress ONNX model)
   for a further 60-95% reduction.

   Requires the Python proxy:
     pip install 'headroom-ai[proxy]'
     headroom proxy --port 8787

   Enable:  .nyma/settings.json → {\"headroom\": {\"enabled\": true}}
   Off by default — graceful no-op when disabled or proxy unreachable.
  "
  (:require [agent.extensions.headroom.shared  :as shared]
            [agent.extensions.headroom.compress :as comp-mod]))

(defn- format-stats []
  (let [s @shared/suite-stats]
    (str "Headroom Compression Stats\n"
         "──────────────────────────\n"
         "Compressed turns:   " (:calls s) "\n"
         "Tokens saved:       ~" (:tokens-saved s) "\n"
         "Compression ratio:  " (when-let [r (:compression-ratio s)]
                                  (.toFixed r 2)) "\n"
         "Skipped (threshold): " (:skipped s) "\n"
         "Errors:              " (:errors s))))

(defn ^:export default [api]
  (let [config (shared/load-config)]

    (when-not (:enabled config)
      ;; Disabled — zero overhead, no listeners
      (fn [] nil))

    (when (:enabled config)
      (let [proxy-available? (atom false)
            deactivators     (atom [])]

        ;; Probe proxy asynchronously — don't block extension activation
        (-> (shared/probe-proxy (or (:proxy-url config) "http://localhost:8787"))
            (.then (fn [ok?]
                     (reset! proxy-available? ok?)
                     (when-not ok?
                       (js/console.warn
                        (str "[headroom] proxy not reachable at "
                             (or (:proxy-url config) "http://localhost:8787")
                             " — compression disabled. "
                             "Run: headroom proxy --port 8787"))))))

        ;; Wire compress hook
        (swap! deactivators conj (comp-mod/activate api config proxy-available?))

        ;; /headroom-stats command
        (.registerCommand api "headroom-stats"
                          #js {:description "Show Headroom ML compression statistics"
                               :handler
                               (fn [_args ctx]
                                 (let [text (format-stats)]
                                   (when (and ctx (.-ui ctx))
                                     (.notify (.-ui ctx) text "info"))
                                   text))})

        ;; Cleanup
        (fn []
          (.unregisterCommand api "headroom-stats")
          (doseq [d @deactivators]
            (when (fn? d) (d))))))))
