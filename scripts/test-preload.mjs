// Loaded before every test file (see bunfig.toml [test] preload).
//
// 2. No live network. Several tests load every built-in extension;
//    custom-provider-relay discovers its model list over the network and reads
//    the key from the ambient environment, so a plain `bun test` on a machine
//    that exports a gateway key would call a third party. Tests that exercise
//    discovery stub `fetch` and opt back in explicitly.
process.env.NYMA_NO_MODEL_DISCOVERY ??= "1";

//    Same for herdr: run inside a herdr pane, agent_state found HERDR_BIN_PATH
//    and HERDR_PANE_ID in the ambient env and every full-load test reported
//    idle/release into the developer's REAL pane — and under `--parallel`
//    load those calls stalled four loader_smoke tests past their timeout.
//    ext_agent_state sets the variables it needs itself.
for (const k of Object.keys(process.env)) if (k.startsWith("HERDR_")) delete process.env[k];

// 1. process.exit inside a test is a silent green: bun stops mid-run, prints
//    no summary, and returns 0. (rpc-mode's stdin-EOF exit did exactly that.)
//    Make it loud. A test that needs to observe an exit stubs process.exit
//    itself, which still works because this only replaces the default.
process.exit = (code) => {
  throw new Error(`process.exit(${code ?? 0}) called inside a test`);
};

// 3. HOME is a scratch directory. credentials.json, debug.log, the extension
//    cache and the sessions dir all resolve under ~/.nyma at call time; with the
//    real HOME a test run read the developer's credentials, appended to their
//    debug log and could list their sessions. Tests that set HOME themselves
//    still save/restore — they restore to this scratch dir, not the real one.
//
// 5. Temp files go under one per-process run root. Every test mkdtemp'd under
//    the real $TMPDIR and almost none cleaned up: 73k entries had piled up (18k
//    nyma-relay-*, 17k of this preload's own scratch HOMEs), enough that one
//    `find $TMPDIR` took minutes. Bun's os.tmpdir() re-reads TMPDIR at call
//    time, so pointing it here catches every test, and CLIs spawned with
//    process.env inherit it.
//
//    Cleanup cannot ride process "exit" — bun test fires neither exit nor
//    beforeExit (checked). So: after each FILE the root is emptied except HOME,
//    and the root is named by pid, so the next run removes any root whose
//    process is gone. The name is exactly `nyma-testrun-<pid>`, found on disk
//    rather than remembered: --isolate gives each file a fresh globalThis AND
//    a fresh process.env, so any in-memory guard made one root per file. A
//    predictable name in a shared /tmp could be pre-planted, so it is used only
//    if lstat says it is a real directory we own; anything else gets a random
//    mkdtemp root instead.
import { existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
const TMP_BASE = (process.env.NYMA_TEST_TMP_BASE ??= tmpdir());
const pidAlive = (pid) => { try { process.kill(pid, 0); return true; } catch (e) { return e.code === "EPERM"; } };
let RUN_TMP = join(TMP_BASE, `nyma-testrun-${process.pid}`);
// Sweep once per process — the first file to find no root of its own.
// Scanning $TMPDIR before every file cost 15 s a run on a cluttered one.
if (!existsSync(RUN_TMP)) {
  for (const n of readdirSync(TMP_BASE)) {
    const m = /^nyma-testrun-(\d+)(-|$)/.exec(n);
    if (m && Number(m[1]) !== process.pid && !pidAlive(Number(m[1])))
      try { rmSync(join(TMP_BASE, n), { recursive: true, force: true }); } catch {}
  }
}
try {
  mkdirSync(RUN_TMP, { recursive: true, mode: 0o700 });
  const st = lstatSync(RUN_TMP);
  if (!st.isDirectory() || st.isSymbolicLink() || st.uid !== process.getuid()) throw new Error("not ours");
} catch {
  RUN_TMP = mkdtempSync(join(TMP_BASE, `nyma-testrun-${process.pid}-`));
}
process.env.TMPDIR = RUN_TMP;
process.env.HOME = mkdtempSync(join(RUN_TMP, "nyma-test-home-"));
const emptyRunTmp = () => {
  const keep = basename(process.env.HOME);
  for (const n of readdirSync(RUN_TMP))
    if (n !== keep) try { rmSync(join(RUN_TMP, n), { recursive: true, force: true }); } catch {}
};

// 4. Module-global registries are restored after every test FILE. bun runs all
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
  const theme = await import("../dist/agent/ui/theme_catalog.mjs");
  restores.push(() => theme.reset_theme_state_BANG_());
} catch {
  // dist not built yet (bare `bun test` before a compile) — the freshness test
  // will say so; nothing to restore.
}
afterAll(() => {
  for (const r of restores) { try { r(); } catch {} }
  try { emptyRunTmp(); } catch {}
});
