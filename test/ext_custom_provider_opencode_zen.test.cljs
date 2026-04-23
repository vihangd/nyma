(ns ext-custom-provider-opencode-zen.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.pricing :as pricing]
            [agent.extensions.custom-provider-opencode-zen.index :as zen-ext]))

;; Note: instantiation tests verify that .chat() and .responses() objects are
;; created, but do NOT catch wire-level divergence between Zen and upstream (e.g.
;; missing usage.input_tokens on Zen streams). Only a live-key smoke test catches
;; those — see the manual-verification section in the plan file.

;; ── Registration ─────────────────────────────────────────────

(describe "custom-provider-opencode-zen — registration" (fn []
                                                          (it "registers the opencode-zen provider with a create-model function"
                                                              (fn []
                                                                (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                      api   (create-extension-api agent)
                                                                      _     ((.-default zen-ext) api)
                                                                      entry ((:get (:provider-registry agent)) "opencode-zen")]
                                                                  (-> (expect (some? entry)) (.toBe true))
                                                                  (-> (expect (fn? (:create-model entry))) (.toBe true)))))

                                                          (it "registers the expected number of models"
                                                              (fn []
                                                                (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                                                      api    (create-extension-api agent)
                                                                      _      ((.-default zen-ext) api)
                                                                      entry  ((:get (:provider-registry agent)) "opencode-zen")
                                                                      models (:models entry)]
        ;; 5 free + 14 GPT-5.x responses + 8 chat/completions paid = 27
                                                                  (-> (expect (count models)) (.toBe 27)))))

                                                          (it "returns a cleanup function that unregisters the provider"
                                                              (fn []
                                                                (let [agent   (create-agent {:model "test" :system-prompt "test"})
                                                                      api     (create-extension-api agent)
                                                                      cleanup ((.-default zen-ext) api)]
                                                                  (-> (expect (fn? cleanup)) (.toBe true))
                                                                  (cleanup)
                                                                  (-> (expect (nil? ((:get (:provider-registry agent)) "opencode-zen")))
                                                                      (.toBe true)))))))

;; ── Model metadata ────────────────────────────────────────────

(describe "custom-provider-opencode-zen — model metadata" (fn []
                                                            (it "registers 400K context window for gpt-5-nano"
                                                                (fn []
                                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                        api   (create-extension-api agent)
                                                                        _     ((.-default zen-ext) api)
                                                                        info  ((:get (:model-registry agent)) "gpt-5-nano")]
                                                                    (-> (expect (:context-window info)) (.toBe 400000)))))

                                                            (it "registers 204K context window for minimax-m2.5-free"
                                                                (fn []
                                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                        api   (create-extension-api agent)
                                                                        _     ((.-default zen-ext) api)
                                                                        info  ((:get (:model-registry agent)) "minimax-m2.5-free")]
                                                                    (-> (expect (:context-window info)) (.toBe 204800)))))

                                                            (it "registers zero-cost pricing for free models"
                                                                (fn []
                                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                        api   (create-extension-api agent)
                                                                        _     ((.-default zen-ext) api)
                                                                        costs (get @pricing/token-costs "minimax-m2.5-free")]
                                                                    (-> (expect (first costs)) (.toBe 0))
                                                                    (-> (expect (second costs)) (.toBe 0)))))

                                                            (it "registers non-zero pricing for paid models"
                                                                (fn []
                                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                        api   (create-extension-api agent)
                                                                        _     ((.-default zen-ext) api)
                                                                        costs (get @pricing/token-costs "gpt-5.4-pro")]
                                                                    (-> (expect (first costs)) (.toBe 30.0))
                                                                    (-> (expect (second costs)) (.toBe 180.0)))))))

;; ── Base URL resolution ─────────────────────────────────────

(describe "custom-provider-opencode-zen — base URL" (fn []
                                                      (it "defaults to https://opencode.ai/zen/v1"
                                                          (fn []
                                                            (let [orig (aget js/process.env "OPENCODE_ZEN_BASE_URL")]
                                                              (try
                                                                (js-delete js/process.env "OPENCODE_ZEN_BASE_URL")
                                                                (-> (expect (zen-ext/resolve-base-url))
                                                                    (.toBe "https://opencode.ai/zen/v1"))
                                                                (finally
                                                                  (if orig
                                                                    (aset js/process.env "OPENCODE_ZEN_BASE_URL" orig)
                                                                    (js-delete js/process.env "OPENCODE_ZEN_BASE_URL")))))))

                                                      (it "uses OPENCODE_ZEN_BASE_URL when set"
                                                          (fn []
                                                            (let [orig (aget js/process.env "OPENCODE_ZEN_BASE_URL")]
                                                              (try
                                                                (aset js/process.env "OPENCODE_ZEN_BASE_URL" "https://custom.example.com/v1")
                                                                (-> (expect (zen-ext/resolve-base-url))
                                                                    (.toBe "https://custom.example.com/v1"))
                                                                (finally
                                                                  (if orig
                                                                    (aset js/process.env "OPENCODE_ZEN_BASE_URL" orig)
                                                                    (js-delete js/process.env "OPENCODE_ZEN_BASE_URL")))))))))

