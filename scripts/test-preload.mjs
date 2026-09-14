// Loaded before every test file (see bunfig.toml [test] preload).
//
// 1. No live network. Several tests load every built-in extension;
//    custom-provider-relay discovers its model list over the network and reads
//    the key from the ambient environment, so a plain `bun test` on a machine
//    that exports a gateway key would call a third party. Tests that exercise
//    discovery stub `fetch` and opt back in explicitly.
process.env.NYMA_NO_MODEL_DISCOVERY ??= "1";

// 2. HOME is a scratch directory. credentials.json, debug.log, the extension
//    cache and the sessions dir all resolve under ~/.nyma at call time; with the
//    real HOME a test run read the developer's credentials, appended to their
//    debug log and could list their sessions. Tests that set HOME themselves
//    still save/restore — they restore to this scratch dir, not the real one.
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
process.env.HOME = mkdtempSync(join(tmpdir(), "nyma-test-home-"));

// 3. Module-global registries are restored after every test FILE. bun runs all
//    files in one process with a shared module cache, so an extension registered
//    in file N was still priced, segmented and metadata'd in file N+1 — which is
//    how a residue test passed in the full run and failed alone, and vice versa.
//    Per-file, not per-test: a describe may legitimately register once and read
//    across several `it`s. cwd is NOT changed here — five lints resolve paths
//    from the repo root.
import { afterAll } from "bun:test";
const restores = [];
try {
  const core = await import("squint-cljs/core.js");
  const pricing = await import("../dist/agent/pricing.mjs");
  for (const a of [pricing.token_costs, pricing.unpriced_providers]) {
    const seed = core.deref(a);
    restores.push(() => core.reset_BANG_(a, seed));
  }
  const seg = await import("../dist/agent/ui/status_line_segments.mjs");
  restores.push(() => seg.reset_registry_BANG_());
  const meta = await import("../dist/agent/tool_metadata.mjs");
  restores.push(() => meta.reset_extension_metadata_BANG_());
} catch {
  // dist not built yet (bare `bun test` before a compile) — the freshness test
  // will say so; nothing to restore.
}
afterAll(() => { for (const r of restores) { try { r(); } catch {} } });
