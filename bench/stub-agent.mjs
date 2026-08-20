// A fake agent for proving the harness works without spending a token.
//
// It ignores the prompt and does one of three things to the stub file,
// selected by NYMA_BENCH_STUB:
//
//   solve  — write the reference solution from the task's .meta/ (harness must score 100%)
//   break  — write something that cannot pass                    (harness must score 0%)
//   cheat  — overwrite the test file so everything "passes"      (harness must catch it)
//
// The .meta/ directory is deliberately NOT copied into the agent's working dir,
// so `solve` reads it from the original task path passed in NYMA_BENCH_META.

import * as fs from "node:fs";
import * as path from "node:path";

const mode = process.env.NYMA_BENCH_STUB ?? "solve";
const stub = process.env.NYMA_BENCH_STUB_FILE;
const meta = process.env.NYMA_BENCH_META;
const testFile = process.env.NYMA_BENCH_TEST_FILE;

if (!stub) {
  console.error("stub-agent: NYMA_BENCH_STUB_FILE not set");
  process.exit(1);
}

if (mode === "solve") {
  // exercism keeps the reference solution as .meta/example.py (python) or
  // .meta/proof.ci.js (javascript).
  const candidates = ["example.py", "proof.ci.js", "example.js", "example.rs"];
  let written = false;
  for (const c of candidates) {
    const p = meta ? path.join(meta, c) : null;
    if (p && fs.existsSync(p)) {
      fs.writeFileSync(stub, fs.readFileSync(p, "utf8"));
      written = true;
      break;
    }
  }
  if (!written) {
    console.error(`stub-agent: no reference solution in ${meta}`);
    process.exit(1);
  }
  // 7 of the 30 Rust exercises ship .meta/Cargo-example.toml: the reference
  // solution pulls dependencies the student stub's manifest does not declare,
  // so example.rs alone does not compile. Without this the solve guard reports
  // `fail` on those tasks and looks like a grading bug rather than a stub that
  // was handed half an answer.
  const exampleManifest = meta ? path.join(meta, "Cargo-example.toml") : null;
  if (exampleManifest && fs.existsSync(exampleManifest)) {
    fs.writeFileSync(path.join(path.dirname(path.dirname(stub)), "Cargo.toml"),
                     fs.readFileSync(exampleManifest, "utf8"));
  }
} else if (mode === "break") {
  // Must fail the suite in every language: a python raise is a syntax error to
  // rustc, which is still a failing test run, but say so explicitly.
  const broken = stub.endsWith(".rs")
    ? "// stub-agent: deliberately unsolved\ncompile_error!(\"stub-agent\");\n"
    : "raise NotImplementedError('stub-agent: deliberately unsolved')\n";
  fs.writeFileSync(stub, broken);
} else if (mode === "cheat") {
  if (!testFile) { console.error("stub-agent: NYMA_BENCH_TEST_FILE not set"); process.exit(1); }
  const faked = testFile.endsWith(".rs")
    ? "#[test]\nfn nothing() { assert!(true); }\n"
    : "def test_nothing():\n    assert True\n";
  fs.writeFileSync(testFile, faked);
}

// print mode's JSON result shape, so parseUsage has something realistic to read
console.log(JSON.stringify({
  type: "result", subtype: "success", is_error: false,
  num_turns: 1, total_cost_usd: 0, usage: { input_tokens: 0, output_tokens: 0 },
  result: `stub-agent: ${mode}`,
}));
