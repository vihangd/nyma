# token-suite

> Eleven-module token-optimization suite — observation masking, KV cache, smart compaction, tool truncation, repo map, structured context, and more.

## What it does

The most ambitious extension in the tree. `token-suite` is a *suite of suites* — eleven cooperating sub-modules that each save tokens in a different way. Together they let nyma run usefully on small context windows and stay efficient on large ones. The shared `:tokens-saved` counter is exposed through `/token-stats`.

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
| `start_focus` | Begin an exploratory work session — messages can be folded back later |
| `complete_focus` | End the focus session, fold messages, return a summary |
| `retrieve_archived` | Pull back messages that smart-compaction archived |
| `context_files` | Suggest files to read based on what the agent is currently working on |

## Hooks

| Event | Sub-module | Behaviour |
|---|---|---|
| `tool_access_check` | suite | Disable expensive tools when context > 80% full |
| `before_provider_request` | `kv_cache` | Hash the request, look up cache |
| `after_provider_request` | `kv_cache`, `smart_compaction`, `expired_context` | Cache store, background compaction, prune stale results |
| `context_assembly` | `observation_mask`, `priority_assembly`, `smart_compaction` | Mask, prune, restructure messages |
| `before_agent_start` | `structured_context`, `context_folding` | Discover hot/warm files; inject focus instructions |
| `before_compact` | `smart_compaction` | Pre-compact archive of dropped messages |
| `tool_execution_end` | `repo_map`, `structured_context`, `expired_context`, `smart_compaction` | Re-index the repo, refresh hot file set, mark stale tool results |
| `editor_change` | `token_preview` | Live token count in the editor footer |

## Sub-modules

| File | Role |
|---|---|
| `tool_truncation.cljs` | Truncate oversized tool outputs to a per-tool policy |
| `observation_mask.cljs` | Mask non-essential message parts (read results, etc.) once they're stale |
| `expired_context.cljs` | Prune tool results the model is unlikely to re-read |
| `kv_cache.cljs` | Memoize provider requests by content hash |
| `priority_assembly.cljs` | Drop low-priority messages when the context is over budget |
| `repo_map.cljs` | Index project files + symbols (used by `structured_context` and `mention_files`) |
| `diff_edit.cljs` | Fuzzy-matched multi-edit application via `multi_edit` |
| `structured_context.cljs` | Discover hot / warm / cold file sets (`context_files` tool) |
| `smart_compaction.cljs` | Background compaction with archive + re-read detection |
| `context_folding.cljs` | Focus sessions — `start_focus` / `complete_focus` fold scoped work |
| `token_preview.cljs` | Live editor-footer token count |

## Capabilities

`events`, `context`, `tools`, `middleware`, `commands`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
- [`docs/roadmap.md`](../../../../docs/roadmap.md) — how the suite evolved
