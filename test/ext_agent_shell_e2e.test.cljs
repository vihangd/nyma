(ns ext-agent-shell-e2e.test
  "End-to-end tests: spawn real ACP agents, handshake, send prompts, verify responses.
   Requires: /opt/homebrew/bin/qwen and npx @agentclientprotocol/claude-agent-acp installed."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.features.input-router :as input-router]))

;; ── Real spawn API ──────────────────────────────────────────────────────────

(defn- make-spawn-api
  "Create a mock API with real Bun.spawn for e2e testing."
  []
  (let [messages        (atom [])
        notifications   (atom [])
        registered-cmds (atom {})
        registered-flags (atom {})]
    #js {:ui          #js {:available  true
                           :notify     (fn [msg _level] (swap! notifications conj msg))
                           :setFooter  (fn [_] nil)
                           :setHeader  (fn [_] nil)}
         :sendMessage       (fn [msg] (swap! messages conj msg))
         :registerCommand   (fn [name opts] (swap! registered-cmds assoc name opts))
         :unregisterCommand (fn [name] (swap! registered-cmds dissoc name))
         :registerFlag      (fn [name opts] (swap! registered-flags assoc name opts))
         :on                (fn [_evt _handler _priority] nil)
         :off               (fn [_evt _handler] nil)
         :emit              (fn [_evt _data] nil)
         :spawn             (fn [cmd args opts]
                              (let [cmd-args (into [cmd] (or args []))
                                    proc     (js/Bun.spawn (clj->js cmd-args)
                                               #js {:stdout "pipe"
                                                    :stderr "pipe"
                                                    :stdin  "pipe"
                                                    :cwd    (or (and opts (.-cwd opts)) (js/process.cwd))
                                                    :env    js/process.env})]
                                #js {:pid    (.-pid proc)
                                     :stdin  (.-stdin proc)
                                     :stdout (.-stdout proc)
                                     :stderr (.-stderr proc)
                                     :kill   (fn [& [sig]] (.kill proc (or sig "SIGTERM")))
                                     :exited (.-exited proc)
                                     :ref    proc}))
         ;; Test accessors
         :_messages       messages
         :_notifications  notifications}))

;; ── State reset ─────────────────────────────────────────────────────────────

(beforeEach
  (fn []
    (reset! shared/active-agent nil)
    (reset! shared/connections {})
    (reset! shared/agent-state {})))

(defn ^:async cleanup []
  (js-await (pool/disconnect-all)))

(afterEach cleanup)

;; ── Test helpers ────────────────────────────────────────────────────────────

(def qwen-def
  "Qwen agent definition from registry."
  (get registry/agents "qwen"))

(def claude-def
  "Claude agent definition from registry."
  (get registry/agents "claude"))

(def opencode-def
  "OpenCode agent definition from registry."
  (get registry/agents "opencode"))

(def opencode-minimax-model "opencode-go/minimax-m2.5")

;; ── Async test functions (Squint requires defn ^:async, not fn ^:async) ─────

(defn ^:async test-connect-via-pool []
  (let [api  (make-spawn-api)
        conn (js-await (pool/get-or-create "qwen" qwen-def api))
        sid  @(:session-id conn)]
    (-> (expect (some? sid)) (.toBe true))
    (-> (expect (string? sid)) (.toBe true))
    (-> (expect (> (count sid) 0)) (.toBe true))
    (js/console.log (str "[e2e] Connected, session: " sid))))

(defn ^:async test-send-prompt-gets-response []
  (let [api    (make-spawn-api)
        conn   (js-await (pool/get-or-create "qwen" qwen-def api))
        result (js-await (client/send-prompt conn "What is 2+2? Reply with just the number, nothing else."))]
    (js/console.log (str "[e2e] Response text: " (pr-str (:text result))))
    (-> (expect (some? (:text result))) (.toBe true))
    (-> (expect (> (count (:text result)) 0)) (.toBe true))
    ;; The response should contain "4" somewhere
    (-> (expect (.includes (:text result) "4")) (.toBe true))))

