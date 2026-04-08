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
