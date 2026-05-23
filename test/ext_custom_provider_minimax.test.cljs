(ns ext-custom-provider-minimax.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extensions.custom-provider-minimax.index :as minimax-ext]))

;; ── Registration ─────────────────────────────────────────────

(describe "custom-provider-minimax — registration" (fn []
                                                     (it "registers the minimax provider with a create-model function"
                                                         (fn []
                                                           (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                 api   (create-extension-api agent)
                                                                 _     ((.-default minimax-ext) api)
                                                                 entry ((:get (:provider-registry agent)) "minimax")]
                                                             (-> (expect (some? entry)) (.toBe true))
                                                             (-> (expect (fn? (:create-model entry))) (.toBe true)))))

                                                     (it "registers 7 MiniMax M2.x models"
                                                         (fn []
                                                           (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                                                 api    (create-extension-api agent)
                                                                 _      ((.-default minimax-ext) api)
                                                                 entry  ((:get (:provider-registry agent)) "minimax")
                                                                 models (:models entry)]
                                                             (-> (expect (count models)) (.toBe 7)))))

                                                     (it "returns a cleanup function that unregisters the provider"
                                                         (fn []
                                                           (let [agent   (create-agent {:model "test" :system-prompt "test"})
                                                                 api     (create-extension-api agent)
                                                                 cleanup ((.-default minimax-ext) api)]
                                                             (-> (expect (fn? cleanup)) (.toBe true))
                                                             (cleanup)
                                                             (-> (expect (nil? ((:get (:provider-registry agent)) "minimax")))
                                                                 (.toBe true)))))))

;; ── Model metadata wiring ────────────────────────────────────

(describe "custom-provider-minimax — model metadata" (fn []
                                                       (it "registers 204,800-token context window for MiniMax-M2"
                                                           (fn []
                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                   api   (create-extension-api agent)
                                                                   _     ((.-default minimax-ext) api)
                                                                   info  ((:get (:model-registry agent)) "MiniMax-M2")]
                                                               (-> (expect (:context-window info)) (.toBe 204800)))))

                                                       (it "registers 204,800-token context window for all highspeed variants"
                                                           (fn []
                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                   api   (create-extension-api agent)
                                                                   _     ((.-default minimax-ext) api)]
                                                               (doseq [id ["MiniMax-M2.1-highspeed"
                                                                           "MiniMax-M2.5-highspeed"
                                                                           "MiniMax-M2.7-highspeed"]]
                                                                 (let [info ((:get (:model-registry agent)) id)]
                                                                   (-> (expect (:context-window info)) (.toBe 204800)))))))

                                                       (it "does not register pricing (flat-rate plan — no per-token cost)"
                                                           (fn []
      ;; No :cost on any model → pricing/token-costs should have no entry.
      ;; We test this indirectly by verifying models were registered without
      ;; throwing, and that the model-registry entry has no :cost key.
                                                             (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                   api   (create-extension-api agent)
                                                                   _     ((.-default minimax-ext) api)
                                                                   entry ((:get (:provider-registry agent)) "minimax")
                                                                   first-model (first (:models entry))]
                                                               (-> (expect (nil? (:cost first-model))) (.toBe true)))))))

;; ── Base URL resolution ─────────────────────────────────────

(describe "custom-provider-minimax — base URL" (fn []
                                                 (it "defaults to the international endpoint"
                                                     (fn []
                                                       (let [orig (aget js/process.env "MINIMAX_BASE_URL")]
                                                         (try
                                                           (js-delete js/process.env "MINIMAX_BASE_URL")
                                                           (-> (expect (minimax-ext/resolve-base-url))
                                                               (.toBe "https://api.minimax.io/v1"))
                                                           (finally
                                                             (if orig
                                                               (aset js/process.env "MINIMAX_BASE_URL" orig)
                                                               (js-delete js/process.env "MINIMAX_BASE_URL")))))))

                                                 (it "uses MINIMAX_BASE_URL when set (China region)"
                                                     (fn []
                                                       (let [orig (aget js/process.env "MINIMAX_BASE_URL")]
                                                         (try
                                                           (aset js/process.env "MINIMAX_BASE_URL" "https://api.minimaxi.com/v1")
                                                           (-> (expect (minimax-ext/resolve-base-url))
                                                               (.toBe "https://api.minimaxi.com/v1"))
                                                           (finally
                                                             (if orig
                                                               (aset js/process.env "MINIMAX_BASE_URL" orig)
                                                               (js-delete js/process.env "MINIMAX_BASE_URL")))))))))