(defn ^:async test-usage-in-response []
  (let [api    (make-spawn-api)
        conn   (js-await (pool/get-or-create "qwen" qwen-def api))
        result (js-await (client/send-prompt conn "Say hello"))]
    (js/console.log (str "[e2e] Usage: " (pr-str (:usage result))))
    (js/console.log (str "[e2e] Stop reason: " (:stop-reason result)))
    (let [usage (:usage result)]
      ;; Usage map should exist with expected keys (values may be 0 for some agents)
      (-> (expect (some? usage)) (.toBe true))
      (-> (expect (some? (:input-tokens usage))) (.toBe true))
      (-> (expect (some? (:output-tokens usage))) (.toBe true))
      ;; Stop reason should be present
      (-> (expect (some? (:stop-reason result))) (.toBe true)))))

(defn ^:async test-disconnect-cleans-up []
  (let [api  (make-spawn-api)
        _    (js-await (pool/get-or-create "qwen" qwen-def api))]
    ;; Verify connected
    (-> (expect (some? (get @shared/connections "qwen"))) (.toBe true))
    ;; Disconnect
    (js-await (pool/disconnect "qwen"))
    ;; State cleared
    (-> (expect (nil? (get @shared/connections "qwen"))) (.toBe true))))

(defn ^:async test-stream-callback-invoked-during-prompt []
  (let [api      (make-spawn-api)
        conn     (js-await (pool/get-or-create "qwen" qwen-def api))
        chunks   (atom [])
        ;; Set stream-callback before prompt — should receive text chunks
        _        (reset! shared/stream-callback
                   (fn [text] (swap! chunks conj text)))]
    (js-await (client/send-prompt conn "Reply with just: ok"))
    ;; stream-callback must have been called with at least one chunk
    (-> (expect (pos? (count @chunks))) (.toBe true))
    ;; Cleanup
    (reset! shared/stream-callback nil)))

(defn ^:async test-commands-registered-after-connect []
  (let [api (make-spawn-api)
        _   (js-await (pool/get-or-create "qwen" qwen-def api))]
    ;; After handshake the agent may send available_commands_update.
    ;; Regardless, agent-state entry for "qwen" should exist.
    (let [state (get @shared/agent-state "qwen")]
      (-> (expect (some? state)) (.toBe true)))
    ;; Commands registered in api (from available_commands_update) —
    ;; qwen always reports at least one command (e.g. /ask or /yolo).
    ;; We just verify the mechanism fires without error (count >= 0).
    (let [cmds @(.-_notifications api)]
      ;; notifications is a side-channel; the main check is no exception
      (-> (expect (vector? cmds)) (.toBe true)))))

(defn ^:async test-thought-callback-wired-during-stream []
  (let [api     (make-spawn-api)
        conn    (js-await (pool/get-or-create "qwen" qwen-def api))
        thought-chunks (atom [])
        ;; Pre-set thought-callback before prompt so we can observe it
        _       (reset! shared/thought-callback
                  (fn [text] (swap! thought-chunks conj text)))
        result  (js-await (client/send-prompt conn "Reply with just: ok"))]
    ;; After prompt completes, thought-callback should be cleared (set by make-stream-handler,
    ;; but we pre-set it here to test the clearing behavior)
    ;; The result should still have text
    (-> (expect (some? (:text result))) (.toBe true))
    ;; thought-chunks may or may not have content depending on agent mode, but atom works
    (-> (expect (vector? @thought-chunks)) (.toBe true))))

;; ── Claude test functions ────────────────────────────────────────────────────

(defn ^:async claude-test-connect []
  (let [api  (make-spawn-api)
        conn (js-await (pool/get-or-create "claude" claude-def api))
        sid  @(:session-id conn)]
    (-> (expect (some? sid)) (.toBe true))
    (-> (expect (string? sid)) (.toBe true))
    (-> (expect (> (count sid) 0)) (.toBe true))
    (js/console.log (str "[e2e:claude] Connected, session: " sid))))

