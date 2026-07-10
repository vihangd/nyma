# memory

Persistent, agent-maintained memory — the *learned* layer, complementing AGENTS.md/CLAUDE.md (the
human layer nyma already loads).

## What it does

- **Reads** `<cwd>/.nyma/memory/MEMORY.md` (project) + `~/.nyma/memory/MEMORY.md` (global) and injects
  them into the system prompt each run, **size-capped** to `max-lines` (default 200).
- **Writes** via two tools the agent calls to curate the file:
  - `memory_write {key, value}` — add/replace a `## key` block.
  - `memory_forget {key}` — remove a block.

## Why the cap

SOTA on agent memory (2026): memory improves long-task consistency *substantially* (constraint
compliance drops 73%→33% over a long task without it) — **but "store everything" causes memory rot**
(context degradation past ~70-80% capacity). So the injected view is capped and the agent writes
*selectively*. Keep MEMORY.md small and high-signal; consolidate periodically.

## Config (`.nyma/settings.json#memory`)

```json
{ "memory": { "dir": "memory", "max-lines": 200 } }
```

Always on; a no-op when no MEMORY.md exists.
