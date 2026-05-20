(ns ext-custom-provider-deepseek.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extensions.custom-provider-deepseek.index :as ds]))

(describe "custom-provider-deepseek — registration"
          (fn []
            (it "registers the deepseek provider with a create-model fn"
                (fn []
                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                        api   (create-extension-api agent)
                        _     ((.-default ds) api)
                        entry ((:get (:provider-registry agent)) "deepseek")]
                    (-> (expect (some? entry)) (.toBe true))
                    (-> (expect (fn? (:create-model entry))) (.toBe true)))))

            (it "registers the v4 + legacy models"
                (fn []
                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                        api   (create-extension-api agent)
                        _     ((.-default ds) api)
                        entry ((:get (:provider-registry agent)) "deepseek")
                        ids   (set (map :id (:models entry)))]
                    (-> (expect (contains? ids "deepseek-v4-pro"))   (.toBe true))
                    (-> (expect (contains? ids "deepseek-v4-flash")) (.toBe true))
                    (-> (expect (contains? ids "deepseek-chat"))     (.toBe true))
                    (-> (expect (contains? ids "deepseek-reasoner")) (.toBe true)))))

            (it "registers 131072-token context window on every model"
                (fn []
                  (let [agent (create-agent {:model "test" :system-prompt "test"})
                        api   (create-extension-api agent)
                        _     ((.-default ds) api)]
                    (doseq [id ["deepseek-v4-pro" "deepseek-v4-flash"
                                "deepseek-chat"   "deepseek-reasoner"]]
                      (let [info ((:get (:model-registry agent)) id)]
                        (-> (expect (:context-window info)) (.toBe 131072)))))))))

(describe "custom-provider-deepseek — base URL"
          (fn []
            (it "defaults to api.deepseek.com/v1"
                (fn []
                  (let [orig (aget js/process.env "DEEPSEEK_BASE_URL")]
                    (try
                      (js-delete js/process.env "DEEPSEEK_BASE_URL")
                      (-> (expect (ds/resolve-base-url))
                          (.toBe "https://api.deepseek.com/v1"))
                      (finally
                        (if orig
                          (aset js/process.env "DEEPSEEK_BASE_URL" orig)
                          (js-delete js/process.env "DEEPSEEK_BASE_URL")))))))

            (it "uses DEEPSEEK_BASE_URL override when set"
                (fn []
                  (let [orig (aget js/process.env "DEEPSEEK_BASE_URL")]
                    (try
                      (aset js/process.env "DEEPSEEK_BASE_URL" "https://example.test/v1")
                      (-> (expect (ds/resolve-base-url))
                          (.toBe "https://example.test/v1"))
                      (finally
                        (if orig
                          (aset js/process.env "DEEPSEEK_BASE_URL" orig)
                          (js-delete js/process.env "DEEPSEEK_BASE_URL")))))))))

(describe "custom-provider-deepseek — credential resolution"
          (fn []
            (it "creates a model when DEEPSEEK_API_KEY env var is set"
                (fn []
                  (let [orig (aget js/process.env "DEEPSEEK_API_KEY")]
                    (try
                      (aset js/process.env "DEEPSEEK_API_KEY" "sk-test-env")
                      (let [agent (create-agent {:model "test" :system-prompt "test"})
                            api   (create-extension-api agent)
                            _     ((.-default ds) api)
                            entry ((:get (:provider-registry agent)) "deepseek")
                            model ((:create-model entry) "deepseek-v4-pro")]
                        (-> (expect (some? model)) (.toBe true)))
                      (finally
                        (if orig
                          (aset js/process.env "DEEPSEEK_API_KEY" orig)
                          (js-delete js/process.env "DEEPSEEK_API_KEY")))))))

            (it "reads key from credentials.json when env var absent"
                (fn []
                  (let [orig-key  (aget js/process.env "DEEPSEEK_API_KEY")
                        orig-home (aget js/process.env "HOME")
                        tmp-dir   (fs/mkdtempSync "/tmp/nyma-deepseek-test-")]
                    (try
                      (js-delete js/process.env "DEEPSEEK_API_KEY")
                      (aset js/process.env "HOME" tmp-dir)
                      (let [nyma-dir (path/join tmp-dir ".nyma")]
                        (fs/mkdirSync nyma-dir #js {:recursive true})
                        (fs/writeFileSync
                         (path/join nyma-dir "credentials.json")
                         (js/JSON.stringify #js {:deepseek "sk-from-creds"})))
                      (-> (expect (ds/resolve-api-key)) (.toBe "sk-from-creds"))
                      (finally
                        (when orig-key (aset js/process.env "DEEPSEEK_API_KEY" orig-key))
                        (when orig-home (aset js/process.env "HOME" orig-home))
                        (fs/rmSync tmp-dir #js {:recursive true :force true}))))))

            (it "throws a helpful error when no credentials are available"
                (fn []
                  (let [orig-key  (aget js/process.env "DEEPSEEK_API_KEY")
                        orig-home (aget js/process.env "HOME")
                        tmp-dir   (fs/mkdtempSync "/tmp/nyma-deepseek-noauth-")]
                    (try
                      (js-delete js/process.env "DEEPSEEK_API_KEY")
                      (aset js/process.env "HOME" tmp-dir) ; no credentials.json
                      (let [agent (create-agent {:model "test" :system-prompt "test"})
                            api   (create-extension-api agent)
                            _     ((.-default ds) api)
                            entry ((:get (:provider-registry agent)) "deepseek")
                            threw (atom nil)]
                        (try ((:create-model entry) "deepseek-v4-pro")
                             (catch :default e (reset! threw (.-message e))))
                        (-> (expect (some? @threw)) (.toBe true))
                        (-> (expect (.includes @threw "DEEPSEEK_API_KEY")) (.toBe true))
                        (-> (expect (.includes @threw "platform.deepseek.com")) (.toBe true)))
                      (finally
                        (when orig-key (aset js/process.env "DEEPSEEK_API_KEY" orig-key))
                        (when orig-home (aset js/process.env "HOME" orig-home))
                        (fs/rmSync tmp-dir #js {:recursive true :force true}))))))))

(describe "custom-provider-deepseek — cleanup"
          (fn []
            (it "unregisters on disposal"
                (fn []
                  (let [agent     (create-agent {:model "test" :system-prompt "test"})
                        api       (create-extension-api agent)
                        dispose   ((.-default ds) api)]
                    (-> (expect (some? ((:get (:provider-registry agent)) "deepseek"))) (.toBe true))
                    (dispose)
                    (-> (expect (nil? ((:get (:provider-registry agent)) "deepseek"))) (.toBe true)))))))
