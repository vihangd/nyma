# Extension Ideas for Nyma

Community extension ideas organized by complexity and the API capabilities they use.

---

## Git Suite

Automated git workflows: commit suggestions, branch awareness, PR creation, diff-aware context injection.

**Capabilities**: `tools`, `commands`, `context`, `events`, `exec`

**Structure**:
```
~/.nyma/extensions/git-suite/
  extension.json    # capabilities: ["tools", "commands", "context", "events"]
  index.cljs        # entry point
  git-context.cljs  # registerContextProvider — inject branch/status/recent commits
  git-tools.cljs    # registerTool — smart-commit, create-pr, stash-and-switch
  git-hooks.cljs    # on("tool_result") — detect file changes, suggest commits
```

**Key features**:
- Context provider injects current branch, uncommitted changes, recent commit log
- `/commit` command generates commit message from staged diff
- `/pr` command creates PR with auto-generated title/body
- Tool result hook detects when files are modified and suggests staging

**Complexity**: Medium

---

## Agent Telemetry Dashboard

Real-time cost/usage visualization across agents with historical tracking.

**Capabilities**: `events`, `ui`, `state`, `commands`

**Structure**:
```
~/.nyma/extensions/telemetry/
  extension.json    # capabilities: ["events", "ui", "state", "commands"]
  index.cljs
  tracker.cljs      # on("acp_usage") — accumulate per-agent metrics
  dashboard.cljs    # ui.setWidget — render cost/token charts
```

**Key features**:
- Listen to `acp_usage` events, accumulate cost per agent per session
- `/costs` command shows breakdown by agent
- Persistent state stores historical cost data across sessions
- Optional widget shows running total in the UI

**Complexity**: Low-Medium

---

## Project Memory

Persistent project knowledge base that auto-updates from conversations — like CLAUDE.md but extensible.

**Capabilities**: `context`, `events`, `state`, `commands`

**Structure**:
```
~/.nyma/extensions/project-memory/
  extension.json    # capabilities: ["context", "events", "state", "commands"]
  index.cljs
  memory-store.cljs # persistent state for project facts
  context.cljs      # registerContextProvider — inject relevant memories
  collector.cljs    # on("agent_end") — extract learnings from conversation
```

**Key features**:
- `/remember <fact>` stores a project fact
- `/forget <pattern>` removes matching facts
- Context provider injects relevant memories based on current conversation topic
- Optional: auto-extract facts from conversation endings

**Complexity**: Medium

---

## Approval Profiles

Named permission sets instead of per-tool approval — "trust all reads", "block network", "full auto".

**Capabilities**: `middleware`, `flags`, `commands`, `state`

**Structure**:
```
~/.nyma/extensions/approval-profiles/
  extension.json    # capabilities: ["middleware", "flags", "commands", "state"]
  index.cljs
  profiles.cljs     # predefined and custom profiles
  middleware.cljs    # addMiddleware — intercept tool_call, apply profile rules
```

**Key features**:
- `/profile safe` — allow reads, prompt for writes, block network
- `/profile auto` — auto-approve everything
- `/profile custom` — define custom rules via settings
- Middleware intercepts tool calls, checks against active profile rules
- Persistent state remembers last-used profile per project

**Complexity**: Medium

---

## Workspace Snapshots

Save and restore working state (git state, open context, active tools) for context switching.

**Capabilities**: `commands`, `exec`, `state`, `session`

**Structure**:
```
~/.nyma/extensions/workspace-snapshots/
  extension.json    # capabilities: ["commands", "exec", "state", "session"]
  index.cljs
  snapshot.cljs     # capture: git branch, stash, modified files, session name
  restore.cljs      # apply: checkout branch, pop stash, set session
```

**Key features**:
- `/snapshot save <name>` captures current workspace state
- `/snapshot restore <name>` switches to saved state
- `/snapshot list` shows available snapshots
- Stores: git branch, uncommitted changes (stashed), session context, active agent

**Complexity**: Medium

---

## ~~Thinking Renderer (Enhanced)~~ — Implemented

Collapsible thinking/reasoning display with token counting and auto-collapse. Handles both ACP agent thinking (`acp_thought` events) and native provider extended thinking (`reasoning_delta` events from AI SDK).

**Location**: `~/.nyma/extensions/thinking-renderer/`
**Tests**: `test/ext_thinking_renderer.test.cljs` (18 tests)
**API additions**: `getGlobalFlag` for cross-extension flag reading; `reasoning_start/delta/end` stream event mappings in `loop.cljs`

