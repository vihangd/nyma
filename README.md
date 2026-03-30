# Nyma

A minimal, extensible AI coding agent built with **Squint** (ClojureScript) + **Bun** + **Vercel AI SDK** + **Ink**.

Nyma compiles ClojureScript to native ES modules with zero runtime overhead, runs on Bun for speed, and renders a terminal UI with Ink/React.

## Architecture

```
@agent/cli  ─→  @agent/core  ─→  ai (Vercel AI SDK)
                     │
                @agent/ui (Ink components)
```

| Layer | Namespace | Role |
|-------|-----------|------|
| CLI | `agent.cli` | Entry point, arg parsing, mode dispatch |
| Core | `agent.core` | Agent factory, state management |
| Loop | `agent.loop` | Execution loop via `streamText` |
| Events | `agent.events` | Typed event bus for lifecycle hooks |
| Tools | `agent.tools` | Built-in tools: read, write, edit, bash |
| Registry | `agent.tool-registry` | Tool activation/deactivation |
| Extensions | `agent.extensions` | Extension API for plugins |
| Sessions | `agent.sessions.manager` | JSONL tree-based conversation storage |
| Compaction | `agent.sessions.compaction` | Context window summarization |
| Settings | `agent.settings.manager` | Two-scope config (global + project) |
| Resources | `agent.resources.loader` | Discover prompts, skills, themes |
| UI | `agent.ui.*` | Ink/React terminal components |
| Modes | `agent.modes.*` | Interactive, print, RPC, SDK |

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
  agent/           Core agent (cli, core, loop, events, tools, registry, extensions, context)
  modes/           Operational modes (interactive, print, rpc, sdk)
  ui/              Ink/React terminal components
  sessions/        Session management + compaction
  settings/        Configuration system
  resources/       Resource discovery (prompts, skills, themes)
  packages/        Package management
  macros/          Compile-time macros (deftool, defcommand)
test/              Test files (.cljs and .ts)
built-in/themes/   Default dark/light themes
dist/              Compiled output (generated)
```

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
| `events.test.cljs` | Event bus | 9 |
| `tool_registry.test.cljs` | Tool registry | 8 |
| `session_manager.test.cljs` | Session manager | 10 |
| `core.test.cljs` | Agent factory | 8 |
| `context.test.cljs` | Context building | 5 |
| `tools.test.cljs` | Built-in tools (I/O) | 10 |
| `compaction.test.cljs` | Context compaction | 1 |

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
2. **Project settings** (`.agent/settings.json`)
3. **Global settings** (`~/.agent/settings.json`)
4. **Defaults**

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

Extensions add tools, commands, keyboard shortcuts, and UI hooks. They can be written in ClojureScript or TypeScript.

### Extension Locations

- **Global:** `~/.agent/extensions/`
- **Project:** `.agent/extensions/`

### ClojureScript Extension

```clojure
;; .agent/extensions/my_ext.cljs
(fn [api]
  (.registerTool api "my-tool"
    #js {:description "Does something"
         :parameters  (.object z #js {:input (.string z)})
         :execute     (fn [params] (str "Result: " (:input params)))})

  (.registerCommand api "my-cmd"
    #js {:description "A slash command"
         :handler     (fn [args ctx] (println "Running!" args))}))
```

### TypeScript Extension

```typescript
// .agent/extensions/my_ext.ts
export default function(api) {
  api.registerTool("search", {
    description: "Search the web",
    parameters: z.object({ query: z.string() }),
    execute: async ({ query }) => fetch(`https://api.example.com?q=${query}`)
  });
}
```

### Tool DSL (Compile-Time Macros)

Define tools concisely with the `deftool` macro:

```clojure
(deftool web-search
  "Search the web"
  {:query [:string "The search query"]
   :limit [:number "Max results" {:optional true}]}
  [params]
  (js-await (js/fetch (str api-url (:query params)))))
```

Define slash commands with `defcommand`:

```clojure
(defcommand deploy-status
  "Show current deployment status"
  [args ctx]
  (js-await (run-bash "kubectl get pods")))
```

## Events

The event bus provides lifecycle hooks for extensions:

| Event | When |
|-------|------|
| `agent_start` / `agent_end` | Agent lifecycle |
| `turn_start` / `turn_end` | Each conversation turn |
| `message_start` / `message_update` / `message_end` | Streaming messages |
| `tool_call` / `tool_result` | Tool execution |
| `tool_execution_start` / `tool_execution_end` | Tool execution timing |
| `session_start` / `session_end` / `session_switch` | Session lifecycle |
| `before_compact` / `compact` | Context compaction |
| `context` | Context building |
| `input` | User input |

## Resources

### Skills

Place a directory with a `SKILL.md` file in `~/.agent/skills/` or `.agent/skills/`. Skills inject system prompt instructions and can register additional tools.

### Prompts

Place `.md` files in `~/.agent/prompts/` or `.agent/prompts/`. They are loaded as templates for reuse.

### Themes

JSON theme files in `~/.agent/themes/` or `.agent/themes/`. Built-in themes: `dark` and `light`.

### System Prompt

Create a `SYSTEM.md` in `.agent/` or `~/.agent/` to provide a custom system prompt. Create an `AGENTS.md` at the project root to provide project-specific context.

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
