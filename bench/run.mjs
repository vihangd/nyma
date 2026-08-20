// Impure half of the benchmark harness: copy a task, run the agent on it, run
// the exercise's own tests, record what happened. Every decision worth testing
// lives in scoring.mjs; this file spawns processes and writes files.
//
//   bun bench/run.mjs --count 20 --seed 7 --label baseline
//   bun bench/run.mjs --only python/wordy --agent-cmd bench/stub-agent.mjs
//   bun bench/run.mjs --diff bench/results/latest.json bench/results/<other>.json
//
// The agent is run headless in a temp copy of the exercise. Nothing touches the
// task checkout, and no session is written.

import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
import {
  STATUS, discoverTasks, selectTasks, readInstructions, langSpec,
  classifyTestRun, aggregate, summarizeTrials, diffRuns, checkAgentBuild, agentFailure,
  agentScriptPath, isDistEntry, EXCLUDED_FROM_COPY, TOOLCHAIN, toolchainReady,
} from "./scoring.mjs";

const ROOT = path.resolve(import.meta.dirname, "..");

// cargo and rustc must be on PATH together — a rustup shim alone is not enough,
// and this machine's ~/.cargo/bin shims point at a rustup that has moved, so
// `cargo test` dies with "could not execute process `rustc -vV`". Resolve the
// real toolchain bin once and prepend it, for the test run and the agent alike.
function ensureRustOnPath() {
  const { execFileSync } = require("node:child_process");
  const has = (bin) => { try { execFileSync(bin, ["--version"], { stdio: "ignore" }); return true; } catch { return false; } };
  if (has("cargo") && has("rustc")) return;
  try {
    const cargoPath = execFileSync("rustup", ["which", "cargo"], { encoding: "utf8" }).trim();
    if (cargoPath) process.env.PATH = `${path.dirname(cargoPath)}:${process.env.PATH}`;
  } catch { /* no rustup: rust tasks will report skip via the probe */ }
}
ensureRustOnPath();

// One shared target dir instead of 30: every crate rebuilds the same handful of
// dependencies otherwise, which is minutes of compile per task rather than one.
process.env.CARGO_TARGET_DIR ||= path.join(ROOT, "bench", "rust-target");
const DEFAULT_AGENT = ["bun", path.join(ROOT, "dist", "agent", "cli.mjs")];

// ── args ──────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const a = { count: 20, seed: 7, label: "run", trials: 1, timeoutMs: 300000,
              maxSteps: 40, only: null, model: null, agentCmd: null, diff: null,
              tasksDir: path.join(ROOT, "bench", "tasks"), keep: false,
              agentSettings: null, noBuiltinExt: false, envVars: {}, concurrency: 1 };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i], next = () => argv[++i];
    if (k === "--count") a.count = Number(next());
    else if (k === "--seed") a.seed = Number(next());
    else if (k === "--label") a.label = next();
    else if (k === "--trials") a.trials = Number(next());
    else if (k === "--timeout-ms") a.timeoutMs = Number(next());
    else if (k === "--max-steps") a.maxSteps = Number(next());
    else if (k === "--only") a.only = next().split(",");
    else if (k === "--model") a.model = next();
    else if (k === "--agent-cmd") a.agentCmd = next().split(" ");
    else if (k === "--tasks-dir") a.tasksDir = next();
    else if (k === "--diff") a.diff = [next(), next()];
    else if (k === "--all") a.count = null;
    else if (k === "--keep") a.keep = true;
    else if (k === "--agent-settings") a.agentSettings = next();
    else if (k === "--concurrency") a.concurrency = Math.max(1, Number(next()));
    // Deliberately strip the agent for an ablation. Recorded in the result so a
    // number from a 2-extension agent can never be read as a normal run.
    else if (k === "--no-builtin-ext") a.noBuiltinExt = true;
    else if (k === "--env") { const [ek, ...rest] = next().split("="); a.envVars[ek] = rest.join("="); }
    else if (k === "--help" || k === "-h") a.help = true;
  }
  return a;
}

