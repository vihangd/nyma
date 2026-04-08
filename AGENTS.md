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

Tool execution now flows through the **middleware pipeline** (interceptor chain). Extension namespacing and capability gating isolate plugins. Agent state mutations go through the **event-sourced state store**.

```
user input → loop.cljs → middleware pipeline → tool.execute
                              ↑
                    registered interceptors
                    (logging, before-hook-compat, custom)
```

### Key Namespaces

| File | Namespace | Purpose |
|------|-----------|---------|
| `src/agent/cli.cljs` | `agent.cli` | Entry point, arg parsing, mode dispatch |
| `src/agent/core.cljs` | `agent.core` | Agent state, `create-agent` factory |
| `src/agent/loop.cljs` | `agent.loop` | `run`, `steer`, `follow-up` |
| `src/agent/events.cljs` | `agent.events` | Typed event bus (sync + async) |
| `src/agent/tools.cljs` | `agent.tools` | Built-in tools: read, write, edit, bash |
| `src/agent/tool_registry.cljs` | `agent.tool-registry` | Active/inactive tool management |
| `src/agent/extensions.cljs` | `agent.extensions` | Extension API factory |
| `src/agent/extension_loader.cljs` | `agent.extension-loader` | Dual .cljs/.ts loader with scoped APIs |
| `src/agent/context.cljs` | `agent.context` | Message filtering, context building |
| `src/agent/interceptors.cljs` | `agent.interceptors` | Pedestal-style interceptor chain engine |
| `src/agent/middleware.cljs` | `agent.middleware` | Middleware pipeline for tool execution |
| `src/agent/protocols.cljs` | `agent.protocols` | ISessionStore, IToolProvider, IContextBuilder |
| `src/agent/schema.cljs` | `agent.schema` | Data-driven Zod schema compiler |
| `src/agent/state.cljs` | `agent.state` | Event-sourced state store |
| `src/agent/permissions.cljs` | `agent.permissions` | Extension capability system |
| `src/agent/extension_scope.cljs` | `agent.extension-scope` | Namespaced + capability-gated extension API |
| `src/agent/extensions/agent_shell/features/effort_switcher.cljs` | `agent.extensions.agent-shell.features.effort-switcher` | `/effort <low\|medium\|high\|max\|auto>` — thinking budget control via ACP |
| `src/agent/extensions/workspace_config/index.cljs` | `agent.extensions.workspace-config.index` | Workspace config — loads `.nyma/settings.json`, registers `/alias` and `/reload` |
| `src/agent/extensions/workspace_config/aliases.cljs` | `agent.extensions.workspace-config.aliases` | Custom command aliases — `/alias` CRUD, late-binding dispatch |
| `src/agent/extensions/token_suite/token_preview.cljs` | `agent.extensions.token-suite.token-preview` | Live token-count preview widget — subscribes to `editor_change`, shows `~N tokens` |
| `src/agent/commands/builtins.cljs` | `agent.commands.builtins` | Built-in slash command implementations (/help, /model, /clear, /sessions, /export, etc.) |
| `src/agent/commands/share.cljs` | `agent.commands.share` | Session export to Markdown and HTML |
| `src/agent/keybindings.cljs` | `agent.keybindings` | Loads `~/.nyma/keybindings.json` user key mappings |
| `src/agent/pricing.cljs` | `agent.pricing` | Token cost table + `calculate-cost` for all supported models |
| `src/agent/providers/registry.cljs` | `agent.providers.registry` | LLM provider registry (register/resolve by name) |
| `src/agent/providers/builtins.cljs` | `agent.providers.builtins` | Default Anthropic/OpenAI/Google provider factories |
| `src/agent/schema/typebox_adapter.cljs` | `agent.schema.typebox-adapter` | TypeBox ↔ Zod schema bridge for TS extensions |
| `src/agent/utils/ansi.cljs` | `agent.utils.ansi` | ANSI-aware text utilities (`truncate-text`, `terminal-width`) |
| `src/agent/ui/app.cljs` | `agent.ui.app` | Root Ink component |
| `src/agent/ui/chat_view.cljs` | `agent.ui.chat-view` | Message rendering |
| `src/agent/ui/editor.cljs` | `agent.ui.editor` | Text input component |
| `src/agent/ui/dialogs.cljs` | `agent.ui.dialogs` | `ConfirmDialog` and `PromptDialog` components |
| `src/agent/ui/notification.cljs` | `agent.ui.notification` | Inline status notification component |
| `src/agent/ui/tool_status.cljs` | `agent.ui.tool-status` | Tool execution status display with spinner |
| `src/agent/ui/widget_container.cljs` | `agent.ui.widget-container` | Extension widget rendering (above/below chat) |
| `src/agent/sessions/manager.cljs` | `agent.sessions.manager` | JSONL tree session storage |
| `src/agent/sessions/compaction.cljs` | `agent.sessions.compaction` | Context window compaction |
| `src/agent/sessions/listing.cljs` | `agent.sessions.listing` | Scans `.jsonl` session files, returns sorted metadata |
| `src/agent/sessions/storage.cljs` | `agent.sessions.storage` | SQLite-backed session entry store with usage tracking |
| `src/agent/resources/loader.cljs` | `agent.resources.loader` | Resource discovery |
| `src/agent/settings/manager.cljs` | `agent.settings.manager` | Two-scope settings |
| `src/agent/modes/interactive.cljs` | `agent.modes.interactive` | TUI mode |
| `src/agent/modes/print.cljs` | `agent.modes.print` | Print mode |
| `src/agent/modes/rpc.cljs` | `agent.modes.rpc` | JSONL stdio RPC mode |
| `src/agent/modes/sdk.cljs` | `agent.modes.sdk` | Programmatic SDK mode |
| `src/macros/tool_dsl.cljs` | `macros.tool-dsl` | Compile-time macros: `deftool`, `defcommand`, `definterceptor`, `defmiddleware`, `defreducer`, `defextension` |

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

