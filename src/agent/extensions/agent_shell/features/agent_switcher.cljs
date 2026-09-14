(ns agent.extensions.agent-shell.features.agent-switcher
  "The /agent command — connect, disconnect, list, and switch agents."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.features.handoff :as handoff]
            [agent.extensions.agent-shell.features.mode-switcher :as mode-switcher]
            [clojure.string :as str]))

(defn- notify [api msg & [level]]
  (if (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))
    nil))

(defn- connect-agent!
  "Connect to an agent by key."
  [api agent-key-str]
  (let [agent-key agent-key-str
        config    (shared/load-config)
        agent-def (registry/get-agent agent-key config)]
    (if-not agent-def
      (notify api (str "Unknown agent: " agent-key-str ". Available: "
                       (str/join ", " (map shared/kw-name (keys registry/agents))))
              "error")
      (do
        (notify api (str "Connecting to " (:name agent-def) "..."))
        ;; Disconnect current agent first
        (-> (if-let [current @shared/active-agent]
              (pool/disconnect current)
              (js/Promise.resolve nil))
            (.then
             (fn [_]
               (pool/get-or-create agent-key agent-def api)))
            (.then
             (fn [conn]
               (reset! shared/active-agent agent-key)
               ;; Point at the capture command while the user is in the state
               ;; where it makes sense — it is not discoverable otherwise, and
               ;; plan mode is exactly when someone is about to want it.
               (when (= "plan" (str (shared/get-agent-state agent-key :mode)))
                 (notify api (str "planning with " (shared/kw-name agent-key)
                                  " — /plan-capture when the plan looks right")))
               ;; A remembered session from a PREVIOUS nyma process. The id is
               ;; opaque and nobody retypes one, so surfacing it here is the
               ;; only realistic way a conversation gets picked back up.
               (when-let [saved (shared/recall-session api agent-key)]
                 (let [sid (aget saved "sessionId")]
                   (when (and sid (not= sid @(:session-id conn)))
                     (notify api (str "previous session for this project"
                                      (when (seq (str (or (aget saved "title") "")))
                                        (str ": " (aget saved "title")))
                                      "\n  /agent-shell__sessions resume to pick it up")))))
                ;; Initialize agent state
               (shared/update-agent-state! agent-key :connected true)
               (notify api (str "Connected to " (:name agent-def)
                                (when-let [sid @(:session-id conn)]
                                  (str " (session: " (subs sid 0 8) "...)"))))
                ;; Give the agent a short grace period to
                ;; push its slash-command list via
                ;; available_commands_update. If it never does, log a
                ;; one-shot note so the user knows `//` fallback is
                ;; active — otherwise `//` silently shows nyma commands
                ;; and they can't tell why. Claude Code publishes a
                ;; command list at session start; others (qwen, gemini)
                ;; currently do not.
               (js/setTimeout
                (fn []
                  (when (and (= @shared/active-agent agent-key)
                             (empty? (shared/get-agent-state agent-key :dynamic-commands)))
                    (notify api
                            (str "Note: " (:name agent-def)
                                 " did not publish slash commands; `//` falls back to nyma commands.")
                            "info")))
                2000)))
            (.catch
             (fn [e]
               (notify api (str "Failed to connect to " (:name agent-def) ": " (.-message e))
                       "error"))))))))

(defn- disconnect-agent!
  "Disconnect the current agent."
  [api]
  (if-let [agent-key @shared/active-agent]
    (let [agent-def (get registry/agents agent-key)]
      (-> (pool/disconnect agent-key)
          (.then (fn [_]
                   (reset! shared/active-agent nil)
                   (notify api (str "Disconnected from " (or (:name agent-def) (shared/kw-name agent-key))))))
          (.catch (fn [e]
                    (notify api (str "Disconnect error: " (.-message e)) "error")))))
    (notify api "No agent connected")))

