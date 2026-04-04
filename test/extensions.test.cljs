(ns extensions.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]))

(defn- make-api []
  (let [agent (create-agent {:model "test-model"
                              :system-prompt "test"})]
    {:agent agent :api (create-extension-api agent)}))

(describe "agent.extensions - create-extension-api"
  (fn []
    (it "returns object with all expected methods"
      (fn []
        (let [{:keys [api]} (make-api)]
          (-> (expect (fn? (.-on api))) (.toBe true))
          (-> (expect (fn? (.-off api))) (.toBe true))
          (-> (expect (fn? (.-registerTool api))) (.toBe true))
          (-> (expect (fn? (.-unregisterTool api))) (.toBe true))
          (-> (expect (fn? (.-registerCommand api))) (.toBe true))
          (-> (expect (fn? (.-unregisterCommand api))) (.toBe true))
          (-> (expect (fn? (.-registerShortcut api))) (.toBe true))
          (-> (expect (fn? (.-unregisterShortcut api))) (.toBe true))
          (-> (expect (fn? (.-getCommands api))) (.toBe true))
          (-> (expect (fn? (.-sendMessage api))) (.toBe true))
          (-> (expect (fn? (.-sendUserMessage api))) (.toBe true))
          (-> (expect (.-ui api)) (.toBeTruthy)))))

    (it "on delegates to event bus"
      (fn []
        (let [{:keys [agent api]} (make-api)
              called (atom false)]
          (.on api "test_evt" (fn [_] (reset! called true)))
          ((:emit (:events agent)) "test_evt" {})
          (-> (expect @called) (.toBe true)))))

    (it "off removes handler from event bus"
      (fn []
        (let [{:keys [agent api]} (make-api)
              called  (atom 0)
              handler (fn [_] (swap! called inc))]
          (.on api "evt" handler)
          ((:emit (:events agent)) "evt" {})
          (-> (expect @called) (.toBe 1))
          (.off api "evt" handler)
          ((:emit (:events agent)) "evt" {})
          (-> (expect @called) (.toBe 1)))))

    (it "on supports priority parameter"
      (fn []
        (let [{:keys [agent api]} (make-api)
              order (atom [])]
          (.on api "evt" (fn [_] (swap! order conj "low")) 0)
          (.on api "evt" (fn [_] (swap! order conj "high")) 10)
          ((:emit (:events agent)) "evt" {})
          (-> (expect (first @order)) (.toBe "high"))
          (-> (expect (second @order)) (.toBe "low")))))))

(describe "agent.extensions - tool registration"
  (fn []
    (it "registerTool adds tool to registry"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.registerTool api "custom" {:description "custom tool"})
          (-> (expect (get ((:all (:tool-registry agent))) "custom")) (.toBeTruthy)))))

    (it "unregisterTool removes tool from registry"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.registerTool api "custom" {:description "custom tool"})
          (.unregisterTool api "custom")
          (-> (expect (get ((:all (:tool-registry agent))) "custom")) (.toBeUndefined)))))

    (it "registerTool / unregisterTool round-trip"
      (fn []
        (let [{:keys [agent api]} (make-api)
              initial-count (count ((:all (:tool-registry agent))))]
          (.registerTool api "tmp" {:description "temp"})
          (-> (expect (count ((:all (:tool-registry agent))))) (.toBe (inc initial-count)))
          (.unregisterTool api "tmp")
          (-> (expect (count ((:all (:tool-registry agent))))) (.toBe initial-count)))))))

(describe "agent.extensions - command registration"
  (fn []
    (it "registerCommand adds command"
      (fn []
        (let [{:keys [api]} (make-api)]
          (.registerCommand api "deploy" {:handler (fn [])})
          (-> (expect (get (.getCommands api) "deploy")) (.toBeTruthy)))))

    (it "unregisterCommand removes command"
      (fn []
        (let [{:keys [api]} (make-api)]
          (.registerCommand api "deploy" {:handler (fn [])})
          (.unregisterCommand api "deploy")
          (-> (expect (get (.getCommands api) "deploy")) (.toBeUndefined)))))

    (it "getCommands reflects current state"
      (fn []
        (let [{:keys [api]} (make-api)]
          (-> (expect (count (.getCommands api))) (.toBe 0))
          (.registerCommand api "a" {:handler (fn [])})
          (.registerCommand api "b" {:handler (fn [])})
          (-> (expect (count (.getCommands api))) (.toBe 2))
          (.unregisterCommand api "a")
          (-> (expect (count (.getCommands api))) (.toBe 1)))))))

