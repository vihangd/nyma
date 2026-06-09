// Manual smoke: exercises run-isolated-agent end-to-end against a real model.
// Isolated from the parent agent loop so it's fast. Has its own timeout.
//   bun scripts/smoke_subagent.mjs
import { create_agent } from "../dist/agent/core.mjs";
import { create_extension_api } from "../dist/agent/extensions.mjs";
import { create_settings_manager } from "../dist/agent/settings/manager.mjs";
import { tool_execute } from "../dist/agent/extensions/subagent/index.mjs";

const log = (...a) => console.log("[smoke]", ...a);

async function main() {
  const sm = create_settings_manager();
  // Minimal parent agent just to get an extension api with resolveModel.
  const agent = create_agent({ model: null, "system-prompt": "smoke", settings: sm });
  const api = create_extension_api(agent);

  // Drive the REAL tool path: tool_execute → all-agents (merges built-in
  // subagent roles) → run-isolated-agent (child create-agent + loop/run).
  log("invoking subagent tool: agent=scout (read-only, haiku)…");
  const out = await tool_execute(api, {
    agent: "scout",
    task: "Reply with exactly the word PONG and nothing else. Do not call any tools.",
  });
  log("OUTPUT:\n" + out);
  const found = !out.includes("Unknown subagent role");
  const ran = !out.includes("(FAILED)");
  log("RESULT:", found ? (ran ? "PASS (scout resolved + child returned summary)"
                              : "RAN (scout resolved; child failed — likely no API key/network)")
                       : "FAIL (scout role not found — merge broken)");
  process.exit(0);
}

const guard = setTimeout(() => { console.log("[smoke] TIMEOUT after 90s"); process.exit(2); }, 90000);
guard.unref?.();
main().catch((e) => { console.log("[smoke] ERROR:", e?.message || e); process.exit(1); });
