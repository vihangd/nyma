(ns extension-api-v3.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.providers.registry :refer [create-provider-registry]]))

;; ── Provider Registry ────────────────────────────────────────

(defn test-provider-register-unregister []
  (let [registry (create-provider-registry {})]
    ((:register registry) "test-provider" {:create-model (fn [id] (str "model:" id))})
    (-> (expect (some? ((:get registry) "test-provider"))) (.toBe true))
    ((:unregister registry) "test-provider")
    (-> (expect ((:get registry) "test-provider")) (.toBeUndefined))))

(defn test-provider-resolve []
  (let [registry (create-provider-registry {})]
    ((:register registry) "mock" {:create-model (fn [id] (str "resolved:" id))})
    (let [model ((:resolve registry) "mock" "gpt-4")]
      (-> (expect model) (.toBe "resolved:gpt-4")))))

(defn test-provider-resolve-unknown-throws []
  (let [registry (create-provider-registry {})]
    (-> (expect (fn [] ((:resolve registry) "nonexistent" "model")))
        (.toThrow "Unknown provider"))))

(defn test-provider-list []
  (let [registry (create-provider-registry {"a" {:name "a"} "b" {:name "b"}})]
    (-> (expect (count ((:list registry)))) (.toBe 2))))

;; ── Extension API — New Methods ──────────────────────────────

(defn test-api-has-new-methods []
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})
        api   (create-extension-api agent)]
    ;; Phase 2 methods should exist
    (-> (expect (fn? (.-appendEntry api))) (.toBe true))
    (-> (expect (fn? (.-setSessionName api))) (.toBe true))
    (-> (expect (fn? (.-getSessionName api))) (.toBe true))
    (-> (expect (fn? (.-setLabel api))) (.toBe true))
    (-> (expect (fn? (.-registerMessageRenderer api))) (.toBe true))
    (-> (expect (fn? (.-registerProvider api))) (.toBe true))
    (-> (expect (fn? (.-unregisterProvider api))) (.toBe true))
    (-> (expect (fn? (.-setModel api))) (.toBe true))
    (-> (expect (fn? (.-getThinkingLevel api))) (.toBe true))
    (-> (expect (fn? (.-setThinkingLevel api))) (.toBe true))
    (-> (expect (some? (.-events api))) (.toBe true))
    ;; Tool management extensions
    (-> (expect (fn? (.-getActiveTools api))) (.toBe true))
    (-> (expect (fn? (.-getAllTools api))) (.toBe true))
    (-> (expect (fn? (.-setActiveTools api))) (.toBe true))))

(defn test-thinking-level-validation []
  (let [agent (create-agent {:model "test" :system-prompt "test"})
        api   (create-extension-api agent)]
    ;; Valid levels should work
    (.setThinkingLevel api "off")
    (-> (expect (.getThinkingLevel api)) (.toBe "off"))
    (.setThinkingLevel api "high")
    (-> (expect (.getThinkingLevel api)) (.toBe "high"))
    ;; Invalid level should throw
    (-> (expect (fn [] (.setThinkingLevel api "invalid"))) (.toThrow "Invalid thinking level"))))

(defn test-message-renderer-registration []
  (let [agent (create-agent {:model "test" :system-prompt "test"})
        api   (create-extension-api agent)
        renderer (fn [msg] "custom render")]
    (.registerMessageRenderer api "custom-type" renderer)
    (-> (expect (get @(:message-renderers agent) "custom-type")) (.toBe renderer))))

(defn test-inter-extension-events []
  (let [agent (create-agent {:model "test" :system-prompt "test"})
        api   (create-extension-api agent)
        received (atom nil)]
    (.on (.-events api) "my-event" (fn [data] (reset! received data)))
    (.emit (.-events api) "my-event" #js {:value 42})
    (-> (expect (some? @received)) (.toBe true))))

(defn test-provider-via-api []
  (let [agent (create-agent {:model "test" :system-prompt "test"})
        api   (create-extension-api agent)]
    (.registerProvider api "custom" #js {:createModel (fn [id] (str "custom:" id))})
    (-> (expect (some? ((:get (:provider-registry agent)) "custom"))) (.toBe true))
    (.unregisterProvider api "custom")
    (-> (expect ((:get (:provider-registry agent)) "custom")) (.toBeUndefined))))

;; ── Session atom on agent ────────────────────────────────────

(defn test-session-atom-exists []
  (let [agent (create-agent {:model "test" :system-prompt "test"})]
    (-> (expect (some? (:session agent))) (.toBe true))
    (-> (expect @(:session agent)) (.toBeNull))))

;; ── describe blocks ──────────────────────────────────────────

(describe "provider-registry" (fn []
  (it "register and unregister providers" test-provider-register-unregister)
  (it "resolves providers to models" test-provider-resolve)
  (it "throws on unknown provider" test-provider-resolve-unknown-throws)
  (it "lists all providers" test-provider-list)))

(describe "extension-api-v3" (fn []
  (it "exposes all new Phase 2 methods" test-api-has-new-methods)
  (it "validates thinking levels" test-thinking-level-validation)
  (it "registers message renderers" test-message-renderer-registration)
  (it "supports inter-extension events" test-inter-extension-events)
  (it "manages providers via API" test-provider-via-api)
  (it "agent has session atom" test-session-atom-exists)))