(defn- detach-agent!
  "Stop routing input to the agent WITHOUT tearing the process down.

   `disconnect` kills the subprocess (stdin close -> SIGTERM -> SIGKILL), and
   that process holds the only copy of the agent's conversation context. So
   after planning there was no way to hand control back to nyma's own loop and
   still be able to return: staying attached sends your typing to the agent
   while /spec run drives nyma, and disconnecting loses the plan discussion.

   Detaching clears `active-agent` only. `pool/get-or-create` keys off the
   connections map and returns any entry that already has a :session-id, so a
   later `/agent <key>` reattaches to the SAME session with its context intact."
  [api]
  (if-let [agent-key @shared/active-agent]
    (let [agent-def (get registry/agents agent-key)]
      (reset! shared/active-agent nil)
      (notify api (str "Detached from " (or (:name agent-def) (shared/kw-name agent-key))
                       " — session left running. /agent " (shared/kw-name agent-key)
                       " to resume, /agent disconnect to stop it.")))
    (notify api "No agent connected")))

(defn format-features
  "Pure: an agent's declared capabilities as one stable, sorted line.
   Empty set → \"(none declared)\", so a blank definition reads as missing data
   rather than as an agent that can do nothing."
  [features]
  (if (seq features)
    (str/join ", " (sort (map shared/kw-name features)))
    "(none declared)"))

(defn- list-agents
  "Show available agents, their declared capabilities, and the active one."
  [api]
  (let [agents  (registry/list-agents)
        active  @shared/active-agent
        lines   (mapcat (fn [{agent-key :key agent-name :name features :features}]
                          [(if (= agent-key active)
                             (str "  > " (shared/kw-name agent-key) " - " agent-name " (active)")
                             (str "    " (shared/kw-name agent-key) " - " agent-name))
                           (str "        " (format-features features))])
                        agents)]
    (notify api (str "Available agents:\n" (str/join "\n" lines)))))

(defn- set-mode!
  "`/agent mode <id>` — the ACP agent's own permission mode. Plan mode lives
   here rather than at /plan, which both this extension and model_roles' native
   plan mode used to claim (load-order dependent, and ambiguous when both won)."
  [api mode-id]
  (if (empty? (str (or mode-id "")))
    (let [agent-def (get registry/agents @shared/active-agent)
          modes     (keys (:modes agent-def))]
      (notify api (str "Usage: /agent mode <id>"
                       (when (seq modes)
                         (str "\n" (:name agent-def) " supports: "
                              (str/join ", " (map shared/kw-name modes))))
                       "\n(this is the ACP agent's mode; for nyma's own permission mode use /mode)")
              "info"))
    (mode-switcher/switch-mode! api (str mode-id))))

(defn activate
  "Register the /agent command."
  [api]
  (.registerCommand api "agent"
                    #js {:description "Connect to a coding agent. /agent <key> | handoff | mode <id> | detach | disconnect"
                         :handler (fn [args _ctx]
                                    (let [subcmd (first args)]
                                      (cond
                                        (nil? subcmd)
                                        (list-agents api)

                                        (= subcmd "disconnect")
                                        (disconnect-agent! api)

                                        (= subcmd "detach")
                                        (detach-agent! api)

                                        ;; Agent-scoped handoff. The top-level
                                        ;; /handoff belongs to the `handoff`
                                        ;; extension (session brief); this one
                                        ;; moves an ACP session to another agent.
                                        (= subcmd "handoff")
                                        (handoff/handle api (vec (rest args)))

                                        (= subcmd "mode")
                                        (set-mode! api (second args))

                                        :else
                                        (connect-agent! api subcmd))))})

  ;; Also register a /disconnect shortcut
  (.registerCommand api "disconnect"
                    #js {:description "Disconnect current ACP agent"
                         :handler (fn [_args _ctx] (disconnect-agent! api))})

  ;; Return deactivator
  (fn []
    (.unregisterCommand api "agent")
    (.unregisterCommand api "disconnect")))
