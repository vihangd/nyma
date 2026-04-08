(ns ext-effort-switcher.test
  "Tests for the effort switcher feature."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.features.effort-switcher :as effort-switcher]))

;;; ─── Mock API ─────────────────────────────────────────────────

(defn- make-mock-api []
  (let [registered-commands (atom {})
        notifications       (atom [])]
    #js {:ui          #js {:available true
                           :notify    (fn [msg _level] (swap! notifications conj msg))}
         :registerCommand   (fn [name opts]
                              (swap! registered-commands assoc name opts))
         :unregisterCommand (fn [name]
                              (swap! registered-commands dissoc name))
         :_commands         registered-commands
         :_notifications    notifications}))

;;; ─── Mock connection ──────────────────────────────────────────

(defn- make-mock-conn [agent-key]
  (let [writes (atom [])]
    {:stdin      #js {:write (fn [data] (swap! writes conj data) nil)
                      :flush (fn [] nil)}
     :state      (atom {:pending {}})
     :session-id (atom "test-session-123")
     :id-counter (atom 0)
     :agent-key  agent-key
     :_writes    writes}))

(defn- parse-last-rpc [conn]
  (when-let [w (last @(:_writes conn))]
    (js/JSON.parse w)))

;;; ─── State reset ──────────────────────────────────────────────

(beforeEach
  (fn []
    (reset! shared/active-agent nil)
    (reset! shared/connections {})
    (reset! shared/agent-state {})))

(afterEach
  (fn []
    (reset! shared/active-agent nil)
    (reset! shared/connections {})
    (reset! shared/agent-state {})))

;;; ─── Group 1: Registration ────────────────────────────────────

(describe "effort-switcher:registration" (fn []
  (it "registers /effort command on activate"
    (fn []
      (let [api (make-mock-api)
            _   (effort-switcher/activate api)]
        (-> (expect (contains? @(.-_commands api) "effort")) (.toBe true)))))
  (it "deactivator unregisters /effort"
    (fn []
      (let [api   (make-mock-api)
            deact (effort-switcher/activate api)]
        (deact)
        (-> (expect (contains? @(.-_commands api) "effort")) (.toBe false)))))
  (it "command has a description"
    (fn []
      (let [api (make-mock-api)
            _   (effort-switcher/activate api)]
        (-> (expect (some? (.-description (get @(.-_commands api) "effort"))))
            (.toBe true)))))))

;;; ─── Group 2: No agent connected ──────────────────────────────

(describe "effort-switcher:no-agent" (fn []
  (it "/effort high with no active agent shows error"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["high"] nil)
        (-> (expect (some #(str/includes? % "No agent") @(.-_notifications api)))
            (.toBe true)))))
  (it "/effort with no args shows usage"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js [] nil)
        (-> (expect (some #(str/includes? % "Usage") @(.-_notifications api)))
            (.toBe true)))))))

;;; ─── Group 3: Validation ──────────────────────────────────────

(describe "effort-switcher:validation" (fn []
  (it "no args shows usage listing valid levels"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js [] nil)
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "low")) (.toBe true))
          (-> (expect (str/includes? notif "max")) (.toBe true))))))
  (it "invalid level shows error with the invalid name"
    (fn []
      (reset! shared/active-agent :claude)
      (reset! shared/connections {:claude (make-mock-conn :claude)})
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["banana"] nil)
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "Invalid")) (.toBe true))
          (-> (expect (str/includes? notif "banana")) (.toBe true))))))
  (it "uppercase input is lowercased before sending"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["HIGH"] nil)
        (-> (expect (.-value (.-params (parse-last-rpc conn)))) (.toBe "high")))))))

;;; ─── Group 4: RPC dispatch ───────────────────────────────────

(describe "effort-switcher:rpc" (fn []
  (it "sends session/set_config_option method"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["high"] nil)
        (-> (expect (.-method (parse-last-rpc conn))) (.toBe "session/set_config_option")))))
  (it "params include configId effort and correct value"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["max"] nil)
        (let [params (.-params (parse-last-rpc conn))]
          (-> (expect (.-configId params)) (.toBe "effort"))
          (-> (expect (.-value params)) (.toBe "max"))))))
  (it "params include sessionId from connection"
    (fn []
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            conn    (make-mock-conn :claude)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (reset! shared/active-agent :claude)
        (reset! shared/connections {:claude conn})
        (handler #js ["low"] nil)
        (-> (expect (.-sessionId (.-params (parse-last-rpc conn)))) (.toBe "test-session-123")))))
  (it "each valid level sends the correct value"
    (fn []
      (doseq [level ["low" "medium" "high" "max" "auto"]]
        (let [api     (make-mock-api)
              _       (effort-switcher/activate api)
              conn    (make-mock-conn :claude)
              handler (.-handler (get @(.-_commands api) "effort"))]
          (reset! shared/active-agent :claude)
          (reset! shared/connections {:claude conn})
          (handler #js [level] nil)
          (-> (expect (.-value (.-params (parse-last-rpc conn)))) (.toBe level))))))))

;;; ─── Group 5: Graceful failure ────────────────────────────────

(describe "effort-switcher:graceful-failure" (fn []
  (it "missing connection shows error"
    (fn []
      (reset! shared/active-agent :claude)
      (reset! shared/connections {})
      (let [api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["high"] nil)
        (-> (expect (some #(str/includes? % "not connected") @(.-_notifications api)))
            (.toBe true)))))
  (it "RPC rejection shows error notification"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn    (make-mock-conn :claude)
            _       (reset! shared/connections {:claude conn})
            api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["high"] nil)
        (let [first-entry (first (vals (:pending @(:state conn))))]
          (when first-entry
            ((:reject first-entry) (js/Error. "ACP error: unsupported"))))
        ;; setTimeout flushes all microtask hops before checking
        (js/Promise. (fn [resolve _]
                       (js/setTimeout
                         (fn []
                           (-> (expect (some #(str/includes? % "Effort switch failed")
                                            @(.-_notifications api)))
                               (.toBe true))
                           (resolve nil))
                         10))))))
  (it "deactivator mid-flight does not throw"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn    (make-mock-conn :claude)
            _       (reset! shared/connections {:claude conn})
            api     (make-mock-api)
            deact   (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["high"] nil)
        (deact)
        (-> (expect (contains? @(.-_commands api) "effort")) (.toBe false)))))))

;;; ─── Group 6: State update ────────────────────────────────────

(describe "effort-switcher:state" (fn []
  (it "success updates agent-state effort key"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn    (make-mock-conn :claude)
            _       (reset! shared/connections {:claude conn})
            api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["max"] nil)
        (let [first-entry (first (vals (:pending @(:state conn))))]
          (when first-entry ((:resolve first-entry) #js {})))
        (js/Promise. (fn [resolve _]
                       (js/setTimeout
                         (fn []
                           (-> (expect (shared/get-agent-state :claude :effort))
                               (.toBe "max"))
                           (resolve nil))
                         10))))))
  (it "uppercase input stores lowercased value in state"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn    (make-mock-conn :claude)
            _       (reset! shared/connections {:claude conn})
            api     (make-mock-api)
            _       (effort-switcher/activate api)
            handler (.-handler (get @(.-_commands api) "effort"))]
        (handler #js ["AUTO"] nil)
        (let [first-entry (first (vals (:pending @(:state conn))))]
          (when first-entry ((:resolve first-entry) #js {})))
        (js/Promise. (fn [resolve _]
                       (js/setTimeout
                         (fn []
                           (-> (expect (shared/get-agent-state :claude :effort))
                               (.toBe "auto"))
                           (resolve nil))
                         10))))))))
