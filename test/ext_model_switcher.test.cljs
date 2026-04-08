(ns ext-model-switcher.test
  "Comprehensive tests for the model switcher feature."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.features.model-switcher :as model-switcher]))

;;; ─── Mock API ─────────────────────────────────────────────────

(defn- make-mock-api []
  (let [registered-commands (atom {})
        notifications       (atom [])
        custom-calls        (atom [])
        input-calls         (atom [])]
    #js {:ui          #js {:available  true
                           :notify     (fn [msg _level] (swap! notifications conj msg))
                           :custom     (fn [picker] (swap! custom-calls conj picker))
                           :input      (fn [title placeholder]
                                         (swap! input-calls conj {:title title :placeholder placeholder})
                                         (js/Promise.resolve nil))}
         :registerCommand   (fn [name opts]
                              (swap! registered-commands assoc name opts))
         :unregisterCommand (fn [name]
                              (swap! registered-commands dissoc name))
         :getGlobalFlag     (fn [_name] nil)
         :_commands         registered-commands
         :_notifications    notifications
         :_custom           custom-calls
         :_inputs           input-calls}))

;;; ─── Mock connection ──────────────────────────────────────────

(defn- make-mock-conn
  "Create a mock ACP connection with capturing stdin."
  [agent-key]
  (let [writes (atom [])]
    {:stdin      #js {:write (fn [data] (swap! writes conj data) nil)
                      :flush (fn [] nil)}
     :state      (atom {:pending {}})
     :session-id (atom "test-session-123")
     :id-counter (atom 0)
     :agent-key  agent-key
     :_writes    writes}))

(defn- parse-last-rpc
  "Parse the last JSON-RPC message written to a mock connection's stdin."
  [conn]
  (when-let [w (last @(:_writes conn))]
    (js/JSON.parse w)))

;;; ─── Test data ────────────────────────────────────────────────

(def test-models
  [{:id "opencode-go/minimax-m2.5" :display "Minimax M2.5"}
   {:id "opencode-go/qwen-coder"   :display "Qwen Coder"}
   {:id "anthropic/claude-sonnet"   :display "Claude Sonnet"}
   {:id "google/gemini-2.5-pro"    :display "Gemini 2.5 Pro"}
   {:id "minimax/standard"         :display "Minimax Standard"}])

;;; ─── State reset ──────────────────────────────────────────────

(beforeEach
  (fn []
    (reset! shared/active-agent nil)
    (reset! shared/connections {})
    (reset! shared/agent-state {})))

(afterEach
  (fn []
    (reset! shared/active-agent nil)
    (reset! shared/connections {})
    (reset! shared/agent-state {})))

;;; ─── Group 1: Registry — :model-method ────────────────────────

(describe "model-switcher:registry" (fn []
  (it "opencode has :model-method :set_model"
    (fn []
      (-> (expect (:model-method (get registry/agents :opencode))) (.toBe :set_model))))

  (it "claude has no :model-method (defaults to nil)"
    (fn []
      (-> (expect (nil? (:model-method (get registry/agents :claude)))) (.toBe true))))

  (it "all agents have :model-config-id"
    (fn []
      (doseq [[_k v] registry/agents]
        (-> (expect (some? (:model-config-id v))) (.toBe true)))))))

;;; ─── Group 2: Method dispatch ─────────────────────────────────