### `name`, `keyword?`, and `keyword` are not available at runtime

These Clojure core functions compile to bare identifiers (`name(k)`, `keyword_QMARK_(x)`, `keyword(s)`) that are `ReferenceError`s at runtime. In Squint, keywords **are** strings — no conversion is needed.

```clojure
;; BROKEN — ReferenceError: name is not defined
(name :my-key)  ; => wants "my-key"

;; CORRECT — just use str, or use the keyword directly (it's already a string)
(str :my-key)   ; => ":my-key"  (includes the colon)
;; For keyword→string, just use the string literal directly

;; BROKEN — ReferenceError: keyword_QMARK_ is not defined
(keyword? x)

;; CORRECT — check for string type instead
(string? x)

;; BROKEN — ReferenceError: keyword is not defined
(keyword "foo")

;; CORRECT — just use the string
"foo"
```

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

;; For deep-cloning a JS object to a plain structure:
(js/JSON.parse (js/JSON.stringify js-obj))
```

`clj->js` **does** work (for converting to JS objects with `JSON.stringify`).

### `array-seq` does not exist — use `seq` or pass JS arrays directly

`array-seq` compiles to a bare identifier that is not defined at runtime. In Squint, JS arrays are already iterable — `seq`, `vec`, `into`, `map`, `filter`, `reduce` all work on them natively.

```clojure
;; BROKEN — ReferenceError: array_seq is not defined
(let [items (array-seq js-array)] ...)

;; CORRECT — seq works on JS arrays
(let [items (seq js-array)] ...)

;; ALSO CORRECT — into/map/filter work directly on JS arrays
(into [] js-array)
(map inc js-array)
(into [cmd] (or args []))  ;; args is a JS array — no conversion needed
```

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

### `clojure.string` must be required explicitly

`clojure.string/join` without a require compiles to `clojure.string.join(...)` — a `ReferenceError` at runtime.

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

### Props use string keys — kebab-case ≠ camelCase

Squint keywords become string keys. `:on-submit` becomes `"on-submit"`, but `:onSubmit` becomes `"onSubmit"` — these are different keys. If a caller passes `{:onSubmit handler}` and the component destructures `{:keys [on-submit]}`, the prop will be `undefined`.

**Rule:** Use camelCase for JSX component props to match React conventions. Both caller and receiver must use the same casing.

```clojure
;; Caller (app.cljs)
[Editor {:onSubmit handle-submit :streaming streaming}]

