# Extension & Feature Ideas

Organized by status and implementation priority (feasibility × impact).

---

## Already Implemented

| Feature | Location | Commands / Tools |
|---|---|---|
| **Model Roles** | `extensions/model_roles/` | `/role`, `/roles` |
| **Prompt History + Ctrl+R** | `extensions/prompt_history/` | `/history` |
| **Stats Dashboard** | `extensions/stats_dashboard/` | `/stats`, `/stats-session` |
| **AST Tools (ast-grep)** | `extensions/ast_tools/` | tools: `ast_grep`, `ast_edit` |
| **Token Cost Preview** | `extensions/token_suite/token_preview.cljs` | `/token-preview` |
| **MCP Bridge** | `extensions/agent_shell/features/mcp_discovery.cljs` | `/mcp list`, `/mcp refresh` |
| **Workspace Config + Aliases** | `extensions/workspace_config/` | `/alias`, `/reload` |
| **Thinking Renderer** | `extensions/token_suite/` (via stream events) | — |
| **Context Compression** | `commands/builtins.cljs:363` + token_suite | `/compact` (auto + manual) |
| **Questionnaire Tool** | `extensions/questionnaire/` | tool: `questionnaire` |
| **Background Jobs** | `extensions/bash_suite/background_jobs.cljs` | tools: `list_jobs`, `job_output`, `kill_job` |
| **Desktop Notify** | `extensions/desktop_notify/` | — |
| **Bash Output Handling** | `extensions/bash_suite/output_handling.cljs` | tool: `retrieve_bash_output` |
| **Mention Files** | `extensions/mention_files/` | `@file` in prompts |
| **Custom Provider (Qwen)** | `extensions/custom_provider_qwen_cli/` | — |

---

## Quick Wins — Low Effort, Clear ROI

### 1. Grouped Tool Display

Consecutive tool calls of the same type (multiple reads, multiple writes) rendered as a compact file tree instead of N separate full-width blocks. Same information, dramatically less scroll.

**Where**: `src/agent/ui/` — chat rendering; no new API surface needed.
**Capabilities**: `ui`
**Effort**: ~1 day — pure rendering change, no state or events required.

---

### 2. Desktop Notify — Batched Digest

`desktop_notify` currently fires one macOS notification per tool event. High-activity sessions produce notification storms.

Use `make-reminder` (from `src/agent/middleware/self_reminder.cljs`) to buffer events and flush a single digest every N turns instead of one notification per event.

**Where**: `extensions/desktop_notify/index.cljs`
**Capabilities**: `events` (already has it)
**Effort**: ~half day — make-reminder handles counting; only the buffer + formatter is new.

---

### 3. `/compress` Explicit Command

`/compact` (built-in) already exists and calls `compact-session!`. This is just a `/compress` alias that also accepts an optional "focus hint" string passed to the LLM summarizer, so the compacted context emphasizes a particular task thread.

**Where**: `src/agent/commands/builtins.cljs` — add alongside existing `/compact`.
**Capabilities**: core commands
**Effort**: ~2 hours — thin wrapper with optional arg forwarding.

---

### 4. File Watcher

Watch specified directories for external file changes and inject a steer message when files change. Useful for TDD workflows — agent sees test results in real-time without polling.

**Where**: new `extensions/file_watcher/`
**Capabilities**: `events`, `messages`, `exec`
**Commands**: `/watch <dir>`, `/unwatch`
**Effort**: ~half day — `fs.watch` + event injection.

---

### 5. Workspace Config — Guided Alias Wizard

`/alias <name> <target>` requires knowing the syntax. Add `/alias --new` that walks through alias creation with `api.ui.input` / `api.ui.select` prompts: name → target → confirm → persist to `.nyma/settings.json`.

**Where**: `extensions/workspace_config/aliases.cljs`
**Capabilities**: add `"ui"` to existing extension; no new API needed.
**Effort**: ~2-3 hours — chain 3 async UI calls + persist.

---

### 6. Agent Telemetry Dashboard (enhanced)

The existing `/stats` shows session-level stats. This extends it to track cost/tokens **per ACP agent** across sessions — useful when running multi-agent pipelines. Listens to `acp_usage` events, accumulates in SQLite alongside existing session data.

