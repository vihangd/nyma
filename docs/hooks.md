# Hooks reference

Nyma supports Claude-Code-shape hooks: small scripts (or HTTP
endpoints, or LLM prompts) that nyma runs at well-defined points
in its lifecycle. Hooks work without writing any nyma extension
code — you describe them in JSON and nyma calls them.

This document is the user-facing reference. If you're writing a
nyma extension in CLJS and want to subscribe to events from the
inside, see [extension-guide-cljs.md](./extension-guide-cljs.md).

> **Compatibility:** every example below uses Claude Code's
> canonical hook schema verbatim. Hook scripts that work in
> Claude Code work unchanged in nyma — just point your config at
> them.

---

## Quick start

Add a `hooks` block to `.nyma/settings.json`:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          { "type": "command", "command": "rtk hook claude" }
        ]
      }
    ]
  }
}
```

That's a complete rtk integration. `rtk hook claude` reads the
PreToolUse JSON from stdin, rewrites the bash command via rtk's
50+ command wrappers, and emits CC's response shape with the
rewritten command. Nyma applies the rewrite as a tool-args
mutation. (Verified end-to-end in `test/claude_hook_bridge_rtk.test.cljs`.)

---

## Configuration files

Settings are merged in this order (later sources win for top-level
keys, **except** `hooks` which is concatenated per-event):

| Source | Default | When to use |
|---|---|---|
| `~/.nyma/settings.json` | always loaded | personal hooks across all projects |
| `.nyma/settings.json` | always loaded | project hooks shared via VCS |
| `.nyma/settings.local.json` | always loaded | project hooks NOT shared (gitignored) |

Opt-in compat sources via the `hooks-compat` flag:

```json
{
  "hooks-compat": { "claude": true, "agents": false }
}
```

| Source | Enabled by | Notes |
|---|---|---|
| `~/.claude/settings.json` | `compat.claude: true` | reads CC's user-global hooks |
| `.claude/settings.json` | `compat.claude: true` | reads CC's project hooks |
| `.claude/settings.local.json` | `compat.claude: true` | reads CC's project-local hooks |
| `~/.agents/hooks.json` | `compat.agents: true` | bare `{EventName: [...]}` shape |
| `.agents/hooks.json` | `compat.agents: true` | bare `{EventName: [...]}` shape |

`disableAllHooks: true` in any source short-circuits every source
below it (matches CC's behavior).

---

## Schema

```json
{
  "hooks": {
    "<EventName>": [
      {
        "matcher": "<matcher-string>",
        "hooks": [
          {
            "type": "command|http|prompt|mcp_tool",
            "command": "...",
            "url": "...",
            "headers": { "Authorization": "Bearer $TOKEN" },
            "allowedEnvVars": ["TOKEN"],
            "prompt": "...",
            "model": "...",
            "server": "...",
            "tool": "...",
            "input": { "file_path": "${tool_input.file_path}" },
            "timeout": 600
          }
        ]
      }
    ]
  },
  "hooks-compat": { "claude": false, "agents": false },
  "disableAllHooks": false
}
```

`timeout` is in **seconds**. Defaults: command 600, http 30,
prompt 30, mcp_tool 60.

### Matcher syntax

| Form | Example | Matches |
|---|---|---|
| empty / `*` / omitted | `""` | everything |
| simple (alnum/underscore/pipe) | `Bash`, `Edit\|Write` | exact string or pipe-list |
| anything else | `^Notebook`, `mcp__memory__.*` | JS regex |

Tool-name matchers use Claude Code's TitleCase: `Bash`, `Read`,
`Edit`, `Write`, `Glob`, `Grep`, `WebFetch`, `WebSearch`,
`Agent`, `LS`, `Think`. Nyma's lowercase tool names are
translated automatically at the boundary.

For MCP tools the matcher format is `mcp__<server>__<tool>` —
e.g. `mcp__memory__write_graph` or `mcp__.*__read.*`.

---

## Events

The 11 events nyma maps to CC's schema today. Other CC events
(`Setup`, `SubagentStart`/`Stop`, `TaskCreated`/`Completed`,
`PostToolBatch`, etc.) are unmapped — see "Not mapped" below.

### `PreToolUse`

Fires before a tool runs. Discriminated by tool name (CC TitleCase).

**stdin payload:**
```json
{
  "session_id": "...",
  "transcript_path": "...",
  "cwd": "/abs/path",
  "permission_mode": "default",
  "hook_event_name": "PreToolUse",
  "tool_name": "Bash",
  "tool_input": { "command": "git status" },
  "tool_use_id": "..."
}
```

**Response (any subset):**
```json
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "allow|deny|ask|defer",
    "permissionDecisionReason": "...",
    "updatedInput": { "command": "rtk git status" },
    "additionalContext": "..."
  }
}
```

- `permissionDecision: "deny"` → cancel the tool call, surface
  `permissionDecisionReason` to the model.
- `updatedInput` → full replacement of the tool's args.
- Multiple matching hooks: precedence is **deny > defer > ask > allow**.

### `PostToolUse` / `PostToolUseFailure`

Fires after a tool completes (success / failure path).
Discriminated by tool name.

**Response:**
```json
{
  "decision": "block",
  "reason": "tool succeeded but result is unacceptable",
  "hookSpecificOutput": {
    "hookEventName": "PostToolUse",
    "additionalContext": "extra info appended to the tool result"
  }
}
```

### `PermissionRequest`

Fires when a tool needs user approval. Discriminated by tool name.

**Response:**
```json
{
  "hookSpecificOutput": {
    "hookEventName": "PermissionRequest",
    "decision": {
      "behavior": "allow|deny",
      "message": "shown to claude when denied",
      "updatedInput": { ... }
    }
  }
}
```

### `SessionStart`

Fires when a session begins. Matcher is the source kind.

| Matcher | When it fires |
|---|---|
| `startup` | new session (default) |
| `resume` | session resume / branch switch |
| `clear` | `/clear` command |

`additionalContext` is injected as a system reminder on the
next user turn — useful for things like "current branch: main"
or task-tracker notes.

### `SessionEnd`

Fires when a session ends. Matcher is the end reason
(`clear`, `other`).

### `UserPromptSubmit`

Fires when the user submits a prompt, before nyma sends it to
the model. No matcher (always fires).

`additionalContext` is injected as a system reminder before
the prompt. `decision: "block"` swallows the prompt and shows
the reason instead.

### `Stop`

Fires when nyma finishes a turn. Observational in nyma — the
response has already streamed.

### `StopFailure`

Fires on a provider error. Matcher is the error type.

### `PreCompact`

Fires before context compaction. Matcher is `manual` or `auto`.
`decision: "block"` aborts the compaction.

### `PostCompact`

Fires after context compaction. Observational.

### Not mapped (yet)

These CC events have no nyma analog today and don't fire from
the bridge: `Setup`, `SubagentStart`, `SubagentStop`,
`TeammateIdle`, `WorktreeCreate`, `WorktreeRemove`,
`Elicitation`, `ElicitationResult`, `PostToolBatch` (nyma runs
tools sequentially), `CwdChanged`, `FileChanged`,
`InstructionsLoaded`, `TaskCreated`, `TaskCompleted`.

---

## Handler types

### `type: "command"` — subprocess

Default. Spawns a shell command, pipes the JSON event to stdin,
reads JSON (or plain text) from stdout.

```json
{ "type": "command",
  "command": "/path/to/script.sh",
  "timeout": 30,
  "shell": "bash" }
