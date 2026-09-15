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
         ;; Ring-capped: history stores full event payloads (tool args AND
         ;; results) and nothing in production reads it yet — uncapped it is
         ;; a session-length memory leak. 500 entries keeps the debug/replay
         ;; value without pinning every tool result forever.
         (swap! history (fn [h]
                          (let [h' (conj h {:type event-type :data data :timestamp (js/Date.now)})]
                            (if (> (count h') 500) (vec (drop (- (count h') 500) h')) h'))))
         ;; Subscribers get the payload as a third argument: a listener that
         ;; needs the per-event delta (usage per run) could not recover it
         ;; from the accumulated state.
         (let [current @state]
           (doseq [s @subs]
             (s event-type current data)))))

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
   ;; A skill's activation lives in the conversation it was activated in;
   ;; its allowed-tools allowance must not outlive /new or /clear.
   :messages-cleared (fn [state _data] (assoc state :messages []
                                              :active-skills #{}
                                              :skill-allowed-tools {}))
   ;; Wholesale replacement (context relief pruning). Goes through the store
   ;; so subscribers see it, instead of a raw swap! on the shared atom.
   :messages-replaced (fn [state data] (assoc state :messages (vec (:messages data))))
   :tools-changed    (fn [state data] (assoc state :active-tools (:active-tools data)))
   :model-changed    (fn [state data] (assoc state :model (:model data)))
   ;; Cache tokens are accumulated alongside the rest because they are the
   ;; single largest cost lever on an agent loop and nothing was keeping a
   ;; session total: the loop read them per turn for costing and dropped them,
   ;; so `-p --output-format json` reported cache_read_input_tokens: 0 forever.
   ;; NB input-tokens already INCLUDES cache reads and writes; these are a
   ;; breakdown of that number, not an addition to it.
   :usage-updated    (fn [state data]
                       (-> state
                           (update :total-input-tokens (fnil + 0) (:input-tokens data))
                           (update :total-output-tokens (fnil + 0) (:output-tokens data))
                           (update :total-cache-read-tokens (fnil + 0) (or (:cache-read-tokens data) 0))
                           (update :total-cache-write-tokens (fnil + 0) (or (:cache-write-tokens data) 0))
                           (update :total-cost (fnil + 0.0) (:cost data))
                           (update :total-steps (fnil + 0) (or (:steps data) 0))
                           ;; NB counts RUNS, not model steps — usage is
                           ;; dispatched once per run from result.totalUsage.
                           ;; :total-steps is the per-step figure.
                           (update :turn-count (fnil inc 0))))
   :tool-execution-started (fn [state data]
                             (update state :active-executions (fnil conj #{}) (:exec-id data)))
   :tool-execution-ended   (fn [state data]
                             (update state :active-executions (fnil disj #{}) (:exec-id data)))
   ;; There were :tool-call-started/:tool-call-ended reducers here keeping a
   ;; :tool-calls map of every call's args and result. Nothing ever read it, so
   ;; it was a per-session leak of full tool payloads. :active-executions above
   ;; is the live view that IS read.
   })

(defn create-agent-store
  "Create a state store pre-loaded with agent core reducers.
   Optionally accepts an existing atom to share state with legacy code."
  [initial-state & [existing-atom]]
  (let [store (create-store initial-state existing-atom)]
    (doseq [[evt reducer] core-reducers]
      ((:register store) evt reducer))
    store))
