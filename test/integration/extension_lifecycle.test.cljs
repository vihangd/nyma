(ns integration.extension-lifecycle.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            [agent.middleware :refer [wrap-tools-with-middleware]]))

;; Integration: extension lifecycle — register, use, deactivate

(defn ^:async test-extension-registers-tool-via-scoped-api []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        scoped   (create-scoped-api base-api "my-ext" #{:all})]
    (.registerTool scoped "greet"
      #js {:execute (fn [args] (str "Hello " (:name args)))
           :description "Greet someone"})
    (let [all-tools ((:all (:tool-registry agent)))]
      (-> (expect (get all-tools "my-ext__greet")) (.toBeDefined)))))

(defn ^:async test-extension-registers-command []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        scoped   (create-scoped-api base-api "git" #{:all})]
    (.registerCommand scoped "status"
      {:description "Git status" :handler (fn [_ _] "ok")})
    (-> (expect (get @(:commands agent) "git__status")) (.toBeDefined))))

(defn ^:async test-extension-adds-middleware []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        scoped   (create-scoped-api base-api "audit" #{:all})
        log      (atom [])]
    (.addMiddleware scoped
      {:name :audit-log
       :enter (fn [ctx] (swap! log conj (:tool-name ctx)) ctx)})
    (let [pipeline (:middleware agent)
          tool     #js {:execute (fn [_] "ok") :description "mock"}
          tools    (wrap-tools-with-middleware {"test" tool} pipeline (:events agent))
          _        (js-await ((.-execute (get tools "test")) {}))]
      (-> (expect (clj->js @log)) (.toContain "test")))))

(defn ^:async test-extension-event-handler []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        scoped   (create-scoped-api base-api "monitor" #{:all})
        captured (atom nil)]
    (.on scoped "agent_start" (fn [data] (reset! captured "started")))
    ((:emit (:events agent)) "agent_start" {})
    (-> (expect @captured) (.toBe "started"))))

(defn ^:async test-extension-deactivation-cleanup []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        scoped   (create-scoped-api base-api "temp" #{:all})]
    ;; Extension registers a tool
    (.registerTool scoped "helper" #js {:execute (fn [_] "ok") :description "temp"})
    (-> (expect (get ((:all (:tool-registry agent))) "temp__helper")) (.toBeDefined))
    ;; Simulated deactivation: unregister
    (.unregisterTool scoped "helper")
    (-> (expect (get ((:all (:tool-registry agent))) "temp__helper")) (.toBeUndefined))))

(defn ^:async test-scoped-extensions-dont-collide []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        ext-a    (create-scoped-api base-api "ext-a" #{:all})
        ext-b    (create-scoped-api base-api "ext-b" #{:all})]
    (.registerTool ext-a "run" #js {:execute (fn [_] "a") :description "ext-a run"})
    (.registerTool ext-b "run" #js {:execute (fn [_] "b") :description "ext-b run"})
    (let [all-tools ((:all (:tool-registry agent)))]
      (-> (expect (get all-tools "ext-a__run")) (.toBeDefined))
      (-> (expect (get all-tools "ext-b__run")) (.toBeDefined)))))

(defn ^:async test-extension-sendMessage []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        base-api (create-extension-api agent)
        scoped   (create-scoped-api base-api "bot" #{:all})]
    (.sendMessage scoped {:role "system" :content "injected"})
    (let [msgs (:messages @(:state agent))]
      (-> (expect (count msgs)) (.toBe 1))
      (-> (expect (:content (first msgs))) (.toBe "injected")))))

(describe "integration: extension lifecycle" (fn []
  (it "registers tool via scoped API" test-extension-registers-tool-via-scoped-api)
  (it "registers command with namespace" test-extension-registers-command)
  (it "adds middleware interceptor" test-extension-adds-middleware)
  (it "receives events via scoped handler" test-extension-event-handler)
  (it "deactivation removes tools" test-extension-deactivation-cleanup)
  (it "namespaced extensions dont collide" test-scoped-extensions-dont-collide)
  (it "injects messages via sendMessage" test-extension-sendMessage)))
