(ns ext-custom-provider-openrouter.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.pricing :as pricing]
            [agent.extensions.custom-provider-openrouter.index :as or-ext]))

;; Instantiation tests verify that .chat() returns a model object but do NOT
;; exercise wire-level behavior (streaming, tool-calls, free-tier rate limits).
;; Only a live-key smoke test catches those.

;; ── Registration ─────────────────────────────────────────────

(describe "custom-provider-openrouter — registration" (fn []
                                                        (it "registers the openrouter provider with a create-model function"
                                                            (fn []
                                                              (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                    api   (create-extension-api agent)
                                                                    _     ((.-default or-ext) api)
                                                                    entry ((:get (:provider-registry agent)) "openrouter")]
                                                                (-> (expect (some? entry)) (.toBe true))
                                                                (-> (expect (fn? (:create-model entry))) (.toBe true)))))

                                                        (it "registers the curated free-model catalog"
                                                            (fn []
                                                              (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                                                    api    (create-extension-api agent)
                                                                    _      ((.-default or-ext) api)
                                                                    entry  ((:get (:provider-registry agent)) "openrouter")
                                                                    ids    (set (map (fn [m] (aget m "id")) (:models entry)))]
                                                                (-> (expect (count ids)) (.toBe 10))
                                                                (-> (expect (contains? ids "openrouter/free")) (.toBe true))
                                                                (-> (expect (contains? ids "openai/gpt-oss-120b:free")) (.toBe true))
                                                                (-> (expect (contains? ids "nvidia/nemotron-3-nano-30b-a3b:free")) (.toBe true)))))

                                                        (it "returns a cleanup function that unregisters the provider"
                                                            (fn []
                                                              (let [agent   (create-agent {:model "test" :system-prompt "test"})
                                                                    api     (create-extension-api agent)
                                                                    cleanup ((.-default or-ext) api)]
                                                                (-> (expect (fn? cleanup)) (.toBe true))
                                                                (cleanup)
                                                                (-> (expect (nil? ((:get (:provider-registry agent)) "openrouter")))
                                                                    (.toBe true)))))))

;; ── Model metadata ────────────────────────────────────────────

(describe "custom-provider-openrouter — model metadata" (fn []
                                                          (it "registers 200K context window for openrouter/free"
                                                              (fn []
                                                                (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                      api   (create-extension-api agent)
                                                                      _     ((.-default or-ext) api)
                                                                      info  ((:get (:model-registry agent)) "openrouter/free")]
                                                                  (-> (expect (:context-window info)) (.toBe 200000)))))

                                                          (it "registers 131072 context window for openai/gpt-oss-120b:free"
                                                              (fn []
                                                                (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                      api   (create-extension-api agent)
                                                                      _     ((.-default or-ext) api)
                                                                      info  ((:get (:model-registry agent)) "openai/gpt-oss-120b:free")]
                                                                  (-> (expect (:context-window info)) (.toBe 131072)))))

                                                          (it "registers 256K context window for nvidia/nemotron-3-nano-30b-a3b:free"
                                                              (fn []
                                                                (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                      api   (create-extension-api agent)
                                                                      _     ((.-default or-ext) api)
                                                                      info  ((:get (:model-registry agent)) "nvidia/nemotron-3-nano-30b-a3b:free")]
                                                                  (-> (expect (:context-window info)) (.toBe 256000)))))

                                                          (it "registers zero-cost pricing for all models"
                                                              (fn []
                                                                (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                      api   (create-extension-api agent)
                                                                      _     ((.-default or-ext) api)]
                                                                  (doseq [id ["openrouter/free"
                                                                              "openai/gpt-oss-120b:free"
                                                                              "nvidia/nemotron-3-nano-30b-a3b:free"
                                                                              "qwen/qwen3-coder:free"]]
                                                                    (let [costs (get @pricing/token-costs id)]
                                                                      (-> (expect (first costs)) (.toBe 0))
                                                                      (-> (expect (second costs)) (.toBe 0)))))))))

;; ── Base URL resolution ─────────────────────────────────────

(describe "custom-provider-openrouter — base URL" (fn []
                                                    (it "defaults to https://openrouter.ai/api/v1"
                                                        (fn []
                                                          (let [orig (aget js/process.env "OPENROUTER_BASE_URL")]
                                                            (try
                                                              (js-delete js/process.env "OPENROUTER_BASE_URL")
                                                              (-> (expect (or-ext/resolve-base-url))
                                                                  (.toBe "https://openrouter.ai/api/v1"))
                                                              (finally
                                                                (if orig
                                                                  (aset js/process.env "OPENROUTER_BASE_URL" orig)
                                                                  (js-delete js/process.env "OPENROUTER_BASE_URL")))))))

                                                    (it "uses OPENROUTER_BASE_URL when set"
                                                        (fn []
                                                          (let [orig (aget js/process.env "OPENROUTER_BASE_URL")]
                                                            (try
                                                              (aset js/process.env "OPENROUTER_BASE_URL" "https://custom.example.com/v1")
                                                              (-> (expect (or-ext/resolve-base-url))
                                                                  (.toBe "https://custom.example.com/v1"))
                                                              (finally
                                                                (if orig
                                                                  (aset js/process.env "OPENROUTER_BASE_URL" orig)
                                                                  (js-delete js/process.env "OPENROUTER_BASE_URL")))))))))

;; ── Credential resolution ────────────────────────────────────