(describe "model-switcher:method-dispatch" (fn []
  (it "calls session/set_config_option for claude"
    (fn []
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "model"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["opus-4"] nil)
        (let [rpc (parse-last-rpc conn)]
          (-> (expect (.-method rpc)) (.toBe "session/set_config_option"))))))

  (it "calls session/set_model for opencode"
    (fn []
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            conn    (make-mock-conn :opencode)
            handler (.-handler (get @(.-_commands api) "model"))]
        (reset! shared/active-agent :opencode)
        (reset! shared/connections {:opencode conn})
        (handler #js ["opencode-go/minimax-m2.5"] nil)
        (let [rpc (parse-last-rpc conn)]
          (-> (expect (.-method rpc)) (.toBe "session/set_model"))))))

  (it "session/set_config_option params include configId from registry"
    (fn []
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "model"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["some-model"] nil)
        (let [rpc    (parse-last-rpc conn)
              params (.-params rpc)]
          (-> (expect (.-configId params)) (.toBe "model"))
          (-> (expect (.-value params)) (.toBe "some-model"))))))

  (it "session/set_model params include modelId matching requested model"
    (fn []
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            conn    (make-mock-conn :opencode)
            handler (.-handler (get @(.-_commands api) "model"))]
        (reset! shared/active-agent :opencode)
        (reset! shared/connections {:opencode conn})
        (handler #js ["opencode-go/test-model"] nil)
        (let [rpc    (parse-last-rpc conn)
              params (.-params rpc)]
          (-> (expect (.-modelId params)) (.toBe "opencode-go/test-model"))))))))

;;; ─── Group 3: apply-model-filter (pure function) ──────────────

(describe "model-switcher:filtering" (fn []
  (it "no filter returns all models"
    (fn []
      (let [result (model-switcher/apply-model-filter test-models nil)]
        (-> (expect (count result)) (.toBe 5)))))

  (it "empty filter array returns all models"
    (fn []
      (let [result (model-switcher/apply-model-filter test-models [])]
        (-> (expect (count result)) (.toBe 5)))))

  (it "single pattern filters to matching models"
    (fn []
      (let [result (model-switcher/apply-model-filter test-models ["opencode-go"])]
        (-> (expect (count result)) (.toBe 2))
        (-> (expect (:id (first result))) (.toBe "opencode-go/minimax-m2.5"))
        (-> (expect (:id (second result))) (.toBe "opencode-go/qwen-coder")))))

  (it "multiple patterns match ANY pattern (OR logic)"
    (fn []
      (let [result (model-switcher/apply-model-filter test-models ["opencode-go" "minimax"])]
        ;; opencode-go/minimax-m2.5 matches both, opencode-go/qwen-coder matches "opencode-go",
        ;; minimax/standard matches "minimax" = 3 total
        (-> (expect (count result)) (.toBe 3)))))

  (it "filter is case-insensitive"
    (fn []
      (let [models [{:id "OpenCode-Go/Model" :display "Mixed Case"}]
            result (model-switcher/apply-model-filter models ["opencode-go"])]
        (-> (expect (count result)) (.toBe 1)))))

  (it "filter with no matching patterns returns empty"
    (fn []
      (let [result (model-switcher/apply-model-filter test-models ["nonexistent"])]
        (-> (expect (count result)) (.toBe 0)))))

  (it "filter matches substring anywhere in id"
    (fn []
      (let [result (model-switcher/apply-model-filter test-models ["sonnet"])]
        (-> (expect (count result)) (.toBe 1))
        (-> (expect (:id (first result))) (.toBe "anthropic/claude-sonnet")))))))

;;; ─── Group 4: /model list command ─────────────────────────────

