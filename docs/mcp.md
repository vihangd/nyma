# MCP servers

Nyma's main LLM can call tools from any MCP (Model Context
Protocol) server. Drop a `.mcp.json` in your project, restart, and
the server's tools become available as `mcp__<server>__<tool>`
alongside nyma's built-in tools.

This document is the user-facing reference. For the architecture
and module layout, see
[plan-mcp-client.md](./plan-mcp-client.md).

---

## Quick start: lean-ctx

```json
// .mcp.json (project root)
{
  "mcpServers": {
    "lean-ctx": {
      "command": "lean-ctx",
      "args": ["mcp"]
    }
  }
}
```

Restart nyma. The startup banner lists active servers:

```
[hook-bridge] active — 2 source(s), 3 event(s) configured
MCP 1/1
```

Now ask the model anything that needs file context — it can call
`mcp__lean-ctx__ctx_read`, `mcp__lean-ctx__ctx_tree`,
`mcp__lean-ctx__ctx_search`, etc. lean-ctx's compressed responses
typically save 60–95% of tokens vs nyma's native `read` / `grep`.

---

## Configuration files

Sources are loaded in this order (lowest → highest precedence —
later sources override earlier ones on the same server name):

| # | File | Scope |
|---|---|---|
| 1 | `~/.nyma/mcp.json` | User-global. Servers you want available in every project. |
| 2 | `<cwd>/.nyma/mcp.json` | Project-shared, nyma-specific. Lives under `.nyma/` like other nyma config. |
| 3 | `<cwd>/.cursor/mcp.json` | Cursor compat — read for parity with Cursor projects. |
| 4 | `<cwd>/.mcp.json` | Project-shared, CC convention. The most common form. |

Drop `.mcp.json` (or any of the variants above) anywhere on this list
and its servers appear. Same server name in multiple files: highest-
precedence wins. Different names: all merged.

All four locations use the standard MCP shape:

```json
{
  "mcpServers": {
    "<name>": {
      "command": "<binary>",
      "args": ["<arg1>", "<arg2>"],
      "env": { "VAR": "value" }
    }
  }
}
```

`${ENV_VAR}` expansion is supported in both `args` and `env` values.

After editing, run `/mcp refresh` (from agent-shell) to re-scan
without restarting.

---

## Tool naming

All MCP tools appear under `mcp__<server>__<tool>`:

```
.mcp.json server name           ┐
                                │
.mcp.json: "lean-ctx" ──────────┴─→ mcp__lean-ctx__ctx_read
                                              ↑
                                         tool's MCP name
```

This matches Claude Code's convention — hook-bridge matchers and
tool-name renderers already understand it.

---

## Status line

Two segments are auto-appended when at least one server is
configured:

| Segment | Position | Default | Shows |
|---|---|---|---|
| `mcp.summary` | left | on | `MCP 2/3` — connected / total. Color: green = all healthy, yellow = any starting, red = any errored. |
| `mcp.detail` | right | off | `lean-ctx ✓ filesystem ⚠ memory ✗` — per-server icons. |

Enable the detail segment in `.nyma/settings.json`:

```json
{
  "mcp": {
    "show-detail-segment": true
  }
}
```

The `/mcp-status` command prints a tabular view including tool
counts and last error messages — useful when something's red.

---

## Other settings

```json
// .nyma/settings.json
{
  "mcp": {
    "show-detail-segment": false,
    "max-restarts":        3,
    "startup-timeout-ms":  30000,
    "call-timeout-ms":     30000
  }
}
```

| Key | Default | Notes |
|---|---|---|
| `show-detail-segment` | `false` | Per-server icons in the status line |
| `max-restarts` | `3` | Auto-reconnect attempts on disconnect |
| `startup-timeout-ms` | `30000` | Max time to spawn + handshake |
| `call-timeout-ms` | `30000` | Per-tool-call deadline |

---

## Lifecycle

- **Spawn**: eager at `session_start` — every configured server
  spawns in parallel. A failure on one doesn't gate others.
- **Tool registration**: as each server hands off `tools/list` at
  handshake, its tools land in nyma's tool registry. Any server
  slow to handshake just misses turn 1; its tools appear at turn 2.
- **Auto-restart**: if a subprocess dies mid-session, the client
  reconnects with exponential backoff (`500ms · 2^n`, capped at
  `max-restarts`). After the cap, state is `:stopped-error` and
  stays there until the next session.
- **Shutdown**: at `session_shutdown` / `session_end`, all clients
  are closed in parallel and tool registrations are removed.

---

## Diagnostics

| Question | How to check |
|---|---|
| Is the bridge live? | `[hook-bridge] active …` line at startup; `MCP N/M` in status line |
| Which servers are configured? | `/mcp list` |
| Which servers are connected? | status line `MCP X/Y`, or `/mcp-status` for the table |
| Why is a server red? | `/mcp-status` shows the last-error string per server |
| Did rescanning the config pick up a change? | `/mcp refresh` then `/mcp-status` |

---

## Supported servers

Anything that speaks MCP over stdio. Common picks:

- **lean-ctx** — file/grep/tree compression, persistent project
  knowledge. https://github.com/yvgude/lean-ctx
- **@modelcontextprotocol/server-everything** — official reference
  server, useful as a sanity check: `npx -y @modelcontextprotocol/server-everything`
- **@modelcontextprotocol/server-filesystem** — sandboxed file I/O
- **@modelcontextprotocol/server-github** — GitHub API
- **@modelcontextprotocol/server-postgres** — read-only Postgres

For a curated list, see the official MCP server registry.

---

## Limitations

- **stdio transport only.** HTTP/SSE transports are spec'd but
  every server in common use is stdio. Add when needed.
- **Tools only.** MCP also exposes resources and prompts. We don't
  surface those yet — they need their own integration design.
- **No server-initiated tool list refresh.** Tools are fetched once
  at handshake. If the server adds a new tool mid-session, it won't
  appear until restart. (`tools/list_changed` notifications are
  unhandled today.)
- **No elicitation.** A server asking the user to fill a form via
  `elicitation/create` is unhandled.
- **mcp_tool hook handler is a stub** at the hook-bridge layer;
  it now becomes a one-line implementation against this client —
  follow-up.

None of these block the common case (LLM calls a tool, server
returns text, nyma renders it).

---

## Troubleshooting

**Status segment is missing entirely.** The bridge auto-hides when
no servers are configured. Check `cat .mcp.json`.

**Status shows `MCP 0/1` red.** Run `/mcp-status` for the error
message. Common causes: command not on PATH, wrong working
directory, env var missing.

**LLM doesn't call any `mcp__*` tool.** That's the LLM's choice.
Check that the tool is in the active list with `/mcp-status` showing
`(N tools)` > 0. If you want to nudge usage, add a system-prompt
section in `.nyma/settings.json#system-prompt-additions`.

**Server is restarting repeatedly.** Check the subprocess logs —
the SDK pipes stderr through. If it's misbehaving on every
handshake, set `max-restarts: 0` in settings to stop the storm,
then debug.

---

## See also

- [plan-mcp-client.md](./plan-mcp-client.md) — implementation plan,
  module architecture, phases.
- [hooks.md](./hooks.md) — sibling protocol; rtk uses hooks, lean-ctx
  uses MCP, both compose.
- MCP spec: https://modelcontextprotocol.io
