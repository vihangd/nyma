# refine

> `/refine` — mine this session for the failure patterns it already recorded, and offer to keep what you agree with.

## Why this exists

The signals that identify a stuck run are already on disk. Every session is an
append-only JSONL of user messages, tool calls and results; the run that prompted
this extension had 61 of 101 turns doing no work, one file read 26 times, and the
same `bin/rails test` invocation issued three times unchanged. All of it was
sitting in `~/.nyma/sessions/` and nothing ever read it back.

Mining is **deterministic** — no model call, no cost, no latency. It reports what
happened; it does not grade the agent or invent narrative.

## Commands

| Command | What it does |
|---|---|
| `/refine` | Mine the current session |
| `/refine <session-file>` | Mine a past one (the ids from `nyma resume -r` work here) |

## What it looks for

| Signal | Threshold | Why |
|---|---|---|
| No-op turn runs | longest run ≥ 3 turns with zero tool calls | The signature of a context-rot collapse: the model keeps talking and stops working |
| Repeated commands | same command ≥ 3 times | Compared after stripping the `unset …;` env scrub and stacked `cd '…' &&` prefixes, so re-runs are recognised as re-runs |
| Re-read files | same path read ≥ 4 times | Re-reading unchanged files is the cheapest evidence that context was lost |
| Correction messages | user text matching "you didn't", "wrong", "i said", "instead", … | The turns where you had to intervene |

A clean session produces **nothing**: no report file, no prompt, one notification.
That is deliberate — busywork output trains you to ignore the tool.

## What it does with them

1. Writes a report to `.nyma/refine/refine-<timestamp>.md`.
2. Asks once, via the standard select overlay:

   ```
   4 finding(s) from this session — apply where?
     › Append to project MEMORY.md   (.nyma/memory/MEMORY.md)
       Append to global MEMORY.md    (~/.nyma/memory/MEMORY.md)
       Keep the report only
       Cancel
   ```

The report file is the multi-select: edit it before accepting and only what
remains is appended. Nothing is written to `MEMORY.md` without that answer.

Non-interactive runs (`-p`, json, rpc) write the report, print the path, and
never prompt — the same `ui-prompt-ready?` guard plan mode uses.

## Boundaries

- **No model-facing tools.** `/refine` is a user command. nyma already exposes
  ~36 tools against a 30–50 practical ceiling for small models, where each extra
  semantically-similar tool costs measurable selection accuracy.
- **Proposes, never applies.** It may propose memory entries and nothing else —
  not `verify.cmd`, not permissions, not roles, not its own code.
- **Reads, never rewrites.** The session JSONL is untouched, so a session pruned
  by escalation can still be mined for the span that was dropped from context.

## Capabilities

`commands`, `session`, `ui`

## See also

- [`model_roles`](../model_roles/README.md) — escalation reacts to the same
  stall signals live, while `/refine` reads them afterwards
- [`handoff`](../handoff/README.md) — a brief for continuing the work, rather
  than a diagnosis of how it went
