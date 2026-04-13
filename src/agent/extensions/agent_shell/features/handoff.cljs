(ns agent.extensions.agent-shell.features.handoff
  "Session handoff between agents. Captures conversation context from
   the current agent, disconnects, connects to a new agent, and sends
   the context as the first prompt so the new agent can continue.

   When /handoff is called with no argument and a UI is available,
   an interactive agent picker is shown instead of a usage string."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.acp.client :as client]
            [clojure.string :as str]))

(defn- notify [api msg & [level]]
  (when (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))))

(defn- build-context
  "Build a handoff context string from the current agent's state."
  [agent-key custom-msg]
  (let [state (get @shared/agent-state agent-key)
        agent-def (get registry/agents agent-key)
        agent-name (or (:name agent-def) (shared/kw-name agent-key))
        title (:session-title state)
        mode  (:mode state)
        lines [(str "I'm continuing a task that was started in " agent-name ".")
               (when title (str "The session was titled: " title))
               (when mode (str "The agent was in " mode " mode."))
               ""
               (if (seq custom-msg)
                 (str "Here's what I need you to continue with:\n" custom-msg)
                 "Please review the project state and continue where the previous agent left off. Start by checking recent git changes and file modifications.")]]
    (str/join "\n" (remove nil? lines))))

(defn ^:async do-handoff
  "Execute the actual handoff from `from-key` to `to-key-str` agent.
   `custom-msg` is an optional string to include in the context transfer."
  [api from-key to-key-str custom-msg]
  (let [to-def (get registry/agents to-key-str)]
    (cond
      (not to-def)
      (notify api (str "Unknown agent: " to-key-str) "error")

      (= from-key to-key-str)
      (notify api "Already connected to that agent" "error")

      :else
      (do
        (notify api (str "Handing off from "
                         (shared/kw-name from-key)
                         " to " (:name to-def) "..."))
        (let [context (build-context from-key custom-msg)]
          (-> (pool/disconnect from-key)
              (.then
               (fn [_]
                 (reset! shared/active-agent nil)
                 (pool/get-or-create to-key-str to-def api)))
              (.then
               (fn [conn]
                 (reset! shared/active-agent to-key-str)
                 (shared/update-agent-state! to-key-str :connected true)
                 ;; Install the custom header now that an agent is
                 ;; active. Same reasoning as agent_switcher: cli.cljs
                 ;; fires session_ready before the ink app is mounted,
                 ;; so the session_ready-time call no-ops. This path
                 ;; runs at user-driven handoff time, when api.ui is
                 ;; guaranteed live. setup-ui! is idempotent.
                 (shared/setup-ui!)
                 (notify api (str "Connected to " (:name to-def) ". Sending context..."))
                 (client/send-prompt conn context)))
              (.then
               (fn [result]
                 (when-let [usage (:usage result)]
                   (shared/update-agent-state! to-key-str :turn-usage usage))
                 (notify api "Handoff complete.")))
              (.catch
               (fn [e]
                 (notify api (str "Handoff failed: " (.-message e)) "error")))))))))

(defn activate
  "Register the /handoff command."
  [api]
  (.registerCommand api "handoff"
                    #js {:description "Hand off current session to another agent with context transfer"
                         :handler
                         (fn [args _ctx]
                           (let [from-key @shared/active-agent]
                             (cond
                               (not from-key)
                               (notify api "No agent connected. Use /agent <name> first." "error")

                               (empty? args)
                               ;; Interactive picker when UI is available; usage text otherwise
                               (if (and (.-ui api) (.-available (.-ui api)))
                                 (let [agent-keys (keys registry/agents)
                                       options    (clj->js
                                                   (mapv (fn [k]
                                                           (let [def (get registry/agents k)]
                                                             #js {:value       (shared/kw-name k)
                                                                  :label       (or (:name def) (shared/kw-name k))
                                                                  :description (or (:description def) "")}))
                                                         agent-keys))]
                                   (-> (.select (.-ui api) "Select agent to hand off to:" options)
                                       (.then (fn [selected]
                                                (when selected
                                                  (do-handoff api from-key selected nil))))))
                                 (notify api (str "Usage: /handoff <agent> [context message]\n"
                                                  "Available agents: "
                                                  (str/join ", " (map shared/kw-name (keys registry/agents))))
                                         "info"))

                               :else
                               (let [to-key-str (first args)
                                     custom-msg (when (> (count args) 1)
                                                  (str/join " " (rest args)))]
                                 (do-handoff api from-key to-key-str custom-msg)))))})

  (fn []
    (.unregisterCommand api "handoff")))