const HELP = `bench/run.mjs — Aider-Polyglot subset runner

  --count N        tasks to sample (default 20; --all for every runnable task)
  --seed N         sampling seed; same seed always picks the same tasks
  --only IDS       comma-separated task ids ("python/wordy") or "python/*"
  --trials N       repeat the whole set N times and report mean +/- spread
  --model SPEC     role name (resolved from settings) or provider/model
  --label NAME     goes in the result filename
  --timeout-ms N   wall-clock cap per task (default 300000)
  --agent-cmd CMD  override the agent (used by the stub-agent dry run)
  --keep           keep each task's working dir for inspection
  --no-builtin-ext ABLATION: run with NYMA_NO_BUILTIN_EXT=1 (12 native tools,
                   no extension prompt injectors). Recorded in the result file.
  --env K=V        extra env var for the agent process (repeatable)
  --agent-settings F  JSON written to <task>/.nyma/settings.json for the run,
                   so a config can be A/B'd without touching your own settings
  --diff A B       print what changed between two result files and exit
`;

// ── process helpers ───────────────────────────────────────────────────────

function run(cmd, args, { cwd, timeoutMs, input, env }) {
  return new Promise((resolve) => {
    // detached: the agent spawns bash, which spawns test runners. Killing only
    // the direct child leaves those grandchildren holding the stdout pipe, so
    // 'close' never fires and a task with a 420s cap was measured at 1992s.
    // Own process group => one kill reaches the whole tree.
    const child = spawn(cmd, args, { cwd, env, stdio: ["pipe", "pipe", "pipe"], detached: true });
    let stdout = "", stderr = "", timedOut = false;
    // SIGKILL discards everything the agent was about to report — a timed-out
    // task recorded no tokens, no cost and no hint of what it was doing. Ask
    // first, insist after: SIGTERM lets print mode flush its result object,
    // SIGKILL follows if it does not.
    let hardTimer = null;
    const timer = timeoutMs
      ? setTimeout(() => {
          timedOut = true;
          killTree(child, "SIGTERM");
          hardTimer = setTimeout(() => {
            killTree(child, "SIGKILL");
            // Resolve on our own schedule. 'close' waits for every inherited
            // stdio handle, and a grandchild that escapes the process group can
            // hold it open for minutes — tasks with a 420s cap were measured at
            // 1119s and 1992s. The status is already decided; waiting only
            // corrupts the duration and stalls the queue.
            setTimeout(() => resolve({ exitCode: null, stdout, stderr, timedOut: true }), 2000);
          }, 5000);
        }, timeoutMs)
      : null;
    child.stdout.on("data", (d) => { stdout += d; });
    child.stderr.on("data", (d) => { stderr += d; });
    child.on("close", (code) => {
      if (timer) clearTimeout(timer);
      if (hardTimer) clearTimeout(hardTimer);
      resolve({ exitCode: code, stdout, stderr, timedOut });
    });
    child.on("error", (e) => {
      if (timer) clearTimeout(timer);
      resolve({ exitCode: -1, stdout, stderr: String(e.message), timedOut });
    });
    if (input !== undefined) { child.stdin.write(input); }
    child.stdin.end();
  });
}

/** Signal the whole process group; fall back to the child if it already died. */
function killTree(child, signal) {
  try { process.kill(-child.pid, signal); }
  catch { try { child.kill(signal); } catch { /* already gone */ } }
}

function readIfExists(p) {
  try { return fs.readFileSync(p, "utf8"); } catch { return null; }
}

function copyTask(task, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const entry of fs.readdirSync(task.dir)) {
    // .meta holds the reference solution — copying it would benchmark the
    // agent's ability to find the answer key.
    if (EXCLUDED_FROM_COPY.includes(entry)) continue;
    fs.cpSync(path.join(task.dir, entry), path.join(dest, entry), { recursive: true });
  }
}

// ── the agent under test ──────────────────────────────────────────────────

const PROMPT = (task, instructions) => `Solve this exercise.

Edit ONLY ${task.stub}. Do not modify or delete ${task.testFile} — it is how your
work is graded. Do not create new files. When the tests pass, stop.

Run the tests with: ${langSpec(task.lang).cmd.join(" ")}

${instructions ?? "(no instructions file; read the test file to infer the contract)"}`;

/**
 * `--model fast` is a ROLE; the CLI only understands provider/model. Resolve it
 * from settings here, and fail loudly if it cannot be resolved — falling back
 * to the default model would attribute a score to the wrong model.
 */
function resolveModelSpec(model) {
  if (!model) return { ok: true, spec: null, source: "agent default" };
  if (model.includes("/")) return { ok: true, spec: model, source: "explicit" };
  for (const [file, label] of [[path.join(ROOT, ".nyma", "settings.json"), "project settings"],
                               [path.join(os.homedir(), ".nyma", "settings.json"), "global settings"]]) {
    if (!fs.existsSync(file)) continue;
    let json;
    try { json = JSON.parse(fs.readFileSync(file, "utf8")); } catch { continue; }
    const role = json?.roles?.[model];
    if (role?.provider && role?.model) {
      return { ok: true, spec: `${role.provider}/${role.model}`, source: `${label} roles.${model}` };
    }
  }
  return { ok: false, reason: `role "${model}" not found in settings roles` };
}