;; Receiver (editor.cljs) — must match the caller's casing
(defn Editor [{:keys [onSubmit streaming theme]}]
  ...)
```

### Paren discipline in JSX

JSX components (using `#jsx` reader tag) mix Hiccup-style brackets with ClojureScript parens. Extra or missing parens are hard to spot and produce confusing "Unmatched delimiter" errors at compile time. Also note:

```clojure
;; BROKEN — property access does not take a default argument
(.-textDelta chunk "")

;; CORRECT
(or (.-textDelta chunk) "")
```

### Keyword args in functions — use explicit opts map

Squint's varargs destructuring `[& {:keys [a b]}]` does **not** work reliably. Pass an explicit opts map instead:

```clojure
;; BROKEN — keyword args destructuring silently fails
(defn interceptor [name & {:keys [enter leave]}] ...)
(interceptor :my-int :enter (fn [ctx] ctx))  ; enter is nil

;; CORRECT — explicit opts map
(defn interceptor [name opts]
  {:name name :enter (:enter opts) :leave (:leave opts)})
(interceptor :my-int {:enter (fn [ctx] ctx)})
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

## Interceptor Chain

The interceptor chain (`src/agent/interceptors.cljs`) is the execution engine underlying the middleware pipeline. It's modeled after Pedestal interceptors.

### Concepts

- Each interceptor is a map: `{:name keyword, :enter fn, :leave fn, :error fn}`
- `:enter` stages run **left-to-right** through the chain
- `:leave` stages run **right-to-left** (unwinding)
- `:error` stages run right-to-left from the point of failure
- All stage functions may return Promises (auto-awaited)
- The context map (`ctx`) flows through all stages

### API

```clojure
(require '[agent.interceptors :refer [execute interceptor into-chain]])

;; Create an interceptor
(def my-int
  (interceptor :my-int
    {:enter (fn [ctx] (assoc ctx :started true))
     :leave (fn [ctx] (assoc ctx :finished true))
     :error (fn [ctx] (assoc ctx :recovered true))}))

;; Execute a chain
(js-await (execute [my-int] {:some "data"}))

;; Compose chains
(def combined (into-chain chain-a chain-b extra-int))
```

### Using the `definterceptor` Macro

```clojure
(require-macros '[macros.tool-dsl :refer [definterceptor]])

(definterceptor audit-log
  {:enter (fn [ctx] (println "entering" (:tool-name ctx)) ctx)
   :leave (fn [ctx] (println "leaving" (:tool-name ctx)) ctx)})
```

## Middleware Pipeline

The middleware pipeline (`src/agent/middleware.cljs`) wraps tool execution as an interceptor chain. Each tool call flows through all registered interceptors before and after execution.

### Built-in Interceptors

- **`tool-execution-interceptor`** — terminal interceptor that calls `tool.execute` with args
- **`before-hook-compat`** — bridges the legacy `before_tool_call` event system; emits the event and checks `ctx.cancelled`

### API

```clojure
;; pipeline is part of the agent map: (:middleware agent)
(let [pipeline (:middleware agent)]
  ;; Add custom interceptor
  ((:add pipeline) my-interceptor)
  ((:add pipeline) my-interceptor {:position :first})  ; run before others

  ;; Remove by name
  ((:remove pipeline) :my-interceptor-name)

  ;; Execute directly (loop.cljs does this automatically)
  (js-await ((:execute pipeline) "tool-name" tool-obj args-map)))
```

### Extension API

```javascript
// Register middleware from an extension
api.addMiddleware({
  name: "rate-limiter",
  enter: (ctx) => {
    if (tooManyCallsRecently()) ctx.cancelled = true;
    return ctx;
  }
});

api.removeMiddleware("rate-limiter");
```

### Using the `defmiddleware` Macro

```clojure
(require-macros '[macros.tool-dsl :refer [defmiddleware]])

(defmiddleware rate-limiter
  {:enter (fn [ctx]
            (if (too-many-calls?)
              (assoc ctx :cancelled true)
              ctx))})
```

## Protocols

`src/agent/protocols.cljs` defines protocols for pluggable subsystems. Squint compiles `defprotocol` to Symbol-based dispatch, which works at runtime.

```clojure
(defprotocol ISessionStore
  (session-load [this])
  (session-append [this entry])
  (session-build-context [this])
  (session-branch [this entry-id])
  (session-get-tree [this])
  (session-leaf-id [this]))

(defprotocol IToolProvider
  (provide-tools [this])
  (register-tool [this name tool-def])
  (unregister-tool [this name])
  (set-active-tools [this names])
  (get-active-tools [this]))

(defprotocol IContextBuilder
  (build-ctx [this agent opts]))
```

Implementations set Symbol keys via `aset` on returned objects. The existing map-of-closures interface is preserved for backwards compatibility — both `(session-load mgr)` (protocol dispatch) and `((:load mgr))` (old style) work.

**Note:** `defmulti`/`defmethod` does **not** work in Squint — it compiles to undefined `defmulti()` calls. Use `defprotocol` instead.

## Data-Driven Schemas

`src/agent/schema.cljs` provides a data-first API for defining tool parameter schemas. Plain Clojure maps compile to Zod schemas at runtime.

### Schema Format

```clojure
{:field-name {:type        <type-spec>
              :description "Human-readable description"
              :optional    true   ; optional, defaults to false
              :default     value  ; optional default value
              }}
```

Supported type specs:

| Spec | Zod output |
|------|-----------|
| `:string` | `z.string()` |
| `:number` | `z.number()` |
| `:boolean` | `z.boolean()` |
| `[:tuple :string :number]` | `z.tuple([z.string(), z.number()])` |
| `[:enum "a" "b" "c"]` | `z.enum(["a", "b", "c"])` |
| `[:array :string]` | `z.array(z.string())` |
| `[:object {...nested}]` | `z.object({...})` |

### API

```clojure
(require '[agent.schema :refer [data-tool compile-schema]])

;; Define a tool from data
(def my-tool
  (data-tool
    {:description "Read a file"
     :schema {:path  {:type :string :description "File path"}
              :range {:type [:tuple :number :number] :optional true}}
     :execute (fn ^:async [{:keys [path range]}]
                ...)}))

;; Compile a schema directly
(def schema (compile-schema {:query {:type :string :description "Search query"}}))
```

### `deftool` with Data Schema

The `deftool` macro now accepts data schemas instead of inline Zod:

```clojure
(require-macros '[macros.tool-dsl :refer [deftool]])

(deftool web-search
  "Search the web"
  {:query {:type :string :description "The search query"}
   :limit {:type :number :description "Max results" :optional true}}
  [{:keys [query limit]}]
  (js-await (js/fetch (str "https://api.example.com?q=" query))))
```

## Event-Sourced State Store

`src/agent/state.cljs` replaces the bare atom with an event-sourced store. All state mutations go through `dispatch!` with registered reducers. Full history is maintained.

### API

```clojure
(require '[agent.state :refer [create-store create-agent-store]])

(def store (create-agent-store {:messages [] :model "claude-sonnet-4-20250514"}))

;; Dispatch a state event
((:dispatch! store) :message-added {:message {:role "user" :content "hello"}})

;; Read current state
((:get-state store))

;; Register a custom reducer
((:register store) :my-event
  (fn [state data] (assoc state :last-event data)))

;; Subscribe to all state changes
(def unsub ((:subscribe store) (fn [event-type new-state] ...)))
(unsub)  ;; unsubscribe

;; Inspect history
((:history store))  ;; [{:type :message-added :data {...} :timestamp 1234567890}]
```

### Built-in Reducers

| Event type | Effect |
|-----------|--------|
| `:message-added` | `(update state :messages conj (:message data))` |
| `:messages-cleared` | `(assoc state :messages [])` |
| `:tools-changed` | `(assoc state :active-tools (:active-tools data))` |
| `:model-changed` | `(assoc state :model (:model data))` |

### Backwards Compatibility

The store exposes a bare-atom interface so existing code continues to work:

```clojure
;; Still works
((:swap store) update :messages conj msg)
((:reset store) new-state)
((:deref store))
```

### `defreducer` Macro

```clojure
(require-macros '[macros.tool-dsl :refer [defreducer]])

(defreducer handle-tool-approved :tool-approved [state data]
  (update state :approved-tools conj (:tool-name data)))
```

### Extension API

```javascript
// Subscribe to state changes
api.onStateChange((eventType, newState) => {
  console.log("State changed:", eventType);
});

// Dispatch custom state events
api.dispatch("custom-event", { key: "value" });

// Read current state
const state = api.getState();
```

## Extension Namespacing & Capabilities

Extensions now run in a scoped API sandbox. Each extension has a **namespace** (derived from its filename or `extension.json` manifest) and a set of **capabilities** that gate which API methods it can call.

### Namespace Prefixing

All tools and commands registered by an extension are automatically prefixed with its namespace:

```clojure
;; Extension "git-tools" calls:
(.registerTool api "status" ...)
;; Tool is registered as "git-tools/status"

;; Extension "git-tools" calls:
(.registerCommand api "log" ...)
;; Command is registered as "git-tools/log"
```

### Capabilities

| Capability | Grants access to |
|-----------|-----------------|
| `:tools` | `registerTool`, `unregisterTool` |
| `:commands` | `registerCommand`, `unregisterCommand` |
| `:shortcuts` | `registerShortcut`, `unregisterShortcut` |
| `:events` | `on`, `off` |
| `:messages` | `sendMessage`, `sendUserMessage` |
| `:state` | `getState`, `dispatch`, `onStateChange` |
| `:ui` | `ui.showOverlay`, `ui.confirm` |
| `:middleware` | `addMiddleware`, `removeMiddleware` |
| `:all` | All of the above |

Extensions default to `:all` unless restricted via manifest.

### Extension Manifest

Place an `extension.json` file alongside the extension:

```json
{
  "namespace": "git-tools",
  "capabilities": ["tools", "events", "commands"]
}
```

Without a manifest, the namespace is derived from the filename (`git_tools.cljs` → `"git-tools"`).

### `defextension` Macro

```clojure
(require-macros '[macros.tool-dsl :refer [defextension]])

(defextension git-tools
  {:capabilities #{:tools :events}}
  [api]
  (.registerTool api "status"
    #js {:description "Git status"
         :execute     (fn [_] (js-await (run-bash "git status")))})
  (fn [] (.unregisterTool api "status")))
```

This macro:
1. Defines `git-tools-metadata` with namespace and capabilities
2. Defines `git-tools-activate` as the main async function
3. Sets `module.default` to the activate function

## Event System

All agent lifecycle events flow through the event bus. Handlers are error-isolated — a throwing handler is logged and the next handler continues.

```
session_start / session_end / session_before_switch / session_switch
agent_start / agent_end
turn_start / turn_end
message_start / message_update / message_end
tool_call / tool_result
before_tool_call           ← set ctx.cancelled = true to block execution
context / before_agent_start / input
compact / before_compact
```

**Note:** `tool_execution_start` and `tool_execution_end` are defined in `all-event-types` but are never emitted by the current loop implementation.

### Async Event Emission

The event bus now supports `emit-async` for awaiting all handlers:

```clojure
;; Sync (fire-and-forget, existing behavior)
((:emit bus) "tool_call" data)

;; Async (awaits all handlers, including async ones)
(js-await ((:emit-async bus) "before_compact" data))
```

Use `emit-async` when you need to know if a handler has cancelled or modified the context before proceeding.

### Intercepting Tool Calls

`before_tool_call` fires before each tool executes. The handler receives a mutable context object:

```typescript
api.on("before_tool_call", (ctx) => {
  if (ctx.name === "bash" && ctx.args.command.includes("rm -rf")) {
    ctx.cancelled = true;  // blocks execution
  }
});
```

For middleware-based interception (more structured), use `api.addMiddleware`.

## Settings

Settings are resolved in priority order:
1. Runtime overrides (flags, API)
2. Project settings (`.nyma/settings.json`)
3. Global settings (`~/.nyma/settings.json`)
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
  {:query {:type :string :description "The search query"}
   :limit {:type :number :description "Max results" :optional true}}
  [{:keys [query limit]}]
  (let [res (js-await (js/fetch (str "https://api.example.com?q=" query)))]
    (js-await (.json res))))
```

All available macros in `macros.tool-dsl`:

| Macro | Purpose |
|-------|---------|
| `deftool` | Define an LLM-callable tool with data schema |
| `defcommand` | Define a `/slash` command |
| `definterceptor` | Define a named interceptor `{:name :enter :leave :error}` |
| `defmiddleware` | Shorthand for interceptors used as middleware |
| `defreducer` | Define a state reducer for a specific event type |
| `defextension` | Define an extension with metadata, activate fn, and module.default |

## Quick Reference: DOs and DON'Ts

### Squint / ClojureScript

| ❌ Don't | ✅ Do instead |
|---------|--------------|
| `(fn ^:async [x] ...)` | `(defn ^:async f [x] ...)` |
| `(name :my-key)` | Use string literal directly (keywords are strings in Squint) |
| `(keyword? x)` | `(string? x)` |
| `(keyword "foo")` | `"foo"` — just use a string |
| `(my-set :key)` | `(contains? my-set :key)` |
| `(my-map :key)` | `(get my-map :key)` |
| `(js->clj ...)` | Use the value directly — it's already a JS object. For deep clone: `(js/JSON.parse (js/JSON.stringify x))` |
| `(array-seq xs)` | `(seq xs)` or just pass the JS array directly — `into`/`map`/`filter` work on it |
| `(clojure.string/join ...)` | `(:require [clojure.string :as str])` then `(str/join ...)` |
| `(js/process.env.HOME)` | `(.. js/process -env -HOME)` |
| `(.-textDelta chunk "")` | `(or (.-textDelta chunk) "")` |
| `[agent.ui.header :refer [Header]]` | `["./header.jsx" :refer [Header]]` |
| `:on-submit` prop in JSX | `:onSubmit` (camelCase to match React) |
| `(js/Bun.spawn ...)` without pipes | Add `#js {:stdout "pipe" :stderr "pipe"}` |
| `(defn f [& {:keys [a]}] ...)` keyword args | `(defn f [opts] ...)` explicit opts map |
| `(defmulti ...)` / `(defmethod ...)` | `(defprotocol ...)` — multimethods don't compile |

### Extension Development

| ❌ Don't | ✅ Do instead |
|---------|--------------|
| Call `api.ui.showOverlay(...)` unconditionally | Check `api.ui.available` first |
| Forget to clean up event handlers | Return a cleanup fn from your extension's init |
| Register a command and never unregister | Call `api.unregisterCommand` in cleanup |
| Assume tool calls will always proceed | Use `before_tool_call` or middleware for pre-execution checks |
| Subscribe with high-priority for normal work | Reserve high `priority` for security/safety handlers |
| Register tools without namespace awareness | Tools are auto-prefixed: `"my-ext__tool-name"` |
| Pass capabilities you don't need | List only needed capabilities in `extension.json` |

### Testing

| ❌ Don't | ✅ Do instead |
|---------|--------------|
| `(fn ^:async [] ...)` as `it` callback | Extract to `(defn ^:async test-foo [] ...)`, pass by name |
| Leave temp dirs after async tests | Always `.rmSync` in the test body before returning |
| Spy on `console.error` without restoring | Save `orig` first, restore in all branches |
| Test compiled output paths | Test source — squint compile is part of the test run |
| Nest `describe` without `(fn [] ...)` wrappers | Each `describe` body must be `(fn [] ...)` |

## Git Workflow

- Commit messages: `<module>: <short description>` (e.g., `memory: add TTL to working memory`)
- One logical change per commit
