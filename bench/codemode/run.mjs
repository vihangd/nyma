// Before/after for code mode on one task. Runs nyma -p twice per model in a fresh
// project/HOME — codemode off, then on — against mail-mcp.mjs, and reads the
// session JSONL for steps and tool-result bytes. Usage:
//   bun bench/codemode/run.mjs <model> [runs] [scale]   (scale 6 ≈ the eM Client session)
import fs from "fs"; import path from "path"; import os from "os";
const [model = "glm-5.3-flash", runs = "1", scale = "1"] = process.argv.slice(2);
const root = path.resolve(import.meta.dir, "../..");
const bin = fs.existsSync(path.join(root, "nyma")) ? [path.join(root, "nyma")] : ["bun", path.join(root, "dist/agent/cli.mjs")];
const prompt = "Find all mails from Renga about AFU, FRM or AML. For each give date, subject and the ask in one line. Save every attachment of those mails into ./attachments. Reply with the list only.";
// mail-mcp.mjs: Renga sends i%4==0, AFU/FRM/AML is topic i%6 in 0..2 → mails 0, 8, 12, each with one attachment
const truth = [...Array(18 * Number(scale)).keys()].filter(i => i % 4 === 0 && i % 6 <= 2);

function realHome() { return process.env.HOME; }
function run(codemode) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "cm-bench-"));
  const proj = path.join(dir, "proj"); fs.mkdirSync(proj);
  // A fresh HOME with the user's settings, credentials and auth but NOT their
  // global mcp.json: with the real HOME, a run reached the user's live eM
  // Client server instead of the stub.
  const home = path.join(dir, "home"), real = path.join(realHome(), ".nyma");
  fs.mkdirSync(path.join(home, ".nyma"), { recursive: true });
  for (const f of ["settings.json", "credentials.json", "auth"])
    if (fs.existsSync(path.join(real, f))) fs.cpSync(path.join(real, f), path.join(home, ".nyma", f), { recursive: true });
  fs.mkdirSync(path.join(proj, ".nyma"));
  fs.writeFileSync(path.join(proj, ".mcp.json"), JSON.stringify({ mcpServers: { mail: { command: "bun", args: [path.join(import.meta.dir, "mail-mcp.mjs")], env: { MAIL_SCALE: scale } } } }));
  fs.writeFileSync(path.join(proj, ".nyma/settings.json"), JSON.stringify({ codemode: { enabled: codemode, "script-only": ["mcp__*"] } }));
  const t0 = Date.now();
  const p = Bun.spawnSync([...bin, "-p", "--output-format", "stream-json", "--model", model, "--permission-mode", "full-auto", prompt],
    { cwd: proj, env: { ...process.env, HOME: home, NYMA_TRUST: "1" }, stdout: "pipe", stderr: "pipe", timeout: 600000 });
  const secs = (Date.now() - t0) / 1000;
  fs.mkdirSync(path.join(import.meta.dir, "results", "streams"), { recursive: true });
  fs.writeFileSync(path.join(import.meta.dir, "results", "streams", `${t0}-${codemode ? "on" : "off"}.jsonl`), p.stdout);
  // Calls made INSIDE a script emit tool events too; only top-level results
  // reach the model, so a call is nested while a codemode__script is open.
  let calls = 0, nested = 0, bytes = 0, steps = 0, inTok = 0, outTok = 0, open = 0, out = "";
  const names = {};
  for (const l of p.stdout.toString().split("\n")) {
    let e; try { e = JSON.parse(l); } catch { continue; }
    if (e.type === "tool_execution_start") { if (e.toolName === "codemode__script") open++; else if (open) nested++; }
    if (e.type === "tool_execution_end") {
      if (e.toolName === "codemode__script") open--;
      else if (open) continue;
      calls++; bytes += String(e.result).length; names[e.toolName] = (names[e.toolName] || 0) + 1;
    }
    if (e.type === "usage") { steps++; inTok += e.inputTokens ?? e.input_tokens ?? 0; outTok += e.outputTokens ?? e.output_tokens ?? 0; }
    if (e.type === "result") out = String(e.result ?? "");
  }
  const asks = ["review and sign off by Monday", "share the updated thresholds", "confirm the STR format for FIU"];
  const found = truth.filter(i => out.includes(asks[i % 6]) || out.toLowerCase().includes(asks[i % 6].toLowerCase().split(" ").slice(0, 3).join(" ")));
  const attDir = path.join(proj, "attachments");
  const atts = fs.existsSync(attDir) ? fs.readdirSync(attDir).length : 0; // expected: truth.length
  return { codemode, scale, model, secs, exit: p.exitCode, steps, inTok, outTok, calls, nested, toolResultKB: Math.round(bytes / 1024), names, attachments: atts, expectedAttachments: truth.length, mentionedTopics: found.length, outHead: out.slice(0, 400), stderrTail: p.stderr.toString().slice(-400) };
}
const results = [];
for (let r = 0; r < Number(runs); r++) for (const cm of [false, true]) { const x = run(cm); console.log(JSON.stringify(x)); results.push(x); }
fs.mkdirSync(path.join(import.meta.dir, "results"), { recursive: true });
fs.writeFileSync(path.join(import.meta.dir, "results", `${new Date().toISOString().slice(0, 16).replace(/:/g, "")}-${model.replace(/\W/g, "_")}-x${scale}.json`), JSON.stringify(results, null, 2));
