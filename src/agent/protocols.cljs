(ns agent.protocols)

(defprotocol ISessionStore
  "Protocol for conversation session storage backends."
  (session-load [this])
  (session-append [this entry])
  (session-build-context [this])
  (session-branch [this entry-id])
  (session-get-tree [this])
  (session-leaf-id [this]))

(defprotocol IToolProvider
  "Protocol for tool registries that manage available tools."
  (provide-tools [this])
  (register-tool [this name tool-def])
  (unregister-tool [this name])
  (set-active-tools [this names])
  (get-active-tools [this]))

(defprotocol IContextBuilder
  "Protocol for building LLM context from conversation state."
  (build-ctx [this agent opts]))