(describe "model-switcher:list-command" (fn []
  (it "/model list with no agent connected shows error"
    (fn []
      (reset! shared/active-agent nil)
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (-> (expect (some #(str/includes? % "No agent connected")
                          @(.-_notifications api)))
            (.toBe true)))))

  (it "/model list with empty model list shows message"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models [])
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (-> (expect (some #(str/includes? % "No models available")
                          @(.-_notifications api)))
            (.toBe true)))))

  (it "/model list shows all model ids"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models test-models)
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (let [notif (str/join "\n" @(.-_notifications api))]
          (-> (expect (str/includes? notif "opencode-go/minimax-m2.5")) (.toBe true))
          (-> (expect (str/includes? notif "anthropic/claude-sonnet")) (.toBe true))
          (-> (expect (str/includes? notif "google/gemini-2.5-pro")) (.toBe true))))))

  (it "/model list shows display name when different from id"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models [{:id "opus" :display "Claude Opus 4.6"}])
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (let [notif (str/join "\n" @(.-_notifications api))]
          (-> (expect (str/includes? notif "(Claude Opus 4.6)")) (.toBe true))))))

  (it "/model list marks current model with *"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models
        [{:id "model-a" :display "Model A"}
         {:id "model-b" :display "Model B"}
         {:id "model-c" :display "Model C"}])
      (shared/update-agent-state! :claude :model "model-b")
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (let [notif (str/join "\n" @(.-_notifications api))
              lines (.split notif "\n")]
          ;; Find the line with model-b — should have *
          (let [b-line (some #(when (str/includes? % "model-b") %) lines)
                a-line (some #(when (str/includes? % "model-a") %) lines)]
            (-> (expect (str/includes? b-line "*")) (.toBe true))
            (-> (expect (str/includes? a-line "*")) (.toBe false)))))))

  (it "/model list omits display name when same as id"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models [{:id "opus" :display "opus"}])
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (let [notif (str/join "\n" @(.-_notifications api))]
          ;; Should NOT have "(opus)" since id equals display
          (-> (expect (str/includes? notif "(opus)")) (.toBe false))
          ;; But should have "opus"
          (-> (expect (str/includes? notif "opus")) (.toBe true))))))))

;;; ─── Group 5: Existing basic tests ────────────────────────────

(describe "model-switcher:basics" (fn []
  (it "registers /model command on activate"
    (fn []
      (let [api (make-mock-api)
            _   (model-switcher/activate api)]
        (-> (expect (contains? @(.-_commands api) "model")) (.toBe true)))))

  (it "deactivator unregisters /model"
    (fn []
      (let [api   (make-mock-api)
            deact (model-switcher/activate api)]
        (deact)
        (-> (expect (contains? @(.-_commands api) "model")) (.toBe false)))))

  (it "/model <name> with no active agent notifies error"
    (fn []
      (reset! shared/active-agent nil)
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["opus-4"] nil)
        (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
            (.toBe true)))))

  (it "/model with no args and no agent notifies error"
    (fn []
      (reset! shared/active-agent nil)
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js [] nil)
        (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
            (.toBe true)))))))

;;; ─── Group 6: Picker integration ──────────────────────────────

(describe "model-switcher:picker" (fn []
  (it "/model with no args and models shows picker via ui.custom"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models test-models)
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js [] nil)
        (-> (expect (pos? (count @(.-_custom api)))) (.toBe true)))))

  (it "/model with no args and no models falls back to text input"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models [])
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js [] nil)
        (-> (expect (pos? (count @(.-_inputs api)))) (.toBe true)))))))

;;; ─── Group 7: Edge cases ──────────────────────────────────────

(describe "model-switcher:edge-cases" (fn []
  (it "model count shown in /model list header"
    (fn []
      (reset! shared/active-agent :claude)
      (shared/update-agent-state! :claude :models test-models)
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "model"))]
        (handler #js ["list"] nil)
        (let [notif (str/join "\n" @(.-_notifications api))]
          (-> (expect (str/includes? notif "Models (5)")) (.toBe true))))))

  (it "session/set_config_option includes sessionId"
    (fn []
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "model"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["test-model"] nil)
        (let [rpc    (parse-last-rpc conn)
              params (.-params rpc)]
          (-> (expect (.-sessionId params)) (.toBe "test-session-123"))))))

  (it "session/set_model includes sessionId"
    (fn []
      (let [api     (make-mock-api)
            _       (model-switcher/activate api)
            conn    (make-mock-conn :opencode)
            handler (.-handler (get @(.-_commands api) "model"))]
        (reset! shared/active-agent :opencode)
        (reset! shared/connections {:opencode conn})
        (handler #js ["some-model"] nil)
        (let [rpc    (parse-last-rpc conn)
              params (.-params rpc)]
          (-> (expect (.-sessionId params)) (.toBe "test-session-123"))))))))
