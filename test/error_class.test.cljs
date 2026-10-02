(ns error-class-test
  "The provider-error classifier, and the ordering that makes it correct.

   It exists because `claude_hook_bridge/events/stop` read `(.-errorType data)`
   off the `provider_error` payload and **nothing has ever set that field**.
   Every StopFailure hook therefore dispatched with the literal \"unknown\", so a
   user's `matchers: {\"rate_limit\": …}` could not fire. The type is now derived
   from the message, which is what the provider actually gives us."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.error-class :as ec]))

(describe "error-type"
          (fn []
            (it "classifies the shapes that actually occur"
                (fn []
                  (doseq [[msg want]
                          [["429 rate limit exceeded"                         "rate_limit"]
                           ["401 Unauthorized"                                "auth"]
                           ["invalid x-api-key"                               "auth"]
                           ["prompt is too long: 250000 tokens"               "context_length"]
                           ["maximum tokens exceeded"                         "context_length"]
                           ["Overloaded"                                      "overloaded"]
                           ["503 Service Unavailable"                         "overloaded"]
                           ["connect ECONNREFUSED 127.0.0.1:8000"             "unavailable"]
                           ["context deadline exceeded"                       "unavailable"]
                           ["something nobody has seen before"                "unknown"]]]
                    (-> (expect #js [msg (ec/error-type msg)]) (.toEqual #js [msg want])))))

            (it "the AI SDK's real wording for a refused connection"
                (fn []
        ;; Captured by pointing @ai-sdk/openai at a closed port. The SDK never
        ;; surfaces an errno — it wraps, retries, and reports its own text.
                  (-> (expect (ec/error-type
                               (str "Failed after 6 attempts. Last error: AI_APICallError: "
                                    "Cannot connect to API: Unable to connect.")))
                      (.toBe "unavailable"))))

            (it "context overflow beats auth, because Anthropic reports it as invalid_request"
                (fn []
        ;; The ordering trap recorded in `classify-error-message`: put auth
        ;; first, or test a bare \"invalid\", and someone whose prompt is too
        ;; long gets sent to check their API key.
                  (-> (expect (ec/error-type "invalid_request_error: prompt is too long"))
                      (.toBe "context_length"))))

            (it "does not mistake \"generate\" for a rate limit"
                (fn []
        ;; A bare "rate" substring matches "generate" — the other half of the
        ;; same recorded trap.
                  (-> (expect (ec/error-type "failed to generate a response"))
                      (.not.toBe "rate_limit"))))

            (it "never returns nil — a nil matcher silently matches nothing"
                (fn []
                  (doseq [m [nil "" "   "]]
                    (-> (expect (ec/error-type m)) (.toBe "unknown")))))

            (it "only ever returns a declared type"
                (fn []
                  (doseq [m ["429" "401" "too long" "overloaded" "fetch failed" "???" nil]]
                    (-> (expect #js [m (contains? ec/types (ec/error-type m))])
                        (.toEqual #js [m true])))))))
