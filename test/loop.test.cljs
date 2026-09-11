(ns loop.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.loop :as l :refer [steer follow-up stream-event-types wrap-tools-with-before-hook]]
            [agent.events :refer [create-event-bus]]))

;; --- stream-event-types map ---

(describe "agent.loop - stream-event-types"
  (fn []
    (it "maps text-start to message_start"
      (fn []
        (-> (expect (get stream-event-types "text-start"))
            (.toBe "message_start"))))

    (it "maps text-delta to message_update"
      (fn []
        (-> (expect (get stream-event-types "text-delta"))
            (.toBe "message_update"))))

    (it "maps text-end to message_end"
      (fn []
        (-> (expect (get stream-event-types "text-end"))
            (.toBe "message_end"))))

    (it "maps tool-call to tool_call"
      (fn []
        (-> (expect (get stream-event-types "tool-call"))
            (.toBe "tool_call"))))

    (it "maps tool-result to tool_result"
      (fn []
        (-> (expect (get stream-event-types "tool-result"))
            (.toBe "tool_result"))))

    (it "does NOT map finish-step (onStepFinish owns turn_end — a mapping here made it fire twice per step)"
      (fn []
        (-> (expect (get stream-event-types "finish-step"))
            (.toBeUndefined))))

    (it "maps finish to agent_end"
      (fn []
        (-> (expect (get stream-event-types "finish"))
            (.toBe "agent_end"))))

    (it "maps reasoning-start to reasoning_start"
      (fn []
        (-> (expect (get stream-event-types "reasoning-start"))
            (.toBe "reasoning_start"))))

    (it "maps reasoning-delta to reasoning_delta"
      (fn []
        (-> (expect (get stream-event-types "reasoning-delta"))
            (.toBe "reasoning_delta"))))

    (it "maps reasoning-end to reasoning_end"
      (fn []
        (-> (expect (get stream-event-types "reasoning-end"))
            (.toBe "reasoning_end"))))

    (it "contains exactly 9 mappings"
      (fn []
        (-> (expect (count stream-event-types)) (.toBe 9))))))

;; --- steer / follow-up queue functions ---

(defn- make-agent []
  {:steer-queue  (atom [])
   :follow-queue (atom [])
   :state        (atom {:messages []})})

(describe "agent.loop - steer"
  (fn []
    (it "appends message to steer-queue"
      (fn []
        (let [agent (make-agent)]
          (steer agent {:role "user" :content "nudge"})
          (-> (expect (count @(:steer-queue agent))) (.toBe 1))
          (-> (expect (:content (first @(:steer-queue agent)))) (.toBe "nudge")))))

    (it "preserves order of multiple steers"
      (fn []
        (let [agent (make-agent)]
          (steer agent {:role "user" :content "first"})
          (steer agent {:role "user" :content "second"})
          (-> (expect (count @(:steer-queue agent))) (.toBe 2))
          (-> (expect (:content (first @(:steer-queue agent)))) (.toBe "first"))
          (-> (expect (:content (second @(:steer-queue agent)))) (.toBe "second")))))))

(describe "agent.loop - follow-up"
  (fn []
    (it "appends message to follow-queue"
      (fn []
        (let [agent (make-agent)]
          (follow-up agent {:role "user" :content "next task"})
          (-> (expect (count @(:follow-queue agent))) (.toBe 1))
          (-> (expect (:content (first @(:follow-queue agent)))) (.toBe "next task")))))

    (it "preserves order of multiple follow-ups"
      (fn []
        (let [agent (make-agent)]
          (follow-up agent {:role "user" :content "a"})
          (follow-up agent {:role "user" :content "b"})
          (-> (expect (count @(:follow-queue agent))) (.toBe 2))
          (-> (expect (:content (first @(:follow-queue agent)))) (.toBe "a"))
          (-> (expect (:content (second @(:follow-queue agent)))) (.toBe "b")))))))

;; --- wrap-tools-with-before-hook ---

