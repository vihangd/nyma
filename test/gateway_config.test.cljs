(ns gateway-config.test
  "Unit tests for gateway.config — pure config parsing, env interpolation,
   and validation. No network / IO."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [gateway.config :as cfg]))

;;; ─── interpolate-env ─────────────────────────────────────────

(describe "gateway.config/interpolate-env" (fn []
                                             (it "replaces ${VAR} in string leaves"
                                                 (fn []
                                                   (aset (.-env js/process) "TEST_VAR_A" "hello")
                                                   (let [result (cfg/interpolate-env #js {:k "${TEST_VAR_A}"})]
                                                     (-> (expect (.-k result)) (.toBe "hello")))))

                                             (it "leaves unresolved ${VAR} tokens untouched"
                                                 (fn []
                                                   (js-delete (.-env js/process) "NO_SUCH_VAR_XXX")
                                                   (let [result (cfg/interpolate-env #js {:k "${NO_SUCH_VAR_XXX}"})]
                                                     (-> (expect (.-k result)) (.toBe "${NO_SUCH_VAR_XXX}")))))

                                             (it "recurses into nested objects"
                                                 (fn []
                                                   (aset (.-env js/process) "TEST_VAR_B" "deep")
                                                   (let [result (cfg/interpolate-env
                                                                 #js {:nested #js {:inner #js {:leaf "${TEST_VAR_B}"}}})]
                                                     (-> (expect (.. result -nested -inner -leaf)) (.toBe "deep")))))

                                             (it "recurses into arrays with mixed element types"
                                                 (fn []
                                                   (aset (.-env js/process) "TEST_VAR_C" "x")
                                                   (let [result (cfg/interpolate-env
                                                                 #js {:arr #js [1 "${TEST_VAR_C}" #js {:k "${TEST_VAR_C}"}]})]
                                                     (-> (expect (aget (.-arr result) 0)) (.toBe 1))
                                                     (-> (expect (aget (.-arr result) 1)) (.toBe "x"))
                                                     (-> (expect (.-k (aget (.-arr result) 2))) (.toBe "x")))))

                                             (it "leaves non-string leaves unchanged"
                                                 (fn []
                                                   (let [result (cfg/interpolate-env #js {:n 42 :b true :z nil})]
                                                     (-> (expect (.-n result)) (.toBe 42))
                                                     (-> (expect (.-b result)) (.toBe true))
                                                     (-> (expect (.-z result)) (.toBeNull)))))

                                             (it "handles strings with multiple ${VAR} tokens"
                                                 (fn []
                                                   (aset (.-env js/process) "TEST_VAR_D1" "foo")
                                                   (aset (.-env js/process) "TEST_VAR_D2" "bar")
                                                   (let [result (cfg/interpolate-env #js {:k "${TEST_VAR_D1}-${TEST_VAR_D2}"})]
                                                     (-> (expect (.-k result)) (.toBe "foo-bar")))))))

;;; ─── validate-config ─────────────────────────────────────────

(describe "gateway.config/validate-config" (fn []
                                             (it "rejects missing agent.model"
                                                 (fn []
                                                   (let [r (cfg/validate-config
                                                            {:channels [{:type "http" :name "web"}]})]
                                                     (-> (expect (:valid? r)) (.toBe false))
                                                     (-> (expect (some #(= % "agent.model is required") (:errors r)))
                                                         (.toBe true)))))

                                             (it "rejects empty channels array"
                                                 (fn []
                                                   (let [r (cfg/validate-config {:agent {:model "claude-test"}})]
                                                     (-> (expect (:valid? r)) (.toBe false))
                                                     (-> (expect (some #(.includes % "channels") (:errors r))) (.toBe true)))))

                                             (it "rejects channel with missing type"
                                                 (fn []
                                                   (let [r (cfg/validate-config
                                                            {:agent {:model "claude-test"}
                                                             :channels [{:name "x"}]})]
                                                     (-> (expect (:valid? r)) (.toBe false))
                                                     (-> (expect (some #(.includes % "channels[0].type") (:errors r)))
                                                         (.toBe true)))))

                                             (it "rejects channel with missing name"
                                                 (fn []
                                                   (let [r (cfg/validate-config
                                                            {:agent {:model "claude-test"}
                                                             :channels [{:type "http"}]})]
                                                     (-> (expect (:valid? r)) (.toBe false))
                                                     (-> (expect (some #(.includes % "channels[0].name") (:errors r)))
                                                         (.toBe true)))))

                                             (it "rejects duplicate channel names"
                                                 (fn []
                                                   (let [r (cfg/validate-config
                                                            {:agent {:model "claude-test"}
                                                             :channels [{:type "http" :name "same"}
                                                                        {:type "slack" :name "same"}]})]
                                                     (-> (expect (:valid? r)) (.toBe false))
                                                     (-> (expect (some #(.includes % "Duplicate") (:errors r))) (.toBe true)))))

                                             (it "accepts a minimal valid config"
                                                 (fn []
                                                   (let [r (cfg/validate-config
                                                            {:agent {:model "claude-test"}
                                                             :channels [{:type "http" :name "web"}]})]
                                                     (-> (expect (:valid? r)) (.toBe true)))))

                                             (it "returns ALL errors, not just the first"
                                                 (fn []
                                                   (let [r (cfg/validate-config
                                                            {:channels [{:name "x"}
                                                                        {:type "http"}]})]
                                                     (-> (expect (:valid? r)) (.toBe false))
          ;; Expect at least 3 errors: missing model, missing type, missing name
                                                     (-> (expect (>= (count (:errors r)) 3)) (.toBe true)))))))

;;; ─── streaming-policy-from-config ────────────────────────────

(describe "gateway.config/streaming-policy-from-config" (fn []
                                                          (it "defaults to :debounce when streaming section is absent"
                                                              (fn []
                                                                (-> (expect (cfg/streaming-policy-from-config {})) (.toBe :debounce))))

                                                          (it "returns a keyword for simple :policy value"
                                                              (fn []
                                                                (-> (expect (cfg/streaming-policy-from-config
                                                                             {:gateway {:streaming {:policy "immediate"}}}))
                                                                    (.toBe :immediate))))

                                                          (it "wraps :debounce in a map when :delay-ms is present"
                                                              (fn []
                                                                (let [r (cfg/streaming-policy-from-config
                                                                         {:gateway {:streaming {:policy "debounce" :delay-ms 250}}})]
                                                                  (-> (expect (:type r)) (.toBe :debounce))
                                                                  (-> (expect (:delay-ms r)) (.toBe 250)))))

                                                          (it "wraps :throttle in a map when :interval-ms is present"
                                                              (fn []
                                                                (let [r (cfg/streaming-policy-from-config
                                                                         {:gateway {:streaming {:policy "throttle" :interval-ms 750}}})]
                                                                  (-> (expect (:type r)) (.toBe :throttle))
                                                                  (-> (expect (:interval-ms r)) (.toBe 750)))))

                                                          (it "returns :batch-on-end unchanged"
                                                              (fn []
                                                                (-> (expect (cfg/streaming-policy-from-config
                                                                             {:gateway {:streaming {:policy "batch-on-end"}}}))
                                                                    (.toBe :batch-on-end))))))

;;; ─── session-pool-opts-from-config ───────────────────────────

(describe "gateway.config/session-pool-opts-from-config" (fn []
                                                           (it "returns sane defaults when session section is absent"
                                                               (fn []
                                                                 (let [opts (cfg/session-pool-opts-from-config {})]
                                                                   (-> (expect (:default-policy opts)) (.toBe :persistent))
                                                                   (-> (expect (:idle-evict-ms opts)) (.toBe 3600000))
                                                                   (-> (expect (:dedup-ttl-ms opts)) (.toBe 300000)))))

                                                           (it "converts :policy string to keyword"
                                                               (fn []
                                                                 (let [opts (cfg/session-pool-opts-from-config
                                                                             {:gateway {:session {:policy "idle-evict"}}})]
                                                                   (-> (expect (:default-policy opts)) (.toBe :idle-evict)))))

                                                           (it "uses provided :idle-evict-ms value"
                                                               (fn []
                                                                 (let [opts (cfg/session-pool-opts-from-config
                                                                             {:gateway {:session {:idle-evict-ms 1000}}})]
                                                                   (-> (expect (:idle-evict-ms opts)) (.toBe 1000)))))

                                                           (it "uses provided :dedup-ttl-ms from dedup section"
                                                               (fn []
                                                                 (let [opts (cfg/session-pool-opts-from-config
                                                                             {:gateway {:dedup {:cache-ttl-ms 99}}})]
                                                                   (-> (expect (:dedup-ttl-ms opts)) (.toBe 99)))))))

;;; ─── agent-opts-from-config ──────────────────────────────────

(describe "gateway.config/agent-opts-from-config" (fn []
                                                    (it "extracts :model"
                                                        (fn []
                                                          (-> (expect (:model (cfg/agent-opts-from-config
                                                                               {:agent {:model "claude-test"}})))
                                                              (.toBe "claude-test"))))

                                                    (it "passes through :system-prompt when present"
                                                        (fn []
                                                          (let [opts (cfg/agent-opts-from-config
                                                                      {:agent {:model "x" :system-prompt "be kind"}})]
                                                            (-> (expect (:system-prompt opts)) (.toBe "be kind")))))

                                                    (it "does not include :system-prompt when absent"
                                                        (fn []
                                                          (let [opts (cfg/agent-opts-from-config {:agent {:model "x"}})]
                                                            (-> (expect (contains? opts :system-prompt)) (.toBe false)))))

                                                    (it "converts :modes strings to a keyword set"
                                                        (fn []
                                                          (let [opts (cfg/agent-opts-from-config
                                                                      {:agent {:model "x" :modes ["gateway" "slim"]}})]
                                                            (-> (expect (contains? (:modes opts) :gateway)) (.toBe true))
                                                            (-> (expect (contains? (:modes opts) :slim)) (.toBe true)))))

                                                    (it "converts :exclude-capabilities strings to a keyword set"
                                                        (fn []
                                                          (let [opts (cfg/agent-opts-from-config
                                                                      {:agent {:model "x" :exclude-capabilities ["execution" "shell"]}})]
                                                            (-> (expect (contains? (:exclude-capabilities opts) :execution)) (.toBe true))
                                                            (-> (expect (contains? (:exclude-capabilities opts) :shell)) (.toBe true)))))

                                                    (it "converts :require-capabilities strings to a keyword set"
                                                        (fn []
                                                          (let [opts (cfg/agent-opts-from-config
                                                                      {:agent {:model "x" :require-capabilities ["read"]}})]
                                                            (-> (expect (contains? (:require-capabilities opts) :read)) (.toBe true)))))))
