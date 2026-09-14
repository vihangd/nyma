# model-roles

> Named model presets — `default`, `fast`, `deep`, `plan`, `commit` — for one-keystroke model switching.

## What it does

Defines named **roles** that map to provider/model pairs (and optionally `allowed-tools` + per-tool `permissions`). At runtime, `model_roles` hooks `model_resolve` to swap in the role's model, `tool_access_check` to gate the tool list, and `permission_request` to apply the per-role permission map. Built-in defaults cover Anthropic Sonnet / Haiku / Opus; users override via settings.

## Commands

| Command | What it does |
|---|---|
| `/model-roles__escalate` | `[/escalate]` — hand the current task to the stronger model, from a pruned context. `/escalate off` reverts and disarms for the session; `/escalate status` shows tier, budget and cooldown |
| `/model-roles__role` | `[/role <name>]` — switch the active role. With no arg, shows the current role and the list. `/role reset` reverts to `default` |
| `/model-roles__roles` | List every available role |
| `/model-roles__planmode` | Enter plan mode: bind the `plan` role (read-only tools, write/edit/bash denied), capture the plan, and hand off to execution on exit |
| `/model-roles__plan` | Same as `/planmode`; registered only when no other extension (e.g. the ACP agent shell's mode switcher) already owns `/plan` |

## Hooks

| Event | Behaviour |
|---|---|
| `model_resolve` | Override the model for the current turn based on the active role (escalation overrides it again at priority -20) |
| `provider_error` | Availability failover — 429/quota/5xx/network/model-not-found takes the next model in the chain and retries the same turn |
| `turn_finalize` | Stall detection — 3+ no-op turns *with a task in flight*, or the verify gate running out of fix attempts |
| `tool_access_check` | Restrict the tool list to `allowed-tools` from the active role config |
| `permission_request` | Apply the role's per-tool `permissions` map (allow / deny / ask) |

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
| `roles` | map | Role name → `{provider, model, allowed-tools?, permissions?}` |
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
