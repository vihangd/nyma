(ns loop-step-cap.test
  "The step cap used to end a run in silence: streamText returned, the loop
   notified, and the transcript's last assistant entry was whatever the model
   said mid-work — which is exactly what a capped SUBAGENT handed its parent.
   Borrowed from apprentice's force-final-answer: one more call with the tools
   taken away and a nudge to report. One call, not an auto-continue."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]
            [agent.loop :refer [run step-cap-nudge cut-off-nudge]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn- tool-call-parts
  "One assistant step that calls `think` and stops for tool results."
  []
  #js [#js {:type "tool-call" :toolCallId "c1" :toolName "think"
            :input "{\"thought\":\"still working\"}"}
       #js {:type "finish" :finishReason #js {:unified "tool-calls" :raw "tool-calls"}
            :usage #js {:inputTokens 1 :outputTokens 1 :totalTokens 2}}])

(defn- text-parts [text]
  #js [#js {:type "text-start" :id "t1"}
       #js {:type "text-delta" :id "t1" :delta text}
       #js {:type "text-end" :id "t1"}
       #js {:type "finish" :finishReason #js {:unified "stop" :raw "stop"}
            :usage #js {:inputTokens 1 :outputTokens 1 :totalTokens 2}}])

(defn- busy-model
  "Calls a tool on every step while tools are offered; answers in prose the
   moment they are not. Records each call's tool list. `report-finish` is the
   finish reason of that prose answer."
  [& [report-finish]]
  (let [calls (atom [])
        model (new MockLanguageModelV4
                   #js {:doStream (fn [opts]
                                    (let [tools (or (.-tools opts) #js [])]
                                      (swap! calls conj (count tools))
                                      (js/Promise.resolve
                                       #js {:stream (convertArrayToReadableStream
                                                     (if (pos? (count tools))
                                                       (tool-call-parts)
                                                       (let [ps (text-parts "REPORT: did two things")
                                                             fin (aget ps (dec (.-length ps)))]
                                                         (when report-finish
                                                           (aset fin "finishReason" #js {:unified report-finish :raw report-finish}))
                                                         ps)))})))})]
    {:model model :calls calls}))

(defn- last-assistant-text [agent]
  (some (fn [m] (when (= (:role m) "assistant")
                  (let [c (:content m)]
                    (if (string? c)
                      c
                      (apply str (map (fn [p] (or (.-text p) (get p "text") "")) c))))))
        (reverse (:messages @(:state agent)))))

(describe "loop:step-cap-report" (fn []
                                   (it "nudge names the budget and forbids tools"
                                       (fn []
                                         (-> (expect (step-cap-nudge 7)) (.toContain "7 steps"))
                                         (-> (expect (step-cap-nudge 7)) (.toContain "cannot call any more tools"))))

                                   (it "a capped run ends with one tool-less report call, not silence"
                                       (fn []
                                         (let [{:keys [model calls]} (busy-model)
                                               agent (make-test-agent {:model model :max-steps 2})]
                                           (-> (run agent "do the thing")
                                               (.then (fn []
                       ;; two tool steps hit the cap, then exactly one call with no tools
                                                        (-> (expect (mapv pos? @calls)) (.toEqual (clj->js [true true false])))
                                                        (-> (expect (last-assistant-text agent)) (.toContain "REPORT"))
                       ;; the nudge went in as the user turn the report answers
                                                        (-> (expect (some #(and (= (:role %) "user")
                                                                                (= (:content %) (step-cap-nudge 2)))
                                                                          (:messages @(:state agent))))
                                                            (.toBeTruthy))
                       ;; one-shot: nothing left armed for the next user turn
                                                        (-> (expect (:turn-opts @(:state agent))) (.toBeFalsy))))))))

                                   (it "a report cut off at the output cap is not continued with tools restored"
                                       (fn []
                                         (let [{:keys [model calls]} (busy-model "length")
                                               agent (make-test-agent {:model model :max-steps 2})]
                                           (-> (run agent "do the thing")
                                               (.then (fn []
                                                        ;; two tool steps, one report — and nothing after it
                                                        (-> (expect (mapv pos? @calls)) (.toEqual (clj->js [true true false])))
                                                        (-> (expect (some #(= (:content %) cut-off-nudge)
                                                                          (:messages @(:state agent))))
                                                            (.toBeFalsy))
                                                        ;; nor counted as a no-op turn
                                                        (-> (expect (or (:no-op-turns @(:state agent)) 0)) (.toBe 0))))))))

                                   (it "step-cap-report false keeps the old behaviour: cap, notify, stop"
                                       (fn []
                                         (let [{:keys [model calls]} (busy-model)
                                               agent (make-test-agent {:model model :max-steps 2
                                                                       :settings {:step-cap-report false}})]
                                           (-> (run agent "do the thing")
                                               (.then (fn []
                                                        (-> (expect (mapv pos? @calls)) (.toEqual (clj->js [true true])))))))))

                                   (it "a run that finishes under the cap never sees the nudge"
                                       (fn []
                                         (let [calls (atom 0)
                                               model (new MockLanguageModelV4
                                                          #js {:doStream (fn [_opts]
                                                                           (swap! calls inc)
                                                                           (js/Promise.resolve
                                                                            #js {:stream (convertArrayToReadableStream (text-parts "done"))}))})
                                               agent (make-test-agent {:model model :max-steps 5})]
                                           (-> (run agent "hi")
                                               (.then (fn []
                                                        (-> (expect @calls) (.toBe 1))
                                                        (-> (expect (some #(= (:content %) (step-cap-nudge 5))
                                                                          (:messages @(:state agent))))
                                                            (.toBeFalsy))))))))))
