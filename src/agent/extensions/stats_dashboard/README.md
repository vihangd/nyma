# stats-dashboard

> Usage stats dashboard — cost, tokens, model breakdown, daily trends, per-tool performance.

## What it does

Aggregates usage data from SQLite (the `__sqlite-store` API exposed by `agent.sessions.storage`) into a terminal dashboard: total cost, token totals, per-model breakdown, and daily trends. Also tracks tool performance metrics in memory (calls, total latency, errors) — `/stats tools` shows the top tools by call count and latency.

`/stats-session` shows just the current session's totals (no SQLite required).

## Commands

| Command | What it does |
|---|---|
| `/stats-dashboard__stats` | Show aggregate usage stats. Subcommand: `tools` for per-tool performance |
| `/stats-dashboard__stats-session` | Show usage stats for the current session only |

## Hooks

| Event | Behaviour |
|---|---|
| `tool_complete` | Track per-tool metrics: `calls`, `total-ms`, `errors` (in-memory, per session) |

## Capabilities

`commands`, `events`, `state`, `ui`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
- `src/agent/sessions/storage.cljs` — the underlying SQLite schema
