# Nyma

**Nyma** (Nyma Yokai Mystic Agent) is a minimal, extensible AI coding agent built with **Squint** (ClojureScript) + **Bun** + **Vercel AI SDK** + **Ink**. Write your agent logic in ClojureScript — macros, threading, destructuring, data-oriented design — and ship plain ES modules with zero runtime overhead.

Nyma compiles ClojureScript to native ES modules, runs on Bun for speed, and renders a terminal UI with Ink/React. It is designed around a **middleware pipeline**, **event-sourced state**, **Pedestal-style interceptor chains**, and a **namespaced extension system** — making it easy to extend without modifying core code.

## vs. pi-mono

Nyma is a spiritual successor to [pi-mono](https://github.com/badlogic/pi-mono) — Mario Zechner's TypeScript coding agent — with a different set of trade-offs:

| | pi-mono | Nyma |
|---|---|---|
| **Language** | TypeScript | ClojureScript (Squint) → ES modules |
| **Extension language** | TypeScript | TypeScript **or** ClojureScript |
| **Schema library** | TypeBox | Zod (data-driven, no imports needed in extensions) |
| **State model** | Mutable atom | Event-sourced store with full history |
| **Tool pipeline** | Linear | Pedestal-style interceptor chain (composable middleware) |
| **Extension isolation** | Shared namespace | Capability-gated, namespaced sandbox |
| **Macros** | None | `deftool`, `defcommand`, `definterceptor`, `defreducer`, `defextension` |
| **Session storage** | JSONL | JSONL tree + optional SQLite with usage tracking |

**Why Nyma over pi-mono:**

- **Macros eliminate boilerplate.** `deftool` generates schema + handler in one form. `defextension` wires up activation/deactivation automatically.
- **Interceptors compose cleanly.** Adding logging, rate-limiting, or permission checks is a chain entry — not a wrapper function.
- **Extensions are safer.** Each plugin gets a scoped API with explicit capability declarations (`tools`, `commands`, `middleware`, `state`, `ui`). No extension can reach outside its declared scope.
- **ClojureScript extensions** get the full macro DSL, threading (`->`), and data-oriented idioms — a significant ergonomic win over imperative TS for complex agent logic.
- **pi-mono extensions port with minimal changes** — the API surface is intentionally compatible. See `docs/porting-guide-ts.md`.

## Architecture

```
@agent/cli  ─→  @agent/core  ─→  ai (Vercel AI SDK)
                     │
                @agent/ui (Ink components)
```

Tool execution flows through the **middleware pipeline** (interceptor chain). Extension isolation uses namespacing and capability gating. State mutations go through an **event-sourced store**.

```
user input → loop.cljs → middleware pipeline → tool.execute
                              ↑
                    registered interceptors
                    (logging, before-hook-compat, custom)
```

| Layer | Namespace | Role |
|-------|-----------|------|
| CLI | `agent.cli` | Entry point, arg parsing, mode dispatch |
| Core | `agent.core` | Agent factory, state management |
| Loop | `agent.loop` | Execution loop via `streamText` |
| Events | `agent.events` | Typed event bus (sync + async `emit-async`) |
| Tools | `agent.tools` | Built-in tools: read, write, edit, bash |
| Registry | `agent.tool-registry` | Tool activation/deactivation |
| Extensions | `agent.extensions` | Extension API for plugins |
| Extension Loader | `agent.extension-loader` | Dual .cljs/.ts loader with scoped APIs |
| Sessions | `agent.sessions.manager` | JSONL tree-based conversation storage |
| Compaction | `agent.sessions.compaction` | Context window summarization |
| Settings | `agent.settings.manager` | Two-scope config (global + project) |
| Resources | `agent.resources.loader` | Discover prompts, skills, themes |
| UI | `agent.ui.*` | Ink/React terminal components |
| Modes | `agent.modes.*` | Interactive, print, RPC, SDK |
| **Interceptors** | `agent.interceptors` | Pedestal-style interceptor chain engine |
| **Middleware** | `agent.middleware` | Middleware pipeline for tool execution |
| **Protocols** | `agent.protocols` | ISessionStore, IToolProvider, IContextBuilder |
| **Schema** | `agent.schema` | Data-driven Zod schema compiler |
| **State** | `agent.state` | Event-sourced state store with history |
| **Permissions** | `agent.permissions` | Extension capability system |
| **Extension Scope** | `agent.extension-scope` | Namespaced + capability-gated extension API |

## Prerequisites

- [Bun](https://bun.sh) (v1.0+)
- [Node.js](https://nodejs.org) (v18+ for squint CLI)
- An API key for at least one LLM provider:
  - `ANTHROPIC_API_KEY` for Claude (default)
  - `OPENAI_API_KEY` for OpenAI
  - `GOOGLE_GENERATIVE_AI_API_KEY` for Google

## Quick Start

```bash
# Install dependencies
bun install

# Build (compile ClojureScript to JavaScript)
bun run build

# Run the agent
bun run start
```

## Development

### Dev Mode (Watch + Auto-Reload)

```bash
bun run dev
```

This runs two processes concurrently:
1. **`squint watch`** — watches `src/` and `test/` for `.cljs` changes, compiles to `dist/`
2. **`bun --watch`** — watches compiled output and auto-restarts the agent

### Build

```bash
bun run build
```

Compiles all `.cljs` files from `src/` and `test/` to `.mjs` (ES modules) in `dist/`. JSX files (Ink components) compile to `.jsx`. JSON resources are copied as-is.

### REPL

```bash
bun run repl
```

Starts a Squint REPL for interactive ClojureScript development.

### Project Structure

```
src/
  agent/           Core agent modules (all under agent.* namespace)
    cli.cljs       Entry point
    core.cljs      Agent factory
    loop.cljs      Execution loop
    events.cljs    Event bus (sync + async)
    tools.cljs     Built-in tools
    tool_registry.cljs
    extensions.cljs / extension_loader.cljs
    context.cljs
    interceptors.cljs   Interceptor chain engine
    middleware.cljs     Tool execution middleware pipeline
    protocols.cljs      ISessionStore, IToolProvider, IContextBuilder
    schema.cljs         Data-driven Zod schema compiler
    state.cljs          Event-sourced state store
    permissions.cljs    Extension capability gating
    extension_scope.cljs  Namespaced extension API wrapper
    keybindings.cljs    Loads ~/.nyma/keybindings.json user key mappings
    pricing.cljs        Token cost table + calculate-cost
    commands/      Built-in slash command implementations + session export
    providers/     LLM provider registry (Anthropic, OpenAI, Google)
    schema/        TypeBox ↔ Zod adapter for TS extensions
    utils/         Shared utilities (ANSI text, terminal width)
    modes/         Operational modes (interactive, print, rpc, sdk)
    ui/            Ink/React terminal components (.jsx)
      dialogs.cljs       ConfirmDialog, PromptDialog
      notification.cljs  Inline status notifications
      tool_status.cljs   Tool execution display with spinner
      widget_container.cljs  Extension widget rendering
    sessions/      Session management + compaction
      listing.cljs   Scans .jsonl files, returns sorted metadata
      storage.cljs   SQLite-backed session store with usage tracking
    settings/      Configuration system
    resources/     Resource discovery (prompts, skills, themes)
    packages/      Package management
  macros/          Compile-time macros (deftool, defcommand, definterceptor, ...)
test/              Test files (.cljs and .ts)
  integration/     Integration tests (tool pipeline, extension lifecycle, state+events)
built-in/themes/   Default dark/light themes
dist/              Compiled output (generated, gitignored)
```

> **Important:** File paths must mirror namespace prefixes. `(ns agent.foo.bar ...)` must live at `src/agent/foo/bar.cljs`. Squint resolves imports by converting namespace dots to path separators.

### Compilation Model

Squint compiles ClojureScript to plain JavaScript:
- Kebab-case identifiers become snake_case (`create-agent` -> `create_agent`)
- Keywords become string keys (`:role` -> `"role"`)
- Atoms compile to mutable wrappers with `deref()`, `swap()`, `reset()`
- `^:async` on `defn` generates `async function` (note: does not work on anonymous `fn`)
- `#jsx` tag enables JSX output for Ink components

Config in `squint.edn`:
```clojure
{:paths      ["src" "test"]
 :output-dir "dist"
 :extension  "mjs"
 :copy-resources #{:json}}
```

## Testing

```bash
bun test
```

This automatically compiles all source and test files (via the `pretest` script) then runs tests with Bun's built-in test runner.

### Test Structure

Tests are written in ClojureScript using `bun:test` via JS interop:

```clojure
(ns my-module.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]))

(describe "my module"
  (fn []
    (it "does the thing"
      (fn []
        (let [bus (create-event-bus)]
          (-> (expect (fn? (:on bus))) (.toBe true)))))))
```

For async tests, use `defn ^:async` (not `fn ^:async`):

```clojure
(defn ^:async test-async-operation []
  (let [result (js-await (some-async-fn))]
    (-> (expect result) (.toBe "expected"))))

(it "async test" test-async-operation)
```

TypeScript tests (`test/*.test.ts`) are also supported and run alongside compiled ClojureScript tests.

### Current Test Coverage

| Test File | Module | Tests |
|-----------|--------|-------|
| `events.test.cljs` | Event bus (sync + async) | 21 |
| `interceptors.test.cljs` | Interceptor chain engine | 20 |
| `middleware.test.cljs` | Tool middleware pipeline | 12 |
| `protocols.test.cljs` | Protocol dispatch | 14 |
| `schema.test.cljs` | Data-driven schemas | 14 |
| `state.test.cljs` | Event-sourced state store | 15 |
| `permissions.test.cljs` | Extension capabilities | 6 |
| `extension_scope.test.cljs` | Namespaced extension API | 10 |
| `tool_registry.test.cljs` | Tool registry | 8 |
| `session_manager.test.cljs` | Session manager | 10 |
| `core.test.cljs` | Agent factory | 8 |
| `context.test.cljs` | Context building | 5 |
| `tools.test.cljs` | Built-in tools (I/O) | 15 |
| `compaction.test.cljs` | Context compaction | 5 |
| `prompts.test.cljs` | Template expansion | 5 |
| `settings.test.cljs` | Settings manager | 5 |
| `extension_loader.test.cljs` | Extension loader | 8 |
| `loop.test.cljs` | Execution loop | 10 |
| `integration/tool_pipeline.test.cljs` | Full middleware chain | 6 |
| `integration/extension_lifecycle.test.cljs` | Extension load/use/deactivate | 7 |
| `integration/state_events.test.cljs` | State + event bus integration | 5 |
| `workspace_config.test.cljs` | Workspace config + command aliases | 20 |
| `token_preview.test.cljs` | Live token count preview | 10 |
| `clear_session.test.cljs` | /clear session reset | 9 |
| `commands_skills.test.cljs` | Skills activation (/skill, /skills, dedup) | 16 |
| `model_roles.test.cljs` | Model roles, settings, model_resolve event | 12 |
| `grouped_tool_display.test.cljs` | Grouped tool display, message grouping | 12 |
| `ast_tools.test.cljs` | AST tool command building, truncation | 10 |
| `prompt_history.test.cljs` | Prompt history SQLite + picker | 11 |
| `stats_dashboard.test.cljs` | Usage stats aggregate queries | 9 |

**Total: ~344 tests**

## Running the Agent

### Interactive Mode (Default)

```bash
bun run start
```

Launches the full terminal UI with message display, text input, and keyboard shortcuts.

### Print Mode

```bash
bun run start -- -p "Explain this codebase"
```

Runs once with the given prompt, prints the response, and exits. Useful for scripting.

### JSON Mode

```bash
bun run start -- --mode json "What files are in src/"
```

Like print mode but outputs structured JSON.

### RPC Mode

```bash
bun run start -- --mode rpc
```

Starts a JSONL stdio protocol for external process communication.

### CLI Flags

| Flag | Description | Default |
|------|-------------|---------|
| `-m, --model` | LLM model to use | `claude-sonnet-4-20250514` |
| `--provider` | AI provider | `anthropic` |
| `--mode` | Operational mode | `interactive` |
| `-p, --print` | Print mode shorthand | — |
| `-c, --continue` | Continue last session | — |
| `-r, --resume` | Resume a session | — |
| `--tools` | Comma-separated tool list | `read,write,edit,bash` |
| `--thinking` | Enable extended thinking | `off` |
| `--session` | Custom session path | — |
| `--fork` | Fork current session | — |
| `--no-session` | Disable session persistence | — |

### SDK / Programmatic Usage

```javascript
import { create_session } from "./dist/modes/sdk.mjs";

const session = await create_session({
  model: "claude-sonnet-4-20250514",
  "system-prompt": "You are a helpful assistant."
});

session.on("message_update", (chunk) => {
  process.stdout.write(chunk.textDelta);
});

await session.send("Hello, what can you do?");
```

The SDK returns an object with:
- `send(text)` — send a user message (async)
- `steer(msg)` — inject a steering message mid-turn
- `on(event, handler)` — subscribe to events
- `state()` — get current agent state

## Configuration

### Settings Scopes

Settings are resolved in priority order:

1. **Runtime overrides** (CLI flags)
2. **Project settings** (`.nyma/settings.json`)
3. **Global settings** (`~/.nyma/settings.json`)
4. **Defaults**

### Workspace Config

The built-in `workspace-config` extension reads `.nyma/settings.json` at startup and registers per-project command aliases and flags:

```json
{
  "aliases": {
    "cc": "/model claude-sonnet-4-6",
    "bye": "/exit"
  },
  "flags": {}
}
```

Aliases become `/slash` commands immediately — `/cc` would switch to claude-sonnet-4-6 in the example above. Manage aliases at runtime with `/alias` and reload the file with `/workspace-config__reload`.

### Default Settings

```json
{
  "model": "claude-sonnet-4-20250514",
  "provider": "anthropic",
  "thinking": "off",
  "compaction": { "enabled": true, "threshold": 0.85 },
  "retry": { "enabled": true, "max-retries": 3 },
  "tools": ["read", "write", "edit", "bash"],
  "steering-mode": "one-at-a-time",
  "follow-up-mode": "one-at-a-time",
  "transport": "auto"
}
```

## Extensions

Extensions add tools, commands, keyboard shortcuts, middleware, and UI hooks. They can be written in ClojureScript or TypeScript.

### Built-in Extensions

Nyma ships with several extension suites in `src/agent/extensions/`:

| Extension | Namespace | Purpose |
|-----------|-----------|---------|
| `agent_shell` | `agent-shell` | Unified frontend for ACP coding agents (Claude Code, Gemini CLI, etc.) |
| `token_suite` | `token-suite` | Token optimizations, smart compaction, live cost preview (`/token-preview`) |
| `workspace_config` | `workspace-config` | Per-project aliases and flags from `.nyma/settings.json` |
| `bash_suite` | `bash-suite` | Shell execution helpers |
| `desktop_notify` | `desktop-notify` | System desktop notifications on turn completion |
| `mention_files` | `mention-files` | `@filename` file insertion in the editor |

### Extension Locations

- **Global:** `~/.nyma/extensions/`
- **Project:** `.nyma/extensions/`

### Namespacing and Capabilities

Each extension runs in a **scoped API sandbox**. All tools and commands are automatically prefixed with the extension's namespace, preventing collisions:

```
Extension "git-tools" registers "status" → stored as "git-tools/status"
```

Control which API methods the extension can access via a capabilities list in `extension.json`:

```json
{
  "namespace": "git-tools",
  "capabilities": ["tools", "events", "commands"]
}
```

Available capabilities: `tools`, `commands`, `shortcuts`, `events`, `messages`, `state`, `ui`, `middleware`, `all`.

### ClojureScript Extension

```clojure
;; .nyma/extensions/my_ext.cljs
(fn [api]
  (.registerTool api "my-tool"
    #js {:description "Does something"
         :execute     (fn [params] (str "Result: " (:input params)))})

  (.registerCommand api "my-cmd"
    #js {:description "A slash command"
         :handler     (fn [args ctx] (println "Running!" args))})

  ;; Tools registered as "my-ext/my-tool", commands as "my-ext/my-cmd"
  (fn [] (.unregisterTool api "my-tool")))
```

### TypeScript Extension

```typescript
// .nyma/extensions/my_ext.ts
export default function(api) {
  api.registerTool("search", {
    description: "Search the web",
    execute: async ({ query }) => fetch(`https://api.example.com?q=${query}`)
  });

  // Register middleware
  api.addMiddleware({
    name: "rate-limiter",
    enter: (ctx) => {
      if (tooManyCallsRecently()) ctx.cancelled = true;
      return ctx;
    }
  });

  return () => {
    api.removeMiddleware("rate-limiter");
  };
}
```

### Full Extension API

**Event subscriptions:**
- `api.on(event, handler, priority?)` — subscribe; higher priority runs first (default 0)
- `api.off(event, handler)` — unsubscribe

**Tool registration:**
- `api.registerTool(name, tool)` — add an LLM-callable tool (auto-namespaced)
- `api.unregisterTool(name)` — remove a tool

**Command registration:**
- `api.registerCommand(name, opts)` — add a `/slash` command (auto-namespaced)
- `api.unregisterCommand(name)` — remove a command
- `api.getCommands()` — get all registered commands

**Middleware:**
- `api.addMiddleware(interceptor)` — add a tool-execution interceptor
- `api.removeMiddleware(name)` — remove an interceptor by name

**State:**
- `api.getState()` — read current agent state
- `api.dispatch(eventType, data)` — dispatch a state mutation event
- `api.onStateChange(listener)` — subscribe to state changes, returns unsubscribe fn

**Messaging:**
- `api.sendMessage(msg)` — inject a raw message into state
- `api.sendUserMessage(text, opts)` — inject a user message (`{deliverAs: "steer"|"followUp"}`)

**UI (interactive mode only):**
- `api.ui.available` — `false` in print/json/rpc mode, `true` in interactive mode; always check before calling UI methods
- `api.ui.showOverlay(content)` — display modal content
- `api.ui.confirm(msg)` — show Yes/No dialog, returns `Promise<boolean>`

### Using the `defextension` Macro

```clojure
(require-macros '[macros.tool-dsl :refer [defextension]])

(defextension git-tools
  {:capabilities #{:tools :events :commands}}
  [api]
  (.registerTool api "status"
    #js {:description "Git status"
         :execute     (fn [_] (js-await (run-bash "git status")))})
  ;; Return cleanup fn
  (fn [] (.unregisterTool api "status")))
```

## Tool DSL (Compile-Time Macros)

All macros live in `macros.tool-dsl`:

### `deftool` — Define LLM-callable tools

```clojure
(deftool web-search
  "Search the web"
  {:query {:type :string :description "The search query"}
   :limit {:type :number :description "Max results" :optional true}}
  [{:keys [query limit]}]
  (js-await (js/fetch (str api-url query))))
```

### `defcommand` — Define slash commands

```clojure
(defcommand deploy-status
  "Show current deployment status"
  [args ctx]
  (js-await (run-bash "kubectl get pods")))
```

### `definterceptor` / `defmiddleware` — Define interceptors

```clojure
(definterceptor audit-log
  {:enter (fn [ctx] (log "enter" (:tool-name ctx)) ctx)
   :leave (fn [ctx] (log "leave" (:tool-name ctx)) ctx)})

(defmiddleware rate-limiter
  {:enter (fn [ctx]
            (if (too-many-calls?)
              (assoc ctx :cancelled true)
              ctx))})
```

### `defreducer` — Define state reducers

```clojure
(defreducer handle-approval :tool-approved [state data]
  (update state :approved-tools conj (:tool-name data)))
```

## Events

The event bus provides lifecycle hooks for extensions:

| Event | When |
|-------|------|
| `agent_start` / `agent_end` | Agent lifecycle |
| `turn_start` / `turn_end` | Each conversation turn |
| `message_start` / `message_update` / `message_end` | Streaming messages |
| `tool_call` / `tool_result` | Tool execution |
| `before_tool_call` | Before tool runs — set `ctx.cancelled = true` to block |
| `session_start` / `session_end` / `session_switch` | Session lifecycle |
| `before_compact` / `compact` | Context compaction |
| `context` | Context building |
| `input` | User input |
| `editor_change` | User typing in editor — `{text: string}` payload |
| `session_clear` | `/clear` invoked — extensions may reset their agent sessions |

### Async Events

Use `emit-async` when handlers need to complete before the caller proceeds:

```clojure
;; In extension code:
((:emit-async bus) "before_compact" data)  ;; awaits all handlers
```

## Resources

### Skills

Place a directory with a `SKILL.md` file in `~/.nyma/skills/` or `.nyma/skills/`. Skills inject system prompt instructions and can register additional tools.

**Activating skills:**
- `/skills` — opens a fuzzy picker to browse and activate available skills
- `/skill <name>` — activate a skill directly by name

Active skills are tracked in agent state (`:active-skills`) to prevent duplicate injection. The system prompt lists available skills with their description (first non-heading line of `SKILL.md`).

```
~/.nyma/skills/
  git-helper/
    SKILL.md      ← # Git Helper\nAutomates git workflows.
    tools.cljs    ← optional extra tools (loaded on activation)
```

### Prompts

Place `.md` files in `~/.nyma/prompts/` or `.nyma/prompts/`. They are loaded as templates for reuse, supporting `{{variable}}` placeholder expansion.

### Themes

JSON theme files in `~/.nyma/themes/` or `.nyma/themes/`. Built-in themes: `dark` and `light`.

### System Prompt

Create a `SYSTEM.md` in `.nyma/` or `~/.nyma/` to provide a custom system prompt. Create an `AGENTS.md` at the project root to provide project-specific context.

## Dependencies

| Package | Purpose |
|---------|---------|
| `ai` | Vercel AI SDK — LLM integration |
| `@ai-sdk/anthropic` | Claude provider |
| `@ai-sdk/openai` | OpenAI provider |
| `@ai-sdk/google` | Google provider |
| `ink` | React-based terminal UI |
| `ink-text-input` | Text input component |
| `react` | UI framework |
| `zod` | Schema validation for tool parameters |
| `squint-cljs` | ClojureScript-to-JS compiler |
| `nanoid` | ID generation |

## License

MIT