;; ── Credential resolution ────────────────────────────────────

(describe "custom-provider-minimax — credential resolution" (fn []
                                                              (it "creates a model object when MINIMAX_API_KEY env var is set"
                                                                  (fn []
                                                                    (let [orig (aget js/process.env "MINIMAX_API_KEY")]
                                                                      (try
                                                                        (aset js/process.env "MINIMAX_API_KEY" "sk-test-env")
                                                                        (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                              api   (create-extension-api agent)
                                                                              _     ((.-default minimax-ext) api)
                                                                              entry ((:get (:provider-registry agent)) "minimax")]
            ;; create-model should not throw with a key present
                                                                          (-> (expect (fn? (:create-model entry))) (.toBe true))
            ;; The returned model object should be truthy
                                                                          (let [model ((:create-model entry) "MiniMax-M2")]
                                                                            (-> (expect (some? model)) (.toBe true))))
                                                                        (finally
                                                                          (if orig
                                                                            (aset js/process.env "MINIMAX_API_KEY" orig)
                                                                            (js-delete js/process.env "MINIMAX_API_KEY")))))))

                                                              (it "reads key from credentials.json when env var is absent"
                                                                  (fn []
                                                                    (let [orig-key  (aget js/process.env "MINIMAX_API_KEY")
                                                                          orig-home (aget js/process.env "HOME")
                                                                          tmp-dir   (fs/mkdtempSync "/tmp/nyma-minimax-test-")]
                                                                      (try
          ;; Remove env var so only credentials.json is the source
                                                                        (js-delete js/process.env "MINIMAX_API_KEY")
          ;; Point HOME at temp dir and write a credentials file
                                                                        (aset js/process.env "HOME" tmp-dir)
                                                                        (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                          (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                          (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                            (js/JSON.stringify #js {"minimax" "sk-from-file"})
                                                                                            "utf8"))
                                                                        (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                              api   (create-extension-api agent)
                                                                              _     ((.-default minimax-ext) api)
                                                                              entry ((:get (:provider-registry agent)) "minimax")
                                                                              model ((:create-model entry) "MiniMax-M2.7")]
                                                                          (-> (expect (some? model)) (.toBe true)))
                                                                        (finally
            ;; Restore HOME and env var
                                                                          (aset js/process.env "HOME" orig-home)
                                                                          (if orig-key
                                                                            (aset js/process.env "MINIMAX_API_KEY" orig-key)
                                                                            (js-delete js/process.env "MINIMAX_API_KEY"))
            ;; Clean up temp dir
                                                                          (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                              (it "env var takes precedence over credentials.json when both are present"
                                                                  (fn []
                                                                    (let [orig-key  (aget js/process.env "MINIMAX_API_KEY")
                                                                          orig-home (aget js/process.env "HOME")
                                                                          tmp-dir   (fs/mkdtempSync "/tmp/nyma-minimax-precedence-")]
                                                                      (try
                                                                        (aset js/process.env "MINIMAX_API_KEY" "sk-from-env")
                                                                        (aset js/process.env "HOME" tmp-dir)
                                                                        (let [nyma-dir (path/join tmp-dir ".nyma")]
                                                                          (fs/mkdirSync nyma-dir #js {:recursive true})
                                                                          (fs/writeFileSync (path/join nyma-dir "credentials.json")
                                                                                            (js/JSON.stringify #js {"minimax" "sk-from-file"})
                                                                                            "utf8"))
                                                                        (-> (expect (minimax-ext/resolve-api-key))
                                                                            (.toBe "sk-from-env"))
                                                                        (finally
                                                                          (aset js/process.env "HOME" orig-home)
                                                                          (if orig-key
                                                                            (aset js/process.env "MINIMAX_API_KEY" orig-key)
                                                                            (js-delete js/process.env "MINIMAX_API_KEY"))
                                                                          (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

                                                              (it "throws a helpful error when no credentials are available"
                                                                  (fn []
                                                                    (let [orig-key  (aget js/process.env "MINIMAX_API_KEY")
                                                                          orig-home (aget js/process.env "HOME")
                                                                          tmp-dir   (fs/mkdtempSync "/tmp/nyma-minimax-nokey-")]
                                                                      (try
                                                                        (js-delete js/process.env "MINIMAX_API_KEY")
          ;; HOME points to a dir with no credentials.json
                                                                        (aset js/process.env "HOME" tmp-dir)
                                                                        (let [agent (create-agent {:model "test" :system-prompt "test"})
                                                                              api   (create-extension-api agent)
                                                                              _     ((.-default minimax-ext) api)
                                                                              entry ((:get (:provider-registry agent)) "minimax")]
                                                                          (-> (expect (fn [] ((:create-model entry) "MiniMax-M2")))
                                                                              (.toThrow "MINIMAX_API_KEY"))
                                                                          (-> (expect (fn [] ((:create-model entry) "MiniMax-M2")))
                                                                              (.toThrow "/login minimax")))
                                                                        (finally
                                                                          (aset js/process.env "HOME" orig-home)
                                                                          (if orig-key
                                                                            (aset js/process.env "MINIMAX_API_KEY" orig-key)
                                                                            (js-delete js/process.env "MINIMAX_API_KEY"))
                                                                          (fs/rmSync tmp-dir #js {:recursive true :force true}))))))))

;; ── reasoning_split splice ──────────────────────────────────

(describe "custom-provider-minimax — reasoning_split splice"
          (fn []
            (it "adds reasoning_split: true to a JSON body"
                (fn []
                  (let [body (js/JSON.stringify
                              #js {:model "MiniMax-M2.5"
                                   :messages #js [#js {:role "user" :content "hi"}]})
                        out  (minimax-ext/splice-reasoning-split body)
                        parsed (js/JSON.parse out)]
                    (-> (expect (.-reasoning_split parsed)) (.toBe true))
                    (-> (expect (.-model parsed)) (.toBe "MiniMax-M2.5")))))

            (it "preserves existing keys"
                (fn []
                  (let [body (js/JSON.stringify
                              #js {:model "x" :temperature 0.7 :stream true})
                        parsed (js/JSON.parse (minimax-ext/splice-reasoning-split body))]
                    (-> (expect (.-temperature parsed)) (.toBe 0.7))
                    (-> (expect (.-stream parsed)) (.toBe true))
                    (-> (expect (.-reasoning_split parsed)) (.toBe true)))))

            (it "non-JSON body passes through unchanged"
                (fn []
                  (let [body "not-json-at-all"
                        out (minimax-ext/splice-reasoning-split body)]
                    (-> (expect out) (.toBe body)))))

            (it "JSON null body passes through (no crash)"
                (fn []
                  (-> (expect (minimax-ext/splice-reasoning-split "null"))
                      (.toBe "null"))))))

(describe "custom-provider-minimax — wrap-fetch-with-reasoning"
          (fn []
            (it "wraps POST with JSON body to add reasoning_split"
                (fn []
                  (let [captured-body (atom nil)
                        fake-fetch (fn [_url init]
                                     (reset! captured-body (.-body init))
                                     (js/Promise.resolve #js {}))
                        wrapped (minimax-ext/wrap-fetch-with-reasoning fake-fetch)
                        body    (js/JSON.stringify #js {:model "MiniMax-M2.5"})]
                    (wrapped "https://example.com/v1/chat/completions"
                             #js {:method "POST" :body body})
                    (-> (expect (.-reasoning_split (js/JSON.parse @captured-body)))
                        (.toBe true)))))

            (it "passes GET requests through untouched"
                (fn []
                  (let [captured (atom nil)
                        fake-fetch (fn [url init]
                                     (reset! captured #js {:url url :init init})
                                     (js/Promise.resolve #js {}))
                        wrapped (minimax-ext/wrap-fetch-with-reasoning fake-fetch)]
                    (wrapped "https://example.com/v1/models"
                             #js {:method "GET"})
                    (-> (expect (.-method (.-init @captured))) (.toBe "GET"))
                    ;; No body on GETs — just check we didn't add one.
                    (-> (expect (.-body (.-init @captured))) (.toBeNil)))))

            (it "passes POST with non-string body untouched"
                (fn []
                  (let [captured (atom nil)
                        fake-fetch (fn [_url init]
                                     (reset! captured (.-body init))
                                     (js/Promise.resolve #js {}))
                        wrapped (minimax-ext/wrap-fetch-with-reasoning fake-fetch)
                        ;; Some clients send a Buffer/Uint8Array — don't touch.
                        binary  (new js/Uint8Array #js [1 2 3])]
                    (wrapped "https://example.com/v1/chat/completions"
                             #js {:method "POST" :body binary})
                    (-> (expect (= @captured binary)) (.toBe true)))))))
