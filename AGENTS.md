# NYMA — Nyma Yokai Mystic Agent

## Project Overview

NYMA is a minimal, extensible coding agent built with:
- **Squint** — ClojureScript-to-JS compiler (compile-time macros, threading, destructuring)
- **Bun** — runtime, package manager, native TypeScript loader
- **Vercel AI SDK (`ai`)** — LLM streaming, tool loop, provider abstraction
- **Ink** — React-based terminal UI via squint's `#jsx` reader tag

## Architecture

```
@agent/cli → @agent/core → ai (Vercel AI SDK)
                        ↓
              @agent/ui (Ink components)
```

### Key Namespaces

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/agent/cli.cljs` | `agent.cli` | Entry point, arg parsing, mode dispatch |
| `src/agent/core.cljs` | `agent.core` | Agent state, `create-agent` factory |
| `src/agent/loop.cljs` | `agent.loop` | `run`, `steer`, `follow-up` |
| `src/agent/events.cljs` | `agent.events` | Typed event bus |
| `src/agent/tools.cljs` | `agent.tools` | Built-in tools: read, write, edit, bash |
| `src/agent/tool_registry.cljs` | `agent.tool-registry` | Active/inactive tool management |
| `src/agent/extensions.cljs` | `agent.extensions` | Extension API factory |
| `src/agent/extension_loader.cljs` | `agent.extension-loader` | Dual .cljs/.ts loader |
| `src/agent/context.cljs` | `agent.context` | Message filtering, context building |
| `src/ui/app.cljs` | `agent.ui.app` | Root Ink component |
| `src/ui/chat_view.cljs` | `agent.ui.chat-view` | Message rendering |
| `src/ui/editor.cljs` | `agent.ui.editor` | Text input component |
| `src/sessions/manager.cljs` | `agent.sessions.manager` | JSONL tree session storage |
| `src/sessions/compaction.cljs` | `agent.sessions.compaction` | Context window compaction |
| `src/resources/loader.cljs` | `agent.resources.loader` | Resource discovery |
| `src/settings/manager.cljs` | `agent.settings.manager` | Two-scope settings |
| `src/modes/interactive.cljs` | `agent.modes.interactive` | TUI mode |
| `src/modes/print.cljs` | `agent.modes.print` | Print mode |
| `src/modes/rpc.cljs` | `agent.modes.rpc` | JSONL stdio RPC mode |
| `src/modes/sdk.cljs` | `agent.modes.sdk` | Programmatic SDK mode |
| `src/macros/tool_dsl.cljs` | `macros.tool-dsl` | Compile-time `deftool`/`defcommand` macros |

## Development Workflow

```bash
# Install dependencies
bun install

# Development (two terminals)
npx squint watch          # terminal 1: compile .cljs → .mjs/.jsx
bun --watch dist/agent/cli.mjs  # terminal 2: run with auto-restart

# Or combined
bun run dev

# Production build
npx squint compile
bun dist/agent/cli.mjs

# Run tests
bun test

# REPL
npx squint repl
```

## Extension System

Extensions can be written in **ClojureScript (.cljs)** or **TypeScript (.ts)**:

```
~/.agent/extensions/     # global extensions
.agent/extensions/       # project-local (overrides global)
```

Both receive the same `ExtensionAPI` object:
- `api.registerTool(name, tool)` — add an LLM-callable tool
- `api.registerCommand(name, opts)` — add a /slash command
- `api.on(event, handler)` — subscribe to agent events
- `api.sendUserMessage(text, opts)` — inject a user message

## Event System

All agent lifecycle events flow through the event bus:

```
agent_start / agent_end
turn_start / turn_end
tool_call / tool_result / tool_execution_start / tool_execution_end
message_start / message_update / message_end
context / before_agent_start / input
compact / before_compact
session_start / session_end / session_switch
```

## Settings

Settings are resolved in priority order:
1. Runtime overrides (flags, API)
2. Project settings (`.agent/settings.json`)
3. Global settings (`~/.agent/settings.json`)
4. Defaults

## Operational Modes

| Mode | Flag | Description |
|------|------|-------------|
| interactive | (default) | Full TUI with Ink |
| print | `-p` / `--print` | Run once, print result to stdout |
| json | `--mode json` | Run once, output JSON messages |
| rpc | `--mode rpc` | JSONL protocol over stdio |
| sdk | (import) | Programmatic embedding |

## Tool DSL (Macros)

Use `deftool` and `defcommand` macros for concise tool definitions:

```clojure
(require-macros '[macros.tool-dsl :refer [deftool defcommand]])

(deftool web-search
  "Search the web"
  {:query [:string "The search query"]
   :limit [:number "Max results" {:optional true}]}
  [{:keys [query limit]}]
  (let [res (js-await (js/fetch (str "https://api.example.com?q=" query)))]
    (js-await (.json res))))
```
