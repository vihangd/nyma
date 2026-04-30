(ns agent.extensions.mcp-client.index
  "MCP client for nyma's main LLM path.

   Spawns each configured MCP server (from agent-shell's
   `shared/mcp-servers` atom — populated by `mcp_discovery.cljs`
   reading .mcp.json / .cursor/mcp.json), keeps a managed pool of
   stdio JSON-RPC clients, and registers each remote tool as a
   nyma-side tool named `mcp__<server>__<tool>`. The LLM picks them
   like any other tool; calls delegate to the underlying client.

   Activation today is a no-op skeleton — subsequent commits add
   client.cljs / manager.cljs / tool_bridge.cljs / status_segments.cljs."
  (:require ["@modelcontextprotocol/sdk/client/index.js" :as mcp-client-sdk]
            ["@modelcontextprotocol/sdk/client/stdio.js" :as mcp-stdio]))

(defn ^:export default [_api]
  ;; Phase 1 skeleton: prove auto-discovery picks the extension up
  ;; and the SDK imports load cleanly. No subscriptions yet.
  (when (.-NYMA_MCP_DEBUG js/process.env)
    (js/console.log "[mcp-client] skeleton activated"
                    (boolean (.-Client mcp-client-sdk))
                    (boolean (.-StdioClientTransport mcp-stdio))))
  ;; Deactivate (no-op for skeleton).
  (fn [] nil))
