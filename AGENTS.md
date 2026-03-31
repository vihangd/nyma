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
| `src/agent/ui/app.cljs` | `agent.ui.app` | Root Ink component |
| `src/agent/ui/chat_view.cljs` | `agent.ui.chat-view` | Message rendering |
| `src/agent/ui/editor.cljs` | `agent.ui.editor` | Text input component |
| `src/agent/sessions/manager.cljs` | `agent.sessions.manager` | JSONL tree session storage |
| `src/agent/sessions/compaction.cljs` | `agent.sessions.compaction` | Context window compaction |
| `src/agent/resources/loader.cljs` | `agent.resources.loader` | Resource discovery |
| `src/agent/settings/manager.cljs` | `agent.settings.manager` | Two-scope settings |
| `src/agent/modes/interactive.cljs` | `agent.modes.interactive` | TUI mode |
| `src/agent/modes/print.cljs` | `agent.modes.print` | Print mode |
| `src/agent/modes/rpc.cljs` | `agent.modes.rpc` | JSONL stdio RPC mode |
| `src/agent/modes/sdk.cljs` | `agent.modes.sdk` | Programmatic SDK mode |
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

## Squint Conventions & Pitfalls

Squint compiles ClojureScript to JavaScript, but several Clojure idioms do **not** work as expected. These have caused real bugs in this codebase.

### `fn ^:async` does NOT work — use `defn ^:async`

Squint only generates `async function` for top-level `defn`. The `^:async` metadata on anonymous `fn` is silently ignored, producing a non-async function where `await` becomes a runtime syntax error.

```clojure
;; BROKEN — compiles to plain `function`, `await` fails at runtime
(def handler (fn ^:async [x] (js-await (some-promise x))))

;; CORRECT — compiles to `async function`
(defn ^:async handler [x] (js-await (some-promise x)))
```

This applies everywhere: tool execute functions, event handlers, test callbacks. If you need an async callback, extract it to a named `defn ^:async`.

### Sets are not callable

In Clojure, sets work as functions: `(#{:a :b} :a) ;=> :a`. In squint, sets compile to JS `Set` objects which are **not callable**.

```clojure
;; BROKEN — TypeError: ac is not a function
(filter (fn [[k _]] (my-set k)) items)

;; CORRECT
(filter (fn [[k _]] (contains? my-set k)) items)
```

### Maps/objects are not callable — use `get`

`(into {} ...)` produces a plain JS object. You cannot call it as a function like a Clojure map.

```clojure
;; BROKEN — TypeError: by-id is not a function
(let [by-id (into {} (map (fn [e] [(:id e) e]) entries))]
  (by-id some-key))

;; CORRECT
(let [by-id (into {} (map (fn [e] [(:id e) e]) entries))]
  (get by-id some-key))
```

**Rule:** Always use `get`, `get-in`, or keyword access (`:key obj`) — never call a map/object as a function.

### `js->clj` does not exist

Squint compiles the symbol `js->clj` but does not provide the function at runtime (`ReferenceError: js__GT_clj is not defined`). This is because squint maps **are** plain JS objects — there is no conversion needed.

```clojure
;; BROKEN — ReferenceError at runtime
(js->clj (js/JSON.parse json-str) :keywordize-keys true)

;; CORRECT — JSON.parse output is already a plain object, keywords are strings
(js/JSON.parse json-str)
```

`clj->js` **does** work (for converting to JS objects with `JSON.stringify`).

### `Bun.spawn` requires explicit pipe config

Without `stdout: "pipe"` and `stderr: "pipe"`, `Bun.spawn` does not capture output — it goes directly to the parent process.

```clojure
;; BROKEN — stderr is empty string, output goes to terminal
(js/Bun.spawn #js ["sh" "-c" cmd] #js {:timeout 30000})

;; CORRECT
(js/Bun.spawn #js ["sh" "-c" cmd]
  #js {:timeout 30000 :stdout "pipe" :stderr "pipe"})
```

### Namespace path must match file path

Squint resolves ClojureScript namespace requires by converting the namespace to a file path. `agent.sessions.manager` becomes `./agent/sessions/manager.mjs`. If the file lives at `src/sessions/manager.cljs` (compiled to `dist/sessions/manager.mjs`), the import will fail with "Cannot find package".

**Rule:** The directory structure under `src/` must mirror the namespace prefix. A file declaring `(ns agent.foo.bar ...)` must live at `src/agent/foo/bar.cljs`.

### JSX files must be imported with explicit `.jsx` extension

Squint compiles files that use `#jsx` or declare `{:squint/extension "jsx"}` to `.jsx`. However, namespace-based requires always generate `.mjs` import paths — even from within another `.jsx` file. This causes a "Cannot find module" error at runtime.

```clojure
;; BROKEN — generates `import { Header } from './header.mjs'` but file is header.jsx
[agent.ui.header :refer [Header]]

;; CORRECT — explicit string require with .jsx extension
["./header.jsx" :refer [Header]]
```

For dynamic imports of JSX modules, use `(js/import "path/to/file.jsx")` directly.

### `js->clj` does not exist — use plain JS objects

See above. Additionally: `clojure.string` must be explicitly required even for namespaced calls like `clojure.string/join`. Without the require, it compiles to `clojure.string.join(...)` which is a `ReferenceError` at runtime.

```clojure
;; BROKEN — ReferenceError: clojure is not defined
(clojure.string/join "\n" items)

;; CORRECT
(:require [clojure.string :as str])
(str/join "\n" items)
```

### `process.env.FOO` is a property, not a function

Squint compiles `(js/process.env.HOME)` as a function call `process.env.HOME()` — TypeError at runtime. Use the `..` interop form:

```clojure
;; BROKEN — TypeError: process.env.HOME is not a function
(js/process.env.HOME)

;; CORRECT
(.. js/process -env -HOME)
```

### Paren discipline in JSX

JSX components (using `#jsx` reader tag) mix Hiccup-style brackets with ClojureScript parens. Extra or missing parens are hard to spot and produce confusing "Unmatched delimiter" errors at compile time. Also note:

```clojure
;; BROKEN — property access does not take a default argument
(.-textDelta chunk "")

;; CORRECT
(or (.-textDelta chunk) "")
```

## Testing

Tests are in `test/` as `.cljs` (compiled by squint) or `.ts` files. Run with `bun test`.

For async tests, use `defn ^:async` helpers (since `fn ^:async` doesn't work):

```clojure
(defn ^:async test-my-async-thing []
  (let [result (js-await (some-async-fn))]
    (-> (expect result) (.toBe "expected"))))

(it "does the async thing" test-my-async-thing)
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

## Git Workflow

- Commit messages: `<module>: <short description>` (e.g., `memory: add TTL to working memory`)
- One logical change per commit
