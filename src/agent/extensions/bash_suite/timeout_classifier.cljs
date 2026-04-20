(ns agent.extensions.bash-suite.timeout-classifier
  (:require [agent.extensions.bash-suite.shared :as shared]))

(defn classify-timeout
  "Return the timeout (ms) to apply for a command. Bumps to long-running
   if any configured regex matches. Respects explicit timeout if already set."
  [cmd config]
  (let [cfg        (:timeout-classifier config)
        default-ms (:default-timeout-ms cfg)
        long-ms    (:long-running-timeout-ms cfg)
        patterns   (:patterns cfg)
        cmd-str    (str cmd)]
    (if (and (:enabled cfg)
             (some (fn [p] (re-find (js/RegExp. p) cmd-str)) patterns))
      long-ms
      default-ms)))

(defn activate [api]
  (let [config (shared/load-config)
        cfg    (:timeout-classifier config)]
    (.addMiddleware api
                    #js {:name  "bash-suite/timeout-classifier"
                         :enter (fn [ctx]
                                  (when (and (:enabled cfg)
                                             (shared/is-bash-tool? (.-tool-name ctx)))
                                    (let [args     (.-args ctx)
                                          cmd      (or (.-command args) "")
                                          existing (.-timeout args)]
                        ;; Only inject when no explicit timeout was provided
                                      (when (nil? existing)
                                        (let [classified (classify-timeout cmd config)]
                                          (when (not= classified (:default-timeout-ms cfg))
                                            (aset args "timeout" classified))))))
                                  ctx)})
    (fn [] (.removeMiddleware api "bash-suite/timeout-classifier"))))
