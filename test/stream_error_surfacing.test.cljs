(ns stream-error-surfacing.test
  "A provider's stream error must reach the user.

   The AI SDK puts the real failure in the stream as an `error` part and then,
   if no step completed, rejects `.text` with the generic \"No output generated.
   Check the stream for errors.\" nyma had no mapping for that chunk type, so it
   was emitted under a nil event name nobody listens to, and every failure — a
   bad model id, a missing key, an unreachable host — arrived as the same
   sentence telling the user to check a stream they cannot see.

   Measured while chasing it: `-p` reported that sentence for three different
   underlying causes on two providers."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.loop :refer [run stream-error-message]]
            [test-util.agent-harness :refer [make-test-agent]]))

;;; ─── the pure half ──────────────────────────────────────────

(describe "stream-error-message"
          (fn []
            (it "reads an Error in the error part"
                (fn []
                  (-> (expect (stream-error-message #js {:type "error" :error (js/Error. "boom")}))
                      (.toBe "boom"))))

            (it "reads a provider's nested {error:{message}}"
                (fn []
                  (-> (expect (stream-error-message
                               #js {:type "error" :error #js {:error #js {:message "Unknown Model"}}}))
                      (.toBe "Unknown Model"))))

            (it "reads a bare string"
                (fn []
                  (-> (expect (stream-error-message #js {:type "error" :error "rate limited"}))
                      (.toBe "rate limited"))))

            (it "falls back to JSON rather than losing the error"
                (fn []
                  ;; Whatever shape a provider invents, the user gets something.
                  (-> (expect (stream-error-message #js {:type "error" :error #js {:code 429}}))
                      (.toContain "429"))))

            (it "is nil when there is no error to report"
                (fn []
                  (-> (expect (stream-error-message #js {:type "error" :error nil})) (.toBeNil))))))

;;; ─── end to end through run ─────────────────────────────────

(defn- error-only-model
  "A model whose stream carries one error part and no step — the exact shape
   that produced the generic message."
  [message]
  (new MockLanguageModelV4
       #js {:doStream (fn [_]
                        (js/Promise.resolve
                         #js {:stream (convertArrayToReadableStream
                                       #js [#js {:type "error" :error (js/Error. message)}])}))}))

(defn ^:async t-run-surfaces-the-stream-error []
  (let [agent (make-test-agent)
        seen  (atom nil)]
    (set! (.-model (:config agent)) (error-only-model "Unknown Model, please check the model code."))
    ((:on (:events agent)) "provider_error" (fn [d] (reset! seen (.-message d))))
    (let [msg (try
                (js-await (run agent "say ok"))
                nil
                (catch :default e (str (.-message e))))]
      ;; The thrown error names the cause, not just the SDK's sentence.
      (-> (expect (str msg)) (.toContain "Unknown Model"))
      ;; …and the same fact reached the bus, where escalate's failover reads it.
      (-> (expect (str @seen)) (.toContain "Unknown Model")))))

(describe "run surfaces a stream error"
          (fn []
            (it "names the provider's message instead of 'No output generated'"
                t-run-surfaces-the-stream-error)))
