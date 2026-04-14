# prompt-history

> Persistent prompt history with **Ctrl+R** fuzzy search, backed by SQLite.

## What it does

Captures every submitted prompt into a SQLite table (via the `__sqlite-store` API exposed by `agent.sessions.storage`), deduplicating consecutive identical entries. **Ctrl+R** opens a fuzzy-search overlay where the user can scroll through past prompts and restore one into the editor. `/history [query]` does the same search via slash command.

The schema lives in `src/agent/sessions/storage.cljs` (`prompt_history` table). Sessions and prompts share the same SQLite file (`~/.nyma/prompts.db`).

## Commands

| Command | What it does |
|---|---|
| `/prompt-history__history` | `[/history <query>]` — search prompt history; opens the picker filtered by `query` |

## Shortcuts

- **Ctrl+R** — open the fuzzy prompt-history picker (no query required)

## Hooks

| Event | Behaviour |
|---|---|
| `input_submit` | Capture the submitted prompt (deduplicates against the previous entry) |

## Capabilities

`commands`, `events`, `shortcuts`, `ui`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
- `src/agent/sessions/storage.cljs` — the underlying SQLite schema