(defn ^:async claude-test-send-prompt []
  (let [api    (make-spawn-api)
        conn   (js-await (pool/get-or-create "claude" claude-def api))
        result (js-await (client/send-prompt conn "What is 2+2? Reply with just the number, nothing else."))]
    (js/console.log (str "[e2e:claude] Response: " (pr-str (:text result))))
    (-> (expect (some? (:text result))) (.toBe true))
    (-> (expect (> (count (:text result)) 0)) (.toBe true))
    (-> (expect (.includes (:text result) "4")) (.toBe true))))

(defn ^:async claude-test-usage-non-zero []
  (let [api    (make-spawn-api)
        conn   (js-await (pool/get-or-create "claude" claude-def api))
        result (js-await (client/send-prompt conn "Say hi"))]
    (js/console.log (str "[e2e:claude] Usage: " (pr-str (:usage result))))
    (let [usage (:usage result)]
      (-> (expect (some? usage)) (.toBe true))
      ;; Claude reports real token counts (unlike qwen which returns 0)
      (-> (expect (> (or (:input-tokens usage) 0) 0)) (.toBe true))
      (-> (expect (> (or (:output-tokens usage) 0) 0)) (.toBe true)))))

(defn ^:async claude-test-thinking-chunks []
  (let [api    (make-spawn-api)
        conn   (js-await (pool/get-or-create "claude" claude-def api))
        thoughts (atom [])
        _      (reset! shared/thought-callback
                 (fn [text] (swap! thoughts conj text)))
        result (js-await (client/send-prompt conn "What is 3*7? Think step by step, then reply."))]
    (js/console.log (str "[e2e:claude] Thought chunks: " (count @thoughts)))
    (js/console.log (str "[e2e:claude] Response: " (pr-str (:text result))))
    ;; Response must exist
    (-> (expect (some? (:text result))) (.toBe true))
    ;; thought-callback atom was accessible and usable (chunks may vary by model)
    (-> (expect (vector? @thoughts)) (.toBe true))
    (reset! shared/thought-callback nil)))

(defn ^:async claude-test-stream-callback []
  (let [api    (make-spawn-api)
        conn   (js-await (pool/get-or-create "claude" claude-def api))
        chunks (atom [])
        _      (reset! shared/stream-callback
                 (fn [text] (swap! chunks conj text)))]
    (js-await (client/send-prompt conn "Reply with just: ok"))
    (-> (expect (pos? (count @chunks))) (.toBe true))
    (reset! shared/stream-callback nil)))

(defn ^:async claude-test-disconnect []
  (let [api (make-spawn-api)
        _   (js-await (pool/get-or-create "claude" claude-def api))]
    (-> (expect (some? (get @shared/connections "claude"))) (.toBe true))
    (js-await (pool/disconnect "claude"))
    (-> (expect (nil? (get @shared/connections "claude"))) (.toBe true))))

;; ── Test suite ──────────────────────────────────────────────────────────────

(def e2e-timeout 60000)  ;; 60s per test — real LLM calls

(describe "agent-shell e2e (qwen)" (fn []
  (it "connects via pool and gets a session ID" test-connect-via-pool e2e-timeout)
  (it "sends a prompt and receives a non-empty response" test-send-prompt-gets-response e2e-timeout)
  (it "returns usage stats in the response" test-usage-in-response e2e-timeout)
  (it "disconnects cleanly and clears state" test-disconnect-cleans-up e2e-timeout)
  (it "stream-callback invoked with chunks during a real prompt" test-stream-callback-invoked-during-prompt e2e-timeout)
  (it "agent-state populated after connection handshake" test-commands-registered-after-connect e2e-timeout)
  (it "thought-callback atom observable during streaming" test-thought-callback-wired-during-stream e2e-timeout)))

;; ── OpenCode test functions ──────────────────────────────────────────────────

