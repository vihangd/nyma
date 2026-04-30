# claude-hook-bridge

Claude-Code-shape hooks for nyma.

This extension lets nyma run hook scripts written in Claude Code's
canonical schema. Hook scripts and HTTP endpoints from the entire
Claude Code ecosystem (rtk, claude-mem, validators, audit tools, …)
work in nyma without per-tool integration.

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

That's the entire rtk integration. `rtk hook claude` reads CC's
PreToolUse JSON from stdin and writes a CC response with
`updatedInput.command` rewritten to the rtk-wrapped form. The bridge
applies that rewrite as a mutation on nyma's tool args.

## Source precedence

Always loaded:

1. `~/.nyma/settings.json` (user-global)
2. `.nyma/settings.json` (project, shared via VCS)
3. `.nyma/settings.local.json` (project-local, gitignored)

Opt-in via `hooks-compat` in any of the always-loaded files:

```json
{
  "hooks-compat": { "claude": true, "agents": false }
}
```

When `claude: true` the bridge also reads:

4. `~/.claude/settings.json`
5. `.claude/settings.json`
6. `.claude/settings.local.json`

When `agents: true`:

7. `~/.agents/hooks.json` (bare hooks block at root)
8. `.agents/hooks.json`

`disableAllHooks: true` in any source short-circuits the rest.

## Supported events

Mapped from nyma's native event bus:

| Claude Code event | Fires on nyma | Notes |
|---|---|---|
| `PreToolUse` | `before_tool_call` | `permissionDecision` (deny/ask/allow), `updatedInput`, `additionalContext` |
| `PostToolUse` | `tool_complete` (success) | `decision: block`, `additionalContext` |
| `PostToolUseFailure` | `tool_complete` (`isError: true`) | as PostToolUse |
| `PermissionRequest` | `permission_request` | `decision.behavior: allow|deny`, `updatedInput` |
| `SessionStart` | `session_start` | `additionalContext` injected into next turn |
| `SessionEnd` | `session_end` / `session_shutdown` | observational |
| `UserPromptSubmit` | `input_submit` | `decision: block`, `additionalContext` |
| `Stop` | `agent_end` | observational in nyma (response already streamed) |
| `StopFailure` | `provider_error` | observational |
| `PreCompact` | `before_compact` | `decision: block` aborts compaction |
| `PostCompact` | `compact` | observational |

Not currently mapped: `Setup`, `SubagentStart`/`Stop`,
`TeammateIdle`, `WorktreeCreate`/`Remove`, `Elicitation*`,
`PostToolBatch` (nyma runs tools sequentially), `CwdChanged`,
`FileChanged`, `InstructionsLoaded`, `TaskCreated`/`Completed`.

## Handler types

`type: "command"` (default) — spawns a subprocess, JSON to stdin,
JSON from stdout. Default 600s timeout.

`type: "http"` — POSTs JSON to a URL, default 30s timeout. Supports
`headers` with `$VAR` substitution from `allowedEnvVars` (the
allowlist prevents arbitrary env exfiltration).

`type: "prompt"` — small-model LLM evaluator with `$ARGUMENTS` =
JSON event body. Default 30s.

`type: "mcp_tool"` — currently a stub; full MCP-client wiring is a
follow-up.

## Cancellation

When the agent's `AbortController` fires (e.g. on Ctrl+C),
in-flight subprocesses and HTTP requests are killed. Hooks cannot
hold up a session indefinitely.

## Output truncation

Per the CC spec, hook output exceeding 10K chars is meant to be
spilled to a file. Currently the bridge passes the full output
through; truncation is on the follow-up task list.

## Replacing the old `rtk_compression` extension

The old in-process `rtk_compression` extension has been removed.
To get the same behavior:

1. Make sure rtk is installed (`brew install rtk`).
2. Add to `.nyma/settings.json`:

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

That's it. You now get rtk's full 50+ command coverage (vs the
old extension's 16, three of which were broken — `eslint`, `tail`,
partial `cat`/`head`/`rg`). When rtk releases new wrappers, you
inherit them automatically; no nyma update required.

## Disabling hooks

```json
{ "disableAllHooks": true }
```

Set in any source to short-circuit every source below it.

## Trust

The bridge does not currently prompt before executing a hook script
it hasn't seen. Auditing/trust prompting is on the follow-up task
list. Until then: review every settings file before activating it.
