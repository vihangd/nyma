(ns agent.state)

(defn create-store
  "Event-sourced state store. All mutations go through dispatch!.
   Supports multiple reducers per event type, subscriber notifications,
   and history tracking for debugging/replay.
   Optionally accepts an existing atom to share state with legacy code."
  [initial-state & [existing-atom]]
  (let [state    (or existing-atom (atom initial-state))
        reducers (atom {})
        history  (atom [])
        subs     (atom [])]
    {:get-state  (fn [] @state)

     :register
     (fn [event-type reducer-fn]
       (swap! reducers update event-type (fnil conj []) reducer-fn))

     :dispatch!
     (fn [event-type data]
       (let [rs (get @reducers event-type [])]
         ;; Compose all reducers into a single atomic swap
         (when (seq rs)
           (swap! state (fn [s] (reduce (fn [acc r] (r acc data)) s rs))))
         (swap! history conj {:type event-type :data data :timestamp (js/Date.now)})
         (let [current @state]
           (doseq [s @subs]
             (s event-type current)))))

     :subscribe
     (fn [listener]
       (swap! subs conj listener)
       ;; Return unsubscribe function
       (fn [] (swap! subs (fn [ss] (vec (remove #(= % listener) ss))))))

     :history (fn [] @history)

     ;; Backwards compat: atom-like interface for code that uses swap!/deref
     :deref (fn [] @state)
     :swap  (fn [f] (swap! state f))
     :reset (fn [v] (reset! state v))}))

(def core-reducers
  "Default reducers for agent state transitions."
  {:message-added    (fn [state data] (update state :messages conj (:message data)))
   :messages-cleared (fn [state _data] (assoc state :messages []))
   :tools-changed    (fn [state data] (assoc state :active-tools (:active-tools data)))
   :model-changed    (fn [state data] (assoc state :model (:model data)))
   :usage-updated    (fn [state data]
                       (-> state
                           (update :total-input-tokens (fnil + 0) (:input-tokens data))
                           (update :total-output-tokens (fnil + 0) (:output-tokens data))
                           (update :total-cost (fnil + 0.0) (:cost data))
                           (update :turn-count (fnil inc 0))))
   :tool-execution-started (fn [state data]
                             (update state :active-executions (fnil conj #{}) (:exec-id data)))
   :tool-execution-ended   (fn [state data]
                             (update state :active-executions (fnil disj #{}) (:exec-id data)))
   :tool-call-started      (fn [state data]
                             (update state :tool-calls (fnil assoc {})
                               (:exec-id data)
                               {:tool-name  (:tool-name data)
                                :args       (:args data)
                                :status     "running"
                                :start-time (:start-time data)}))
   :tool-call-ended        (fn [state data]
                             (update-in state [:tool-calls (:exec-id data)]
                               merge {:status   "done"
                                      :duration (:duration data)
                                      :result   (:result data)}))})

(defn create-agent-store
  "Create a state store pre-loaded with agent core reducers.
   Optionally accepts an existing atom to share state with legacy code."
  [initial-state & [existing-atom]]
  (let [store (create-store initial-state existing-atom)]
    (doseq [[evt reducer] core-reducers]
      ((:register store) evt reducer))
    store))
