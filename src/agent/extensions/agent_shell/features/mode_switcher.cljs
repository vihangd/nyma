(ns agent.extensions.agent-shell.features.mode-switcher
  "ACP mode switching: /yolo, /approve, /auto-edit — and `/agent mode <id>`,
   which is where plan mode lives.

   These drive the connected ACP AGENT's permission mode over
   session/set_mode. They are not nyma's own permission modes (/mode), and the
   two used to be confusable: this ns also registered /plan, as did
   model_roles' native plan mode. Whoever activated second skipped, so which
   extension owned /plan depended on load order — and when both registered,
   the resolver saw two `__plan` keys and /plan resolved to nothing. Native
   plan mode is /planmode; the ACP one is /agent mode plan."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.client :as client]))

(defn- notify [api msg & [level]]
  (if (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))
    nil))

(def no-agent-msg
  "Said by every ACP mode command when nothing is connected. Naming /mode
   matters: with no agent attached, `/yolo` looks like it should loosen nyma's
   own approval gate, and it does not."
  (str "no agent connected — this switches the ACP agent's mode; "
       "for nyma's own permission mode use /mode"))

(defn switch-mode!
  "Send session/set_mode to the active agent. Public: `/agent mode <id>`
   dispatches here from the agent switcher."
  [api mode-key]
  (let [agent-key @shared/active-agent
        conn      (shared/find-conn-by-agent agent-key)
        agent-def (get registry/agents agent-key)]
    (cond
      (not agent-key)
      (notify api no-agent-msg "error")

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
  ;; No /plan row. ACP plan mode is `/agent mode plan`; /planmode is nyma's
  ;; own. Registering both /plan commands made the resolver ambiguous, and the
  ;; guard that avoided that made ownership depend on load order.
  (let [commands [["yolo"      :yolo      "Switch the ACP agent to auto-approve mode"]
                  ["approve"   :approve   "Switch the ACP agent to approval mode (default)"]
                  ["auto-edit" :auto-edit "Switch the ACP agent to auto-edit mode (edits approved, shell prompts)"]]]
    (doseq [[cmd-name mode-key description] commands]
      (.registerCommand api cmd-name
                        #js {:description description
                             :handler (fn [_args _ctx] (switch-mode! api mode-key))}))

    ;; Return deactivator
    (fn []
      (doseq [[cmd-name _ _] commands]
        (.unregisterCommand api cmd-name)))))
