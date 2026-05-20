#!/usr/bin/env bun
// Full-load tool schema probe. Loads EVERY extension via the real
// discover-and-load path (same as cli.cljs), then walks every
// registered tool through asSchema. Reproduces the production
// schema graph the user's session actually has.
//
// Run: bun run scripts/probe-all-tool-schemas.mjs

import * as core from "../dist/agent/core.mjs";
import * as ext from "../dist/agent/extensions.mjs";
import * as loader from "../dist/agent/extension_loader.mjs";
import * as path from "node:path";
import * as os from "node:os";
import { asSchema } from "@ai-sdk/provider-utils";

const builtin = path.join(import.meta.dir, "..", "dist", "agent", "extensions");
const globalDir = path.join(os.homedir(), ".nyma", "extensions");
const projectDir = path.join(process.cwd(), ".nyma", "extensions");

const agent = core.create_agent({
  "model": { modelId: "test" },
  "system-prompt": "test",
  "settings": {}
});

const api = ext.create_extension_api(agent);
agent["extension-api"] = api;

console.log("Loading extensions from:");
console.log("  builtin:", builtin);
console.log("  global :", globalDir);
console.log("  project:", projectDir);
console.log();

const loaded = await loader.discover_and_load([builtin, globalDir, projectDir], api);
console.log(`Loaded ${loaded.length} extensions\n`);

// Allow async session_ready handlers (mcp_client) to settle.
const events = agent.events;
const emitAsync = events["emit-async"] ?? events.emit_async;
if (emitAsync) {
  await emitAsync.call(events, "session_ready", {});
}
await new Promise(r => setTimeout(r, 5000));

const reg = agent["tool-registry"];
const allTools = reg.all();
const names = Object.keys(allTools).sort();
console.log(`Total tools registered: ${names.length}\n`);

let bad = 0;
for (const name of names) {
  const td = allTools[name];
  const sch = td?.inputSchema ?? td?.parameters;
  if (!sch) {
    console.log(`  ?  ${name} — NO inputSchema`);
    continue;
  }
  try {
    asSchema(sch);
  } catch (e) {
    bad++;
    console.log(`  ✗  ${name} — ${e.message}`);
    console.log(`     keys: ${Object.keys(sch).join(", ")}`);
    console.log(`     symbols: [${Object.getOwnPropertySymbols(sch).map(s => s.toString()).join(",")}]`);
    console.log(`     ~standard: ${"~standard" in sch}`);
    console.log(`     typeof: ${typeof sch}, ctor: ${sch.constructor?.name}`);
  }
}

console.log(`\n${bad === 0 ? "All schemas valid." : `${bad} broken — see above.`}`);

for (const e of loaded) {
  if (typeof e.deactivate === "function") {
    try { await e.deactivate(); } catch {}
  }
}
process.exit(bad === 0 ? 0 : 1);
