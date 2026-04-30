(ns agent.extensions.claude-hook-bridge.handlers.mcp-tool
  "MCP-tool handler — calls a tool on a configured MCP server.

   STATUS: stub. Full implementation requires an MCP client speaking
   JSON-RPC over stdio against the configured server. The server
   discovery side already exists at
   `agent.extensions.agent-shell.features.mcp-discovery`, but no
   client/transport is wired in nyma yet, and `@modelcontextprotocol/sdk`
   isn't a dependency.

   When a hook config sets `type: \"mcp_tool\"`, this stub returns
   a non-blocking error envelope with a clear reason string so the
   user sees what's missing instead of a silent failure.

   Implementation plan when wired:
     1. npm i @modelcontextprotocol/sdk + bun-friendly stdio transport
     2. Maintain a connection pool keyed by server name; spawn lazily.
     3. Substitute `${tool_input.x}` style references in spec.input
        against the inbound event payload.
     4. Call `client.callTool(server, tool, substituted-input)`.
     5. Map the result's content (text or structured) into our
        {:exit-code :stdout :stderr} envelope.
     6. Honor abort-signal by closing the connection.

   Until this lands, the handler is a clear no-op."
  (:require [clojure.string :as str]))

(defn ^:async run-mcp-tool
  "Stub. Returns a deterministic 'not implemented' envelope."
  [{:keys [server tool _input _stdin-json _abort-signal]}]
  (js/Promise.resolve
   {:exit-code 1
    :stdout    ""
    :stderr    (str "[hook-bridge] mcp_tool handler not yet wired "
                    "(server=" (or server "?")
                    " tool=" (or tool "?") "). "
                    "Use type=command + a small wrapper script as a workaround.")
    :timed-out? false
    :aborted?   false
    :error      nil}))
