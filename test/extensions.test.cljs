(ns extensions.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.loop :refer [run]]))

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

;;; ─── Agent state API: getState / dispatch / onStateChange / __state-atom ───
;; These were missing from create-extension-api for a long time; model_roles
;; and stats_dashboard called them and silently failed (the throws were
;; swallowed by extension_scope's safe-on wrapper). The integration test in
;; cli_integration.test.cljs caught it.

(describe "agent.extensions - getState"
          (fn []
            (it "returns the current agent state map"
                (fn []
                  (let [{:keys [agent api]} (make-api)]
                    (swap! (:state agent) assoc :turn-count 7)
                    (-> (expect (:turn-count (.getState api))) (.toBe 7)))))

            (it "is a snapshot read — subsequent swaps don't mutate the prior result"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        snap1 (.getState api)]
                    (swap! (:state agent) assoc :turn-count 99)
                    ;; squint cljs maps are JS objects sharing the underlying
                    ;; reference, so `snap1` reflects the new value. Assert
                    ;; the read pattern is "live", which is what extensions
                    ;; that re-call getState every event handler expect.
                    (-> (expect (:turn-count (.getState api))) (.toBe 99)))))))

(describe "agent.extensions - dispatch"
          (fn []
            (it "emits a custom event onto the agent's event bus"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        seen (atom nil)]
                    ((:on (:events agent)) "role-changed"
                                           (fn [data] (reset! seen data)))
                    (.dispatch api "role-changed" {:role "deep"})
                    (-> (expect (some? @seen)) (.toBe true))
                    ;; clj->js converts the kw key to a JS string property
                    (-> (expect (.-role @seen)) (.toBe "deep")))))))

(describe "agent.extensions - onStateChange"
          (fn []
            (it "fires the listener on state mutation"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        observed (atom nil)
                        unsub    (.onStateChange api (fn [new-state]
                                                       (reset! observed new-state)))]
                    (swap! (:state agent) assoc :turn-count 1)
                    (-> (expect (some? @observed)) (.toBe true))
                    (-> (expect (:turn-count @observed)) (.toBe 1))
                    (unsub))))

            (it "unsub stops further notifications"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        calls (atom 0)
                        unsub (.onStateChange api (fn [_] (swap! calls inc)))]
                    (swap! (:state agent) assoc :turn-count 1)
                    (unsub)
                    (swap! (:state agent) assoc :turn-count 2)
                    ;; Only the first swap fired the listener
                    (-> (expect @calls) (.toBe 1)))))))

(describe "agent.extensions - __state-atom"
          (fn []
            (it "exposes the live state atom for direct swap!"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        atm (.-__state-atom api)]
                    (-> (expect (some? atm)) (.toBe true))
                    (swap! atm assoc :active-role "fast")
                    ;; Same atom — the agent sees the change too
                    (-> (expect (:active-role @(:state agent))) (.toBe "fast")))))))

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

;;; ─── Extension-API path for new hooks (G1/G2, G20) ────────────────────
;; These tests verify that when an extension subscribes via `api.on` (the
;; wrapper path through create-extension-api), its handler is invoked with
;; the correct shape AND its return value flows back to the emit-collect
;; caller — the raw event-bus tests in stream_filter.test.cljs / loop_run
;; cover the bus itself, but not the wrapper that actual extensions go
;; through.

(describe "agent.extensions - stream_filter via api.on"
          (fn []
            (it "handler receives event data + ext-ctx and its return propagates"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        received (atom nil)]
                    (.on api "stream_filter"
                         (fn [data _ctx]
                           (reset! received data)
                           #js {:abort  true
                                :reason "matched via api.on"
                                :inject #js [#js {:role "system" :content "no eval"}]}))
                    (let [p ((:emit-collect (:events agent))
                             "stream_filter"
                             #js {:delta "call eval()" :chunk "eval()" :type "message_update"})]
                      (.then p (fn [result]
                                 (-> (expect (some? @received)) (.toBe true))
                                 (-> (expect (.-chunk @received)) (.toBe "eval()"))
                                 (-> (expect (get result "abort")) (.toBe true))
                                 (-> (expect (get result "reason")) (.toBe "matched via api.on"))
                                 (let [inject (get result "inject")]
                                   (-> (expect (.-length inject)) (.toBe 1))
                                   (-> (expect (.-content (aget inject 0))) (.toBe "no eval")))))))))

            (it "handler returning nil is a no-op"
                (fn []
                  (let [{:keys [agent api]} (make-api)]
                    (.on api "stream_filter" (fn [_data _ctx] nil))
                    (let [p ((:emit-collect (:events agent))
                             "stream_filter"
                             #js {:delta "safe" :chunk "safe" :type "message_update"})]
                      (.then p (fn [result]
                                 (-> (expect (get result "abort")) (.toBeUndefined))))))))

            (it "handler receives the ext-ctx second arg"
                (fn []
                  (let [{:keys [agent api]} (make-api)
                        seen-ctx (atom nil)]
                    (.on api "stream_filter"
                         (fn [_data ctx]
                           (reset! seen-ctx ctx)
                           nil))
                    (let [p ((:emit-collect (:events agent))
                             "stream_filter"
                             #js {:delta "x" :chunk "x" :type "message_update"})]
                      (.then p (fn [_]
                                 (-> (expect (some? @seen-ctx)) (.toBe true))))))))))

;;; ─── before_message_send via api.on ───────────────────────────────────
;; For before_message_send we can exercise the full agent loop by blocking
;; at before_provider_request — this proves the extension handler is called
;; between context_assembly and the LLM call AND that its return transforms
;; the effective system prompt / message list that the LLM would have seen.

(defn ^:async test-before-message-send-via-api-replaces-system []
  (let [{:keys [agent api]} (make-api)
        seen-system         (atom nil)]
    (.on api "before_message_send"
         (fn [_data _ctx] #js {:system "REPLACED BY EXT"}))
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"}))
    (js-await (run agent "hello"))
    (-> (expect @seen-system) (.toBe "REPLACED BY EXT"))))

(defn ^:async test-before-message-send-via-api-receives-data-and-ctx []
  (let [{:keys [agent api]} (make-api)
        seen-args           (atom nil)]
    (.on api "before_message_send"
         (fn [data ctx]
           (reset! seen-args {:data data :ctx ctx})
           nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "probe"))
    (-> (expect (some? (:data @seen-args))) (.toBe true))
    (-> (expect (some? (:ctx  @seen-args))) (.toBe true))
    ;; data carries messages + system + model
    (-> (expect (some? (.-messages (:data @seen-args)))) (.toBe true))
    (-> (expect (some? (.-system   (:data @seen-args)))) (.toBe true))))

(defn ^:async test-before-message-send-via-api-nil-is-no-op []
  (let [{:keys [agent api]} (make-api)
        seen-system         (atom nil)]
    (.on api "before_message_send" (fn [_data _ctx] nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [config]
                             (reset! seen-system (.-system config))
                             #js {:block true :reason "ok"}))
    (js-await (run agent "hello"))
    ;; Original agent system prompt survives unchanged
    (-> (expect (.includes @seen-system "test")) (.toBe true))))

(describe "agent.extensions - before_message_send via api.on"
          (fn []
            (it "handler returning {system: ...} replaces the effective system prompt"
                test-before-message-send-via-api-replaces-system)
            (it "handler receives event data + ext-ctx"
                test-before-message-send-via-api-receives-data-and-ctx)
            (it "handler returning nil has no effect"
                test-before-message-send-via-api-nil-is-no-op)))
