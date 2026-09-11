#!/usr/bin/env bun
// Regenerates docs/event-map.md.
//
// Borrowed from deepseek-harness, whose docs/event-producer-consumer.md is a
// generated matrix of every event's dispatchers AND listeners, printing a bare
// "-" when a column is empty. It tolerates an event with no listener; what it
// does not tolerate is not knowing. nyma had half of that (a lint that fails on
// an event with no emitter) and no view of the other direction at all, plus a
// hand-written roadmap table of the registerX registries that went stale the
// moment someone added one — which is how five registries with no consumer
// survived as long as they did.
//
// Run: bun run gen:event-map      (the test fails if the committed file drifts)

import { writeFileSync } from "node:fs";
import { render_event_map as renderEventMap } from "../dist/agent/dev/event_map.mjs";

const out = renderEventMap();
writeFileSync("docs/event-map.md", out);
console.log(`docs/event-map.md — ${out.split("\n").length} lines`);