**Where**: `extensions/stats_dashboard/` — additive columns
**Capabilities**: `events`, `state` (already has both)
**Effort**: ~half day — new event listener + 2 SQLite columns.

---

### 7. Snippet Library

Save and retrieve code snippets across sessions. `/snippet save <name>` stores clipboard/selection; `/snippet get <name>` retrieves; tool `snippet_search` lets the LLM find relevant snippets by description.

**Where**: new `extensions/snippets/`
**Capabilities**: `commands`, `state`, `tools`
**Effort**: ~1 day — simple key-value store + 3 commands + 1 search tool.

---

## High Impact, Medium Effort

### 8. Checkpoint System

Git-based snapshots after every file-modifying tool call. Commands: `/checkpoint list`, `/checkpoint revert <id>`, `/checkpoint diff <id>`. Subscribes to `tool_complete` event (already emitted). Enables safe exploration, non-destructive undo, branch-from-checkpoint.

**Where**: new `extensions/checkpoint/`
**Capabilities**: `events` (`tool_complete`), `exec` (git), `commands`
**Effort**: ~2 days — the hook is ready; the work is git stash/branch management.

**Prior art**: dirac's `ContextManager` keys message-log updates by `(outerIndex, innerIndex, timestamp)` so **truncations and compactions are reversible**, not just file writes. Pairs naturally with this item — `/checkpoint revert` should be able to undo a `/compact` call, not only a tool execution. See `src/core/context/context-management/ContextManager.ts` in the dirac repo.

---

### 9. Code Review Command

`/review` with mode selection (branch diff, uncommitted changes, specific commit SHA). Produces structured findings with priority levels (critical/major/minor). The model uses existing read/bash tools; this just provides the structured prompt + output format.

**Where**: new `extensions/code_review/` or agent_shell feature
**Capabilities**: `commands`, `exec`
**Effort**: ~1-2 days — mostly prompt engineering + output formatting.

---

### 10. Git Suite

Full git workflow automation: context provider injects branch/status/recent commits; `/commit` generates commit message from staged diff; `/pr` creates PR with auto-generated title/body; tool result hook detects file changes and suggests staging.

**Where**: new `extensions/git_suite/`
**Capabilities**: `tools`, `commands`, `context`, `events`, `exec`
**Effort**: ~3-4 days — multiple interacting components.

---

### 11. Approval Profiles

Named permission sets that replace per-tool approval prompts. `/profile safe` (reads OK, writes prompt, network blocked), `/profile auto` (approve all), `/profile custom` (user-defined rules). Middleware intercepts `before_tool_call`, checks active profile, returns `{:skip? true :result "..."}` for auto-denies.

**Where**: new `extensions/approval_profiles/` + `src/agent/middleware.cljs` (minor)
**Capabilities**: `middleware`, `flags`, `commands`, `state`
**Effort**: ~2 days — middleware integration + profile persistence.
**Prior art**: dirac's CLI `-y` flag (Yolo mode) auto-approves all tools — the simplest single-point profile on the spectrum this extension would offer. See also item #27 (Plan/Act Mode) which references the same `-y` flag for its CLI layer.

---

### 12. Project Memory

Persistent project knowledge base. `/remember <fact>` stores structured facts; `/forget <pattern>` removes; context provider injects relevant memories based on current task. Auto-extraction from session end is optional.

**Where**: new `extensions/project_memory/`
**Capabilities**: `context`, `events`, `state`, `commands`
**Effort**: ~2-3 days — context provider integration is the complex part.

---

### 13. TTSR — Stream Filter Rules

Zero-context-cost rules that watch the LLM output stream via regex. On match: abort stream, inject rule as system message, retry. Most innovative pattern from oh-my-pi. Requires a new `stream_filter` event in `loop.cljs`.

**Where**: new `extensions/ttsr/` + `src/agent/loop.cljs` (add stream_filter emit)
**Capabilities**: `events`, `middleware`
**Effort**: ~3 days — the loop change is the risky part; extension itself is simple once the hook exists.

---

### 14. `/mcp auth` — MCP Server Authentication

Adds credential management for MCP servers that require auth (GitHub, Jira, Linear). Extends the existing `/mcp` command with an `auth` subcommand that walks through OAuth or API-key flows via `api.ui.input`.

