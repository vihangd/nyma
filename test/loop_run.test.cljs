(ns loop-run.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run follow-up]]))

;; Testing agent.loop/run via the before_provider_request block path.
;; This avoids needing to mock the AI SDK's streamText while still exercising
;; the critical pre-LLM pipeline: message appending, event emission,
;; emit-collect, system prompt modification, and provider blocking.

(defn- make-test-agent []
  (create-agent {:model "mock-model" :system-prompt "You are a test agent."}))

(defn ^:async test-run-appends-user-message []
  (let [agent (make-test-agent)]
    ;; Block LLM call via before_provider_request
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "Test block"}))
    (js-await (run agent "Hello world"))
    (let [msgs (:messages @(:state agent))]
      ;; Should have user message + blocked assistant response
      (-> (expect (count msgs)) (.toBeGreaterThanOrEqual 2))
      (-> (expect (:content (first msgs))) (.toBe "Hello world"))
      (-> (expect (:role (first msgs))) (.toBe "user")))))

(defn ^:async test-run-emits-agent-start-end []
  (let [agent   (make-test-agent)
        started (atom false)
        ended   (atom false)]
    ((:on (:events agent)) "agent_start" (fn [_] (reset! started true)))
    ((:on (:events agent)) "agent_end" (fn [_] (reset! ended true)))
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "Test block"}))
    (js-await (run agent "test"))
    (-> (expect @started) (.toBe true))
    (-> (expect @ended) (.toBe true))))

(defn ^:async test-run-blocked-stores-assistant-response []
  (let [agent (make-test-agent)]
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "Custom block reason"}))
    (js-await (run agent "test"))
    (let [msgs (:messages @(:state agent))
          last-msg (last msgs)]
      (-> (expect (:role last-msg)) (.toBe "assistant"))
      (-> (expect (:content last-msg)) (.toBe "Custom block reason")))))

(defn ^:async test-run-blocked-default-reason []
  (let [agent (make-test-agent)]
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true}))
    (js-await (run agent "test"))
    (let [msgs (:messages @(:state agent))
          last-msg (last msgs)]
      (-> (expect (:content last-msg)) (.toBe "Blocked by extension")))))

(defn ^:async test-run-emits-agent-end-with-block-text []
  (let [agent    (make-test-agent)
        end-data (atom nil)]
    ((:on (:events agent)) "agent_end"
      (fn [data] (reset! end-data data)))
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "Blocked!"}))
    (js-await (run agent "test"))
    (-> (expect (get @end-data :text)) (.toBe "Blocked!"))
    (-> (expect (get @end-data :usage)) (.toBeNull))))

(defn ^:async test-run-before-agent-start-modifies-prompt []
  (let [agent        (make-test-agent)
        seen-system  (atom nil)]
    ;; Modify system prompt via before_agent_start
    ((:on (:events agent)) "before_agent_start"
      (fn [_] #js {:systemPromptAddition "Extra context here"}))
    ;; Capture the system prompt sent to provider
    ((:on (:events agent)) "before_provider_request"
      (fn [data]
        (reset! seen-system (.-system data))
        #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect @seen-system) (.toContain "You are a test agent."))
    (-> (expect @seen-system) (.toContain "Extra context here"))))

(defn ^:async test-run-tracks-usage-via-store []
  (let [agent (make-test-agent)]
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    ;; Store should have dispatched message-added events
    (let [s @(:state agent)]
      ;; turn-count stays 0 because we blocked before turn_start
      (-> (expect (:turn-count s)) (.toBe 0))
      ;; But messages should be present
      (-> (expect (count (:messages s))) (.toBeGreaterThanOrEqual 2)))))

(defn ^:async test-run-inject-messages-from-extensions []
  (let [agent (make-test-agent)]
    ;; Inject a system-level message via before_agent_start
    ((:on (:events agent)) "before_agent_start"
      (fn [_] #js {:inject-messages #js [#js {:role "system" :content "injected"}]}))
    ((:on (:events agent)) "before_provider_request"
      (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (let [msgs (:messages @(:state agent))]
      ;; Should contain the injected message
      (-> (expect (some #(= (:content %) "injected") msgs)) (.toBeTruthy)))))

;; ── describe blocks ──────────────────────────────────────────

(describe "agent.loop/run - blocked path" (fn []
  (it "appends user message to state" test-run-appends-user-message)
  (it "emits agent_start and agent_end events" test-run-emits-agent-start-end)
  (it "stores blocked assistant response" test-run-blocked-stores-assistant-response)
  (it "uses default block reason" test-run-blocked-default-reason)
  (it "emits agent_end with block text" test-run-emits-agent-end-with-block-text)
  (it "before_agent_start modifies system prompt" test-run-before-agent-start-modifies-prompt)
  (it "tracks state via store dispatches" test-run-tracks-usage-via-store)
  (it "injects messages from extensions" test-run-inject-messages-from-extensions)))
