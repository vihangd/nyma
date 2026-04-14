# workspace-config

> Loads `.nyma/settings.json` for per-project command aliases and flags.

## What it does

At activation, reads the **project-level** `.nyma/settings.json` and registers any custom command aliases plus pre-defined flag values. Aliases let a project define short slash-commands like `/cc` → `/model claude-sonnet-4-6` so common ops can fit in muscle memory. The `/alias` command lets the user create / remove / list aliases at runtime; `/reload` re-reads the settings file without restarting the daemon.

Aliases are **late-binding** — they resolve to the target command at invocation time, so an alias never gets cached pointing at a stale value. They cannot **shadow built-in commands** — if a built-in already owns the name, the alias is rejected with a warning.

## Commands

| Command | What it does |
|---|---|
| `/workspace-config__reload` | Re-read `.nyma/settings.json` and re-register aliases / flags |
| `/workspace-config__alias` | Create, remove, or list workspace aliases. Usage: `/alias <name> <target-command>` |

## Settings

Read from project-level `.nyma/settings.json`:

```json
{
  "aliases": {
    "cc":  "/model claude-sonnet-4-6",
    "bye": "/exit",
    "lf":  "/role fast"
  },
  "flags": {
    "desktop-notify__enabled": true
  }
}
```

| Key | Type | Description |
|---|---|---|
| `aliases` | map | `alias-name` → `target-command-string`. Cannot shadow built-ins |
| `flags` | map | `flag-name` → value. Pre-populates extension flags at startup |

## Sub-modules

| File | Role |
|---|---|
| `aliases.cljs` | `/alias` command — create / remove / list, with shadowing guard |

## Capabilities

`commands`, `flags`, `events`, `state`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — flag registration section
- [`README.md`](../../../../README.md#workspace-config) — top-level docs reference
