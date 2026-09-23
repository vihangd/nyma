#!/usr/bin/env bun
// Regenerates the README's event table from `agent.events/event-registry`.
//
// The table was hand-kept: ~30 rows for ~65 events, several combined per row
// and the merge semantics of a collect hook described nowhere near the
// place that implements them. The registry in events.cljs now carries a
// :doc per event; this renders it. Same shape as gen-builtin-extensions.mjs.
//
// Run: bun run gen:events-doc   (test/events_doc.test.cljs fails on drift;
// compile first — it imports dist/).

import { readFileSync, writeFileSync } from "node:fs";

export const README_START = "<!-- generated: events (bun run gen:events-doc) -->";
export const README_END = "<!-- /generated: events -->";

const KIND = { emit: "emit", async: "emit-async", collect: "emit-collect" };

export async function render() {
  const { event_registry } = await import("../dist/agent/events.mjs");
  const rows = [];
  for (const [name, meta] of event_registry) {
    const kind = KIND[meta.kind] ?? String(meta.kind);
    const wire = meta.wire === false ? " (not forwarded over rpc)" : "";
    const doc = (meta.doc ?? "—").replace(/\|/g, "\\|");
    rows.push(`| \`${name}\` | ${kind}${wire} | ${doc} |`);
  }
  return ["| Event | Kind | When |", "|-------|------|------|", ...rows].join("\n");
}

export function splice(doc, table) {
  const i = doc.indexOf(README_START), j = doc.indexOf(README_END);
  if (i < 0 || j < 0) return null;
  return doc.slice(0, i + README_START.length) + "\n" + table + "\n" + doc.slice(j);
}

if (import.meta.main) {
  const table = await render();
  const spliced = splice(readFileSync("README.md", "utf8"), table);
  if (spliced === null) console.log("README.md — no generated-events markers, left alone");
  else {
    writeFileSync("README.md", spliced);
    console.log(`README.md — ${table.split("\n").length - 2} events`);
  }
}