(describe "agent.loop - wrap-tools-with-before-hook"
  (fn []
    (it "handler receives tool name and args"
      (fn []
        (let [bus      (create-event-bus)
              received (atom nil)
              mock-tool #js {:execute (fn [_] "ok")}
              tools    {"my-tool" mock-tool}]
          ((:on bus) "before_tool_call" (fn [ctx] (reset! received ctx)))
          (let [wrapped (wrap-tools-with-before-hook tools bus)
                exec    (.-execute (get wrapped "my-tool"))]
            (exec #js {:query "test"})
            (-> (expect (.-name @received)) (.toBe "my-tool"))))))

    (it "setting cancelled=true prevents tool execution"
      (fn []
        (let [bus       (create-event-bus)
              executed  (atom false)
              mock-tool #js {:execute (fn [_] (reset! executed true) "result")}
              tools     {"t" mock-tool}]
          ((:on bus) "before_tool_call" (fn [ctx] (set! (.-cancelled ctx) true)))
          (let [wrapped (wrap-tools-with-before-hook tools bus)
                result  ((.-execute (get wrapped "t")) #js {})]
            (-> (expect @executed) (.toBe false))
            (-> (expect result) (.toBe "Tool call 't' was cancelled by extension"))))))

    (it "multiple handlers called in order; one cancel stops execution"
      (fn []
        (let [bus       (create-event-bus)
              order     (atom [])
              mock-tool #js {:execute (fn [_] "ok")}
              tools     {"t" mock-tool}]
          ((:on bus) "before_tool_call" (fn [ctx]
            (swap! order conj "first")
            (set! (.-cancelled ctx) true)))
          ((:on bus) "before_tool_call" (fn [_ctx]
            (swap! order conj "second")))
          (let [wrapped (wrap-tools-with-before-hook tools bus)]
            ((.-execute (get wrapped "t")) #js {})
            (-> (expect (count @order)) (.toBe 2))))))

    (it "handler errors don't prevent tool execution"
      (fn []
        (let [bus       (create-event-bus)
              executed  (atom false)
              orig      js/console.error
              mock-tool #js {:execute (fn [_] (reset! executed true) "ok")}
              tools     {"t" mock-tool}]
          (set! js/console.error (fn [& _]))
          ((:on bus) "before_tool_call" (fn [_] (throw (js/Error. "boom"))))
          (let [wrapped (wrap-tools-with-before-hook tools bus)]
            ((.-execute (get wrapped "t")) #js {})
            (set! js/console.error orig)
            (-> (expect @executed) (.toBe true))))))))

;; --- Real AI SDK tool test ---
;; Ensures wrap-tools-with-before-hook works with production Vercel AI SDK tool() objects

(describe "agent.loop - wrap-tools-with-before-hook with real AI SDK tools"
  (fn []
    (it "wraps real AI SDK tool() objects without crashing"
      (fn []
        (let [bus      (create-event-bus)
              received (atom nil)
              real-t   (tool #js {:description "real tool"
                                  :inputSchema  (.object z #js {:x (.string z)})
                                  :execute     (fn [args] (str "got:" (:x args)))})
              tools    {"real" real-t}]
          ((:on bus) "before_tool_call" (fn [ctx] (reset! received (.-name ctx))))
          (let [wrapped (wrap-tools-with-before-hook tools bus)
                result  ((.-execute (get wrapped "real")) {:x "test"})]
            (-> (expect @received) (.toBe "real"))
            (-> (expect result) (.toBe "got:test"))
            ;; Verify properties preserved
            (-> (expect (.-description (get wrapped "real"))) (.toBe "real tool"))))))))

;;; ─── Turn outcome (mini-swe-agent borrows) ──────────────────
;;; Two limits that used to fire in silence: a response truncated at the
;;; output-token cap, and the step cap stopping a model mid-task.

(defn- outcome [fr tools steps max-steps]
  (l/turn-outcome fr tools steps max-steps))

(describe "turn-outcome"
          (fn []
            (it "a plain text turn is a stall candidate"
                (fn []
                  (-> (expect (:count-stall? (outcome "stop" 0 1 100))) (.toBe true))
                  (-> (expect (:cut-off? (outcome "stop" 0 1 100))) (.toBe false))))

            (it "a turn that ran tools is never a stall"
                (fn []
                  (-> (expect (:count-stall? (outcome "stop" 3 4 100))) (.toBe false))))

            ;; The model was still talking when the cap hit. Counting it drove
            ;; the two-turn warning and escalate's model swap — the wrong remedy
            ;; for a response that was merely too long.
            (it "a cut-off turn with no tools is NOT counted as a stall"
                (fn []
                  (let [o (outcome "length" 0 1 100)]
                    (-> (expect (:cut-off? o)) (.toBe true))
                    (-> (expect (:count-stall? o)) (.toBe false)))))

            ;; mini-swe-agent's case is "cut off before producing a tool call on
            ;; THIS step" — earlier tool calls in the same turn don't change it.
            (it "a cut-off turn still reports cut-off after five tool calls"
                (fn []
                  (-> (expect (:cut-off? (outcome "length" 5 6 100))) (.toBe true))))

            (it "the step cap is counted, not inferred from finishReason"
                (fn []
                  (-> (expect (:step-capped? (outcome "tool-calls" 2 100 100))) (.toBe true))
                  (-> (expect (:step-capped? (outcome "tool-calls" 2 99 100))) (.toBe false))
                  ;; An abort produces the same finishReason without hitting the cap.
                  (-> (expect (:step-capped? (outcome "tool-calls" 2 3 100))) (.toBe false))))

            (it "no cap configured means never capped"
                (fn []
                  (-> (expect (:step-capped? (outcome "stop" 1 500 0))) (.toBe false))))

            (it "the nudge names the limit and asks for the next action"
                (fn []
                  (-> (expect l/cut-off-nudge) (.toContain "output token limit"))
                  (-> (expect l/cut-off-nudge) (.toContain "concisely"))))))
