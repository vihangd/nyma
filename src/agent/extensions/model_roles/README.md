# model-roles

> Named model presets — `default`, `fast`, `deep`, `plan`, `commit` — for one-keystroke model switching.

## What it does

Defines named **roles** that map to provider/model pairs (and optionally `allowed-tools` + per-tool `permissions`). At runtime, `model_roles` hooks `model_resolve` to swap in the role's model, `tool_access_check` to gate the tool list, and `permission_request` to apply the per-role permission map. Built-in defaults cover Anthropic Sonnet / Haiku / Opus; users override via settings.

## Commands

| Command | What it does |
|---|---|
| `/model-roles__role` | `[/role <name>]` — switch the active role. With no arg, shows the current role and the list. `/role reset` reverts to `default` |
| `/model-roles__roles` | List every available role |

## Hooks

| Event | Behaviour |
|---|---|
| `model_resolve` | Override the model for the current turn based on the active role |
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

If `roles` is absent, a built-in fallback set is used.

## Capabilities

`events`, `commands`, `model`, `state`, `ui`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — `model_resolve` and `tool_access_check` hook docs
