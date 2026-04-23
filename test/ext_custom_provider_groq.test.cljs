(ns ext-custom-provider-groq.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extensions.custom-provider-groq.index :as groq-ext]))

;; ── Registration ─────────────────────────────────────────────

(describe "custom-provider-groq — registration" (fn []
                                                  (it "registers the groq provider with a create-model function"
                                                      (fn []
                                                        (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                              api   (create-extension-api agent)
                                                              _     ((.-default groq-ext) api)
                                                              entry ((:get (:provider-registry agent)) "groq")]
                                                          (-> (expect (some? entry)) (.toBe true))
                                                          (-> (expect (fn? (:create-model entry))) (.toBe true)))))

                                                  (it "registers all 9 models"
                                                      (fn []
                                                        (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                                              api    (create-extension-api agent)
                                                              _      ((.-default groq-ext) api)
                                                              entry  ((:get (:provider-registry agent)) "groq")
                                                              ids    (set (map (fn [m] (aget m "id")) (:models entry)))]
                                                          (-> (expect (count ids)) (.toBe 9))
                                                          (-> (expect (contains? ids "llama-3.3-70b-versatile")) (.toBe true))
                                                          (-> (expect (contains? ids "openai/gpt-oss-120b")) (.toBe true))
                                                          (-> (expect (contains? ids "meta-llama/llama-4-scout-17b-16e-instruct")) (.toBe true))
                                                          (-> (expect (contains? ids "qwen/qwen3-32b")) (.toBe true)))))

                                                  (it "returns a cleanup function that unregisters the provider"
                                                      (fn []
                                                        (let [agent   (create-agent {:model "test" :system-prompt "test"})
                                                              api     (create-extension-api agent)
                                                              cleanup ((.-default groq-ext) api)]
                                                          (-> (expect (fn? cleanup)) (.toBe true))
                                                          (cleanup)
                                                          (-> (expect (nil? ((:get (:provider-registry agent)) "groq")))
                                                              (.toBe true)))))))

;; ── Model metadata ────────────────────────────────────────────

(describe "custom-provider-groq — model metadata" (fn []
                                                    (it "registers 128K context window for all models"
                                                        (fn []
                                                          (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                api   (create-extension-api agent)
                                                                _     ((.-default groq-ext) api)]
                                                            (doseq [id ["llama-3.3-70b-versatile"
                                                                        "llama-3.1-8b-instant"
                                                                        "openai/gpt-oss-120b"
                                                                        "openai/gpt-oss-20b"
                                                                        "qwen/qwen3-32b"]]
                                                              (let [info ((:get (:model-registry agent)) id)]
                                                                (-> (expect (:context-window info)) (.toBe 131072)))))))

                                                    (it "marks reasoning models correctly"
                                                        (fn []
                                                          (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                api   (create-extension-api agent)
                                                                _     ((.-default groq-ext) api)
                                                                entry ((:get (:provider-registry agent)) "groq")
                                                                by-id (into {} (map (fn [m] [(aget m "id") m]) (:models entry)))]
        ;; reasoning models
                                                            (doseq [id ["openai/gpt-oss-120b" "openai/gpt-oss-20b" "qwen/qwen3-32b"]]
                                                              (-> (expect (aget (get by-id id) "reasoning")) (.toBe true)))
        ;; non-reasoning models
                                                            (doseq [id ["llama-3.3-70b-versatile" "llama-3.1-8b-instant"]]
                                                              (-> (expect (aget (get by-id id) "reasoning")) (.toBe false))))))))

;; ── Base URL resolution ─────────────────────────────────────

(describe "custom-provider-groq — base URL" (fn []
                                              (it "defaults to https://api.groq.com/openai/v1"
                                                  (fn []
                                                    (let [orig (aget js/process.env "GROQ_BASE_URL")]
                                                      (try
                                                        (js-delete js/process.env "GROQ_BASE_URL")
                                                        (-> (expect (groq-ext/resolve-base-url))
                                                            (.toBe "https://api.groq.com/openai/v1"))
                                                        (finally
                                                          (if orig
                                                            (aset js/process.env "GROQ_BASE_URL" orig)
                                                            (js-delete js/process.env "GROQ_BASE_URL")))))))

                                              (it "uses GROQ_BASE_URL when set"
                                                  (fn []
                                                    (let [orig (aget js/process.env "GROQ_BASE_URL")]
                                                      (try
                                                        (aset js/process.env "GROQ_BASE_URL" "https://custom.example.com/v1")
                                                        (-> (expect (groq-ext/resolve-base-url))
                                                            (.toBe "https://custom.example.com/v1"))
                                                        (finally
                                                          (if orig
                                                            (aset js/process.env "GROQ_BASE_URL" orig)
                                                            (js-delete js/process.env "GROQ_BASE_URL")))))))))

