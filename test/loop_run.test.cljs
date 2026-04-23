(ns loop-run.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.loop :refer [run follow-up]]
            [test-util.agent-harness :refer [make-test-agent block-provider!]]))

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

(defn ^:async test-run-provider-request-has-provider-options []
  (let [agent (make-test-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen (some? (.-providerOptions config)))
                             #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect @seen) (.toBe true))))

(defn ^:async test-run-provider-request-mutation-persists []
  (let [agent   (make-test-agent)
        final-v (atom nil)]
    ;; First handler mutates
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (set! (.-system config) "MUTATED BY EXTENSION")
                             nil)
                           10)
    ;; Second handler reads
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! final-v (.-system config))
                             #js {:block true :reason "ok"})
                           5)
    (js-await (run agent "test"))
    (-> (expect @final-v) (.toBe "MUTATED BY EXTENSION"))))

(defn ^:async test-run-context-assembly-fires-before-provider-request []
  (let [agent (make-test-agent)
        order (atom [])]
    ((:on (:events agent)) "context_assembly"
                           (fn [_]
                             (swap! order conj "context_assembly")
                             nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_]
                             (swap! order conj "before_provider_request")
                             #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect (first @order)) (.toBe "context_assembly"))
    (-> (expect (second @order)) (.toBe "before_provider_request"))))

(defn ^:async test-run-after-provider-request-not-fired-when-blocked []
  (let [agent (make-test-agent)
        fired (atom false)]
    ((:on (:events agent)) "after_provider_request"
                           (fn [_] (reset! fired true)))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] #js {:block true :reason "blocked"}))
    (js-await (run agent "test"))
    (-> (expect @fired) (.toBe false))))

(describe "agent.loop/run - blocked path" (fn []
                                            (it "appends user message to state" test-run-appends-user-message)
                                            (it "emits agent_start and agent_end events" test-run-emits-agent-start-end)
                                            (it "stores blocked assistant response" test-run-blocked-stores-assistant-response)
                                            (it "uses default block reason" test-run-blocked-default-reason)
                                            (it "emits agent_end with block text" test-run-emits-agent-end-with-block-text)
                                            (it "before_agent_start modifies system prompt" test-run-before-agent-start-modifies-prompt)
                                            (it "tracks state via store dispatches" test-run-tracks-usage-via-store)
                                            (it "injects messages from extensions" test-run-inject-messages-from-extensions)))

(describe "agent.loop/run - new hooks" (fn []
                                         (it "before_provider_request config has providerOptions" test-run-provider-request-has-provider-options)
                                         (it "before_provider_request mutation persists across handlers" test-run-provider-request-mutation-persists)
                                         (it "context_assembly fires before before_provider_request" test-run-context-assembly-fires-before-provider-request)
                                         (it "after_provider_request not fired when blocked" test-run-after-provider-request-not-fired-when-blocked)))

;;; ─── G20: before_message_send ────────────────────────────────────────

(defn ^:async test-before-message-send-replaces-system-prompt []
  (let [agent       (make-test-agent)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_message_send"
                           (fn [_] #js {:system "REPLACED BY G20"}))
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect @seen-system) (.toBe "REPLACED BY G20"))))

(defn ^:async test-before-message-send-replaces-messages []
  (let [agent         (make-test-agent)
        seen-messages (atom nil)]
    ((:on (:events agent)) "before_message_send"
                           (fn [_]
                             #js {:messages #js [#js {:role "user" :content "injected-by-G20"}]}))
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-messages (.-messages config))
                             #js {:block true :reason "ok"}))
    (js-await (run agent "original"))
    (let [msgs (js/Array.from @seen-messages)
          contents (map #(.-content %) msgs)]
      (-> (expect (.some @seen-messages #(= (.-content %) "injected-by-G20")))
          (.toBe true)))))

(defn ^:async test-before-message-send-nil-has-no-effect []
  (let [agent       (make-test-agent)
        seen-system (atom nil)]
    ((:on (:events agent)) "before_message_send" (fn [_] nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    ;; System prompt should be unchanged from the agent's config
    (-> (expect @seen-system) (.toContain "You are a test agent."))))

(defn ^:async test-before-message-send-fires-after-context-assembly []
  (let [agent (make-test-agent)
        order (atom [])]
    ((:on (:events agent)) "context_assembly"
                           (fn [_] (swap! order conj "context_assembly") nil))
    ((:on (:events agent)) "before_message_send"
                           (fn [_] (swap! order conj "before_message_send") nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] (swap! order conj "before_provider_request")
                             #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    (-> (expect (first @order)) (.toBe "context_assembly"))
    (-> (expect (second @order)) (.toBe "before_message_send"))
    (-> (expect (nth @order 2)) (.toBe "before_provider_request"))))

(describe "agent.loop/run - G20 before_message_send" (fn []
                                                       (it "handler returning {system: '...'} replaces system prompt"
                                                           test-before-message-send-replaces-system-prompt)
                                                       (it "handler returning {messages: [...]} replaces message list"
                                                           test-before-message-send-replaces-messages)
                                                       (it "handler returning nil has no effect on system prompt"
                                                           test-before-message-send-nil-has-no-effect)
                                                       (it "fires after context_assembly and before before_provider_request"
                                                           test-before-message-send-fires-after-context-assembly)))
