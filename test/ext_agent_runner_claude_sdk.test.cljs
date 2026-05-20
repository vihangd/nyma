(ns ext-agent-runner-claude-sdk.test
  "Unit tests for the Agent SDK in-process runner.

   All tests use a mock SDK (an object with a fake `query` async generator)
   so no real Claude process is needed. Tests pin:
     - SDKMessage routing (text deltas, tool starts, result/usage)
     - session-id capture from system/init
     - conn-map shape returned by create-in-process-connection
     - registry accept/reject behaviour with :in-process? flag"
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-runner-claude-sdk.index :as runner]))

;;; ─── Helpers ──────────────────────────────────────────────────

(defn- make-mock-api []
  (let [emits (atom [])]
    #js {:emitGlobal (fn [ev data] (swap! emits conj {:event ev :data data}))
         :ui         #js {:available false}
         :_emits     emits}))

(defn- make-conn
  ([api] (make-conn api nil))
  ([api sdk] (runner/create-in-process-connection "claude-sdk" {} api sdk)))

(defn- make-sdk-gen
  "Return a mock SDK module whose `query()` yields the given messages."
  [messages]
  (let [msgs (atom (vec messages))]
    #js {:query (fn [_params]
                  (let [gen #js {}]
                    (aset gen "next"
                          (fn []
                            (if (seq @msgs)
                              (let [msg (first @msgs)]
                                (swap! msgs rest)
                                (js/Promise.resolve #js {:done false :value msg}))
                              (js/Promise.resolve #js {:done true :value js/undefined}))))
                    gen))}))

(defn- sys-init-msg [session-id]
  #js {:type "system" :subtype "init" :session_id session-id})

(defn- text-delta-msg [text]
  #js {:type "stream_event"
       :event #js {:type "content_block_delta"
                   :delta #js {:type "text_delta" :text text}}})

(defn- tool-start-msg [id name]
  #js {:type "stream_event"
       :event #js {:type "content_block_start"
                   :content_block #js {:type "tool_use" :id id :name name}}})

(defn- result-msg [input-toks output-toks cost]
  #js {:type "result" :subtype "success"
       :usage #js {:input_tokens input-toks :output_tokens output-toks :total_tokens (+ input-toks output-toks)}
       :total_cost_usd cost})

;;; ─── conn-map shape ───────────────────────────────────────────

(describe "agent-runner-claude-sdk:create-in-process-connection"
          (fn []
            (it "returns a map with the required conn-map fields"
                (fn []
                  (let [api  (make-mock-api)
                        conn (make-conn api)]
                    (-> (expect (:in-process? conn)) (.toBe true))
                    (-> (expect (nil? (:proc conn))) (.toBe true))
                    (-> (expect (nil? (:stdin conn))) (.toBe true))
                    (-> (expect (fn? (:sdk-query conn))) (.toBe true))
                    (-> (expect (fn? (:emit conn))) (.toBe true))
                    (-> (expect (some? (:session-id conn))) (.toBe true))
                    (-> (expect (some? (:prompt-state conn))) (.toBe true)))))

            (it "session-id atom starts nil"
                (fn []
                  (let [conn (make-conn (make-mock-api))]
                    (-> (expect (nil? @(:session-id conn))) (.toBe true)))))))

;;; ─── SDKMessage routing ───────────────────────────────────────

(describe "agent-runner-claude-sdk:send-prompt-sdk"
          (fn []
            (beforeEach (fn []
                          (reset! shared/stream-callback nil)
                          (reset! shared/thought-callback nil)))

            (it "captures session-id from system/init message" ^:async
                (fn []
                  (let [sdk  (make-sdk-gen [(sys-init-msg "sess-xyz") (result-msg 10 20 0.001)])
                        api  (make-mock-api)
                        conn (make-conn api sdk)]
                    (js-await ((:sdk-query conn) conn "hello"))
                    (-> (expect @(:session-id conn)) (.toBe "sess-xyz")))))

            (it "accumulates text deltas via stream-callback and prompt-state" ^:async
                (fn []
                  (let [sdk    (make-sdk-gen [(sys-init-msg "s1")
                                              (text-delta-msg "Hello")
                                              (text-delta-msg ", world")
                                              (result-msg 5 10 0.0)])
                        api    (make-mock-api)
                        conn   (make-conn api sdk)
                        chunks (atom [])
                        _      (reset! shared/stream-callback (fn [c] (swap! chunks conj c)))
                        result (js-await ((:sdk-query conn) conn "hi"))]
                    (-> (expect (count @chunks)) (.toBe 2))
                    (-> (expect (first @chunks)) (.toBe "Hello"))
                    (-> (expect (:text result)) (.toBe "Hello, world"))
                    (-> (expect (:text @(:prompt-state conn))) (.toBe "Hello, world")))))

            (it "tracks tool-call in prompt-state :tool-calls" ^:async
                (fn []
                  (let [sdk    (make-sdk-gen [(sys-init-msg "s2")
                                              (tool-start-msg "tc-1" "Read")
                                              (result-msg 5 10 0.0)])
                        api    (make-mock-api)
                        conn   (make-conn api sdk)
                        result (js-await ((:sdk-query conn) conn "read file"))]
                    (-> (expect (count (:tool-calls result))) (.toBe 1))
                    (-> (expect (:toolName (first (:tool-calls result)))) (.toBe "Read"))
                    (-> (expect (count (:tool-calls @(:prompt-state conn)))) (.toBe 1)))))

            (it "captures usage from result message" ^:async
                (fn []
                  (let [sdk    (make-sdk-gen [(sys-init-msg "s3") (result-msg 100 200 0.015)])
                        conn   (make-conn (make-mock-api) sdk)
                        result (js-await ((:sdk-query conn) conn "hi"))]
                    (-> (expect (:input-tokens (:usage result))) (.toBe 100))
                    (-> (expect (:output-tokens (:usage result))) (.toBe 200)))))

            (it "emits acp_connect event on init" ^:async
                (fn []
                  (let [sdk  (make-sdk-gen [(sys-init-msg "sess-emit") (result-msg 1 1 0.0)])
                        api  (make-mock-api)
                        conn (make-conn api sdk)]
                    (js-await ((:sdk-query conn) conn "ping"))
                    (let [connect-ev (some #(when (= "acp_connect" (:event %)) %) @(.-_emits api))]
                      (-> (expect (some? connect-ev)) (.toBe true))))))

            (it "handles generator with no system/init (skips session-id)" ^:async
                (fn []
                  (let [sdk    (make-sdk-gen [(text-delta-msg "bare") (result-msg 1 1 0.0)])
                        conn   (make-conn (make-mock-api) sdk)
                        result (js-await ((:sdk-query conn) conn "bare"))]
                    (-> (expect (:text result)) (.toBe "bare"))
                    (-> (expect (nil? @(:session-id conn))) (.toBe true)))))

            (it "stops iteration when result message received (doesn't drain further)" ^:async
                (fn []
                  (let [extra-msg #js {:type "unknown-after-result"}
                        sdk       (make-sdk-gen [(sys-init-msg "s4")
                                                 (result-msg 1 1 0.0)
                                                 extra-msg])
                        conn      (make-conn (make-mock-api) sdk)]
                    ;; Should not crash and should resolve normally
                    (let [result (js-await ((:sdk-query conn) conn "done"))]
                      (-> (expect (some? result)) (.toBe true))))))))

;;; ─── Registry: :in-process? agent ────────────────────────────

(describe "registry:normalize-agent-config for in-process agents"
          (fn []
            (beforeEach (fn [] (registry/reset-dynamic!)))

            (it "accepts in-process agent without :command"
                (fn []
                  (let [ok (registry/register-agent!
                            "test-inproc"
                            {:in-process? true
                             :name        "Test"
                             :features    #{:cost}
                             :create-fn   (fn [_ _ _] nil)})]
                    (-> (expect ok) (.toBe true)))))

            (it "returns false for agent with no :command and no :in-process?"
                (fn []
                  (let [ok (registry/register-agent!
                            "test-bad"
                            {:name "Bad" :features #{}})]
                    (-> (expect ok) (.toBe false)))))

            (it "preserves :create-fn and :in-process? through normalization"
                (fn []
                  (let [cfn (fn [_ _ _] nil)
                        _   (registry/register-agent!
                             "test-passthrough"
                             {:in-process? true :name "T" :create-fn cfn})
                        def (registry/get-agent "test-passthrough")]
                    (-> (expect (:in-process? def)) (.toBe true))
                    (-> (expect (= (:create-fn def) cfn)) (.toBe true)))))))