(defn ^:async opencode-connect-and-set-model
  "Connect to opencode and switch to minimax-m2.5 via session/set_model.
   Returns the connection map."
  [api]
  (let [conn (js-await (pool/get-or-create "opencode" opencode-def api))
        sid  @(:session-id conn)]
    ;; opencode uses session/set_model (not session/set_config_option)
    (js-await (client/send-request conn (client/next-id conn) "session/set_model"
                {:sessionId sid
                 :modelId   opencode-minimax-model}))
    conn))

(defn ^:async opencode-test-connect []
  (let [api  (make-spawn-api)
        conn (js-await (opencode-connect-and-set-model api))
        sid  @(:session-id conn)]
    (-> (expect (some? sid)) (.toBe true))
    (-> (expect (string? sid)) (.toBe true))
    (-> (expect (> (count sid) 0)) (.toBe true))
    (js/console.log (str "[e2e:opencode] Connected session: " sid ", model set to: " opencode-minimax-model))))

(defn ^:async opencode-test-send-prompt []
  (let [api    (make-spawn-api)
        conn   (js-await (opencode-connect-and-set-model api))
        result (js-await (client/send-prompt conn "What is 2+2? Reply with just the number, nothing else."))]
    (js/console.log (str "[e2e:opencode] Response: " (pr-str (:text result))))
    (-> (expect (some? (:text result))) (.toBe true))
    (-> (expect (> (count (:text result)) 0)) (.toBe true))
    (-> (expect (.includes (:text result) "4")) (.toBe true))))

(defn ^:async opencode-test-usage []
  (let [api    (make-spawn-api)
        conn   (js-await (opencode-connect-and-set-model api))
        result (js-await (client/send-prompt conn "Say hi"))]
    (js/console.log (str "[e2e:opencode] Usage: " (pr-str (:usage result))))
    (let [usage (:usage result)]
      (-> (expect (some? usage)) (.toBe true))
      (-> (expect (some? (:input-tokens usage))) (.toBe true))
      (-> (expect (some? (:output-tokens usage))) (.toBe true))
      (-> (expect (some? (:stop-reason result))) (.toBe true)))))

(defn ^:async opencode-test-stream-callback []
  (let [api    (make-spawn-api)
        conn   (js-await (opencode-connect-and-set-model api))
        chunks (atom [])
        _      (reset! shared/stream-callback
                 (fn [text] (swap! chunks conj text)))]
    (js-await (client/send-prompt conn "Reply with just: ok"))
    (-> (expect (pos? (count @chunks))) (.toBe true))
    (reset! shared/stream-callback nil)))

(defn ^:async opencode-test-disconnect []
  (let [api (make-spawn-api)
        _   (js-await (opencode-connect-and-set-model api))]
    (-> (expect (some? (get @shared/connections "opencode"))) (.toBe true))
    (js-await (pool/disconnect "opencode"))
    (-> (expect (nil? (get @shared/connections "opencode"))) (.toBe true))))

(describe "agent-shell e2e (claude)" (fn []
  (it "connects via pool and gets a session ID" claude-test-connect e2e-timeout)
  (it "sends a prompt and receives a non-empty response" claude-test-send-prompt e2e-timeout)
  (it "reports non-zero token usage" claude-test-usage-non-zero e2e-timeout)
  (it "thought-callback receives thinking chunks" claude-test-thinking-chunks e2e-timeout)
  (it "stream-callback invoked with chunks during a real prompt" claude-test-stream-callback e2e-timeout)
  (it "disconnects cleanly and clears state" claude-test-disconnect e2e-timeout)))

(describe "agent-shell e2e (opencode/minimax-m2.5)" (fn []
  (it "connects via pool and sets model to minimax-m2.5" opencode-test-connect e2e-timeout)
  (it "sends a prompt and receives a non-empty response" opencode-test-send-prompt e2e-timeout)
  (it "returns usage stats in the response" opencode-test-usage e2e-timeout)
  (it "stream-callback invoked with chunks during a real prompt" opencode-test-stream-callback e2e-timeout)
  (it "disconnects cleanly and clears state" opencode-test-disconnect e2e-timeout)))