**Where**: `extensions/agent_shell/features/mcp_discovery.cljs`
**Capabilities**: `commands`, `ui`, `state` (already has all)
**Effort**: ~1 day — depends on what auth protocols the target servers use.

---

### 15. Tree-sitter WASM — Portable AST Tools

`ast_tools` silently no-ops when `sg` binary is not installed. A WASM fallback (via `tree-sitter` npm package) makes `ast_grep`/`ast_edit` portable. Strategy: check `sg` availability at activation; fall back to WASM execute path.

**Where**: `extensions/ast_tools/index.cljs` — add WASM code path
**Capabilities**: `tools` (already)
**Effort**: ~2-3 days — WASM loading, grammar management per language, output normalization.

---

### 16. AI Git Commits

Agentic commit flow with hunk-level staging. Tools: `git_overview`, `git_file_diff`, `git_hunk`. Auto-splits unrelated changes with dependency ordering. More sophisticated than the simple `/commit` in Git Suite above.

**Where**: new `extensions/git_suite/` (or extend it)
**Capabilities**: `tools`, `commands`, `exec`
**Effort**: ~3-4 days — hunk-level staging logic is non-trivial.

---

### 17. Workspace Snapshots

Save and restore full working state: git branch + stash, session name, active agent. `/snapshot save <name>`, `/snapshot restore <name>`, `/snapshot list`. Useful for parallel task switching without losing context.

**Where**: new `extensions/workspace_snapshots/`
**Capabilities**: `commands`, `exec`, `state`, `session`
**Effort**: ~2 days — git stash interaction + session state capture.

---

### 18. Todo / Task Panel

