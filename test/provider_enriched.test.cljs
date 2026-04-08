(ns provider-enriched.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.providers.registry :refer [build-provider-entry create-provider-registry]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.pricing :as pricing]))

;; ── build-provider-entry ─────────────────────────────────────

(describe "build-provider-entry" (fn []
  (it "passes through config with :create-model unchanged"
    (fn []
      (let [config {:create-model (fn [id] (str "m:" id)) :extra "data"}
            entry  (build-provider-entry "test" config)]
        (-> (expect ((:create-model entry) "gpt-4")) (.toBe "m:gpt-4"))
        (-> (expect (:extra entry)) (.toBe "data")))))

  (it "accepts camelCase :createModel key"
    (fn []
      (let [config {:createModel (fn [id] (str "cm:" id))}
            entry  (build-provider-entry "test" config)]
        (-> (expect ((:create-model entry) "x")) (.toBe "cm:x")))))

  (it "normalizes camelCase keys to kebab-case"
    (fn []
      (let [config {:baseUrl "https://example.com"
                    :apiKeyEnv "MY_KEY"
                    :createModel (fn [id] id)}
            entry  (build-provider-entry "test" config)]
        (-> (expect (:base-url entry)) (.toBe "https://example.com"))
        (-> (expect (:api-key-env entry)) (.toBe "MY_KEY")))))

  (it "normalizes OAuth sub-keys"
    (fn []
      (let [config {:createModel (fn [id] id)
                    :oauth {:getApiKey (fn [c] "key")
                            :refreshToken (fn [c] c)
                            :name "Test"}}
            entry (build-provider-entry "test" config)]
        (-> (expect (fn? (get-in entry [:oauth :get-api-key]))) (.toBe true))
        (-> (expect (fn? (get-in entry [:oauth :refresh-token]))) (.toBe true)))))

  (it "auto-generates :create-model when :api is set"
    (fn []
      ;; We can't test the actual AI SDK call without a real API key,
      ;; but we can verify the function is generated
      (let [config {:api "anthropic" :base-url "https://api.example.com"}
            entry  (build-provider-entry "test" config)]
        (-> (expect (fn? (:create-model entry))) (.toBe true)))))))

;; ── registerProvider auto-registration ───────────────────────

(describe "registerProvider - auto model metadata" (fn []
  (it "registers model context windows automatically"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})
            api   (create-extension-api agent)]
        (.registerProvider api "custom-test"
          #js {:createModel (fn [id] (str "test:" id))
               :models #js [#js {:id "test-model-1" :contextWindow 500000}
                            #js {:id "test-model-2" :contextWindow 128000}]})
        ;; Verify model-info was registered
        (let [info1 ((:get (:model-registry agent)) "test-model-1")
              info2 ((:get (:model-registry agent)) "test-model-2")]
          (-> (expect (:context-window info1)) (.toBe 500000))
          (-> (expect (:context-window info2)) (.toBe 128000))))))

  (it "registers pricing automatically"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})
            api   (create-extension-api agent)]
        (.registerProvider api "price-test"
          #js {:createModel (fn [id] (str "test:" id))
               :models #js [#js {:id "price-test-model"
                                  :contextWindow 100000
                                  :cost #js {:input 5.0 :output 25.0}}]})
        ;; Verify pricing was registered
        (let [costs (get @pricing/token-costs "price-test-model")]
          (-> (expect (first costs)) (.toBe 5.0))
          (-> (expect (second costs)) (.toBe 25.0))))))

  (it "works with no models array"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})
            api   (create-extension-api agent)]
        ;; Should not throw
        (.registerProvider api "bare-test"
          #js {:createModel (fn [id] (str "bare:" id))})
        (-> (expect (some? ((:get (:provider-registry agent)) "bare-test"))) (.toBe true)))))

  (it "handles models without cost field"
    (fn []
      (let [agent (create-agent {:model "test" :system-prompt "test"})
            api   (create-extension-api agent)]
        (.registerProvider api "no-cost-test"
          #js {:createModel (fn [id] id)
               :models #js [#js {:id "free-model" :contextWindow 100000}]})
        ;; Should register model info but not pricing
        (let [info ((:get (:model-registry agent)) "free-model")]
          (-> (expect (:context-window info)) (.toBe 100000))))))))