---

## ~~MCP Bridge~~ — Implemented (as agent-shell feature)

Built as `agent-shell/features/mcp_discovery.cljs` instead of a standalone extension (requires pool lifecycle access for `session/new` injection).

**Location**: `src/agent/extensions/agent_shell/features/mcp_discovery.cljs`
**Tests**: `test/ext_agent_shell_mcp.test.cljs` (16 tests)
**Commands**: `/mcp list`, `/mcp refresh`
**Config**: `.mcp.json` (project root) and `.cursor/mcp.json`, with `${ENV_VAR}` expansion

---

## ~~Workspace Config~~ — Implemented

Per-project `.nyma/settings.json` for command aliases, flags, and defaults. Loaded at startup; reloadable at runtime.

**Location**: `src/agent/extensions/workspace_config/`
**Tests**: `test/workspace_config.test.cljs` (20 tests)
**Commands**: `/alias <name> <target>`, `/alias --remove <name>`, `/alias` (list), `/workspace-config__reload`
**Config**: `.nyma/settings.json` — `aliases` map pre-registers commands on startup; `flags` map for future flag use

---

## ~~Token Cost Preview~~ — Implemented (as token-suite feature)

Live token-count estimate shown in the footer while typing, debounced 300 ms. Toggle with `/token-preview`.

**Location**: `src/agent/extensions/token_suite/token_preview.cljs` (part of token-suite)
**Tests**: `test/token_preview.test.cljs` (10 tests)
**Commands**: `/token-preview` (toggle on/off)
**Event**: Subscribes to `editor_change`; renders `~N tokens` widget below the editor

---

## File Watcher

Watch for external file changes and notify the agent — useful for test-driven workflows.

**Capabilities**: `events`, `messages`, `exec`

**Structure**:
```
~/.nyma/extensions/file-watcher/
  extension.json    # capabilities: ["events", "messages", "exec"]
  index.cljs
  watcher.cljs      # fs.watch on project files
```

**Key features**:
- Watch specified directories for changes (e.g., `test/` for test results)
- `/watch test/` starts watching, `/unwatch` stops
- On file change, inject a steer message: "Test file changed: test/foo.test.ts"
- Useful for TDD: agent sees test changes in real-time

**Complexity**: Low

---

## Snippet Library

Save and retrieve code snippets across sessions — a personal library of patterns.

**Capabilities**: `commands`, `state`, `tools`

**Structure**:
```
~/.nyma/extensions/snippets/
  extension.json    # capabilities: ["commands", "state", "tools"]
  index.cljs
```

**Key features**:
- `/snippet save <name>` saves current selection or clipboard
- `/snippet get <name>` retrieves snippet
- `/snippet list [tag]` searches by tags
- Tool: `snippet_search` lets the LLM find relevant snippets

**Complexity**: Low

---

## Roadmap: Inspired by oh-my-pi (can1357/oh-my-pi)

Features identified from oh-my-pi that add clear value to nyma, organized by adoption priority.

### Tier 1 — High Value, Easy to Adopt

#### Model Roles

Named model presets (`default`, `fast`, `deep`, `plan`, `commit`) with per-role model assignment and automatic fallback chains. Users switch with `/role fast` instead of remembering model IDs.

**Capabilities**: `commands`, `state`, `providers`
**Complexity**: Low
**See**: [Implementation Plan](./plan-model-roles.md)

#### Grouped Tool Display

Consecutive tool calls of the same type (e.g., multiple reads) rendered as a compact file tree instead of N separate blocks. Dramatically improves chat scanability.

**Capabilities**: `ui` (chat_view rendering change)
**Complexity**: Low
**See**: [Implementation Plan](./plan-grouped-tool-display.md)

#### Prompt History + Ctrl+R Search

SQLite-backed persistent prompt history across sessions with Ctrl+R fuzzy search overlay. Every terminal power user expects this.

**Capabilities**: `ui`, `state`, `commands`
**Complexity**: Low-Medium
**See**: [Implementation Plan](./plan-prompt-history.md)

#### AST Tools (ast-grep)

`ast_grep` and `ast_edit` tools for syntax-aware code search and structural codemods. Wraps the `sg` (ast-grep) binary.

**Capabilities**: `tools`, `exec`
**Complexity**: Low
**See**: [Implementation Plan](./plan-ast-tools.md)

#### Stats Dashboard

