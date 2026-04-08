(ns agent.extensions.agent-shell.features.mode-switcher
  "Mode switching commands: /plan, /yolo, /approve, /auto-edit."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.client :as client]))

(defn- notify [api msg & [level]]
  (if (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))
    nil))

(defn- switch-mode!
  "Send session/set_mode to the active agent."
  [api mode-key]
  (let [agent-key @shared/active-agent
        conn      (get @shared/connections agent-key)
        agent-def (get registry/agents agent-key)]
    (cond
      (not agent-key)
      (notify api "No agent connected" "error")

      (not conn)
      (notify api "Agent not connected" "error")

      :else
      (let [mode-id (get-in agent-def [:modes mode-key])]
        (if-not mode-id
          (notify api (str (:name agent-def) " does not support " (shared/kw-name mode-key) " mode") "error")
          (let [sid @(:session-id conn)]
            (-> (client/send-request conn (client/next-id conn) "session/set_mode"
                  {:sessionId sid :modeId mode-id})
                (.then (fn [_]
                         (shared/update-agent-state! agent-key :mode mode-id)
                         (notify api (str "Switched to " (shared/kw-name mode-key) " mode"))))
                (.catch (fn [e]
                          (notify api (str "Mode switch failed: " (.-message e)) "error"))))))))))

(defn activate
  "Register mode switching commands."
  [api]
  (let [commands [["plan"      :plan      "Switch to plan/read-only mode"]
                  ["yolo"      :yolo      "Switch to auto-approve mode"]
                  ["approve"   :approve   "Switch to approval mode (default)"]
                  ["auto-edit" :auto-edit "Switch to auto-edit mode (edits approved, shell prompts)"]]]
    (doseq [[cmd-name mode-key description] commands]
      (.registerCommand api cmd-name
        #js {:description description
             :handler (fn [_args _ctx] (switch-mode! api mode-key))}))

    ;; Return deactivator
    (fn []
      (doseq [[cmd-name _ _] commands]
        (.unregisterCommand api cmd-name)))))
