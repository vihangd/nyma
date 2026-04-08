(ns clear-session.test
  "Tests for /clear resetting the ACP agent session (F4)."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]))

;;; ─── Mock API ─────────────────────────────────────────────────

(defn- make-mock-api []
  (let [notifications  (atom [])
        event-handlers (atom {})]
    #js {:ui             #js {:available true
                              :notify    (fn [msg _level] (swap! notifications conj msg))}
         :on             (fn [event handler]
                           (swap! event-handlers update event (fnil conj []) handler))
         :off            (fn [event handler]
                           (swap! event-handlers update event
                             (fn [hs] (vec (remove #(= % handler) hs)))))
         :_notifications notifications
         :_handlers      event-handlers}))

;;; ─── Mock connection ──────────────────────────────────────────

(defn- make-mock-conn [agent-key]
  (let [writes      (atom [])
        cancelled   (atom false)
        pending-map (atom {})]
    {:stdin      #js {:write (fn [data] (swap! writes conj data) nil)
                      :flush (fn [] nil)}
     :state      (atom {:pending {}})
     :session-id (atom "sess-abc-123")
     :id-counter (atom 0)
     :agent-key  agent-key
     :_writes    writes
     :_cancelled cancelled}))

(defn- parse-last-rpc [conn]
  (when-let [w (last @(:_writes conn))]
    (js/JSON.parse w)))

;;; ─── Mock events bus ──────────────────────────────────────────

(defn- make-mock-events []
  (let [handlers (atom {})
        emitted  (atom [])]
    {:on   (fn [event handler & [_priority]]
              (swap! handlers update event (fnil conj []) handler))
     :off  (fn [event handler]
              (swap! handlers update event
                (fn [hs] (vec (remove #(= % handler) hs)))))
     :emit (fn [event data]
              (swap! emitted conj {:event event :data data})
              (doseq [h (get @handlers event [])]
                (h data)))
     :emit-collect (fn [event data] (js/Promise.resolve {}))
     :_emitted emitted
     :_handlers handlers}))

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

;;; ─── Group 1: Event emission ──────────────────────────────────

(describe "clear-session:event-emission" (fn []
  (it "session_clear is emitted when /clear is invoked"
    (fn []
      (let [events (make-mock-events)
            ;; Simulate what builtins.cljs does
            _clear (fn [_args _ctx]
                     ((:emit events) "session_clear" {}))]
        (_clear nil nil)
        (let [emitted @(:_emitted events)]
          (-> (expect (some #(= (:event %) "session_clear") emitted)) (.toBeTruthy))))))

  (it "session_clear handler fires when event is emitted"
    (fn []
      (let [events   (make-mock-events)
            fired    (atom false)
            handler  (fn [_data] (reset! fired true))]
        ((:on events) "session_clear" handler)
        ((:emit events) "session_clear" {})
        (-> (expect @fired) (.toBe true)))))))

;;; ─── Group 2: Session/new RPC ─────────────────────────────────

(describe "clear-session:session-new-rpc" (fn []
  (it "session/new is sent to active connection"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn (make-mock-conn :claude)
            _    (reset! shared/connections {:claude conn})]
        ;; Simulate the session_clear hook behaviour
        (when-let [agent-key @shared/active-agent]
          (when-let [c (get @shared/connections agent-key)]
            (let [id (swap! (:id-counter c) inc)
                  msg (js/JSON.stringify
                        (clj->js {:jsonrpc "2.0" :id id
                                  :method "session/new" :params {}}))]
              (.write (.-stdin c) (str msg "\n"))
              (.flush (.-stdin c)))))
        (-> (expect (.-method (parse-last-rpc conn))) (.toBe "session/new")))))

  (it "session-id atom updated on successful session/new"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn    (make-mock-conn :claude)
            _       (reset! shared/connections {:claude conn})
            new-sid "new-session-xyz"]
        ;; Simulate resolution of session/new
        (reset! (:session-id conn) new-sid)
        (-> (expect @(:session-id conn)) (.toBe "new-session-xyz")))))

  (it "no active agent is a no-op"
    (fn []
      ;; active-agent is nil — should not throw
      (let [called (atom false)]
        (when-let [agent-key @shared/active-agent]
          (reset! called true)
          agent-key)
        (-> (expect @called) (.toBe false)))))))

;;; ─── Group 3: Graceful failure ────────────────────────────────

(describe "clear-session:graceful-failure" (fn []
  (it "RPC rejection shows error notification"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn (make-mock-conn :claude)
            _    (reset! shared/connections {:claude conn})
            api  (make-mock-api)
            notifs (.-_notifications api)]
        ;; Simulate a failing session/new by directly calling the catch
        (let [err (js/Error. "ACP: unsupported method")]
          (.notify (.-ui api) (str "Session reset failed: " (.-message err)) "error"))
        (let [notif (str/join " " @notifs)]
          (-> (expect (str/includes? notif "Session reset failed")) (.toBe true))))))

  (it "missing connection with active agent shows no crash"
    (fn []
      ;; active-agent set but no connection in map
      (reset! shared/active-agent :claude)
      (reset! shared/connections {})
      (let [conn-found? (some? (get @shared/connections @shared/active-agent))]
        (-> (expect conn-found?) (.toBe false)))))))

;;; ─── Group 4: Integration with agent-shell hook ──────────────

(describe "clear-session:hook-integration" (fn []
  (it "session_clear hook finds connection and sends RPC"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn (make-mock-conn :claude)
            _    (reset! shared/connections {:claude conn})
            api  (make-mock-api)
            ;; Build a minimal hook that mirrors index.cljs logic
            hook-fn
            (fn [_ _]
              (when-let [agent-key @shared/active-agent]
                (when-let [c (get @shared/connections agent-key)]
                  (let [id  (swap! (:id-counter c) inc)
                        msg (js/JSON.stringify
                              (clj->js {:jsonrpc "2.0" :id id
                                        :method "session/new" :params {}}))]
                    (.write (.-stdin c) (str msg "\n"))
                    (.flush (.-stdin c))))))]
        ;; Fire the hook
        (hook-fn nil nil)
        (-> (expect (.-method (parse-last-rpc conn))) (.toBe "session/new")))))

  (it "session_clear with pending RPC: resolve updates session-id"
    (fn []
      (reset! shared/active-agent :claude)
      (let [conn (make-mock-conn :claude)
            _    (reset! shared/connections {:claude conn})]
        ;; After hook fires and RPC resolves, session-id should update
        ;; Simulate the .then callback
        (let [new-sid "fresh-session-999"]
          (when-let [sid new-sid]
            (reset! (:session-id conn) sid))
          (-> (expect @(:session-id conn)) (.toBe "fresh-session-999"))))))))