/**
 * Refuse to benchmark the wrong build. There is no runtime flag that reports the
 * loaded-extension count, so this checks the thing that actually differs: the
 * bundled `./nyma` resolves its extensions dir inside the bundle and loads 2,
 * while `dist/agent/cli.mjs` loads every directory in dist/agent/extensions.
 */
function verifyAgent(agentCmd) {
  if (!isDistEntry(agentCmd)) {
    return { ok: false, reason:
      `agent is ${agentScriptPath(agentCmd)}, not dist/agent/cli.mjs — the bundled ` +
      `binary loads 2 extensions and would score a different agent` };
  }
  const extDir = path.join(ROOT, "dist", "agent", "extensions");
  const count = fs.existsSync(extDir)
    ? fs.readdirSync(extDir, { withFileTypes: true }).filter((e) => e.isDirectory()).length
    : NaN;
  if (process.env.NYMA_NO_BUILTIN_EXT) {
    return { ok: false, reason: "NYMA_NO_BUILTIN_EXT is set — built-ins would not load" };
  }
  return checkAgentBuild(count);
}

async function runTask(task, opts) {
  const started = Date.now();
  const spec = langSpec(task.lang);
  if (!task.runnable) {
    return { id: task.id, status: STATUS.skip,
             reason: task.needsInstall ? "needs npm install" : "no runnable test file",
             durationMs: 0 };
  }

  const work = fs.mkdtempSync(path.join(os.tmpdir(), `nyma-bench-${task.name}-`));
  try {
    copyTask(task, work);
    // Babel resolves presets relative to the config file, i.e. the working copy,
    // which has no node_modules of its own. One symlink to the shared toolchain
    // makes jest and @exercism/babel-preset-javascript resolvable without 49
    // separate installs.
    if (task.needsInstall && toolchainReady()) {
      try {
        fs.symlinkSync(path.join(TOOLCHAIN, "node_modules"),
                       path.join(work, "node_modules"), "dir");
      } catch { /* already there */ }
    }
    // Project settings resolve from the agent's CWD, which is this temp copy —
    // the repo's own .nyma/settings.json never applies to a bench run. Writing
    // them here is what makes per-run config (backend routing, extension
    // toggles) possible without editing the user's global settings.
    if (opts.agentSettings) {
      fs.mkdirSync(path.join(work, ".nyma"), { recursive: true });
      fs.copyFileSync(opts.agentSettings, path.join(work, ".nyma", "settings.json"));
    }
    // Exercism's JS track ships every case after the first as `xtest`, for a
    // student to unskip as they go. Graded as delivered, a solution that
    // satisfies one assertion passes a suite of thirty — javascript/say scored
    // a pass on 1 of 16. Activate them before hashing, so the integrity guard
    // covers the file the run is actually graded against.
    // Rust ships 620 of its 650 cases behind #[ignore] for the same reason —
    // 95% dormant, which would have inflated the Rust column exactly as xtest
    // inflated JavaScript.
    const testFiles = task.testFiles?.length ? task.testFiles : [task.testFile];
    for (const rel of testFiles) {
      const abs = path.join(work, rel);
      const src = fs.readFileSync(abs, "utf8");
      const activated = task.lang === "javascript"
        ? src.replace(/\bxtest\(/g, "test(").replace(/\bxit\(/g, "it(")
        : task.lang === "rust"
          ? src.replace(/#\[ignore(\s*=\s*"[^"]*")?\]\s*\n\s*/g, "")
          : src;
      if (activated !== src) fs.writeFileSync(abs, activated);
    }
    // Hash every file cargo/jest will run: hashing one of three would leave the
    // other two rewritable without detection.
    const readTests = () =>
      testFiles.map((rel) => fs.readFileSync(path.join(work, rel), "utf8")).join("\u0000");
    const before = readTests();

    const [cmd, ...base] = opts.agentCmd;
    // No --max-steps flag exists (it is settings-only, default 100), so the
    // wall-clock timeout is the bound. Recorded in the result file as such.
    const agentArgs = [...base, "-p", "--output-format", "json", "--no-session",
                       "--permission-mode", "full-auto"];
    if (opts.modelSpec) agentArgs.push("--model", opts.modelSpec);
    agentArgs.push(PROMPT(task, readInstructions(task.dir)));

    // The stub agent needs to be told which files it may touch and where the
    // reference solution is. A REAL agent must never be handed either: an env
    // var naming the answer-key directory is a leak, whether or not a given
    // model happens to look at it.
    const agentEnv = opts.isStub
      ? { ...process.env, ...opts.envVars,
          NYMA_BENCH_STUB_FILE: path.join(work, task.stub),
          NYMA_BENCH_TEST_FILE: path.join(work, task.testFile),
          NYMA_BENCH_META: path.join(task.dir, ".meta") }
      : { ...process.env, ...opts.envVars };
    // One cargo target dir per lane, not one global: concurrent `cargo test`
    // runs against a shared target block on cargo's file lock, which would
    // serialise the lanes while still paying the wall clock for them.
    if (task.lang === "rust" && opts.lane !== undefined) {
      agentEnv.CARGO_TARGET_DIR = `${process.env.CARGO_TARGET_DIR}-${opts.lane}`;
    }

    const stubPath = path.join(work, task.stub);
    const stubBefore = fs.readFileSync(stubPath, "utf8");

    const agent = await run(cmd, agentArgs, {
      cwd: work, timeoutMs: opts.timeoutMs, env: agentEnv,
    });
    if (agent.timedOut) {
      // A timeout is not one failure mode. "Never touched the file" and "wrote a
      // solution but kept going" want opposite fixes, and the old record could
      // not tell them apart.
      const stubAfter = readIfExists(stubPath);
      const edited = stubAfter !== null && stubAfter !== stubBefore;
      const partial = parseUsage(agent.stdout);
      // Grade what it left behind. Every 9B timeout had written a real solution
      // (2-7 KB) before running out of clock, so the interesting question is
      // whether the work was already finished. This does NOT change the score —
      // a task that could not stop did not succeed — but it separates "needs a
      // stop condition" from "needs more capability", which are different fixes.
      // Grade the leftovers with the same integrity guard the normal path uses:
      // a suite that passes because the agent rewrote the suite is not evidence
      // the work was finished.
      const tamperedTest = readTests() !== before;
      let wouldHavePassed = null;
      if (edited && spec && !tamperedTest) {
        const late = await run(spec.cmd[0], spec.cmd.slice(1), { cwd: work, timeoutMs: 120000, env: agentEnv });
        wouldHavePassed = classifyTestRun(late) === STATUS.pass;
      }
      // A host that sleeps mid-run suspends the timer while the wall clock keeps
      // going: a closed lid once turned a 600s cap into a 3272s "timeout" that
      // read as a model being slow. Real elapsed far beyond the cap means the
      // measurement is about the machine, not the agent.
      const elapsed = Date.now() - started;
      const suspectClock = elapsed > opts.timeoutMs * 1.5 ? Math.round(elapsed / 1000) : null;
      return { id: task.id, status: STATUS.timeout,
               durationMs: elapsed, suspectClock,
               editedStub: edited,
               stubBytes: stubAfter === null ? null : stubAfter.length,
               wouldHavePassed, tamperedTest,
               ...partial };
    }

    // An agent that never ran is not an agent that got it wrong.
    const printed = parsePrintJson(agent.stdout);
    const failure = agentFailure(printed);
    if (failure) {
      return { id: task.id, status: STATUS.error, reason: `agent: ${failure}`,
               durationMs: Date.now() - started };
    }

    // A model that answers without ever calling a tool leaves the skeleton
    // untouched, and the skeleton throws — which graded as `fail`, the same
    // status as a real wrong answer. An unpinned OpenRouter backend that drops
    // `tools` scored exactly this: one turn, 86 output tokens, `fail` in 9s.
    // Never having attempted the task is not the same as getting it wrong.
    const stubNow = readIfExists(stubPath);
    if (stubNow !== null && stubNow === stubBefore) {
      return { id: task.id, status: STATUS.error,
               reason: "agent never modified the stub (no tool call?)",
               durationMs: Date.now() - started, editedStub: false,
               ...parseUsage(agent.stdout) };
    }

    // Tampering with the test file is not a pass, whatever the suite says.
    const after = readTests();
    if (after !== before) {
      return { id: task.id, status: STATUS.error, reason: "agent modified the test file",
               durationMs: Date.now() - started };
    }

    const test = await run(spec.cmd[0], spec.cmd.slice(1), { cwd: work, timeoutMs: 120000, env: agentEnv });
    const usage = parseUsage(agent.stdout);
    return {
      id: task.id,
      status: classifyTestRun(test),
      durationMs: Date.now() - started,
      ...usage,
    };
  } catch (e) {
    return { id: task.id, status: STATUS.error, reason: String(e.message),
             durationMs: Date.now() - started };
  } finally {
    if (opts.keep) console.log(`      kept ${work}`);
    else fs.rmSync(work, { recursive: true, force: true });
  }
}

/**
 * print mode's JSON result object, or null. The line is not pure JSON: a
 * terminal notify escape (OSC 777) can share it, so slice between the outermost
 * braces rather than requiring the line to start with one. Getting this wrong
 * silently nulls every usage figure in the results file.
 */
function parsePrintJson(stdout) {
  for (const line of stdout.replace(/\u0000/g, "").split("\n").reverse()) {
    const a = line.indexOf("{"), b = line.lastIndexOf("}");
    if (a < 0 || b <= a) continue;
    try { return JSON.parse(line.slice(a, b + 1)); } catch { /* keep looking */ }
  }
  return null;
}

/** Pull turns/tokens/cost out of print mode's JSON result object, if present. */
function parseUsage(stdout) {
  try {
    const j = parsePrintJson(stdout);
    if (!j) throw new Error("no json");
    return {
      // print mode reports no turn count today — null, not a guess.
      turns: j.num_turns ?? j.turns ?? null,
      costUsd: j.total_cost_usd ?? j.cost_usd ?? null,
      tokens: j.usage
        ? (j.usage.total_tokens
           ?? (j.usage.input_tokens ?? 0) + (j.usage.output_tokens ?? 0))
        : null,
      // Cache hit rate is the largest cost lever on an agent loop, and until
      // print mode stopped hardcoding zero there was no way to see it.
      cacheRead: j.usage?.cache_read_input_tokens ?? null,
      cacheWrite: j.usage?.cache_creation_input_tokens ?? null,
      inputTokens: j.usage?.input_tokens ?? null,
    };
  } catch { return { turns: null, costUsd: null, tokens: null }; }
}

// ── main ──────────────────────────────────────────────────────────────────

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) { console.log(HELP); return; }

  if (opts.diff) {
    const [a, b] = opts.diff.map((p) => JSON.parse(fs.readFileSync(p, "utf8")));
    const d = diffRuns(a, b);
    console.log(`improved (${d.improved.length}): ${d.improved.join(", ") || "-"}`);
    console.log(`regressed (${d.regressed.length}): ${d.regressed.join(", ") || "-"}`);
    for (const c of d.changed) console.log(`changed: ${c.id} ${c.from} -> ${c.to}`);
    if (d.added.length) console.log(`added: ${d.added.join(", ")}`);
    if (d.removed.length) console.log(`removed: ${d.removed.join(", ")}`);
    return;
  }

  if (!fs.existsSync(opts.tasksDir)) {
    console.error(`No tasks at ${opts.tasksDir}.\n` +
      `  git clone --depth 1 https://github.com/Aider-AI/polyglot-benchmark bench/tasks`);
    process.exit(2);
  }

  const agentCmd = opts.agentCmd ?? DEFAULT_AGENT;
  opts.agentCmd = agentCmd;
  const isStub = Boolean(opts.agentCmd && opts.agentCmd.join(" ").includes("stub-agent"));
  opts.isStub = isStub;

  let build = { ok: true, extensionCount: null, stub: isStub };
  if (opts.noBuiltinExt) {
    opts.envVars.NYMA_NO_BUILTIN_EXT = "1";
    build = { ok: true, extensionCount: 0, ablation: "no-builtin-ext" };
    console.log("ABLATION: built-in extensions disabled — not a normal run");
  } else if (!isStub) {
    build = verifyAgent(agentCmd);
    if (!build.ok) {
      console.error(`Refusing to benchmark: ${build.reason}`);
      process.exit(3);
    }
  }

  const resolved = resolveModelSpec(opts.model);
  if (!resolved.ok) { console.error(`Refusing to benchmark: ${resolved.reason}`); process.exit(4); }
  opts.modelSpec = resolved.spec;

  const all = discoverTasks(opts.tasksDir);
  const tasks = selectTasks(all, { seed: opts.seed, count: opts.count, only: opts.only });
  if (!tasks.length) { console.error("No runnable tasks selected."); process.exit(2); }

  const skipped = all.filter((t) => !t.runnable);
  if (skipped.length) {
    console.log(`note: ${skipped.length} task(s) not runnable here ` +
                `(${[...new Set(skipped.map((t) => t.lang))].join(", ")}) — skipped, not scored`);
  }
  console.log(`${tasks.length} tasks, ${opts.trials} trial(s), agent: ${agentCmd.join(" ")}` +
              (build.extensionCount ? ` (${build.extensionCount} extensions)` : ""));

  const trials = [];
  for (let t = 0; t < opts.trials; t++) {
    // Against a remote provider a task is almost entirely waiting on the
    // network, so running them one at a time leaves the machine idle: 113 tasks
    // at ~153s each is over four hours of mostly-blocked wall clock. Workers
    // pull from a shared queue rather than running in fixed batches, so one
    // 600s timeout cannot stall the other lanes behind it.
    const results = [];
    const queue = [...tasks];
    const lanes = Math.max(1, Math.min(opts.concurrency, tasks.length));
    const worker = async (lane) => {
      for (;;) {
        const task = queue.shift();
        if (!task) return;
        const r = await runTask(task, { ...opts, lane });
        results.push(r);
        const agg = aggregate(results);
        console.log(`  [${results.length}/${tasks.length}] ${r.status.padEnd(7)} ${r.id}` +
                    ` (${Math.round(r.durationMs / 1000)}s, running ${agg.pct ?? "-"}%)`);
      }
    };
    await Promise.all(Array.from({ length: lanes }, (_, i) => worker(i)));
    // Deterministic order regardless of which lane finished first, so two runs
    // of the same task list diff cleanly.
    results.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    trials.push({ results, aggregate: aggregate(results) });
  }

  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const gitSha = (await run("git", ["rev-parse", "--short", "HEAD"], { cwd: ROOT, timeoutMs: 5000 }))
    .stdout.trim();
  const record = {
    // Bump whenever grading semantics change, so two result files can never be
    // merged as if one number. 2: JS xtest/xit activated before hashing; an
    // agent that never modified the stub grades error, not fail; a timeout past
    // 1.5x its cap records suspectClock.
    harness: 2,
    graderNotes: "js suites activated; no-tool-call => error; suspectClock on over-cap timeouts",
    label: opts.label,
    gitSha,
    agent: agentCmd.join(" "),
    extensionCount: build.extensionCount ?? null,
    ablation: build.ablation ?? null,
    envVars: Object.keys(opts.envVars).length ? opts.envVars : null,
    role: opts.model ?? null,
    model: opts.modelSpec ?? "(agent default)",
    modelSource: resolved.source,
    maxSteps: "agent default (no CLI flag); bounded by timeoutMs",
    agentSettings: opts.agentSettings
      ? JSON.parse(fs.readFileSync(opts.agentSettings, "utf8")) : null,
    timeoutMs: opts.timeoutMs,
    concurrency: opts.concurrency,
    seed: opts.seed,
    taskIds: tasks.map((t) => t.id),
    // Stated in every file so nobody reads it as a published-number comparison:
    // this is a subset, scored against itself over time.
    comparability: "python-only subset of Aider Polyglot; NOT comparable to " +
                   "published full-set (225 task, 6 language) scores",
    trials: trials.map((t) => t.aggregate),
    summary: summarizeTrials(trials.map((t) => t.aggregate.pct)),
    results: trials[0].results,
    allTrialResults: opts.trials > 1 ? trials.map((t) => t.results) : undefined,
  };

  const outDir = path.join(ROOT, "bench", "results");
  fs.mkdirSync(outDir, { recursive: true });
  const out = path.join(outDir, `${stamp}-${opts.label}.json`);
  fs.writeFileSync(out, JSON.stringify(record, null, 2));
  fs.writeFileSync(path.join(outDir, "latest.json"), JSON.stringify(record, null, 2));

  const s = record.summary, a = trials[0].aggregate;
  // "80%" reads as "the model got two wrong". Here it was 0 wrong and 2 out of
  // wall clock — a different problem with a different fix, so the headline says
  // which.
  console.log(`\n${s.mean}%${s.spread === null ? "" : ` ± ${s.spread}`} ` +
              `over ${s.trials} trial(s) — ` +
              `${a.passed} pass, ${a.failed} fail, ${a.timeout} timeout, ` +
              `${a.error} error, ${a.skipped} skipped`);
  if (s.trials === 1) {
    console.log("single trial: no spread measured. Use --trials 3 before " +
                "comparing this against another run.");
  }
  console.log(out);
}

main();
