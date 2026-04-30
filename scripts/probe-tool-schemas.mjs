#!/usr/bin/env bun
// Probe every registered tool's inputSchema through AI-SDK's
// asSchema in your live config. Reproduces the same condition
// streamText hits during real use; surfaces the broken tool by name.
//
// Run: bun run scripts/probe-tool-schemas.mjs

import * as core from "../dist/agent/core.mjs";
import * as ext from "../dist/agent/extensions.mjs";
import * as scope from "../dist/agent/extension_scope.mjs";
import * as mcpIndex from "../dist/agent/extensions/mcp_client/index.mjs";
import * as agentShell from "../dist/agent/extensions/agent_shell/features/mcp_discovery.mjs";
import * as shellShared from "../dist/agent/extensions/agent_shell/shared.mjs";
import { reset_BANG_, get } from "squint-cljs/core.js";
import { asSchema } from "@ai-sdk/provider-utils";

reset_BANG_(shellShared.mcp_servers, agentShell.scan_mcp_servers(process.cwd()));

const agent = core.create_agent({
  "model": { modelId: "test" },
  "system-prompt": "test"
});

const baseApi = ext.create_extension_api(agent);
const scoped = scope.create_scoped_api(
  baseApi, "mcp-client",
  new Set(["events", "tools", "commands", "ui"])
);
const dispose = mcpIndex.default(scoped);

// Trigger session_ready so MCP servers spawn.
await get(agent.events, "emit-async")("session_ready", {});

// Wait for tools/list handshake.
await new Promise(r => setTimeout(r, 5000));

const allTools = agent["tool-registry"].all();
const names = Object.keys(allTools).sort();
console.log(`Total tools after bring-up: ${names.length}\n`);

let bad = 0;
for (const name of names) {
  const td = allTools[name];
  if (!td) {
    console.log(`  ?  ${name} — null`);
    continue;
  }
  const sch = td.inputSchema ?? td.parameters;
  if (!sch) {
    console.log(`  ?  ${name} — NO inputSchema`);
    continue;
  }
  const symbols = Object.getOwnPropertySymbols(sch).map(s => s.toString());
  const hasStandard = "~standard" in sch;
  try {
    asSchema(sch);
    console.log(`  ✓  ${name}  symbols=[${symbols.join(",")}] standard=${hasStandard}`);
  } catch (e) {
    bad++;
    console.log(`  ✗  ${name} — ${e.message}`);
    console.log(`     keys: ${Object.keys(sch).join(", ")}`);
    console.log(`     symbols: [${symbols.join(",")}]`);
    console.log(`     ~standard: ${hasStandard}`);
  }
}

console.log(`\n${bad === 0 ? "All schemas valid." : `${bad} broken — see above.`}`);
if (typeof dispose === "function") dispose();
process.exit(bad === 0 ? 0 : 1);
