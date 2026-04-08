(ns agent.extensions.agent-shell.index
  "ACP Agent Shell — unified frontend for coding agents.
   Wraps Claude Code, Gemini CLI, OpenCode, Qwen, Goose, and Kiro
   via the Agent Client Protocol (JSON-RPC 2.0 over stdio)."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.features.input-router :as input-router]
            [agent.extensions.agent-shell.features.agent-switcher :as agent-switcher]
            [agent.extensions.agent-shell.features.model-switcher :as model-switcher]
            [agent.extensions.agent-shell.features.mode-switcher :as mode-switcher]
            [agent.extensions.agent-shell.features.effort-switcher :as effort-switcher]
            [agent.extensions.agent-shell.features.permission-ui :as permission-ui]
            [agent.extensions.agent-shell.features.session-mgmt :as session-mgmt]
            [agent.extensions.agent-shell.features.cost-tracker :as cost-tracker]
            [agent.extensions.agent-shell.features.handoff :as handoff]
            [agent.extensions.agent-shell.features.mcp-discovery :as mcp-discovery]))

;; Note: UI components (header, status_line) use JSX and are loaded
;; via setHeader/setFooter factory functions that return JSX elements.
;; Since this is a multi-file Squint extension, the JSX components
;; will be compiled separately and referenced from here.

(defn ^:export default
  "Extension activation function. Called with scoped API."
  [api]
  (let [config (shared/load-config)]

    ;; Load config-defined agents into the registry
    (registry/refresh! config)

    ;; Activate all sub-modules
    (let [deactivators
          [(input-router/activate api)
           (agent-switcher/activate api)
           (model-switcher/activate api)
           (mode-switcher/activate api)
           (effort-switcher/activate api)
           (permission-ui/activate api)
           (session-mgmt/activate api)
           (cost-tracker/activate api)
           (handoff/activate api)
           (mcp-discovery/activate api)]]

      ;; Store the api reference for lazy UI access (footer/header setup on connect)
      (reset! shared/api-ref api)

      ;; Hook session shutdown for cleanup
      (.on api "session_shutdown"
        (fn [_ _]
          (pool/disconnect-all)))

      ;; Hook /clear → reset ACP session
      (.on api "session_clear"
        (fn [_ _]
          (when-let [agent-key @shared/active-agent]
            (when-let [conn (get @shared/connections agent-key)]
              (client/cancel-prompt conn)
              (-> (client/send-request conn (client/next-id conn) "session/new" {})
                  (.then (fn [resp]
                           (when-let [sid (and resp (.-sessionId resp))]
                             (reset! (:session-id conn) sid))
                           (let [ui (.-ui api)]
                             (when (and ui (.-available ui))
                               (.notify ui "Agent session reset" "info")))))
                  (.catch (fn [e]
                            (let [ui (.-ui api)]
                              (when (and ui (.-available ui))
                                (.notify ui (str "Session reset failed: " (.-message e)) "error"))))))))))

      ;; Auto-connect if configured
      (when (and (:default-agent config) (:auto-connect config))
        (let [agent-key (:default-agent config)]
          (when-not (get @shared/connections agent-key)
            (js/setTimeout
              (fn []
                (when (and (.-ui api) (.-available (.-ui api)))
                  (.notify (.-ui api) (str "Auto-connecting to " (:default-agent config) "...") "info"))
                (when-let [agent-def (registry/get-agent agent-key)]
                  (pool/get-or-create agent-key agent-def api)))
              1000))))

      ;; Return combined deactivator
      (fn []
        ;; Deactivate all sub-modules
        (doseq [deactivate deactivators]
          (when (fn? deactivate) (deactivate)))
        ;; Disconnect all agents
        (pool/disconnect-all)
        ;; Reset state
        (registry/reset-dynamic!)
        (reset! shared/active-agent nil)
        (reset! shared/connections {})
        (reset! shared/agent-state {})))))