`/stats` command showing usage analytics — cost over time, tokens/turn, cache hit rate, model breakdown. Queries existing SQLite usage data.

**Capabilities**: `commands`, `ui`, `state`
**Complexity**: Low
**See**: [Implementation Plan](./plan-stats-dashboard.md)

#### Code Review Command

`/review` with mode selection (branch diff, uncommitted changes, specific commit). Structured findings with priority levels.

**Capabilities**: `commands`, `tools`, `exec`
**Complexity**: Low-Medium

#### Multi-Credential Round-Robin

Multiple API keys per provider with usage-aware selection and automatic fallback on rate limits.

**Capabilities**: `providers`
**Complexity**: Medium

### Tier 2 — High Value, Medium Effort

#### TTSR (Time Traveling Streamed Rules)

Zero-context-cost rules that watch the LLM output stream in real-time via regex patterns. On match: abort stream, inject rule as system message, retry. Most innovative feature in oh-my-pi.

**Capabilities**: `events`, `middleware` (needs new `stream_filter` event — see hooks plan)
**Complexity**: Medium
**Requires**: New `stream_filter` event in loop.cljs

#### Universal Config Discovery

Read configs from Claude Code (`.claude/`), Cursor (`.cursor/`), Windsurf, Gemini, Codex, Cline, GitHub Copilot. Discovers MCP servers, rules, context files, and tools.

**Capabilities**: `context`, `events`, `tools`
**Complexity**: Medium

#### Autonomous Memory Pipeline

Background extraction of durable knowledge from past sessions, consolidated into project memory and skill playbooks. Runs post-session.

**Capabilities**: `events`, `state`, `exec`
**Complexity**: Medium

#### AI Git Commits

Agentic commit with `git-overview`, `git-file-diff`, `git-hunk` tools. Auto-split unrelated changes with dependency ordering. Hunk-level staging.

**Capabilities**: `tools`, `commands`, `exec`
**Complexity**: Medium

#### Subagent Parallel Tasks

Named sub-agents (explore, plan, reviewer) running in parallel with git worktree isolation and real-time artifact streaming.

**Capabilities**: `tools`, `exec`, `events`, `state`
**Complexity**: High
**Requires**: Worktree isolation primitives

### Ready to Build — Hooks Complete, Needs Extension Code

#### Checkpoint System (G16)

Git-based snapshots after every file-modifying tool call. `/checkpoint list|revert|diff` commands. Subscribes to the `tool_complete` event (already implemented). Enables safe exploration, non-destructive undo, and branch-from-checkpoint workflows.

**Capabilities**: `events` (`tool_complete`), `exec` (git), `commands`
**Complexity**: Medium (~100 LOC extension)
**Enables**: Cline-style checkpoints, Windsurf-style revert, CI validation after each edit
**Depends on**: `tool_complete` event (done)

#### Native Subagent System (G17)

A `task` tool that spawns child agent sessions with isolated context, restricted tools, and budget limits. Parent receives summarized results. Enables orchestrator mode, parallel research, and specialist delegation.

**Capabilities**: `tools`, `events`, `state`, `exec`
**Complexity**: High (~300 LOC, new subsystem)
**Enables**: Roo Code boomerang orchestration, Cline subagents, Kilo parallel agents, Cursor /worktree isolation
**Depends on**: `tool_access_check` (done), `tool_complete` (done), session management

### Tier 3 — High Value, High Effort (Long-term)

#### Hashline Edits

Content-hash anchors per line — model references anchors instead of reproducing text. Up to 10x edit accuracy for weaker models. Fundamental change to the edit tool.

**Complexity**: High

#### LSP Integration

Full language server protocol: diagnostics, go-to-definition, references, hover, rename, format-on-write. 40+ language configurations.

**Complexity**: Very High

#### Python/IPython Tool

Persistent IPython kernel with streaming output, rich rendering (HTML, images, Mermaid), and custom module loading.

**Complexity**: High

#### Browser Tool (Puppeteer)

Headless browser with stealth scripts, accessibility snapshots, article extraction. Navigate, click, type, screenshot.

**Complexity**: High

#### Todo/Task Panel

Phased task list with visual panel above editor (Ctrl+T toggle). Auto-normalization ensures exactly one task in-progress. Completion reminders.

**Complexity**: Medium

#### Handoff Command

`/handoff` creates a new session pre-loaded with context summary from current session. Combines session branching + compaction.

**Complexity**: Low-Medium