(describe "custom-provider-openrouter — credential resolution" (fn []
                                                                 (it "creates a model when OPENROUTER_API_KEY is set"
                                                                     (fn []
                                                                       (let [orig (aget js/process.env "OPENROUTER_API_KEY")]
                                                                         (try
                                                                           (aset js/process.env "OPENROUTER_API_KEY" "sk-or-test-env")
                                                                           (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                 api   (create-extension-api agent)
                                                                                 _     ((.-default or-ext) api)
                                                                                 entry ((:get (:provider-registry agent)) "openrouter")
                                                                                 model ((:create-model entry) "openai/gpt-oss-120b:free")]
                                                                             (-> (expect (some? model)) (.toBe true)))
                                                                           (finally
                                                                             (if orig
                                                                               (aset js/process.env "OPENROUTER_API_KEY" orig)
                                                                               (js-delete js/process.env "OPENROUTER_API_KEY")))))))

                                                                 (it "creates a model for the meta-router openrouter/free"
                                                                     (fn []
                                                                       (let [orig (aget js/process.env "OPENROUTER_API_KEY")]
                                                                         (try
                                                                           (aset js/process.env "OPENROUTER_API_KEY" "sk-or-test-env")
                                                                           (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                 api   (create-extension-api agent)
                                                                                 _     ((.-default or-ext) api)
                                                                                 entry ((:get (:provider-registry agent)) "openrouter")
                                                                                 model ((:create-model entry) "openrouter/free")]
                                                                             (-> (expect (some? model)) (.toBe true)))
                                                                           (finally
                                                                             (if orig
                                                                               (aset js/process.env "OPENROUTER_API_KEY" orig)
                                                                               (js-delete js/process.env "OPENROUTER_API_KEY")))))))

                                                                 (it "reads key from credentials.json when env var is absent"
                                                                     (fn []
                                                                       (let [orig-key  (aget js/process.env "OPENROUTER_API_KEY")
                                                                             orig-home (aget js/process.env "HOME")
                                                                             tmp-dir   (fs/mkdtempSync "/tmp/nyma-openrouter-test-")]
                                                                         (try
                                                                           (js-delete js/process.env "OPENROUTER_API_KEY")
                                                                           (aset js/process.env "HOME" tmp-dir)
                                                                           (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                             (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                             (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                               (js/JSON.stringify #js {"openrouter" "sk-or-from-file"})
                                                                                               "utf8"))
                                                                           (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                 api   (create-extension-api agent)
                                                                                 _     ((.-default or-ext) api)
                                                                                 entry ((:get (:provider-registry agent)) "openrouter")
                                                                                 model ((:create-model entry) "openrouter/free")]
                                                                             (-> (expect (some? model)) (.toBe true)))
                                                                           (finally
                                                                             (aset js/process.env "HOME" orig-home)
                                                                             (if orig-key
                                                                               (aset js/process.env "OPENROUTER_API_KEY" orig-key)
                                                                               (js-delete js/process.env "OPENROUTER_API_KEY"))
                                                                             (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                                 (it "env var takes precedence over credentials.json"
                                                                     (fn []
                                                                       (let [orig-key  (aget js/process.env "OPENROUTER_API_KEY")
                                                                             orig-home (aget js/process.env "HOME")
                                                                             tmp-dir   (fs/mkdtempSync "/tmp/nyma-openrouter-prec-")]
                                                                         (try
                                                                           (aset js/process.env "OPENROUTER_API_KEY" "sk-or-from-env")
                                                                           (aset js/process.env "HOME" tmp-dir)
                                                                           (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                             (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                             (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                               (js/JSON.stringify #js {"openrouter" "sk-or-from-file"})
                                                                                               "utf8"))
                                                                           (-> (expect (or-ext/resolve-api-key))
                                                                               (.toBe "sk-or-from-env"))
                                                                           (finally
                                                                             (aset js/process.env "HOME" orig-home)
                                                                             (if orig-key
                                                                               (aset js/process.env "OPENROUTER_API_KEY" orig-key)
                                                                               (js-delete js/process.env "OPENROUTER_API_KEY"))
                                                                             (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                                 (it "throws a helpful error when no credentials are available"
                                                                     (fn []
                                                                       (let [orig-key  (aget js/process.env "OPENROUTER_API_KEY")
                                                                             orig-home (aget js/process.env "HOME")
                                                                             tmp-dir   (fs/mkdtempSync "/tmp/nyma-openrouter-nokey-")]
                                                                         (try
                                                                           (js-delete js/process.env "OPENROUTER_API_KEY")
                                                                           (aset js/process.env "HOME" tmp-dir)
                                                                           (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                 api   (create-extension-api agent)
                                                                                 _     ((.-default or-ext) api)
                                                                                 entry ((:get (:provider-registry agent)) "openrouter")]
                                                                             (-> (expect (fn [] ((:create-model entry) "openrouter/free")))
                                                                                 (.toThrow "OPENROUTER_API_KEY"))
                                                                             (-> (expect (fn [] ((:create-model entry) "openrouter/free")))
                                                                                 (.toThrow "/login openrouter")))
                                                                           (finally
                                                                             (aset js/process.env "HOME" orig-home)
                                                                             (if orig-key
                                                                               (aset js/process.env "OPENROUTER_API_KEY" orig-key)
                                                                               (js-delete js/process.env "OPENROUTER_API_KEY"))
                                                                             (fs/rmSync tmp-dir #js {:recursive true :force true}))))))))
