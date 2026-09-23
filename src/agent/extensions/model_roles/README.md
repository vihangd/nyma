# model-roles

> Named model presets — `default`, `fast`, `deep`, `plan`, `commit`, `lead` — for one-keystroke model switching.

## What it does

Defines named **roles** that map to provider/model pairs (and optionally `allowed-tools` + per-tool `permissions` + a `system-prompt`). At runtime, `model_roles` hooks `model_resolve` to swap in the role's model, `tool_access_check` to gate the tool list, `permission_request` to apply the per-role permission map, and `before_agent_start` to append the role's `system-prompt`. Built-in defaults cover Anthropic Sonnet / Haiku / Opus; users override via settings.

### `lead` — delegation-only primary

`/role lead` turns the main thread into an orchestrator: it keeps `glob`, `grep`,
`ls`, `think`, `web_search` and `subagent__subagent` (the subagent tool's registry name), and loses `read`, `edit`, `write` and
`bash`. Every read-in-depth goes to a `scout` subagent and every change to a
`worker`, so only their reports enter the lead's context — the saving on a long
run is the children's cheaper models plus a primary transcript that never holds
a file. Model-less: it inherits whatever model is active. Its prompt tells the
model to write self-contained tasks (subagents have no memory of the
conversation), pass `steps` sized to the task, and never hand two subagents the
same file. Edits need the `worker` subagent role enabled:

```json
{ "roles": { "worker": { "enabled": true } } }
```

Borrowed from [apprentice](https://github.com/skarnati20/apprentice)'s loop of the
same shape. Its "escalate to the full tool kit after N subagent calls" was left
out: `/role reset` is the escalation.

## Commands

| Command | What it does |
|---|---|
| `/model-roles__escalate` | `[/escalate]` — bare, it reports tier, target, budget and failover chain, plus the usage line. `/escalate now` hands the current task to the stronger model from a pruned context; `/escalate off` reverts and disarms for the session; `/escalate status` is the same report as bare |
| `/model-roles__role` | `[/role <name>]` — switch the active role. With no arg, shows the current role and the list. `/role reset` reverts to `default` |
| `/model-roles__roles` | List every available role |
| `/model-roles__planmode` | Enter plan mode: bind the `plan` role (read-only tools, write/edit/bash denied), capture the plan, and hand off to execution on exit |
| `/model-roles__mode` | `[/mode <name>]` — switch nyma's permission mode (`default`, `accept-edits`, `plan`, `full-auto`) or `cycle`. `/mode plan` enters native plan mode. With no arg, lists each mode's write/exec/network policy |

There is no `/plan`. Native plan mode is `/planmode` (or `/mode plan`); the ACP
agent's own plan mode is `/agent mode plan`. Both extensions used to register
`/plan` behind mirror-image guards, so which one you got depended on load
order — and two registrations made the resolver return nothing at all.

## Hooks

| Event | Behaviour |
|---|---|
| `model_resolve` | Override the model for the current turn based on the active role (escalation overrides it again at priority -20) |
| `provider_error` | Availability failover — 429/quota/5xx/network/model-not-found takes the next model in the chain and retries the same turn |
| `turn_finalize` | Stall detection — 3+ no-op turns *with a task in flight*, or the verify gate running out of fix attempts |
| `tool_access_check` | Restrict the tool list to `allowed-tools` from the active role config |
| `permission_request` | Apply the role's per-tool `permissions` map (allow / deny / ask) |
| `before_agent_start` | Append the active role's `system-prompt`, if it has one (`lead`; any subagent role switched to with `/role`) |

## Settings

Read from `~/.nyma/settings.json` or `.nyma/settings.json`:

```json
{
  "roles": {
    "default": { "provider": "anthropic", "model": "claude-sonnet-4-5" },
    "fast":    { "provider": "anthropic", "model": "claude-haiku-4-5" },
    "deep":    { "provider": "anthropic", "model": "claude-opus-4-6",
                 "allowed-tools": ["read", "grep", "ls", "think"] },
    "plan":    { "provider": "anthropic", "model": "claude-opus-4-6",
                 "allowed-tools": ["read", "grep", "ls", "glob", "think"],
                 "permissions": { "bash": "deny", "write": "deny", "edit": "deny" } },
    "commit":  { "provider": "anthropic", "model": "claude-sonnet-4-5",
                 "allowed-tools": ["read", "grep", "ls", "bash"] }
  }
}
```

| Key | Type | Description |
|---|---|---|
| `roles` | map | Role name → `{provider?, model?, allowed-tools?, permissions?, system-prompt?}`. A role with no model inherits the active one (`lead`); a role with a `policy` and no model is a permission mode |
| `escalate` | map | Escalation: `mode` (`ask`/`auto`/`off`), `to` (role or `provider/model`), `on`, `prune`, `revert`, `max-per-session`, `fallback` |
| `cycle-key` | string | Key that cycles to the next role (one-keystroke model switching). Default `ctrl+g`; set `""` to disable. Avoid `ctrl+r` — `prompt_history` owns it. |

If `roles` is absent, a built-in fallback set is used.

## Capabilities

`events`, `commands`, `model`, `state`, `ui`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — `model_resolve` and `tool_access_check` hook docs

## Escalation

Two different failures, two different answers:

- **The cheap model is stuck** — 3+ turns with no tool call while a task is in flight, or the verify
  gate out of fix attempts. nyma asks (default `mode: "ask"`), then prunes the stalled span and
  re-runs the request on `escalate.to`. Pruning matters: a failed attempt left in context roughly
  7× the error rate of a fresh one. The stalled span stays in the session JSONL, so `/refine` can
  still mine it, and files it edited are untouched — `/rewind` is separate and yours.
- **The provider said no** — 429, quota, 5xx, network, model-not-found. No prompt: it takes the next
  entry in `fallback` and retries the same turn.

It overrides the **model**, never the active role, so tool access and permissions are unchanged. A
manual `/role` always wins. Escalation lasts until your next message (`revert: "next-request"`); the
status bar shows `⚡ <model>` while it does.

```jsonc
{ "escalate": {
    "mode": "ask",              // ask | auto | off (off = failover only)
    "to": "advisor",            // role name or "provider/model"
    "on": { "no-op-turns": 3, "verify-exhausted": true },
    "max-per-session": 2,
    "fallback": { "default": ["build", "fast"], "cooldown-ms": 300000 } } }
```

Escalation is the *control* tier. For the *advice* tier — a stronger model reviewing without taking
over — use `/advisor`, which is a separate extension and unaffected by this.