;; ── Credential resolution ────────────────────────────────────

(describe "custom-provider-groq — credential resolution" (fn []
                                                           (it "creates a model when GROQ_API_KEY is set"
                                                               (fn []
                                                                 (let [orig (aget js/process.env "GROQ_API_KEY")]
                                                                   (try
                                                                     (aset js/process.env "GROQ_API_KEY" "gsk_test")
                                                                     (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                           api   (create-extension-api agent)
                                                                           _     ((.-default groq-ext) api)
                                                                           entry ((:get (:provider-registry agent)) "groq")
                                                                           model ((:create-model entry) "llama-3.3-70b-versatile")]
                                                                       (-> (expect (some? model)) (.toBe true)))
                                                                     (finally
                                                                       (if orig
                                                                         (aset js/process.env "GROQ_API_KEY" orig)
                                                                         (js-delete js/process.env "GROQ_API_KEY")))))))

                                                           (it "reads key from credentials.json when env var is absent"
                                                               (fn []
                                                                 (let [orig-key  (aget js/process.env "GROQ_API_KEY")
                                                                       orig-home (aget js/process.env "HOME")
                                                                       tmp-dir   (fs/mkdtempSync "/tmp/nyma-groq-test-")]
                                                                   (try
                                                                     (js-delete js/process.env "GROQ_API_KEY")
                                                                     (aset js/process.env "HOME" tmp-dir)
                                                                     (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                       (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                       (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                         (js/JSON.stringify #js {"groq" "gsk_from_file"})
                                                                                         "utf8"))
                                                                     (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                           api   (create-extension-api agent)
                                                                           _     ((.-default groq-ext) api)
                                                                           entry ((:get (:provider-registry agent)) "groq")
                                                                           model ((:create-model entry) "llama-3.1-8b-instant")]
                                                                       (-> (expect (some? model)) (.toBe true)))
                                                                     (finally
                                                                       (aset js/process.env "HOME" orig-home)
                                                                       (if orig-key
                                                                         (aset js/process.env "GROQ_API_KEY" orig-key)
                                                                         (js-delete js/process.env "GROQ_API_KEY"))
                                                                       (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                           (it "env var takes precedence over credentials.json"
                                                               (fn []
                                                                 (let [orig-key  (aget js/process.env "GROQ_API_KEY")
                                                                       orig-home (aget js/process.env "HOME")
                                                                       tmp-dir   (fs/mkdtempSync "/tmp/nyma-groq-prec-")]
                                                                   (try
                                                                     (aset js/process.env "GROQ_API_KEY" "gsk_from_env")
                                                                     (aset js/process.env "HOME" tmp-dir)
                                                                     (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                       (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                       (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                         (js/JSON.stringify #js {"groq" "gsk_from_file"})
                                                                                         "utf8"))
                                                                     (-> (expect (groq-ext/resolve-api-key)) (.toBe "gsk_from_env"))
                                                                     (finally
                                                                       (aset js/process.env "HOME" orig-home)
                                                                       (if orig-key
                                                                         (aset js/process.env "GROQ_API_KEY" orig-key)
                                                                         (js-delete js/process.env "GROQ_API_KEY"))
                                                                       (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                           (it "throws a helpful error when no credentials are available"
                                                               (fn []
                                                                 (let [orig-key  (aget js/process.env "GROQ_API_KEY")
                                                                       orig-home (aget js/process.env "HOME")
                                                                       tmp-dir   (fs/mkdtempSync "/tmp/nyma-groq-nokey-")]
                                                                   (try
                                                                     (js-delete js/process.env "GROQ_API_KEY")
                                                                     (aset js/process.env "HOME" tmp-dir)
                                                                     (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                           api   (create-extension-api agent)
                                                                           _     ((.-default groq-ext) api)
                                                                           entry ((:get (:provider-registry agent)) "groq")]
                                                                       (-> (expect (fn [] ((:create-model entry) "llama-3.3-70b-versatile")))
                                                                           (.toThrow "GROQ_API_KEY"))
                                                                       (-> (expect (fn [] ((:create-model entry) "llama-3.3-70b-versatile")))
                                                                           (.toThrow "/login groq")))
                                                                     (finally
                                                                       (aset js/process.env "HOME" orig-home)
                                                                       (if orig-key
                                                                         (aset js/process.env "GROQ_API_KEY" orig-key)
                                                                         (js-delete js/process.env "GROQ_API_KEY"))
                                                                       (fs/rmSync tmp-dir #js {:recursive true :force true}))))))))