Phased task list with visual panel above the editor (Ctrl+T toggle). Auto-normalizes so exactly one task is in-progress. Completion reminders via `make-reminder`. Complements (doesn't replace) the existing `TodoWrite` core tool.

**Where**: new `extensions/todo_panel/`
**Capabilities**: `ui`, `events`, `state`, `commands`
**Effort**: ~3 days — Ink panel component + reminder integration.

---

## Long-term / High Effort

### 19. Universal Config Discovery

Reads configs from Claude Code (`.claude/`), Cursor (`.cursor/`), Windsurf, Gemini, Codex, Cline, GitHub Copilot. Discovers MCP servers, rules, and context files. Merges into nyma's context on startup.

**Effort**: ~3-5 days — wide format surface, many edge cases.

---

### 20. Multi-Credential Round-Robin

Multiple API keys per provider with usage-aware selection and automatic fallback on rate limits. Transparent to the rest of the system.

**Effort**: ~3 days — provider layer change + key rotation logic.

---

### 21. Autonomous Memory Pipeline

Background extraction of durable knowledge from past sessions. Runs post-session, consolidates into project memory and skill playbooks. Feeds into Project Memory (item 12).

**Effort**: ~4 days — async post-session pipeline, LLM extraction prompts, dedup.

---

### 22. Native Subagent System

A `task` tool that spawns child agent sessions with isolated context, restricted tools, and token budget limits. Parent receives summarized results. Enables orchestrator mode and parallel specialist delegation.

**Effort**: ~1-2 weeks — new session management subsystem.

**Prior art — concrete design points from dirac (`src/core/task/tools/subagent/`):**
- **Parallel cap of 5** in one call (`use_subagents` with `prompt_1..prompt_5`).
- **Default read-only tool set** for children: `FILE_READ, LIST_FILES, SEARCH, BASH, USE_SKILL, ATTEMPT`.
- **System-prompt suffix** appended to child instructing read-only bash + concise findings + a mandatory `Relevant file paths` section at the end.
- **No nesting** — subagents cannot spawn subagents (`contextRequirements: (ctx) => ctx.subagentsEnabled && !ctx.isSubagentRun`).
- **Per-subagent stats** (tool calls, tokens, cost, context usage %) surfaced back to the parent.
- **Custom subagents as dynamic tool names** — markdown files at `~/.nyma/agents/*.md` with YAML frontmatter (name, description, allowed-tools, model, system-prompt) each become their own tool the main loop can call by name. Hot-reloaded via a file watcher. This lets users ship `code-reviewer.md` that registers as a `code_reviewer` tool distinct from the generic subagent tool.

---

### 23. Python / IPython Tool

Persistent IPython kernel with streaming output, rich rendering (HTML, images, Mermaid), and custom module loading.

**Effort**: ~1 week — kernel lifecycle, output rendering, security boundary.

---

### 24. Browser Tool (Puppeteer)

Headless browser with stealth scripts, accessibility snapshots, article extraction. Navigate, click, type, screenshot, extract.

**Effort**: ~1 week — Puppeteer integration, snapshot normalization, security.

---

### 25. LSP Integration ✓ shipped as `lsp_suite`

Full language server protocol: diagnostics, go-to-definition, references, hover, rename, format-on-write. Six built-in language configurations (TypeScript, Python, Rust, Go, Clojure, Ruby) with user-overridable catalog.

**Shipped**: `src/agent/extensions/lsp_suite/` — see its README for setup and tool reference.

---

### 26. Hashline Edits

Content-hash anchors per line — model references anchors instead of reproducing text. Up to 10x edit accuracy for weaker models. Requires a fundamental change to the edit tool protocol.

**Effort**: ~1 week — protocol change propagates through all file-editing tools.

**Prior art**: dirac's `edit_file` tool uses anchors of shape `AppleBanana|=|<line content>` where the prefix is an opaque hash and the line content is the current text. The backend validates the hash still matches before applying. Supports `replace`, `insert_after`, `insert_before`, and batches non-overlapping edits across multiple files in one tool call. Reference implementation at `src/core/task/tools/handlers/edit-file/{BatchProcessor,EditExecutor,EditFormatter}.ts`.

---

## From caveman / dirac prior art

Items identified in the caveman/dirac research pass (`/Users/vihangd/.claude/plans/dynamic-bouncing-aho.md`). The top-five Tier 1 items (structured `/compact`, validator loop, PreCompact dump, long-running bash timeouts, deep bash parser) have a dedicated plan at `plan-borrows-caveman-dirac.md`. Infrastructure-level borrows (hook templates, streaming hook output, SQLite lock manager, etc.) live in `roadmap.md` §5. Everything user-facing lives below — efforts vary from ~2 hours to ~4 days, so these are not all "long-term."

### 27. Plan / Act Mode Toggle

First-class `/mode plan` and `/mode act` commands that gate which tools are allowed. Plan mode uses a dedicated `plan_mode_respond` tool (no real file writes, no bash side effects); Act mode restores the full tool set. CLI flags `-p` (start in plan mode) and `-y` (yolo — auto-approve all in act mode).

Builds on nyma's existing `:plan` role (already has `:allowed-tools` + `:permissions` seeded in `settings/manager.cljs:roles.plan`) and the modes work in `docs/plan-extension-improvements.md`.

**Where**: new `extensions/plan_act_mode/` + small changes to `cli.cljs` for the flags.
**Capabilities**: `commands`, `middleware` (tool gating), `flags`
**Effort**: ~2 days.

**Prior art**: dirac's `togglePlanActModeProto.ts` + `mode-selection.ts`. CLI flags at `cli/src/index.ts`.

---

### 28. Workflows Directory (dynamic slash commands)

Markdown files in `~/.nyma/workflows/*.md` and `.nyma/workflows/*.md` become dynamic slash commands. `/<filename>` injects the file body as the user message, with `{{args}}` templating. Workspace-scoped toggles via settings.

Subsumes item #7 (Snippet Library) — a workflow file _is_ a saved prompt.

**Where**: new `extensions/workflows/` (or fold into existing skills infra since the loading pattern is identical).
**Capabilities**: `commands`, `state`
**Effort**: ~1 day — mostly file-watching + command registration; parallels the existing skills system.

**Prior art**: dirac's `src/core/context/instructions/user-instructions/workflows.ts` and the workflow toggle controllers.

---

### 29. Skill Activation — Trigger Phrases + Level Filtering

Two small additions to the existing skills system:

1. **Trigger phrases**: add a `triggers: [...]` array to skill frontmatter. On `input_submit`, any skill whose triggers match the user's prompt is auto-activated for that turn. Enables conversational skill invocation ("less tokens please" → activate terse skill) without forcing a `/skill` command.
2. **Level filtering**: a skill can ship multiple intensities (e.g. `lite / full / ultra`). Frontmatter declares `levels:` and each injection filters to only the active level's sections, cutting context cost when skills grow.

Companion to both: encourage skill authors to declare `overrides:` (contexts where the skill's rule temporarily suspends — e.g. "security warnings, commit messages") and `boundaries:` (contexts where the rule NEVER applies — e.g. "code blocks, diffs") as optional frontmatter fields. These are **authoring conventions**, not code — document them in `docs/extension-guide-cljs.md` and the skill frontmatter spec.

**Where**: `src/agent/skills/*.cljs` (or wherever skills are loaded).
**Capabilities**: `events` (input_submit), `skills`
**Effort**: ~1 day for triggers + levels; docs-only for overrides/boundaries.

**Prior art**: caveman's `SKILL.md` frontmatter + `hooks/caveman-activate.js` (filtered injection pattern) and "Auto-Clarity" / "Boundaries" rule sections.

---

### 30. Git Worktree Controllers

New `extensions/git_worktree/` with commands `/worktree new <branch>`, `/worktree switch <branch>`, `/worktree merge <branch>`, `/worktree list`, `/worktree remove <branch>`. Enables running multiple nyma sessions in isolated branch-worktrees for parallel feature work.

**Where**: new `extensions/git_worktree/`
**Capabilities**: `commands`, `exec`, `session`
**Effort**: ~3-4 days.

**Prior art**: dirac's `src/core/controller/worktree/` — full suite of `createWorktree`, `switchWorktree`, `mergeWorktree`, `listWorktrees`, `getAvailableBranches`, `checkoutBranch` controllers with a dedicated sidebar UI.

---

### 31. Per-Model System-Prompt Variants

Today nyma builds one system prompt for all models. Models vary in quirks (Anthropic vs Gemini vs GPT vs reasoning models like o-series), and a single prompt is a lowest-common-denominator compromise. Introduce a `PromptBuilder` that selects a variant based on `providerInfo` at generate time, with snapshot tests per variant so prompt changes are visible in diffs.

Start with two variants (anthropic + generic) to validate the architecture, expand as quirks surface.

Unblocks: a `/deep-planning` command with per-model variants (see item #32).

**Where**: `src/agent/prompts/` (new dir) + consumers in `loop.cljs` / `core.cljs`.
**Capabilities**: `prompts` (new surface)
**Effort**: ~2-3 days for infrastructure; ongoing for variants.

**Prior art**: dirac's `src/core/prompts/system-prompt/` with `PromptBuilder`, `PromptRegistry`, and per-provider snapshot tests under `__snapshots__/anthropic_claude_4_5_sonnet-basic.snap`, `gemini_gemini_3`, `openai_gpt_5_1`, etc.

---

### 32. `/deep-planning` Command with Per-Model Variants

A meta-command for "plan before executing" — forces the model into a plan-first mode that produces a structured outline before touching any tools. Registry of per-model variants (`variants/anthropic.ts`, `variants/gemini.ts`, etc.) selected at invocation time based on current provider.

Depends on #31 (variant infrastructure) and pairs with #27 (plan/act mode).

**Where**: new `extensions/deep_planning/` or as a builtin command.
**Capabilities**: `commands`, `prompts`
**Effort**: ~2 days after #31 lands.

**Prior art**: dirac's `src/core/prompts/commands/deep-planning/` with `variants/` registry.

---

### 33. Piped Stdin Initial Prompt

Support `git diff | nyma "review this"` — when stdin is not a TTY at startup, read it and prepend to the initial prompt. Common pattern in unix tooling; nyma currently ignores piped input (verified: no `stdin`/`pipe` references in `src/agent/cli.cljs`).

**Where**: `src/agent/cli.cljs` — small addition at startup.
**Capabilities**: core CLI
**Effort**: ~2 hours.

**Prior art**: dirac's `cli/src/piped.ts`.

---

## Deferred / Low Priority

- **i18n / multi-language UI** — low demand, high surface area
- **React DevTools integration** — debug-only, not user-facing
- **Welcome message randomizer** — minor UX, one-off
- **First-run wizard** — nice-to-have, not blocking anything
