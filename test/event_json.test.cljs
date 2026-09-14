(ns event-json.test
  "The StepResult → wire mappers shared by pi-rpc and `-p --output-format
   stream-json`. A wrongly-named field here is invisible — the event still
   arrives, the consumer just reads nothing — hence field-by-field checks."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.event-json :refer [step-usage tool-results]]))

(defn- step
  "An AI SDK StepResult of the shape loop.cljs emits on turn_end."
  [& {:keys [text finish in out tools]}]
  #js {:text         (or text "")
       :finishReason (or finish "stop")
       :usage        (when (or in out) #js {:inputTokens (or in 0) :outputTokens (or out 0)})
       :toolResults  (clj->js (or tools []))})

(describe "step-usage"
          (fn []
            (it "maps inputTokens and outputTokens"
                (fn []
                  (-> (expect (clj->js (step-usage (step :in 120 :out 34))))
                      (.toEqual #js {:inputTokens 120 :outputTokens 34}))))

            (it "is nil when the step reports no usage"
                (fn []
                  ;; Absent, not zeroed — a consumer must be able to tell
                  ;; "no usage reported" from "a turn that cost nothing".
                  (-> (expect (step-usage (step))) (.toBeNil))))

            (it "defaults a missing half to 0 rather than undefined"
                (fn []
                  (-> (expect (:outputTokens (step-usage (step :in 5)))) (.toBe 0))))))

(describe "tool-results"
          (fn []
            (it "carries id, name and output text"
                (fn []
                  (let [out (tool-results (step :tools [{:toolCallId "c1" :toolName "read"
                                                         :output "file contents"}]))]
                    (-> (expect (count out)) (.toBe 1))
                    (-> (expect (:toolCallId (first out))) (.toBe "c1"))
                    (-> (expect (:toolName (first out))) (.toBe "read"))
                    (-> (expect (:text (first (:content (:result (first out))))))
                        (.toBe "file contents")))))

            (it "serialises a non-string output instead of dropping it"
                (fn []
                  (let [out (tool-results (step :tools [{:toolCallId "c" :toolName "t"
                                                         :output {:ok true}}]))]
                    (-> (expect (:text (first (:content (:result (first out))))))
                        (.toContain "ok")))))

            (it "is an empty vector when the step ran no tools"
                (fn []
                  (-> (expect (count (tool-results (step)))) (.toBe 0))))))
