// Pure half of the benchmark harness: task discovery, subset selection, test
// output parsing, aggregation, and run-vs-run diffing. No spawning, no fs
// writes, no clock — everything here is a function of its arguments so the
// parts that claim to be deterministic can be tested as such.
//
// The impure half (copying task dirs, running the agent, running the tests)
// lives in run.mjs.

import * as fs from "node:fs";
import * as path from "node:path";

export const STATUS = {
  pass: "pass",
  fail: "fail",
  // Distinct from fail on purpose. A task we could not run (no toolchain) is
  // not a task the agent got wrong; folding the two together silently deflates
  // every score and hides an environment problem as a model problem.
  skip: "skip",
  timeout: "timeout",
  error: "error",
};

// Languages we can actually run. The polyglot set also ships cpp/go/java/rust;
// those are discovered but marked unrunnable rather than quietly dropped.
export const RUNNABLE_LANGS = ["python", "javascript", "rust"];

const LANG_SPEC = {
  python: {
    stub: (name) => `${snake(name)}.py`,
    test: (name) => `${snake(name)}_test.py`,
    // All 34 python exercises are stdlib unittest — zero imports of pytest — so
    // the suite runs against a bare interpreter with nothing to install.
    cmd: ["python3", "-m", "unittest", "discover", "-s", ".", "-p", "*_test.py"],
    probe: ["python3", "--version"],
    needsInstall: false,
  },
  javascript: {
    stub: (name) => `${dashed(name)}.js`,
    test: (name) => `${dashed(name)}.spec.js`,
    // npx runs the jest binary from the shared toolchain; every one of the 49
    // exercises declares an identical devDependency set, so one install serves
    // all of them instead of 49 separate node_modules trees.
    cmd: null,   // built lazily by langSpec: TOOLCHAIN is defined below
    probe: ["node", "--version"],
    needsInstall: true,
  },
  rust: {
    // Every exercise is a cargo crate: the stub is always src/lib.rs and the
    // suites live in tests/, which is why this is the first language needing
    // more than one test file per task (forth has 2, doubly-linked-list 3).
    stub: () => "src/lib.rs",
    test: (name) => `tests/${name}.rs`,
    testDir: "tests",
    cmd: ["cargo", "test"],
    probe: ["cargo", "--version"],
    needsInstall: false,
  },
};

// One shared install for all JavaScript exercises: bench/js-toolchain.
export const TOOLCHAIN = path.join(import.meta.dirname, "js-toolchain");
export const toolchainReady = () => fs.existsSync(path.join(TOOLCHAIN, "node_modules"));

const snake = (s) => s.replace(/-/g, "_");
const dashed = (s) => s;

export function langSpec(lang) {
  const spec = LANG_SPEC[lang];
  if (!spec) return null;
  // jest comes from the shared toolchain rather than a per-exercise install.
  if (lang === "javascript" && !spec.cmd) {
    spec.cmd = [path.join(TOOLCHAIN, "node_modules", ".bin", "jest"),
                "--silent", "--rootDir", "."];
  }
  return spec;
}

// ── discovery ─────────────────────────────────────────────────────────────

/**
 * Walk <tasksDir>/<lang>/exercises/practice/<name>/ and return one record per
 * exercise, sorted, so discovery order never depends on the filesystem.
 */
/**
 * Every file `cargo test` will compile and run. Hashing only one of them would
 * leave the others rewritable without detection.
 */
export function testFilesFor(dir, spec, testFile) {
  if (!spec?.testDir) return testFile ? [testFile] : [];
  const abs = path.join(dir, spec.testDir);
  if (!fs.existsSync(abs)) return [];
  return fs.readdirSync(abs).filter((f) => f.endsWith(".rs")).sort()
           .map((f) => path.join(spec.testDir, f));
}