(describe "agent.extensions - shortcut registration"
  (fn []
    (it "registerShortcut adds shortcut"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.registerShortcut api "ctrl+k" (fn []))
          (-> (expect (get @(:shortcuts agent) "ctrl+k")) (.toBeTruthy)))))

    (it "unregisterShortcut removes shortcut"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.registerShortcut api "ctrl+k" (fn []))
          (.unregisterShortcut api "ctrl+k")
          (-> (expect (get @(:shortcuts agent) "ctrl+k")) (.toBeUndefined)))))))

(describe "agent.extensions - sendMessage"
  (fn []
    (it "appends message to agent state"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.sendMessage api {:role "user" :content "hello"})
          (let [msgs (:messages @(:state agent))]
            (-> (expect (count msgs)) (.toBe 1))
            (-> (expect (:content (first msgs))) (.toBe "hello"))))))))

(describe "agent.extensions - ui"
  (fn []
    (it "ui.available is false by default"
      (fn []
        (let [{:keys [api]} (make-api)]
          (-> (expect (.-available (.-ui api))) (.toBe false)))))

    (it "ui.available can be set to true"
      (fn []
        (let [{:keys [api]} (make-api)]
          (set! (.-available (.-ui api)) true)
          (-> (expect (.-available (.-ui api))) (.toBe true)))))

    (it "ui hooks are nil by default"
      (fn []
        (let [{:keys [api]} (make-api)]
          (-> (expect (.-showOverlay (.-ui api))) (.toBeNull))
          (-> (expect (.-setWidget (.-ui api))) (.toBeNull))
          (-> (expect (.-clearWidget (.-ui api))) (.toBeNull))
          (-> (expect (.-confirm (.-ui api))) (.toBeNull))
          (-> (expect (.-select (.-ui api))) (.toBeNull))
          (-> (expect (.-input (.-ui api))) (.toBeNull))
          (-> (expect (.-notify (.-ui api))) (.toBeNull))
          (-> (expect (.-setStatus (.-ui api))) (.toBeNull))
          (-> (expect (.-setFooter (.-ui api))) (.toBeNull))
          (-> (expect (.-setHeader (.-ui api))) (.toBeNull))
          (-> (expect (.-setTitle (.-ui api))) (.toBeNull))
          (-> (expect (.-setEditorComponent (.-ui api))) (.toBeNull))
          (-> (expect (.-onTerminalInput (.-ui api))) (.toBeNull))
          (-> (expect (.-custom (.-ui api))) (.toBeNull)))))))

(describe "agent.extensions - flag registration"
  (fn []
    (it "registerFlag stores flag config"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.registerFlag api "verbose" #js {:description "Verbose output"
                                            :type "boolean"
                                            :default false})
          (-> (expect (get @(:flags agent) "verbose")) (.toBeTruthy)))))

    (it "getFlag returns default when no CLI override"
      (fn []
        (let [{:keys [api]} (make-api)]
          (.registerFlag api "format" #js {:description "Output format"
                                           :type "string"
                                           :default "json"})
          (-> (expect (.getFlag api "format")) (.toBe "json")))))

    (it "getFlag returns value when set"
      (fn []
        (let [{:keys [agent api]} (make-api)]
          (.registerFlag api "verbose" #js {:type "boolean" :default false})
          (swap! (:flags agent) assoc-in ["verbose" :value] true)
          (-> (expect (.getFlag api "verbose")) (.toBe true)))))

    (it "getFlag returns nil for unregistered flag"
      (fn []
        (let [{:keys [api]} (make-api)]
          (-> (expect (.getFlag api "nonexistent")) (.toBeUndefined)))))))
