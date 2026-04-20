(ns ext-custom-provider-claude-native.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extensions.custom-provider-claude-native.index :as claude-ext]))

;; ── Registration ─────────────────────────────────────────────

(describe "custom-provider-claude-native — registration" (fn [])
          (it "registers the claude-native provider with a create-model function"
              (fn []
                (let [agent (create-agent {:model "test" :system-prompt "test"})
                      api   (create-extension-api agent)
                      _     ((.-default claude-ext) api)
                      entry ((:get (:provider-registry agent)) "claude-native")]
                  (-> (expect (some? entry)) (.toBe true))
                  (-> (expect (fn? (:create-model entry))) (.toBe true)))))

          (it "registers 4 Claude models"
              (fn []
                (let [agent  (create-agent {:model "test" :system-prompt "test"})
                      api    (create-extension-api agent)
                      _      ((.-default claude-ext) api)
                      entry  ((:get (:provider-registry agent)) "claude-native")
                      models (:models entry)]
                  (-> (expect (count models)) (.toBe 4)))))

          (it "registers expected model IDs"
              (fn []
                (let [agent  (create-agent {:model "test" :system-prompt "test"})
                      api    (create-extension-api agent)
                      _      ((.-default claude-ext) api)
                      entry  ((:get (:provider-registry agent)) "claude-native")
                      ids    (set (map :id (:models entry)))]
                  (-> (expect (contains? ids "claude-sonnet-4-6")) (.toBe true))
                  (-> (expect (contains? ids "claude-opus-4-7"))   (.toBe true))
                  (-> (expect (contains? ids "claude-opus-4-6"))   (.toBe true))
                  (-> (expect (contains? ids "claude-haiku-4-5-20251001")) (.toBe true)))))

          (it "returns a cleanup function that unregisters the provider"
              (fn []
                (let [agent   (create-agent {:model "test" :system-prompt "test"})
                      api     (create-extension-api agent)
                      cleanup ((.-default claude-ext) api)]
                  (-> (expect (fn? cleanup)) (.toBe true))
                  (cleanup)
                  (-> (expect (nil? ((:get (:provider-registry agent)) "claude-native")))
                      (.toBe true))))))

;; ── Model metadata wiring ────────────────────────────────────

(describe "custom-provider-claude-native — model metadata" (fn [])
          (it "registers 200,000-token context window for claude-sonnet-4-6"
              (fn []
                (let [agent (create-agent {:model "test" :system-prompt "test"})
                      api   (create-extension-api agent)
                      _     ((.-default claude-ext) api)
                      info  ((:get (:model-registry agent)) "claude-sonnet-4-6")]
                  (-> (expect (:context-window info)) (.toBe 200000)))))

          (it "registers 200,000-token context window for claude-opus-4-7"
              (fn []
                (let [agent (create-agent {:model "test" :system-prompt "test"})
                      api   (create-extension-api agent)
                      _     ((.-default claude-ext) api)
                      info  ((:get (:model-registry agent)) "claude-opus-4-7")]
                  (-> (expect (:context-window info)) (.toBe 200000))))))

;; ── Credential resolution ────────────────────────────────────

