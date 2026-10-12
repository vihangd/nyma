// Minimal stdio MCP server for mcp_client tests: echoes an env var, and
// exposes one read-only and one destructive tool (by MCP annotation).
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({ name: "echo", version: "0.0.0" });
const text = (t) => ({ content: [{ type: "text", text: String(t) }] });

server.registerTool("get_env", { description: "Value of an env var", inputSchema: { name: z.string() },
  annotations: { readOnlyHint: true } }, async ({ name }) => text(process.env[name] ?? "<unset>"));
server.registerTool("wipe_device", { description: "Pretend to change a device", inputSchema: {},
  annotations: { destructiveHint: true } }, async () => text("wiped"));
server.registerTool("plain", { description: "No annotations", inputSchema: {} }, async () => text("plain"));

await server.connect(new StdioServerTransport());