;; ── Credential resolution ────────────────────────────────────

(describe "custom-provider-opencode-zen — credential resolution" (fn []
                                                                   (it "creates a model when OPENCODE_ZEN_API_KEY is set (chat branch)"
                                                                       (fn []
                                                                         (let [orig (aget js/process.env "OPENCODE_ZEN_API_KEY")]
                                                                           (try
                                                                             (aset js/process.env "OPENCODE_ZEN_API_KEY" "sk-test-env")
                                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                   api   (create-extension-api agent)
                                                                                   _     ((.-default zen-ext) api)
                                                                                   entry ((:get (:provider-registry agent)) "opencode-zen")
                                                                                   model ((:create-model entry) "big-pickle")]
                                                                               (-> (expect (some? model)) (.toBe true)))
                                                                             (finally
                                                                               (if orig
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY")))))))

                                                                   (it "creates a model when OPENCODE_ZEN_API_KEY is set (responses branch)"
                                                                       (fn []
                                                                         (let [orig (aget js/process.env "OPENCODE_ZEN_API_KEY")]
                                                                           (try
                                                                             (aset js/process.env "OPENCODE_ZEN_API_KEY" "sk-test-env")
                                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                   api   (create-extension-api agent)
                                                                                   _     ((.-default zen-ext) api)
                                                                                   entry ((:get (:provider-registry agent)) "opencode-zen")
                                                                                   model ((:create-model entry) "gpt-5-nano")]
                                                                               (-> (expect (some? model)) (.toBe true)))
                                                                             (finally
                                                                               (if orig
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY")))))))

                                                                   (it "falls back to OPENCODE_API_KEY alias"
                                                                       (fn []
                                                                         (let [orig-primary (aget js/process.env "OPENCODE_ZEN_API_KEY")
                                                                               orig-alias   (aget js/process.env "OPENCODE_API_KEY")]
                                                                           (try
                                                                             (js-delete js/process.env "OPENCODE_ZEN_API_KEY")
                                                                             (aset js/process.env "OPENCODE_API_KEY" "sk-from-alias")
                                                                             (-> (expect (zen-ext/resolve-api-key)) (.toBe "sk-from-alias"))
                                                                             (finally
                                                                               (if orig-primary
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig-primary)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY"))
                                                                               (if orig-alias
                                                                                 (aset js/process.env "OPENCODE_API_KEY" orig-alias)
                                                                                 (js-delete js/process.env "OPENCODE_API_KEY")))))))

                                                                   (it "OPENCODE_ZEN_API_KEY takes precedence over OPENCODE_API_KEY"
                                                                       (fn []
                                                                         (let [orig-primary (aget js/process.env "OPENCODE_ZEN_API_KEY")
                                                                               orig-alias   (aget js/process.env "OPENCODE_API_KEY")]
                                                                           (try
                                                                             (aset js/process.env "OPENCODE_ZEN_API_KEY" "sk-primary")
                                                                             (aset js/process.env "OPENCODE_API_KEY" "sk-alias")
                                                                             (-> (expect (zen-ext/resolve-api-key)) (.toBe "sk-primary"))
                                                                             (finally
                                                                               (if orig-primary
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig-primary)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY"))
                                                                               (if orig-alias
                                                                                 (aset js/process.env "OPENCODE_API_KEY" orig-alias)
                                                                                 (js-delete js/process.env "OPENCODE_API_KEY")))))))

                                                                   (it "reads key from credentials.json when env vars are absent"
                                                                       (fn []
                                                                         (let [orig-primary (aget js/process.env "OPENCODE_ZEN_API_KEY")
                                                                               orig-alias   (aget js/process.env "OPENCODE_API_KEY")
                                                                               orig-home    (aget js/process.env "HOME")
                                                                               tmp-dir      (fs/mkdtempSync "/tmp/nyma-oc-zen-test-")]
                                                                           (try
                                                                             (js-delete js/process.env "OPENCODE_ZEN_API_KEY")
                                                                             (js-delete js/process.env "OPENCODE_API_KEY")
                                                                             (aset js/process.env "HOME" tmp-dir)
                                                                             (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                               (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                               (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                                 (js/JSON.stringify #js {"opencode-zen" "sk-from-file"})
                                                                                                 "utf8"))
                                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                   api   (create-extension-api agent)
                                                                                   _     ((.-default zen-ext) api)
                                                                                   entry ((:get (:provider-registry agent)) "opencode-zen")
                                                                                   model ((:create-model entry) "big-pickle")]
                                                                               (-> (expect (some? model)) (.toBe true)))
                                                                             (finally
                                                                               (aset js/process.env "HOME" orig-home)
                                                                               (if orig-primary
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig-primary)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY"))
                                                                               (if orig-alias
                                                                                 (aset js/process.env "OPENCODE_API_KEY" orig-alias)
                                                                                 (js-delete js/process.env "OPENCODE_API_KEY"))
                                                                               (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                                   (it "env var takes precedence over credentials.json"
                                                                       (fn []
                                                                         (let [orig-key  (aget js/process.env "OPENCODE_ZEN_API_KEY")
                                                                               orig-alias (aget js/process.env "OPENCODE_API_KEY")
                                                                               orig-home (aget js/process.env "HOME")
                                                                               tmp-dir   (fs/mkdtempSync "/tmp/nyma-oc-zen-prec-")]
                                                                           (try
                                                                             (aset js/process.env "OPENCODE_ZEN_API_KEY" "sk-from-env")
                                                                             (js-delete js/process.env "OPENCODE_API_KEY")
                                                                             (aset js/process.env "HOME" tmp-dir)
                                                                             (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                               (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                               (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                                 (js/JSON.stringify #js {"opencode-zen" "sk-from-file"})
                                                                                                 "utf8"))
                                                                             (-> (expect (zen-ext/resolve-api-key)) (.toBe "sk-from-env"))
                                                                             (finally
                                                                               (aset js/process.env "HOME" orig-home)
                                                                               (if orig-key
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig-key)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY"))
                                                                               (if orig-alias
                                                                                 (aset js/process.env "OPENCODE_API_KEY" orig-alias)
                                                                                 (js-delete js/process.env "OPENCODE_API_KEY"))
                                                                               (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                                   (it "unknown model id falls back to .chat() without throwing"
                                                                       (fn []
                                                                         (let [orig (aget js/process.env "OPENCODE_ZEN_API_KEY")]
                                                                           (try
                                                                             (aset js/process.env "OPENCODE_ZEN_API_KEY" "sk-test-env")
                                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                   api   (create-extension-api agent)
                                                                                   _     ((.-default zen-ext) api)
                                                                                   entry ((:get (:provider-registry agent)) "opencode-zen")
                                                                                   model ((:create-model entry) "totally-unknown-model-id")]
                                                                               (-> (expect (some? model)) (.toBe true)))
                                                                             (finally
                                                                               (if orig
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY")))))))

                                                                   (it "throws a helpful error when no credentials are available"
                                                                       (fn []
                                                                         (let [orig-key   (aget js/process.env "OPENCODE_ZEN_API_KEY")
                                                                               orig-alias (aget js/process.env "OPENCODE_API_KEY")
                                                                               orig-home  (aget js/process.env "HOME")
                                                                               tmp-dir    (fs/mkdtempSync "/tmp/nyma-oc-zen-nokey-")]
                                                                           (try
                                                                             (js-delete js/process.env "OPENCODE_ZEN_API_KEY")
                                                                             (js-delete js/process.env "OPENCODE_API_KEY")
                                                                             (aset js/process.env "HOME" tmp-dir)
                                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                                   api   (create-extension-api agent)
                                                                                   _     ((.-default zen-ext) api)
                                                                                   entry ((:get (:provider-registry agent)) "opencode-zen")]
                                                                               (-> (expect (fn [] ((:create-model entry) "big-pickle")))
                                                                                   (.toThrow "OPENCODE_ZEN_API_KEY"))
                                                                               (-> (expect (fn [] ((:create-model entry) "big-pickle")))
                                                                                   (.toThrow "/login opencode-zen")))
                                                                             (finally
                                                                               (aset js/process.env "HOME" orig-home)
                                                                               (if orig-key
                                                                                 (aset js/process.env "OPENCODE_ZEN_API_KEY" orig-key)
                                                                                 (js-delete js/process.env "OPENCODE_ZEN_API_KEY"))
                                                                               (if orig-alias
                                                                                 (aset js/process.env "OPENCODE_API_KEY" orig-alias)
                                                                                 (js-delete js/process.env "OPENCODE_API_KEY"))
                                                                               (fs/rmSync tmp-dir #js {:recursive true :force true}))))))))
