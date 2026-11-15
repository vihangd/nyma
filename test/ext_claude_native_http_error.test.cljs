(ns ext-claude-native-http-error.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-claude-native.provider :as p]))

(defn ^:async test-429-is-retryable-api-error []
  (let [orig js/globalThis.fetch]
    (set! js/globalThis.fetch
          (fn [_ _] (js/Promise.resolve
                     (js/Response. "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}"
                                   #js {:status 429 :headers #js {"retry-after" "3"}}))))
    (try
      (let [err (try (js-await (p/do-stream "claude-haiku-4-5" "k" #js {:prompt #js [] :tools nil}))
                     nil
                     (catch :default e e))]
        (-> (expect (.-statusCode err)) (.toBe 429))
        (-> (expect (.-isRetryable err)) (.toBe true))
        (-> (expect (aget (.-responseHeaders err) "retry-after")) (.toBe "3")))
      (finally (set! js/globalThis.fetch orig)))))

(describe "claude-native HTTP errors" (fn []
  (it "a 429 is a retryable APICallError carrying status and headers" test-429-is-retryable-api-error)))
