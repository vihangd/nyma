(ns small-model-thinking-budget.test
  "A turn cut off at the output cap that called no tool is deliberation that
   never committed. The loop queues a continue-nudge for it; before this, that
   nudge ran with thinking still on, so a small model could overflow again on
   the retry. Borrowed from apprentice's little-coder loop: retry once with
   thinking off, then put the level back."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.thinking-budget :as tb]))

(defn- harness []
  (let [hooks (atom {})
        level (atom "medium")
        api   #js {:on (fn [ev f] (swap! hooks assoc ev f) nil)
                   :getThinkingLevel (fn [] @level)
                   :setThinkingLevel (fn [l] (reset! level l))}]
    (tb/activate api {:thinking-budget {}})
    {:hooks hooks :level level}))

(defn- fire [h ev data]
  ((get @(:hooks h) ev) data nil))

(describe "small-model:thinking-budget" (fn []
                                          (it "length cut-off with no tool call disables thinking for the retry, agent_end restores it"
                                              (fn []
                                                (let [h (harness)]
                                                  (fire h "turn_finalize" #js {:finishReason "length" :toolCalls 0 :nudged true})
                                                  (-> (expect @(:level h)) (.toBe "off"))
          ;; the retry turn ends → level back
                                                  (fire h "agent_end" #js {})
                                                  (-> (expect @(:level h)) (.toBe "medium")))))

                                          (it "a cut-off turn that DID call tools is not deliberation overflow"
                                              (fn []
                                                (let [h (harness)]
                                                  (fire h "turn_finalize" #js {:finishReason "length" :toolCalls 2})
                                                  (-> (expect @(:level h)) (.toBe "medium")))))

                                          (it "a normal finish leaves thinking alone"
                                              (fn []
                                                (let [h (harness)]
                                                  (fire h "turn_finalize" #js {:finishReason "stop" :toolCalls 0})
                                                  (-> (expect @(:level h)) (.toBe "medium")))))

                                          (it "thinking already off: nothing to disable, nothing to restore"
                                              (fn []
                                                (let [h (harness)]
                                                  (reset! (:level h) "off")
                                                  (fire h "turn_finalize" #js {:finishReason "length" :toolCalls 0 :nudged true})
                                                  (fire h "agent_end" #js {})
                                                  (-> (expect @(:level h)) (.toBe "off")))))

                                          (it "provider budget error retries once with thinking off and restores after"
                                              (fn []
                                                (let [h (harness)
                                                      r (fire h "provider_error" #js {:message "thinking budget exceeded"})]
                                                  (-> (expect (.-retry r)) (.toBe true))
                                                  (-> (expect @(:level h)) (.toBe "off"))
          ;; a second error in the same turn does not retry again
                                                  (-> (expect (fire h "provider_error" #js {:message "thinking budget exceeded"})) (.toBeUndefined))
                                                  (fire h "agent_end" #js {})
                                                  (-> (expect @(:level h)) (.toBe "medium")))))

                                          (it "a retry turn that errors restores the level at turn_finalize (agent_end never fired)"
      (fn []
        (let [h (harness)]
          (fire h "turn_finalize" #js {:finishReason "length" :toolCalls 0 :nudged true})
          (-> (expect @(:level h)) (.toBe "off"))
          ;; the retry threw: no agent_end, only an error finalize
          (fire h "turn_finalize" #js {:error true :finishReason nil :toolCalls 0})
          (-> (expect @(:level h)) (.toBe "medium")))))

  (it "no nudge queued (a step-cap report turn ran long) → thinking left alone"
      (fn []
        (let [h (harness)]
          (fire h "turn_finalize" #js {:finishReason "length" :toolCalls 0 :nudged false})
          (-> (expect @(:level h)) (.toBe "medium")))))

  (it "budget is not injected while a no-thinking retry is in flight"
                                              (fn []
                                                (let [h (harness)
                                                      before (fn [] (let [d #js {}] (fire h "before_provider_request" d) (.-providerOptions d)))]
                                                  (-> (expect (.-thinkingBudget (before))) (.toBe 8000))
                                                  (fire h "turn_finalize" #js {:finishReason "length" :toolCalls 0 :nudged true})
                                                  (-> (expect (before)) (.toBeUndefined))
                                                  (fire h "agent_end" #js {})
                                                  (-> (expect (.-thinkingBudget (before))) (.toBe 8000)))))))
