(ns agent.extensions.agent-shell.features.agent-switcher
  "The /agent command — connect, disconnect, list, and switch agents."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.acp.client :as client]
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
                ;; Initialize agent state
               (shared/update-agent-state! agent-key :connected true)
                ;; Install the custom header now that an agent is active.
                ;; setup-ui! is idempotent — the gate short-circuits after
                ;; the first successful install. We can't rely on the
                ;; session_ready-time call because cli.cljs emits that
                ;; BEFORE interactive/start mounts the ink app, so api.ui
                ;; is still undefined. By the time a user runs /agent the
                ;; app is guaranteed to be mounted and api.ui is live.
               (shared/setup-ui!)
               (notify api (str "Connected to " (:name agent-def)
                                (when-let [sid @(:session-id conn)]
                                  (str " (session: " (subs sid 0 8) "...)"))))
                ;; Phase 21.7: Give the agent a short grace period to
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

(defn- list-agents
  "Show available agents and highlight the active one."
  [api]
  (let [agents  (registry/list-agents)
        active  @shared/active-agent
        lines   (mapv (fn [{agent-key :key agent-name :name}]
                        (if (= agent-key active)
                          (str "  > " (shared/kw-name agent-key) " - " agent-name " (active)")
                          (str "    " (shared/kw-name agent-key) " - " agent-name)))
                      agents)]
    (notify api (str "Available agents:\n" (str/join "\n" lines)))))

(defn activate
  "Register the /agent command."
  [api]
  (.registerCommand api "agent"
                    #js {:description "Connect to a coding agent (claude, gemini, opencode, qwen, goose, kiro)"
                         :handler (fn [args _ctx]
                                    (let [subcmd (first args)]
                                      (cond
                                        (nil? subcmd)
                                        (list-agents api)

                                        (= subcmd "disconnect")
                                        (disconnect-agent! api)

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