(describe "custom-provider-claude-native — credential resolution" (fn [])
          (it "creates a model object when ANTHROPIC_API_KEY env var is set"
              (fn []
                (let [orig (aget js/process.env "ANTHROPIC_API_KEY")]
                  (try
                    (aset js/process.env "ANTHROPIC_API_KEY" "sk-ant-test-env")
                    (let [agent (create-agent {:model "test" :system-prompt "test"})
                          api   (create-extension-api agent)
                          _     ((.-default claude-ext) api)
                          entry ((:get (:provider-registry agent)) "claude-native")
                          model ((:create-model entry) "claude-sonnet-4-6")]
                      (-> (expect (some? model))              (.toBe true))
                      (-> (expect (.-specificationVersion model)) (.toBe "v3"))
                      (-> (expect (.-provider model))         (.toBe "claude-native"))
                      (-> (expect (.-modelId model))          (.toBe "claude-sonnet-4-6")))
                    (finally
                      (if orig
                        (aset js/process.env "ANTHROPIC_API_KEY" orig)
                        (js-delete js/process.env "ANTHROPIC_API_KEY")))))))

          (it "reads key from credentials.json 'anthropic' entry when env var is absent"
              (fn []
                (let [orig-key  (aget js/process.env "ANTHROPIC_API_KEY")
                      orig-home (aget js/process.env "HOME")
                      tmp-dir   (fs/mkdtempSync "/tmp/nyma-claude-native-test-")]
                  (try
                    (js-delete js/process.env "ANTHROPIC_API_KEY")
                    (aset js/process.env "HOME" tmp-dir)
                    (let [nyma-dir (path/join tmp-dir ".nyma")]
                      (fs/mkdirSync nyma-dir #js {:recursive true})
                      (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                        (js/JSON.stringify #js {"anthropic" "sk-ant-from-file"})
                                        "utf8"))
                    (-> (expect (claude-ext/resolve-api-key)) (.toBe "sk-ant-from-file"))
                    (finally
                      (aset js/process.env "HOME" orig-home)
                      (if orig-key
                        (aset js/process.env "ANTHROPIC_API_KEY" orig-key)
                        (js-delete js/process.env "ANTHROPIC_API_KEY"))
                      (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

          (it "ANTHROPIC_API_KEY takes precedence over credentials.json"
              (fn []
                (let [orig-key  (aget js/process.env "ANTHROPIC_API_KEY")
                      orig-home (aget js/process.env "HOME")
                      tmp-dir   (fs/mkdtempSync "/tmp/nyma-claude-native-prec-")]
                  (try
                    (aset js/process.env "ANTHROPIC_API_KEY" "sk-ant-from-env")
                    (aset js/process.env "HOME" tmp-dir)
                    (let [nyma-dir (path/join tmp-dir ".nyma")]
                      (fs/mkdirSync nyma-dir #js {:recursive true})
                      (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                        (js/JSON.stringify #js {"anthropic" "sk-ant-from-file"})
                                        "utf8"))
                    (-> (expect (claude-ext/resolve-api-key)) (.toBe "sk-ant-from-env"))
                    (finally
                      (aset js/process.env "HOME" orig-home)
                      (if orig-key
                        (aset js/process.env "ANTHROPIC_API_KEY" orig-key)
                        (js-delete js/process.env "ANTHROPIC_API_KEY"))
                      (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

          (it "throws a helpful error when no credentials are available"
              (fn []
                (let [orig-key  (aget js/process.env "ANTHROPIC_API_KEY")
                      orig-home (aget js/process.env "HOME")
                      tmp-dir   (fs/mkdtempSync "/tmp/nyma-claude-native-nokey-")]
                  (try
                    (js-delete js/process.env "ANTHROPIC_API_KEY")
                    (aset js/process.env "HOME" tmp-dir)
                    (let [agent (create-agent {:model "test" :system-prompt "test"})
                          api   (create-extension-api agent)
                          _     ((.-default claude-ext) api)
                          entry ((:get (:provider-registry agent)) "claude-native")]
                      (-> (expect (fn [] ((:create-model entry) "claude-sonnet-4-6")))
                          (.toThrow "ANTHROPIC_API_KEY"))
                      (-> (expect (fn [] ((:create-model entry) "claude-sonnet-4-6")))
                          (.toThrow "/login anthropic")))
                    (finally
                      (aset js/process.env "HOME" orig-home)
                      (if orig-key
                        (aset js/process.env "ANTHROPIC_API_KEY" orig-key)
                        (js-delete js/process.env "ANTHROPIC_API_KEY"))
                      (fs/rmSync tmp-dir #js {:recursive true :force true})))))))

;; ── LanguageModelV3 shape ────────────────────────────────────

(describe "custom-provider-claude-native — model object shape" (fn [])
          (it "model object has required LanguageModelV3 fields"
              (fn []
                (let [orig (aget js/process.env "ANTHROPIC_API_KEY")]
                  (try
                    (aset js/process.env "ANTHROPIC_API_KEY" "sk-ant-test")
                    (let [agent (create-agent {:model "test" :system-prompt "test"})
                          api   (create-extension-api agent)
                          _     ((.-default claude-ext) api)
                          entry ((:get (:provider-registry agent)) "claude-native")
                          model ((:create-model entry) "claude-opus-4-7")]
                      (-> (expect (.-specificationVersion model)) (.toBe "v3"))
                      (-> (expect (.-provider model))             (.toBe "claude-native"))
                      (-> (expect (.-modelId model))              (.toBe "claude-opus-4-7"))
                      (-> (expect (fn? (.-doStream model)))       (.toBe true))
                      (-> (expect (fn? (.-doGenerate model)))     (.toBe true)))
                    (finally
                      (if orig
                        (aset js/process.env "ANTHROPIC_API_KEY" orig)
                        (js-delete js/process.env "ANTHROPIC_API_KEY")))))))

          (it "each registered model resolves to a distinct model object"
              (fn []
                (let [orig (aget js/process.env "ANTHROPIC_API_KEY")]
                  (try
                    (aset js/process.env "ANTHROPIC_API_KEY" "sk-ant-test")
                    (let [agent (create-agent {:model "test" :system-prompt "test"})
                          api   (create-extension-api agent)
                          _     ((.-default claude-ext) api)
                          entry ((:get (:provider-registry agent)) "claude-native")]
                      (doseq [id ["claude-haiku-4-5-20251001" "claude-sonnet-4-6"
                                  "claude-opus-4-6" "claude-opus-4-7"]]
                        (let [model ((:create-model entry) id)]
                          (-> (expect (.-modelId model)) (.toBe id)))))
                    (finally
                      (if orig
                        (aset js/process.env "ANTHROPIC_API_KEY" orig)
                        (js-delete js/process.env "ANTHROPIC_API_KEY"))))))))
