# agent-shell

> Unified frontend for multiple coding agents (Claude Code, Gemini CLI, OpenCode, Qwen, Goose, Kiro) over the Agent Client Protocol.

## What it does

`agent-shell` lets nyma drive other coding agents as backends. It speaks **ACP** (Agent Client Protocol — JSON-RPC 2.0 over stdio) to spawn and supervise an external agent, then routes user input, tool calls, and approvals between the nyma TUI and the agent process. Switch agents with `/agent`, swap models with `/model`, change autonomy with `/plan`, `/yolo`, `/approve`, and `/auto-edit`, and hand off a session to a different agent with `/handoff`.

This is the most feature-rich extension in the tree. For details — wire format, sub-features, MCP discovery, cost tracking, transcript handling — see [`docs/agent-shell.md`](../../../../docs/agent-shell.md) (881 lines).

## Commands

| Command | What it does |
|---|---|
| `/agent` | Connect, detach, disconnect, list, or switch the active agent |
| `/disconnect` | Disconnect the currently active agent |
| `/model` | Show the model picker, list models, or switch model directly |
| `/plan` | Plan / read-only mode |
| `/yolo` | Auto-approve every action |
| `/approve` | Default mode — prompt for every tool call |
| `/auto-edit` | Auto-approve edits, prompt for shell |
| `/effort` | Set thinking effort (low / medium / high / max / auto) |
| `/handoff` | Hand the current session off to a different agent, with context transfer |
| `/mcp` | Discover and manage MCP servers from project config (`.mcp.json`, `.cursor/mcp.json`, …) |
| `/sessions` | List, resume, or create new agent sessions |
| `/plan-capture` | Write the agent's plan to `.nyma/plans/` and detach, so a cheap local model can execute it |

### `detach` vs `disconnect`

`disconnect` kills the subprocess; the conversation goes with it. `detach`
clears input routing only — typing returns to nyma's own model while the ACP
session keeps running, so `/agent <name>` resumes it with context intact.

### Plan here, execute cheaply there

`/plan-capture` exists for one reason: Claude Code runs on your subscription, so
planning with it costs nothing at the margin, while implementation can run on a
cheap local model.

```
/agent claude                       # set init-mode "plan" — see below
add OAuth login to the API          # converse
/plan-capture                       # writes .nyma/plans/…md, detaches
/spec import oauth-login-api --run  # decompose, activate, run the phase loop
```

Without `spec_driven`, `/plan-capture --execute --role=default` sets the role
and points the model at the artifact — but there is no re-injection or task
tracking, so the plan falls out of context as the conversation grows. That is
the argument for using specs on anything long.

Claude Code ships `init-mode: nil`, so it connects able to attempt edits.
Capture proceeds regardless, warning only when the agent actually completed
file changes — a declined write changed nothing. Set plan mode once with
`{"agent-shell": {"claude": {"init-mode": "plan"}}}`.

## Hooks

| Event | Behaviour |
|---|---|
| `session_ready` | Auto-connect the configured default agent and register status segments |
| `session_clear` | Send `session/new` to the active ACP backend so `/clear` resets history on both sides |
| `session_shutdown` | Disconnect every active agent on exit |
| `input` | Route user input to the active agent (high priority — runs before built-in tools) |
| `acp_usage` | Aggregate cost / token metrics from the agent's response |

## Sub-modules

| File | Role |
|---|---|
| `agent_switcher.cljs` | `/agent` — connect / disconnect / list / switch |
| `model_switcher.cljs` | `/model` — fuzzy picker, agent-specific dispatch |
| `mode_switcher.cljs` | `/plan`, `/yolo`, `/approve`, `/auto-edit` |
| `effort_switcher.cljs` | `/effort` thinking-level control |
| `session_mgmt.cljs` | `/sessions` list / resume / new |
| `handoff.cljs` | `/handoff` with context capture and transfer |
| `permission_ui.cljs` | `--auto-approve` flag handling |
| `mcp_discovery.cljs` | Finds `.mcp.json` / `.cursor/mcp.json` MCP server configs |
| `cost_tracker.cljs` | Aggregates per-agent usage |
| `acp/*` | ACP transport — JSON-RPC framing, pool, schema, sessions |

## Flags

- `--agent-shell__auto-approve` — auto-approve all agent tool calls (default: `false`)

## Capabilities

`spawn`, `commands`, `events`, `ui`, `messages`, `session`, `renderers`, `context`, `flags`, `state`

## See also

- [`docs/agent-shell.md`](../../../../docs/agent-shell.md) — full architecture, ACP wire format, transcript model
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