export function discoverTasks(tasksDir, langs = RUNNABLE_LANGS) {
  const out = [];
  for (const lang of langs) {
    const root = path.join(tasksDir, lang, "exercises", "practice");
    if (!fs.existsSync(root)) continue;
    const spec = LANG_SPEC[lang];
    for (const name of fs.readdirSync(root).sort()) {
      const dir = path.join(root, name);
      if (!fs.statSync(dir).isDirectory()) continue;
      const testFile = spec ? spec.test(name) : null;
      out.push({
        id: `${lang}/${name}`,
        lang,
        name,
        dir,
        stub: spec ? spec.stub(name) : null,
        testFile,
        testFiles: testFilesFor(dir, spec, testFile),
        // A task whose test file is missing cannot be scored, ever. Recorded so
        // the runner can skip it explicitly instead of "failing" it.
        needsInstall: Boolean(spec?.needsInstall),
        runnable: Boolean(
          spec && testFile &&
          (spec.testDir
             ? testFilesFor(dir, spec, testFile).length > 0
             : fs.existsSync(path.join(dir, testFile))) &&
          (!spec.needsInstall || toolchainReady())),
      });
    }
  }
  return out.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** The instructions shown to the agent, or null when the exercise has none. */
export function readInstructions(taskDir) {
  for (const p of [path.join(taskDir, ".docs", "instructions.md"),
                   path.join(taskDir, ".docs", "introduction.md"),
                   path.join(taskDir, "README.md")]) {
    if (fs.existsSync(p)) return String(fs.readFileSync(p, "utf8"));
  }
  return null;
}

/**
 * Files that must not reach the agent's copy. `.meta/` carries the reference
 * solution — leaving it in means the benchmark grades the agent on its ability
 * to read the answer key.
 */
export const EXCLUDED_FROM_COPY = [".meta", ".approaches", ".articles"];

// ── selection ─────────────────────────────────────────────────────────────

// mulberry32: tiny, seeded, and stable across node versions — the point is
// reproducibility, not statistical quality.
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Deterministic subset: the same (tasks, seed, count) always yields the same
 * ids in the same order, so two runs of one commit compare like for like.
 * `only` (ids or "lang/*") wins over sampling.
 */
export function selectTasks(tasks, { seed = 0, count = null, only = null } = {}) {
  let pool = tasks.filter((t) => t.runnable);
  if (only && only.length) {
    const want = new Set(only);
    const langs = new Set(only.filter((o) => o.endsWith("/*")).map((o) => o.slice(0, -2)));
    pool = pool.filter((t) => want.has(t.id) || langs.has(t.lang));
    return pool;
  }
  if (count === null || count >= pool.length) return pool;
  const r = rng(seed);
  // Fisher-Yates over a copy, then take the head and restore id order.
  const shuffled = pool.slice();
  for (let i = shuffled.length - 1; i > 0; i--) {
    const j = Math.floor(r() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled.slice(0, count).sort((a, b) => (a.id < b.id ? -1 : 1));
}

// ── test output ───────────────────────────────────────────────────────────

/**
 * Map a finished test process to a status. Exit code is the primary signal;
 * the text is only used to tell "no tests ran" from "tests ran and passed",
 * because a suite that collected nothing is not a pass.
 */
export function classifyTestRun({ exitCode, stdout = "", stderr = "", timedOut = false }) {
  if (timedOut) return STATUS.timeout;
  const text = `${stdout}\n${stderr}`;
  if (/no tests ran|collected 0 items|Ran 0 tests|Error: Cannot find module|ModuleNotFoundError/i.test(text)) {
    return exitCode === 0 ? STATUS.error : STATUS.fail;
  }
  return exitCode === 0 ? STATUS.pass : STATUS.fail;
}

/**
 * Pure: did the agent itself fail, before its work could be graded? An
 * unsupported model, a missing key or a crashed run produces `is_error` with
 * zero turns — scoring that as `fail` blames the model for a run that never
 * happened, which is the same lie as scoring a missing toolchain.
 */
export function agentFailure(printJson) {
  if (!printJson || typeof printJson !== "object") return null;
  const errored = printJson.is_error === true || printJson.subtype === "error";
  if (!errored) return null;
  return String(printJson.result ?? printJson.error ?? "agent reported an error");
}

// ── aggregation ───────────────────────────────────────────────────────────

/**
 * Aggregate one trial. `pct` counts only tasks that were actually attempted —
 * skips are reported alongside, never folded into the denominator, so a missing
 * toolchain cannot masquerade as a low score.
 */
/** A run that ended without the agent ever emitting a usable tool call. */
export function isNoToolCall(r) {
  return r.status === STATUS.error &&
         /never modified the stub/.test(String(r.reason ?? ""));
}

export function aggregate(results) {
  const count = (s) => results.filter((r) => r.status === s).length;
  const passed = count(STATUS.pass);
  const skipped = count(STATUS.skip);
  const attempted = results.length - skipped;
  const noToolCall = results.filter(isNoToolCall).length;
  const infraError = count(STATUS.error) - noToolCall;
  const reached = attempted - infraError;
  const pc = (n) => (attempted === 0 ? null : round1((100 * n) / attempted));
  return {
    total: results.length,
    attempted,
    passed,
    failed: count(STATUS.fail),
    timeout: count(STATUS.timeout),
    error: count(STATUS.error),
    skipped,
    pct: pc(passed),
    // One number hides which half moved. Constrained/rescued tool calls trade
    // structural validity against semantic accuracy in opposite directions —
    // measured at -29.5 points of abstention accuracy against +17..+62 of tool
    // selection, pooling to +7.7 (arXiv 2608.13959), and 91.5% -> 48.0%
    // executable accuracy at unchanged 100% schema validity (arXiv 2605.26128).
    // Both papers' explicit instruction is to report these separately, so a
    // large loss and a large recovery cannot read as nothing.
    metrics: {
      // Did a usable tool call ever arrive, over the runs that actually
      // reached the model. Infrastructure errors — rate limits, 504s — are
      // excluded from this denominator: a request that never landed is
      // neither valid nor invalid output, and counting it as valid was the
      // first version of this metric.
      schemaValidity:  reached === 0 ? null
                       : round1((100 * (reached - noToolCall)) / reached),
      // it ran and the suite passed
      executableAccuracy: pc(passed),
      // well-formed work, wrong answer
      wrongValid:      pc(count(STATUS.fail)),
      // the agent never acted
      noToolCall:      pc(noToolCall),
    },
  };
}

const round1 = (n) => Math.round(n * 10) / 10;

/** mean ± half-range across trials. One trial reports no spread, not zero spread. */
export function summarizeTrials(trialPcts) {
  const xs = trialPcts.filter((n) => typeof n === "number");
  if (!xs.length) return { mean: null, spread: null, trials: 0 };
  const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
  return {
    mean: round1(mean),
    spread: xs.length < 2 ? null : round1((Math.max(...xs) - Math.min(...xs)) / 2),
    trials: xs.length,
  };
}

// ── diffing ───────────────────────────────────────────────────────────────

/** What changed between two result files. Both directions, plus appeared/vanished. */
export function diffRuns(before, after) {
  const byId = (run) => new Map((run.results ?? []).map((r) => [r.id, r.status]));
  const a = byId(before), b = byId(after);
  const improved = [], regressed = [], changed = [], added = [], removed = [];
  for (const [id, sb] of b) {
    if (!a.has(id)) { added.push(id); continue; }
    const sa = a.get(id);
    if (sa === sb) continue;
    if (sb === STATUS.pass) improved.push(id);
    else if (sa === STATUS.pass) regressed.push(id);
    else changed.push({ id, from: sa, to: sb });
  }
  for (const id of a.keys()) if (!b.has(id)) removed.push(id);
  return { improved, regressed, changed, added, removed };
}

// ── the agent under test ──────────────────────────────────────────────────

/**
 * Guard against benchmarking the wrong build. The bundled `./nyma` binary loads
 * 2 extensions; `bun dist/agent/cli.mjs` loads ~40. Both start, both answer, and
 * only one is the agent anyone means — a number from the other looks perfectly
 * plausible and means nothing.
 */
export function agentScriptPath(agentCmd) {
  const args = agentCmd.slice(1);
  const script = args.find((a) => a.endsWith(".mjs") || a.endsWith(".js"));
  return script ?? agentCmd[0];
}

/**
 * Pure: is this the dist entry point (which loads every built-in) or something
 * else? `./nyma` starts, answers, and loads 2 extensions — a benchmark of it
 * produces a plausible number for an agent nobody runs.
 */
export function isDistEntry(agentCmd) {
  return agentScriptPath(agentCmd).replace(/\\/g, "/").endsWith("dist/agent/cli.mjs");
}

export function checkAgentBuild(extensionCount, { min = 20 } = {}) {
  if (typeof extensionCount !== "number" || Number.isNaN(extensionCount)) {
    return { ok: false, reason: "could not determine how many extensions loaded" };
  }
  if (extensionCount < min) {
    return {
      ok: false,
      reason: `only ${extensionCount} extensions loaded (expected >= ${min}) — ` +
              `this looks like the bundled binary, not \`bun dist/agent/cli.mjs\``,
    };
  }
  return { ok: true, extensionCount };
}
