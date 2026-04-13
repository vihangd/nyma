(ns hooks-events.test
  "Tests for all new/upgraded hooks and events (G3, G8, G9, G10, G12, G13, G14)."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.core :refer [create-agent]]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [create-pipeline]]
            [agent.context :refer [get-active-tools-filtered]]))

;;; ─── G8: before_tool_call emit-collect ──────────────────

(describe "hooks:before-tool-call-emit-collect" (fn []
  (it "handler returning {block: true} cancels tool execution"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "before_tool_call"
          (fn [data] #js {:block true :reason "test block"}))
        (let [p ((:emit-collect events) "before_tool_call"
                  #js {:name "bash" :args #js {:command "rm -rf /"}})]
          (.then p (fn [result]
            (-> (expect (get result "block")) (.toBe true))
            (-> (expect (get result "reason")) (.toBe "test block"))))))))

  (it "handler returning {args: modified} transforms arguments"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "before_tool_call"
          (fn [data] #js {:args #js {:path "safe.txt"}}))
        (let [p ((:emit-collect events) "before_tool_call"
                  #js {:name "read" :args #js {:path "secret.env"}})]
          (.then p (fn [result]
            (-> (expect (get result "args")) (.toBeDefined))
            (-> (expect (.-path (get result "args"))) (.toBe "safe.txt"))))))))

  (it "handler returning nil has no effect"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "before_tool_call" (fn [_] nil))
        (let [p ((:emit-collect events) "before_tool_call"
                  #js {:name "read" :args #js {}})]
          (.then p (fn [result]
            (-> (expect (get result "block")) (.toBeUndefined))))))))

  (it "multiple handlers: one blocking wins via boolean OR merge"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "before_tool_call" (fn [_] nil))
        ((:on events) "before_tool_call" (fn [_] #js {:block true :reason "blocked"}))
        (let [p ((:emit-collect events) "before_tool_call" #js {:name "bash"})]
          (.then p (fn [result]
            (-> (expect (get result "block")) (.toBe true))))))))))

;;; ─── G9: tool_complete emit-collect ─────────────────────

(describe "hooks:tool-complete" (fn []
  (it "tool_complete fires and can modify result"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "tool_complete"
          (fn [data] #js {:result "modified result"}))
        (let [p ((:emit-collect events) "tool_complete"
                  #js {:toolName "read" :result "original" :duration 100})]
          (.then p (fn [result]
            (-> (expect (get result "result")) (.toBe "modified result"))))))))

  (it "tool_complete passes through when no handler modifies"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "tool_complete" (fn [_] nil))
        (let [p ((:emit-collect events) "tool_complete"
                  #js {:toolName "read" :result "original" :duration 50})]
          (.then p (fn [result]
            (-> (expect (get result "result")) (.toBeUndefined))))))))))

;;; ─── G3: provider_error emit-collect ────────────────────

(describe "hooks:provider-error" (fn []
  (it "provider_error fires with error data"
    (fn []
      (let [agent    (create-agent {:model "test" :system-prompt "test"})
            events   (:events agent)
            received (atom nil)]
        ((:on events) "provider_error"
          (fn [data] (reset! received data) nil))
        (let [p ((:emit-collect events) "provider_error"
                  #js {:error (js/Error. "rate limit") :model "test-model"})]
          (.then p (fn [_]
            (-> (expect (.-model @received)) (.toBe "test-model"))))))))

  (it "handler returning {retry: true} enables retry"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "provider_error"
          (fn [_] #js {:retry true}))
        (let [p ((:emit-collect events) "provider_error"
                  #js {:error (js/Error. "err") :model "m"})]
          (.then p (fn [result]
            (-> (expect (get result "retry")) (.toBe true))))))))))

;;; ─── G10: input_submit emit-collect ─────────────────────

(describe "hooks:input-submit" (fn []
  (it "handler returning {cancel: true} prevents submission"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "input_submit"
          (fn [_] #js {:cancel true}))
        (let [p ((:emit-collect events) "input_submit"
                  #js {:text "bad input" :timestamp 123})]
          (.then p (fn [result]
            (-> (expect (get result "cancel")) (.toBe true))))))))

  (it "handler returning {text: 'modified'} transforms input"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "input_submit"
          (fn [_] #js {:text "transformed input"}))
        (let [p ((:emit-collect events) "input_submit"
                  #js {:text "original" :timestamp 123})]
          (.then p (fn [result]
            (-> (expect (get result "text")) (.toBe "transformed input"))))))))))

;;; ─── G12: message_before_store ──────────────────────────

(describe "hooks:message-before-store" (fn []
  (it "handler can modify stored content"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "message_before_store"
          (fn [_] #js {:content "[REDACTED]"}))
        (let [p ((:emit-collect events) "message_before_store"
                  #js {:role "assistant" :content "secret data"})]
          (.then p (fn [result]
            (-> (expect (get result "content")) (.toBe "[REDACTED]"))))))))

  (it "nil return preserves original content"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "message_before_store" (fn [_] nil))
        (let [p ((:emit-collect events) "message_before_store"
                  #js {:role "assistant" :content "keep this"})]
          (.then p (fn [result]
            (-> (expect (get result "content")) (.toBeUndefined))))))))))

;;; ─── G13: permission_request ────────────────────────────

(describe "hooks:permission-request" (fn []
  (it "handler returning {decision: 'deny'} blocks tool"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "permission_request"
          (fn [data] #js {:decision "deny" :reason "forbidden"}))
        (let [p ((:emit-collect events) "permission_request"
                  #js {:tool "bash" :category "exec"})]
          (.then p (fn [result]
            (-> (expect (get result "decision")) (.toBe "deny"))))))))

  (it "handler returning {decision: 'allow'} permits tool"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "permission_request"
          (fn [_] #js {:decision "allow"}))
        (let [p ((:emit-collect events) "permission_request"
                  #js {:tool "read" :category "read"})]
          (.then p (fn [result]
            (-> (expect (get result "decision")) (.toBe "allow"))))))))))

;;; ─── G14: tool_access_check ─────────────────────────────

(describe "hooks:tool-access-check" (fn []
  (it "handler returning {allowed: ['read']} restricts tools"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        ((:on events) "tool_access_check"
          (fn [_] #js {:allowed #js ["read" "grep"]}))
        (let [p ((:emit-collect events) "tool_access_check"
                  #js {:tools #js ["read" "write" "bash" "grep"]})]
          (.then p (fn [result]
            (let [allowed (get result "allowed")]
              (-> (expect (count allowed)) (.toBe 2)))))))))

  (it "no handler means all tools available"
    (fn []
      (let [agent  (create-agent {:model "test" :system-prompt "test"})
            events (:events agent)]
        (let [p ((:emit-collect events) "tool_access_check"
                  #js {:tools #js ["read" "write"]})]
          (.then p (fn [result]
            (-> (expect (get result "allowed")) (.toBeUndefined))))))))))

;;; ─── G11: session_ready ─────────────────────────────────

(describe "hooks:session-ready" (fn []
  (it "session_ready event fires with data"
    (fn []
      (let [agent    (create-agent {:model "test" :system-prompt "test"})
            events   (:events agent)
            received (atom nil)]
        ((:on events) "session_ready" (fn [data] (reset! received data)))
        (let [p ((:emit-async events) "session_ready"
                  #js {:cwd "/tmp" :model "test" :extensions 3})]
          (.then p (fn [_]
            (-> (expect (.-cwd @received)) (.toBe "/tmp"))
            (-> (expect (.-extensions @received)) (.toBe 3))))))))))

;;; ─── G4: session_end_summary ────────────────────────────

(describe "hooks:session-end-summary" (fn []
  (it "session_end_summary includes usage data"
    (fn []
      (let [agent    (create-agent {:model "test" :system-prompt "test"})
            events   (:events agent)
            received (atom nil)]
        ;; Simulate some usage
        (swap! (:state agent) assoc :total-cost 1.23 :turn-count 5
               :total-input-tokens 1000 :total-output-tokens 500)
        ((:on events) "session_end_summary" (fn [data] (reset! received data)))
        ((:emit events) "session_end_summary"
          #js {:totalCost 1.23 :turnCount 5 :inputTokens 1000 :outputTokens 500})
        (-> (expect (.-totalCost @received)) (.toBe 1.23))
        (-> (expect (.-turnCount @received)) (.toBe 5)))))))
