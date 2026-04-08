# Agent Shell Extension

Agent Shell is a nyma extension that turns the nyma chat interface into a unified frontend for AI coding agents. It connects to Claude Code, Gemini CLI, OpenCode, Qwen, Goose, and Kiro via the **Agent Client Protocol (ACP)** — a JSON-RPC 2.0 protocol over stdio — giving you a single, consistent interface across all agents.

---

## Table of Contents

1. [Overview](#overview)
2. [Supported Agents](#supported-agents)
3. [Prerequisites](#prerequisites)
4. [Installation](#installation)
5. [Configuration](#configuration)
6. [Connecting to an Agent](#connecting-to-an-agent)
7. [Sending Messages](#sending-messages)
8. [Commands Reference](#commands-reference)
9. [Model Switching](#model-switching)
10. [Mode Switching](#mode-switching)
11. [Effort Level](#effort-level)
12. [Session Management](#session-management)
13. [MCP Server Integration](#mcp-server-integration)
14. [Agent Handoff](#agent-handoff)
15. [Permission Handling](#permission-handling)
16. [Cost and Usage Tracking](#cost-and-usage-tracking)
17. [Streaming Output](#streaming-output)
18. [UI Components](#ui-components)
19. [Auto-Connect](#auto-connect)
20. [Developer Reference](#developer-reference)

---

## Overview

When Agent Shell is active, every message you type in the nyma chat window is forwarded to your connected coding agent. Responses stream back in real-time with support for thinking blocks, tool-call displays, and execution plan rendering.

Agent Shell is designed around a single principle: the agent does the coding work, nyma handles the interface. You get:

- **One interface, many agents** — switch between Claude, Gemini, and others without leaving the session
- **Real-time streaming** — see thinking chunks, tool calls, and text as they happen
- **MCP tools passthrough** — project `.mcp.json` servers are automatically given to the agent
- **Context handoff** — move your session from one agent to another while preserving context
- **Cost visibility** — token usage and dollar cost displayed in the status bar

---

## Supported Agents

| Key | Name | Command | Features |
|-----|------|---------|----------|
| `claude` | Claude Code | `npx @agentclientprotocol/claude-agent-acp` | plan-mode, model-switch, sessions, thinking, subagents, MCP, cost |
| `gemini` | Gemini CLI | `gemini --acp` | plan-mode, model-switch, sessions, thinking, subagents, cost |
| `opencode` | OpenCode | `opencode acp` | model-switch, sessions, cost |
| `qwen` | Qwen Code | `qwen --acp` | model-switch, cost |
| `goose` | Goose | `goose acp` | model-switch, cost |
| `kiro` | Kiro | `kiro --acp` | model-switch, sessions, cost |

Features by agent:

- **plan-mode** — structured plan/auto/approve execution modes
- **model-switch** — runtime model switching via `/model`
- **sessions** — list and resume past sessions via `/sessions`
- **thinking** — streaming extended thinking output
- **subagents** — can spawn child agents for parallel tasks
- **MCP** — accepts MCP server configs in session handshake
- **cost** — reports token usage and pricing

---

## Prerequisites

Install the agent CLIs you want to use. Agent Shell spawns them as subprocesses, so they must be on your `PATH`:

```bash
# Claude Code (requires Node/npm)
npm install -g @agentclientprotocol/claude-agent-acp

# Gemini CLI
npm install -g @google/gemini-cli    # or brew install gemini-cli

# OpenCode
# Install from https://opencode.ai

# Qwen Code
brew install qwen    # or your package manager

# Goose
pip install goose-ai    # or brew install goose

# Kiro
# Install from https://kiro.ai
```

You only need to install the agents you plan to use.

---

## Installation

Agent Shell ships as a built-in extension. It is automatically available in nyma — no separate installation required. The extension activates when nyma starts.

---

## Configuration

Agent Shell reads configuration from `.nyma/settings.json` in your project root, under the `agent-shell` key:

```json
{
  "agent-shell": {
    "default-agent": "claude",
    "auto-connect": true,
    "auto-approve": false,
    "agents": {
      "claude": {
        "model": "claude-opus-4-5"
      },
      "opencode": {
        "model": "anthropic/claude-sonnet-4-5"
      }
    }
  }
}
```

### Options

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `default-agent` | string | `null` | Agent to connect on startup (e.g. `"claude"`) |
| `auto-connect` | boolean | `false` | Connect to `default-agent` automatically when nyma starts |
| `auto-approve` | boolean | `false` | Automatically approve all agent permission requests |
| `agents` | object | `{}` | Per-agent overrides |

### Per-Agent Overrides

The `agents` object maps agent keys to override values. Supported overrides:

```json
{
  "agent-shell": {
    "agents": {
      "claude": {
        "model": "claude-opus-4-6",
        "init-mode": "plan"
      },
      "gemini": {
        "model": "gemini-2.5-pro"
      }
    }
  }
}
```

| Override Key | Description |
|---|---|
| `model` | Model ID to set immediately after connecting |
| `init-mode` | Mode to activate right after the session is created |
| `command` | Override the spawn command (advanced) |
| `args` | Override the spawn arguments (advanced) |

---

## Connecting to an Agent

Use the `/agent` command to connect:

```
/agent claude
/agent gemini
/agent opencode
/agent qwen
/agent goose
/agent kiro
```

To see all available agents:

```
/agent
/agent list
```

To disconnect the current agent:

```
/agent disconnect
/disconnect
```

When you connect to an agent, Agent Shell:

1. Spawns the agent process with `api.spawn()`
2. Waits 500ms for cold-start initialization
3. Sends an `initialize` request exchanging capabilities
4. Sends `session/new` with the project root and any discovered MCP servers
5. Optionally sets the initial mode (`session/set_mode`)
6. Optionally sets the configured model (`session/set_config_option`)
7. Sets up the custom header/footer UI

---

## Sending Messages

Once connected, anything you type is sent directly to the agent. There are three input modes:

| Input | Behavior |
|---|---|
| Plain text | Forwarded to the active ACP agent as a prompt |
| `/command` | Handled by nyma as a built-in command (e.g. `/model`, `/agent`) |
| `//command` | Escaped: sent to the agent as the literal text `/command` |

The `//` escape lets you send slash commands *to the agent* (e.g. if your agent supports `/clear` or `/help` as its own commands).

Response streaming begins immediately. Text chunks, thinking blocks, and tool-call status all appear as the agent works.

---

## Commands Reference

### `/agent` — Connect to or list agents

```
/agent                  → list available agents
/agent list             → list available agents
/agent <name>           → connect to agent (disconnects current)
/agent disconnect       → disconnect current agent
/disconnect             → disconnect current agent
```

### `/model` — Switch models

```
/model                  → open fuzzy model picker
/model <id>             → switch to model directly
```

Model IDs are provided by the agent at connect time via `config_option_update` notifications. The fuzzy picker searches both the model ID and display name.

### `/plan` — Plan/read-only mode

Switches the agent to a cautious mode where it can read files and propose changes but won't execute them without confirmation.

```
/plan
```

### `/yolo` — Auto-approve everything

Switches the agent to a mode where all tool calls are automatically approved without prompting.

```
/yolo
```

### `/approve` — Default approval mode

Resets to the agent's default permission behavior (prompt for approval on write operations).

```
/approve
```

### `/auto-edit` — Auto-approve edits, prompt for shell

Automatically approves file edits but still prompts before running shell commands.

```
/auto-edit
```

### `/sessions` — Manage past sessions

```
/sessions               → list sessions (fuzzy picker if supported)
/sessions list          → list sessions
/sessions resume <id>   → load a previous session
```

Not all agents support sessions. Claude and Gemini do. Agents without `:sessions` in their feature set will show an informational message.

### `/mcp` — Inspect MCP servers

```
/mcp                    → list discovered MCP servers
/mcp list               → list discovered MCP servers
/mcp refresh            → rescan project for .mcp.json changes
```

### `/effort` — Set thinking effort level

Controls how much reasoning effort the agent applies before responding. Only supported by agents that accept `session/set_config_option` with `configId: "effort"` (currently Claude Code).

```
/effort low     → quick responses, minimal reasoning
/effort medium  → balanced (default)
/effort high    → deeper reasoning for complex problems
/effort max     → maximum thinking budget
/effort auto    → agent decides based on task complexity
```

If the connected agent does not support effort control, the command shows an error notification and does not affect the session.

### `/handoff` — Transfer to another agent

```
/handoff <agent>                  → hand off with automatic context
/handoff <agent> <context message> → hand off with custom context
```

---

## Model Switching

When you connect to an agent that supports `:model-switch`, it sends its available models list via a `config_option_update` notification. These models are stored and presented when you run `/model`.

### Fuzzy Picker

Running `/model` with no argument opens an interactive fuzzy picker:

- Type to filter by model ID or display name
- Arrow keys / `Ctrl+P` / `Ctrl+N` to navigate
- `Enter` to select
- `Esc` to cancel
- `Backspace` to clear the filter

### Direct Switch

```
/model claude-opus-4-6
/model gemini-2.5-pro-preview
```

The model change is applied immediately to the current session via `session/set_config_option` with `configId: "model"`.

### Persisting a Model

To always start with a specific model, set it in `.nyma/settings.json`:

```json
{
  "agent-shell": {
    "agents": {
      "claude": { "model": "claude-opus-4-6" }
    }
  }
}
```

---

## Effort Level

The `/effort` command sets the thinking budget — how much internal reasoning the agent applies before answering. This maps to `session/set_config_option` with `configId: "effort"`.

| Level | Behavior |
|-------|----------|
| `low` | Minimal reasoning; fast pattern-matching responses |
| `medium` | Balanced reasoning (default) |
| `high` | Extended reasoning for complex logic |
| `max` | Full available thinking budget |
| `auto` | Agent decides per-task |

```
/effort high
```

The effort level is stored per-agent session. If an agent does not support effort control, an error notification is shown and the session is unchanged.

---

## Mode Switching

Modes control the agent's permission behavior and execution style. Available modes differ per agent.

### Available Modes by Agent

| Command | Claude | Gemini | Qwen | Goose |
|---|---|---|---|---|
| `/plan` | `plan` | `plan` | — | — |
| `/yolo` | `bypassPermissions` | `yolo` | `yolo` | — |
| `/approve` | `default` | `default` | `default` | `approve` |
| `/auto-edit` | `acceptEdits` | `auto_edit` | — | — |

Modes that aren't supported by the current agent display an informational message.

### Setting a Default Mode

```json
{
  "agent-shell": {
    "agents": {
      "claude": { "init-mode": "plan" }
    }
  }
}
```

---

## Session Management

Agents that support `:sessions` can save and resume past sessions. Session data (conversation history, file context, etc.) is managed by the agent itself.

### Listing Sessions

```
/sessions
```

If the agent supports sessions, this shows a list with session IDs and titles. On agents with fuzzy picker support, an interactive picker is displayed.

### Resuming a Session

```
/sessions resume <session-id>
```

This calls `session/load` on the ACP agent, which restores the previous conversation context.

### Session Titles

When an agent sends a `session_info_update` notification, the session title is displayed in the header.

### Resetting a Session with `/clear`

The built-in `/clear` command clears the local message history **and** sends `session/new` to the connected ACP agent, giving it a fresh context window. The agent's session ID is updated from the response so subsequent prompts go to the new session.

If no agent is connected, `/clear` still clears local messages as usual — the ACP reset is a no-op.

---

## MCP Server Integration

Agent Shell automatically discovers MCP (Model Context Protocol) servers from your project configuration and passes them to the agent when creating a new session.

### Configuration Format

Create a `.mcp.json` file in your project root:

```json
{
  "mcpServers": {
    "my-database": {
      "command": "npx",
      "args": ["-y", "@mcp/postgres-server"],
      "env": {
        "DATABASE_URL": "${DATABASE_URL}"
      }
    },
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/files"]
    }
  }
}
```

### Config File Locations

Agent Shell reads MCP servers from two locations (merged, with `.mcp.json` taking precedence):

| File | Precedence | Notes |
|------|-----------|-------|
| `.mcp.json` | High | Project root, the de facto standard |
| `.cursor/mcp.json` | Low | Cursor IDE format, same structure |

### Environment Variable Expansion

All string values in the `env` object support `${VAR}` expansion using `process.env`:

```json
{
  "mcpServers": {
    "api-server": {
      "command": "node",
      "args": ["server.js"],
      "env": {
        "API_KEY": "${MY_API_KEY}",
        "BASE_URL": "https://api.example.com"
      }
    }
  }
}
```

### When Servers Are Passed

Discovered servers are passed in the `session/new` handshake when you connect to an agent. The agent receives them as:

```json
{
  "cwd": "/path/to/project",
  "mcpServers": [
    {
      "name": "my-database",
      "command": "npx",
      "args": ["-y", "@mcp/postgres-server"],
      "env": { "DATABASE_URL": "postgres://..." }
    }
  ]
}
```

Only Claude is known to declare `:mcp` in its capabilities, but the field is safely ignored by agents that don't support it.

### Refreshing After Changes

If you modify `.mcp.json` while nyma is running:

```
/mcp refresh
```

Note: The refreshed list only takes effect for new agent connections. Existing sessions don't receive updated MCP configs. Reconnect (`/agent disconnect` then `/agent <name>`) to apply changes.

---

## Agent Handoff

The `/handoff` command disconnects the current agent and connects a new one, preserving context via a prefilled prompt.

```
/handoff gemini
/handoff claude "Focus on the authentication module"
```

### Handoff Process

1. A context string is built from the current agent's state: session title, active mode, model, and any custom message you provided
2. The current agent is disconnected
3. The new agent is connected and initialized
4. The context string is sent as the first prompt to the new agent

This lets you shift complex tasks between agents without losing the thread of what you were working on.

---

## Permission Handling

Agents request permission before performing sensitive operations (writing files, running shell commands, accessing the network, etc.). Agent Shell handles these permission requests via the `session/request_permission` reverse RPC call.

### Default Behavior

A permission dialog is shown asking you to approve or deny the operation. The dialog includes:

- The operation type (file write, shell command, etc.)
- Details about what will be performed
- Approve / Deny buttons

### Auto-Approve

To skip all permission dialogs, set `auto-approve: true` in your config:

```json
{
  "agent-shell": {
    "auto-approve": true
  }
}
```

Or use the `/yolo` mode (agent-side auto-approval) instead of the nyma-side flag, depending on your agent.

### Elicitation

Some agents use ACP's elicitation protocol to ask for structured input (forms) or prompt you to open a URL. Agent Shell handles both:

- **Form mode**: Renders JSON Schema fields as UI inputs
- **URL mode**: Displays the URL in the UI for you to open

---

## Cost and Usage Tracking

Agent Shell tracks token usage and cost in real-time, displayed in the status bar at the bottom of the screen.

### Status Bar Display

```
[claude] claude-opus-4-6 | ✓ plan    ctx: 45.2k/200k (23%) [████░░░░] | $0.82 | ↑12k ↓4.3k | cache:8k
```

| Component | Description |
|---|---|
| `[claude]` | Active agent key |
| `claude-opus-4-6` | Current model |
| `✓ plan` | Current mode |
| `ctx: 45.2k/200k (23%)` | Context window used/total |
| `[████░░░░]` | Context usage bar |
| `$0.82` | Total session cost |
| `↑12k ↓4.3k` | Current turn input/output tokens |
| `cache:8k` | Cached tokens |

### Header Display

```
nyma × Claude Code | claude-opus-4-6 | plan | My Feature Branch
```

The header shows agent name, model, mode, and the session title when available.

### Usage Data Source

Usage data comes from `usage_update` notifications sent by the agent after each turn. Costs are calculated client-side from pricing tables (when available) or provided directly by the agent.

---

## Streaming Output

Agent Shell supports three types of streamed content:

### Text Streaming

Assistant text arrives as `agent_message_chunk` notifications and is rendered incrementally in the chat view using `append-chunk`. Each chunk is appended to the current assistant message.

### Thinking Blocks

For agents that support `:thinking` (Claude, Gemini), thinking content arrives as `agent_thought_chunk` notifications. These render as a separate collapsible "Thinking..." block above the main response, updated in real-time via `append-thought`.

### Execution Plans

Agents may send `plan` notifications showing their execution plan as a structured list of steps. Each step has a status:

| Status | Display |
|---|---|
| `done` | `✓ Step description` |
| `active` | `→ Step description` |
| pending | `  Step description` |

Plans are rendered in a dedicated message block via `append-plan` and update in place as steps complete.

### Tool Calls

Tool invocations appear as inline messages showing:
- Tool name and input parameters (summarized)
- Status updates as the tool executes
- Result or error on completion

---

## UI Components

Agent Shell installs custom header and footer components when the first agent connects.

### Header

Displays:
- `nyma × <Agent Name>` when an agent is connected
- `nyma | no agent connected` otherwise

Additional info (model, mode, session title) is appended with `|` separators.

### Footer / Status Bar

Shows two sections:

**Left**: Agent key, model, mode with checkmark for plan mode

**Right**: Context usage, progress bar, cost, per-turn token counts, cache hit count

When no agent is connected, the footer shows help hints:
```
ctrl+c exit | /help commands | /agent-shell__agent connect | nyma v0.1.0
```

### Model Picker

An interactive overlay launched by `/model`:

```
Filter: opus▊

  claude-haiku-4-5  Claude Haiku 4.5
  claude-sonnet-4-5 Claude Sonnet 4.5
▶ claude-opus-4-6   Claude Opus 4.6   ← selected
  ... 12 more
```

---

## Auto-Connect

To connect to an agent automatically when nyma starts, set both `default-agent` and `auto-connect` in your config:

```json
{
  "agent-shell": {
    "default-agent": "claude",
    "auto-connect": true
  }
}
```

Auto-connect waits 1 second after activation to allow the UI to become available before spawning the agent. If the UI is not ready, the connection is silently skipped.

---

## Developer Reference

### Architecture

Agent Shell is composed of a thin activation layer (`index.cljs`) that orchestrates 10 independent feature modules. Each module registers its commands and event hooks on activation and returns a cleanup function (deactivator).

```
index.cljs
  └── activates (returns deactivator):
      ├── input-router      → hooks 'input' event, forwards to ACP agent
      ├── agent-switcher    → /agent, /disconnect
      ├── model-switcher    → /model
      ├── mode-switcher     → /plan, /yolo, /approve, /auto-edit
      ├── effort-switcher   → /effort <low|medium|high|max|auto>
      ├── permission-ui     → --auto-approve flag
      ├── session-mgmt      → /sessions
      ├── cost-tracker      → acp_usage event listener
      ├── handoff           → /handoff
      └── mcp-discovery     → /mcp, scans .mcp.json
```

### ACP Protocol

Agent Client Protocol is JSON-RPC 2.0 over NDJSON (newline-delimited JSON) on stdin/stdout.

**Client → Agent Methods**:

| Method | Description |
|---|---|
| `initialize` | Exchange capabilities (version, clientInfo, clientCapabilities) |
| `session/new` | Create session with `{cwd, mcpServers}` |
| `session/prompt` | Send user prompt, get streaming response |
| `session/set_mode` | Switch execution mode `{sessionId, modeId}` |
| `session/set_config_option` | Change config option like model `{sessionId, configId, value}` |
| `session/set_model` | Alternative model switch for OpenCode `{sessionId, modelId}` |
| `session/list` | List available sessions |
| `session/load` | Load a past session `{sessionId}` |
| `session/cancel` | Cancel in-progress prompt (notification, no response) |

**Agent → Client Requests** (reverse RPC — agent calls client):

| Method | Description |
|---|---|
| `session/request_permission` | Ask user to approve a tool call |
| `fs/read_text_file` | Read a file from disk |
| `fs/write_text_file` | Write a file (restricted to project root) |
| `terminal/create` | Spawn a subprocess |
| `terminal/output` | Get accumulated terminal output |
| `terminal/wait_for_exit` | Wait for process exit |
| `terminal/kill` | Kill a running process |
| `terminal/release` | Remove terminal from tracking |
| `session/elicitation` | Request structured input from user |

**Agent → Client Notifications** (no response expected):

All notifications use method `session/update` with a typed `data` payload:

| Type | Description |
|---|---|
| `agent_message_chunk` | Streaming text delta |
| `agent_thought_chunk` | Streaming thinking content |
| `tool_call` | Tool invocation started |
| `tool_call_update` | Tool status/content update |
| `plan` | Execution plan update |
| `available_commands_update` | Dynamic slash commands from agent |
| `current_mode_update` | Mode changed |
| `config_option_update` | Config changed (e.g. available models) |
| `usage_update` | Token usage and cost data |
| `session_info_update` | Session title update |

### Shared State Atoms

All shared state lives in `agent.extensions.agent-shell.shared`:

| Atom | Type | Description |
|------|------|-------------|
| `active-agent` | `keyword \| nil` | Current connected agent key |
| `connections` | `{keyword → connection}` | Pool of live ACP connections |
| `agent-state` | `{keyword → map}` | Per-agent runtime state |
| `stream-callback` | `fn \| nil` | Text streaming hook |
| `thought-callback` | `fn \| nil` | Thinking streaming hook |
| `plan-callback` | `fn \| nil` | Plan update hook |
| `mcp-servers` | `[server-map]` | Discovered MCP servers |
| `api-ref` | `api \| nil` | Scoped API reference for lazy UI |
| `footer-set?` | `boolean` | Footer install guard |

### Connection Map

Each entry in `@connections` is a map:

```clojure
{:proc         handle          ; Bun process handle
 :stdin        WritableStream  ; Agent process stdin
 :stdout       ReadableStream  ; Agent process stdout
 :stderr       ReadableStream  ; Agent process stderr
 :project-root string          ; cwd at connection time
 :agent-key    keyword         ; e.g. :claude
 :state        atom            ; {:pending {id→{resolve,reject}} :terminals {}}
 :prompt-state atom            ; {:text "" :tool-calls []}
 :id-counter   atom            ; monotonic request ID counter
 :session-id   atom            ; ACP session ID string
 :on-reverse-request fn        ; handles agent→client requests
 :on-notification    fn        ; handles session/update notifications
 :emit         fn}             ; event emitter (no-op)
```

### Adding a New Agent

1. Add an entry to `agents/registry.cljs`:

```clojure
:my-agent
{:name           "My Agent"
 :command        "my-agent-cli"
 :args           ["--acp"]
 :features       #{:model-switch :cost}
 :modes          {:approve "default"}
 :model-config-id "model"
 :init-mode       nil}
```

2. Install the CLI and verify it speaks ACP 1.0 over stdio
3. That's it — `/agent my-agent` will work immediately

### Adding a New Feature Module

1. Create `src/agent/extensions/agent_shell/features/my_feature.cljs`
2. Export an `activate [api]` function that returns a deactivator
3. Add the require and activation call in `index.cljs`

```clojure
;; In index.cljs requires:
[agent.extensions.agent-shell.features.my-feature :as my-feature]

;; In the deactivators vector:
(my-feature/activate api)
```

### Build and Test

```bash
# Build
bun run build

# Run all tests
bun test

# Run specific test suite
bun test dist/ext_agent_shell_mcp.test.mjs
bun test dist/ext_agent_shell.test.mjs
bun test dist/ext_agent_shell_e2e.test.mjs
```

E2E tests spawn real agent processes and require the respective CLIs to be installed. They use a 60-second timeout.

---

## Troubleshooting

### Agent not found / command not found

The agent CLI must be on your `PATH`. Verify with:
```bash
which claude    # or gemini, opencode, qwen, etc.
```

### "invalid params" on connect

This typically means the agent's ACP version has a different capability schema. Check the agent CLI version and update if needed.

### MCP servers not being passed

Run `/mcp list` to verify servers were discovered. If empty, check:
1. `.mcp.json` exists in the project root (where you launched nyma from)
2. The JSON is valid (no trailing commas, correct structure)
3. Try `/mcp refresh` and then reconnect the agent

### High memory usage

Each agent runs as a separate subprocess. Multiple simultaneous connections multiply memory usage. Use `/agent disconnect` when not using an agent.

### Cost display shows `$0.00`

The agent may not report cost data, or the model may not be in the pricing table. Token counts are still accurate even when cost is zero.
