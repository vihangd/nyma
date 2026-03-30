(ns agent.events)

(def all-event-types
  ["session_start" "session_end" "session_before_switch" "session_switch"
   "agent_start" "agent_end"
   "turn_start" "turn_end"
   "message_start" "message_update" "message_end"
   "tool_call" "tool_result" "tool_execution_start" "tool_execution_end"
   "context" "before_agent_start" "input"
   "compact" "before_compact"])

(defn create-event-bus
  "Typed event emitter. Handlers are called in registration order."
  []
  (let [handlers (atom {})]
    {:on   (fn [event handler]
             (swap! handlers update event (fnil conj []) handler))
     :emit (fn [event data]
             (doseq [h (get @handlers event [])]
               (h data)))
     :off  (fn [event handler]
             (swap! handlers update event
               (fn [hs] (vec (remove #(= % handler) hs)))))}))
