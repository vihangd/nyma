#!/usr/bin/env bun
// Regenerates the "Key Namespaces" table in AGENTS.md from each core
// namespace's docstring.
//
// The table was hand-kept: 45 rows for a source tree of 100+ core
// namespaces, drifting in both directions (rows for renamed files, nothing
// for new ones). AGENTS.md is read at the start of every session, so a stale
// map there costs every session. Same shape as gen-builtin-extensions.mjs:
// markers in the doc, a render() the drift test compares against.
//
// Purpose = the first sentence of the ns docstring. A namespace without one
// shows "—", which is the nudge to write it.
//
// Run: bun run gen:namespaces   (test/namespaces_table.test.cljs fails on drift)

import { readdirSync, readFileSync, writeFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";

export const ROOTS = ["src/agent", "src/gateway"];
export const AGENTS_START = "<!-- generated: namespaces (bun run gen:namespaces) -->";
export const AGENTS_END = "<!-- /generated: namespaces -->";

/** Core .cljs files: everything under the roots except extensions/ (40 of
 *  them, each with its own README) and the generated builtin registry. */
export function coreFiles(cwd = process.cwd()) {
  const out = [];
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (e.name === "extensions") continue;
        walk(p);
      } else if (e.name.endsWith(".cljs") && e.name !== "builtin_extensions.cljs") {
        out.push(relative(cwd, p));
      }
    }
  };
  for (const r of ROOTS) {
    try { statSync(join(cwd, r)); } catch { continue; }
    walk(join(cwd, r));
  }
  return out.sort();
}

const NS_RE = /\(ns\s+([\w.\-*+!?<>=]+)\s*(?:"((?:[^"\\]|\\.)*)")?/;

/** {ns, purpose} from a source text, or null when it has no ns form. */
export function describe(text) {
  const m = NS_RE.exec(text);
  if (!m) return null;
  const doc = (m[2] ?? "").replace(/\\"/g, '"').replace(/\s+/g, " ").trim();
  // First sentence, capped; a docstring's opening line is its summary.
  let purpose = doc.split(/(?<=[.!?])\s+/)[0] ?? "";
  if (purpose.length > 140) purpose = purpose.slice(0, 137).trimEnd() + "…";
  return { ns: m[1], purpose: purpose || "—" };
}

export function render(cwd = process.cwd()) {
  const rows = [];
  for (const f of coreFiles(cwd)) {
    const d = describe(readFileSync(join(cwd, f), "utf8"));
    if (!d) continue;
    const purpose = d.purpose.replace(/\|/g, "\\|");
    rows.push(`| \`${f}\` | \`${d.ns}\` | ${purpose} |`);
  }
  return ["| File | Namespace | Purpose |", "|------|-----------|---------|", ...rows].join("\n");
}

/** AGENTS.md with the generated block replaced; null when the markers are absent. */
export function splice(doc, table) {
  const i = doc.indexOf(AGENTS_START), j = doc.indexOf(AGENTS_END);
  if (i < 0 || j < 0) return null;
  return doc.slice(0, i + AGENTS_START.length) + "\n" + table + "\n" + doc.slice(j);
}

if (import.meta.main) {
  const table = render();
  const path = "AGENTS.md";
  const spliced = splice(readFileSync(path, "utf8"), table);
  if (spliced === null) console.log("AGENTS.md — no generated-table markers, left alone");
  else {
    writeFileSync(path, spliced);
    console.log(`AGENTS.md — ${table.split("\n").length - 2} namespaces`);
  }
}