```

- `timeout` in seconds (default 600).
- `shell` overrides the shell binary; default is `/bin/sh` (or
  `cmd` on Windows).
- Exit 0 = success, exit 2 = blocking error (stderr becomes
  `permissionDecisionReason`), other non-zero = non-blocking
  error (logged, hook treated as no-op).

### `type: "http"` — POST to URL

```json
{ "type": "http",
  "url": "http://localhost:8080/preflight",
  "headers": {
    "Authorization": "Bearer $LOCAL_HOOK_TOKEN"
  },
  "allowedEnvVars": ["LOCAL_HOOK_TOKEN"],
  "timeout": 5 }
```

- Default timeout 30s.
- `$VAR` and `${VAR}` in headers are substituted from
  `process.env[VAR]` ONLY if `VAR` is in `allowedEnvVars`.
  Other names stay literal — prevents a hostile hooks.json
  from exfiltrating arbitrary env vars via header echo.
- 2xx response is parsed as JSON if it looks like JSON, or
  treated as plain `additionalContext` text otherwise.
- Non-2xx → non-blocking error.

### `type: "prompt"` — small-model LLM evaluator

```json
{ "type": "prompt",
  "prompt": "Review this command. Block if unsafe:\n$ARGUMENTS",
  "model": "claude-haiku-4-5",
  "timeout": 30 }
