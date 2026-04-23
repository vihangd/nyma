(ns test-util.agent-harness
  "Shared test harness for agent integration tests.

   Provides helpers to spin up an agent with an event bus and state atom,
   install provider blocks or forced throws inside run, and capture event logs.
   Extracted from cli_integration.test.cljs and loop_run.test.cljs so every
   integration test can reuse the same setup without copy-paste drift."
  (:require [agent.core :refer [create-agent]]))

(defn make-test-agent
  "Create a minimal agent suitable for integration tests.
   Accepts an optional opts map merged into create-agent opts."
  ([] (make-test-agent {}))
  ([extra-opts]
   (create-agent (merge {:model         "mock-model"
                         :system-prompt "You are a test agent."
                         :max-steps     5}
                        extra-opts))))

(defn block-provider!
  "Install a before_provider_request handler that blocks every LLM call.
   Returns the handler so callers can remove it if needed.
   Optional reason string defaults to \"blocked for test\"."
  ([agent] (block-provider! agent "blocked for test"))
  ([agent reason]
   (let [h (fn [_] #js {:block true :reason reason})]
     ((:on (:events agent)) "before_provider_request" h)
     h)))

(defn throw-from-run!
  "Install a before_agent_start handler that throws err, simulating an error
   originating inside run before any LLM call. Returns the handler."
  [agent err]
  (let [h (fn [_] (throw err))]
    ((:on (:events agent)) "before_agent_start" h)
    h))

(defn record-events
  "Start recording events fired on the agent's event bus.
   Returns an atom accumulating {:event e :data d} maps in order.
   Pass the atom to stop-recording to remove the listeners."
  ([agent] (record-events agent nil))
  ([agent event-filter]
   (let [log      (atom [])
         handlers (atom {})]
     (doseq [ev (or event-filter
                    ["agent_start" "agent_end" "message_update" "message_start"
                     "message_end" "tool_execution_start" "tool_execution_end"])]
       (let [h (fn [data] (swap! log conj {:event ev :data data}))]
         ((:on (:events agent)) ev h)
         (swap! handlers assoc ev h)))
     {:log      log
      :stop     (fn []
                  (doseq [[ev h] @handlers]
                    ((:off (:events agent)) ev h)))})))
