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
// A file the way the spec embeds it, and the way eM Client does (serialised into a text item).
const pdf = { uri: "x://att/../../evil name", mimeType: "application/pdf", blob: Buffer.from("%PDF-1.4 hi").toString("base64") };
server.registerTool("get_file", { description: "Embedded resource", inputSchema: {} },
  async () => ({ content: [{ type: "resource", resource: pdf }] }));
server.registerTool("get_file_as_text", { description: "Resource serialised as text", inputSchema: {} },
  async () => text(JSON.stringify({ type: "resource", resource: pdf })));

// structuredContent with a declared outputSchema (MCP 2025-06-18)
server.registerTool("get_structured", { description: "Typed result", inputSchema: {},
  outputSchema: { count: z.number(), names: z.array(z.string()) } },
  async () => ({ content: [{ type: "text", text: "2 names" }], structuredContent: { count: 2, names: ["a", "b"] } }));

await server.connect(new StdioServerTransport());
