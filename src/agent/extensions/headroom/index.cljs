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
  (:require [agent.debug :as d]
            [agent.extensions.headroom.shared  :as shared]
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

(defn notify-settings-errors!
  "Say, once per broken file, that the `headroom` section could not be read.
   Pure over the injected callback so the test needs no UI."
  [errors notify]
  (doseq [{:keys [path message]} errors]
    (let [m (shared/malformed-settings-message path message)]
      (d/warn (str "[headroom] " m))
      (when notify (notify m "error")))))

(defn ^:export default [api]
  (.registerFlag api "headroom"
                 ;; No :default — an absent flag must read as nil so settings
                 ;; decide. Registered before the read for the same reason
                 ;; openwiki does it: --ext-headroom is applied by the CLI's
                 ;; resolve-ext-flags, which runs after extensions load, and
                 ;; without a registered flag the switch the disabled-hint
                 ;; advertises would do nothing at all.
                 #js {:description "Enable Headroom ML context compression for this session"
                      :type "boolean"})
  (let [{base :config errors :errors} (shared/read-config)
        notify   (fn [msg level]
                   (let [ui (.-ui api)]
                     (when (and ui (.-available ui) (.-notify ui))
                       (.notify ui msg (or level "info")))))
        _        (notify-settings-errors! errors notify)
        flag-val (.getFlag api "headroom")
        config   (cond
                   (true? flag-val)  (assoc base :enabled true)
                   (false? flag-val) (assoc base :enabled false)
                   :else             base)]

    (if-not (:enabled config)
      ;; Off — no listeners, no proxy probe. But the command still answers:
      ;; `/headroom-stats` silently not existing is how a user concludes the
      ;; extension is broken rather than switched off.
      (do
        (.registerCommand api "headroom-stats"
                          #js {:description "Headroom is off — how to turn it on"
                               :handler
                               (fn [_args ctx]
                                 (let [text (shared/disabled-hint (:proxy-url config))]
                                   (when (and ctx (.-ui ctx) (.-notify (.-ui ctx)))
                                     (.notify (.-ui ctx) text "info"))
                                   text))})
        (fn [] (.unregisterCommand api "headroom-stats")))

      (let [proxy-available? (atom false)
            deactivators     (atom [])]

        ;; Probe proxy asynchronously — don't block extension activation
        (-> (shared/probe-proxy (or (:proxy-url config) "http://localhost:8787"))
            (.then (fn [ok?]
                     (reset! proxy-available? ok?)
                     (when-not ok?
                       (d/warn
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