```

- `$ARGUMENTS` is replaced with the JSON event payload
  (pretty-printed) before sending to the model.
- The model is expected to reply with CC response JSON like
  `{ "decision": "block", "reason": "..." }` or `{ "decision": "allow" }`.
- `model` defaults to whatever `getActiveModel(api)` returns.

### `type: "mcp_tool"` — currently a stub

The full MCP-client wiring is a follow-up. Today this returns a
clear non-blocking error so misconfigured hooks don't fail
silently. Use `type: "command"` with a small wrapper script as
a workaround.

---

## Recipes

### rtk — bash output compression

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Bash",
        "hooks": [{ "type": "command", "command": "rtk hook claude" }] }
    ]
  }
}
```

### Block destructive commands

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Bash",
        "hooks": [{ "type": "command",
                    "command": "sh -c 'jq -e \".tool_input.command | test(\\\"rm -rf|sudo rm\\\")\" >/dev/null && (echo blocked >&2; exit 2) || exit 0'" }] }
    ]
  }
}
```

(For real use, write a proper script and reference it by path.
Inline shell with quoting tends to break.)

### Inject git branch into every session

`~/.nyma/hooks/branch.sh`:
```bash
#!/usr/bin/env bash
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)
jq -nc --arg b "$branch" '{
  hookSpecificOutput: {
    hookEventName: "SessionStart",
    additionalContext: ("Working branch: " + $b)
  }
}'
```

`~/.nyma/settings.json`:
```json
{
  "hooks": {
    "SessionStart": [
      { "matcher": "startup",
        "hooks": [{ "type": "command",
                    "command": "~/.nyma/hooks/branch.sh" }] }
    ]
  }
}
```

### Run a linter after every Edit

```json
{
  "hooks": {
    "PostToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command",
                    "command": "~/.nyma/hooks/lint-edited-file.sh" }] }
    ]
  }
}
```

The script reads `tool_input.file_path` from stdin and runs the
right linter; on lint errors it returns a `decision: "block"`
response and the model gets the diagnostic.

### Reuse a Claude Code hook script

If you've already configured rtk / claude-mem / a validator in
`~/.claude/settings.json`, opt in:

```json
// ~/.nyma/settings.json
{ "hooks-compat": { "claude": true } }
```

The bridge now reads your CC hook config alongside nyma's.

---

## Hot reload

Settings files are watched (`fs.watch`) with a 100 ms debounce.
When any of the loaded sources changes, hooks reload without
restarting nyma. The audit log's "first-run seen" cache is
cleared too, so an edited script gets re-audited on its next
fire.

There's no UI for hot reload — it just happens. To inspect the
currently-loaded config from inside an extension, call
`api.getHookConfig()`.

---

## Cancellation

When the agent's `AbortController` fires (Ctrl+C, session
shutdown), in-flight subprocesses (`type: command`) and
HTTP requests (`type: http`) are killed. Hooks cannot hold up
a session indefinitely; the timeout is the upper bound and
abort is the immediate path.

---

## Audit log

The bridge writes a single line to `~/.nyma/hooks-audit.log`
the first time each unique (event, command) pair fires per
session. Format:

```json
{"ts":"2026-04-30T12:34:56.789Z","event":"PreToolUse","command":"command:rtk hook claude","sha256":"…64-hex…"}
```

This is a tripwire, not a permission gate — the hook still
runs. It exists so you can `tail -f` and see what's executing.

---

## Limitations / follow-ups

- **MCP-tool handler is a stub.** Full wiring requires a JSON-
  RPC stdio transport against the configured server.
- **Output spillover.** CC writes >10 KB stdout to a temp file
  and replaces it with a path. Nyma passes through today.
- **No first-run trust prompt.** The audit log records what
  fires; an interactive ack is on the follow-up list.
- **Sequential dispatch.** Hooks within a matcher run in order,
  matchers within an event run in order. No parallel batching
  (matches CC's stable-ordering requirement for precedence).
- **Mode detection is heuristic.** `api.mode` is `interactive`
  / `sdk` based on `api.ui.available`; override with the
  `NYMA_MODE` env var if needed.

---

## See also

- [extension-guide-cljs.md](./extension-guide-cljs.md) — for
  writing nyma extensions in CLJS that subscribe to events
  from inside the runtime.
- [Claude Code hooks docs](https://code.claude.com/docs/en/hooks) —
  the upstream spec; nyma follows it where there's an
  applicable mapping.
- `src/agent/extensions/claude_hook_bridge/README.md` — short
  in-tree summary plus the canonical rtk recipe.
