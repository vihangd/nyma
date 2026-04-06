(ns agent.extensions.token-suite.tool-truncation
  (:require [agent.extensions.token-suite.shared :as shared]))

(defn activate [api]
  (let [config (shared/load-config)
        tc     (:tool-truncation config)]

    (.addMiddleware api
      #js {:name  "token-suite/tool-truncation"
           :leave (fn [ctx]
                    (let [result    (.-result ctx)
                          tool-name (.-tool-name ctx)]
                      (if (and (string? result)
                               (> (count result) (:max-chars tc)))
                        (let [;; Check for error content — never truncate errors
                              has-errors (shared/has-error-pattern? result)
                              tool-cfg   (get (:per-tool tc) tool-name)
                              head-lines (or (:head-lines tool-cfg) (:head-lines tc))
                              tail-lines (or (:tail-lines tool-cfg) (:tail-lines tc))
                              truncated  (if has-errors
                                           result  ;; preserve errors in full
                                           (shared/truncate-head-tail result head-lines tail-lines))
                              saved      (- (count result) (count truncated))]
                          (when (pos? saved)
                            (swap! shared/suite-stats update :tool-truncation
                              (fn [s] (-> s (update :calls inc) (update :chars-saved + saved)))))
                          (aset ctx "result" truncated)
                          ctx)
                        ctx)))})

    ;; Return deactivate function
    (fn []
      (.removeMiddleware api "token-suite/tool-truncation"))))
