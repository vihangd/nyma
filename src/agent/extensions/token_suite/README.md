# token-suite

> Six-module token-optimization suite — KV cache, smart compaction, repo map, structured context, priority assembly, and fuzzy multi-edit.

## What it does

The most ambitious extension in the tree. `token-suite` is a *suite of suites* — six cooperating sub-modules that each save tokens in a different way. Together they let nyma run usefully on small context windows and stay efficient on large ones. The shared `:tokens-saved` counter is exposed through `/token-stats`.

The single hard rule: when context usage crosses **80%**, the suite disables expensive tools (`web_fetch`, `web_search`) via `tool_access_check` so the next turn can't blow past the budget.

## Commands

| Command | What it does |
|---|---|
| `/token-suite__token-stats` | Show per-session optimization stats — observations masked, cache hits, tokens saved, etc. |
| `/token-suite__token-preview` | Preview live token counts in the editor |

## Tools

| Tool | What it does |
|---|---|
| `multi_edit` | Apply multiple code edits in one call via fuzzy / diff-stat heuristics |
| `context_files` | Suggest files to read based on what the agent is currently working on |

## Hooks

| Event | Sub-module | Behaviour |
|---|---|---|
| `tool_access_check` | suite | Disable expensive tools when context > 80% full |
| `before_provider_request` | `kv_cache` | Hash the request, look up cache |
| `after_provider_request` | `kv_cache`, `smart_compaction` | Cache store, background compaction |
| `context_assembly` | `priority_assembly` | Drop low-priority messages when over budget |
| `before_agent_start` | `structured_context` | Discover hot/warm files |
| `before_compact` | `smart_compaction` | Pre-compact archive of dropped messages |
| `tool_execution_end` | `repo_map`, `structured_context` | Re-index the repo, refresh the hot file set |
| `editor_change` | `token_preview` | Live token count in the editor footer |
| `agent_end`, `turn_end`, `agent_start`, `before_provider_request`, `after_provider_request` | `idle_compaction` | Arm, learn from and cancel the idle-compaction timers |

## Sub-modules

| File | Role |
|---|---|
| `kv_cache.cljs` | Memoize provider requests by content hash |
| `priority_assembly.cljs` | Drop low-priority messages when the context is over budget |
| `repo_map.cljs` | Index project files + symbols (used by `structured_context` and `mention_files`) |
| `diff_edit.cljs` | Fuzzy-matched multi-edit application via `multi_edit` |
| `structured_context.cljs` | Discover hot / warm / cold file sets (`context_files` tool) |
| `smart_compaction.cljs` | Background compaction with archive + re-read detection |
| `token_preview.cljs` | Live editor-footer token count |
| `idle_compaction.cljs` | Summarise an idle session on its warm prompt cache; apply the summary only once the cache has expired |

## Idle compaction

A long session left idle comes back to a cold prompt cache, and the next request
pays for the whole prefix again (1.25-2x on Anthropic, which charges for cache
writes). At 90% of the time the cache stays warm, `idle_compaction` builds the
summary on the request prefix, which costs a cache read. It applies the summary
only after the cache has expired. A user who comes back sooner keeps the full
conversation.

How long the cache lasts depends on the provider:
- **Anthropic dialect:** `kv-cache.cache-ttl`, 5m by default.
- **A settings profile:** you can set this per provider.
- **Anything else priced:** learned from the gaps that did and did not read the
  cache. z.ai (GLM 5.3 Flash, 2026-11) read the whole prefix after 8 minutes
  idle and none of it after 12, so idle compaction there learns to summarise at
  about 9 minutes.
- **DeepSeek, Groq and unpriced (local) models:** never compacted this way.

```json
{"token-suite": {"idle-compaction": {
  "enabled": true, "min-tokens": 100000, "fire-at": 0.9,
  "profiles": {"my-gateway": {"ttl-s": 300}, "openlux-kiro": {"class": "none"}}}}}
```

## Capabilities

`events`, `context`, `tools`, `middleware`, `commands`, `ui`, `state`, `session`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
- [`docs/roadmap.md`](../../../../docs/roadmap.md) — how the suite evolved
