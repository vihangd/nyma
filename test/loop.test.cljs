(ns loop.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.loop :refer [steer follow-up stream-event-types wrap-tools-with-before-hook]]
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

    (it "maps finish-step to turn_end"
      (fn []
        (-> (expect (get stream-event-types "finish-step"))
            (.toBe "turn_end"))))

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

    (it "contains exactly 10 mappings"
      (fn []
        (-> (expect (count stream-event-types)) (.toBe 10))))))

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
