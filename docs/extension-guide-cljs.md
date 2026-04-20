# Writing Nyma Extensions in ClojureScript

Nyma extensions can be written in ClojureScript (.cljs) using Squint.
This guide covers nyma-only superpowers not available in pi-mono.

## Quick Start

### Single-file extension

```clojure
;; ~/.nyma/extensions/my-extension.cljs
(ns my-extension)

(defn ^:export default [api]
  (.on api "agent_start" (fn [event ctx] (js/console.log "Agent started!")))
  ;; Return deactivate function
  (fn [] (js/console.log "Extension deactivated")))
```

The loader looks for the ES module `default` export. Use `(defn ^:export default [api] ...)` — Squint compiles this to `export default default$`.

### Multi-file extension (suite)

For extensions with multiple files, create a directory with an `extension.json` manifest. Only `index.cljs` (the entry point) is loaded by the extension loader — all other files are treated as supporting modules and imported by `index.cljs`.

```
~/.nyma/extensions/my-suite/
  extension.json     ← declares namespace, capabilities, npm dependencies
  index.cljs         ← ONLY this file is loaded by the extension system
  shared.cljs        ← imported by index.cljs via (:require [...])
  feature-a.cljs     ← imported by index.cljs via (:require [...])
  feature-b.cljs
```

**`extension.json`**:
```json
{
  "namespace": "my-suite",
  "capabilities": ["tools", "events", "middleware"],
  "dependsOn": [],
  "dependencies": {
    "some-npm-package": "^1.0.0"
  }
}
```

**`index.cljs`**:
```clojure
(ns my-suite.index
  (:require [my-suite.feature-a :as feature-a]
            [my-suite.feature-b :as feature-b]))

(defn ^:export default [api]
  (let [deactivators (atom [])]
    (swap! deactivators conj (feature-a/activate api))
    (swap! deactivators conj (feature-b/activate api))
    ;; Combined deactivator
    (fn []
      (doseq [d @deactivators]
        (when (fn? d) (d))))))
```

> **Important**: Multi-file suites must be pre-compiled with `squint compile` before they can be loaded as `.mjs` files. The extension loader's Squint compilation only handles single-file extensions. Built-in suites in `src/agent/extensions/` are pre-compiled to `dist/agent/extensions/` by `bun run build`.

## Using Macros

```clojure
(ns my-extension
  (:require-macros [macros.tool-dsl :refer [deftool defcommand defextension defevent defwidget]]))

;; Define a tool with data-driven schema (no Zod imports needed)
(deftool my-search "Search my database"
  {:query [:string "The search query"]
   :limit [:number "Max results" {:optional true}]}
  [{:keys [query limit]}]
  (str "Found " (or limit 10) " results for: " query))

;; Define an event handler
(defevent bash-guard "tool_call" [event ctx]
  (when (= (.-toolName event) "bash")
    (when (.includes (.. event -input -command) "rm -rf")
      #js {:block true :reason "Dangerous command blocked"})))

;; Define a widget
(defwidget token-counter {:position "below"} [agent]
  (let [s @(:state agent)]
    [(str "Tokens: " (:total-input-tokens s) " in / " (:total-output-tokens s) " out")]))

;; Wire everything together
(defextension my-ext
  {:capabilities #{:tools :events :ui}}
  [api]
  (.registerTool api "my-search" my-search)
  (.on api "tool_call" (:handler bash-guard)))
```

## Nyma-Only Features

### 1. Interceptor Chain (Middleware)

Pi-mono has simple before/after hooks. Nyma has a full Pedestal-style
interceptor chain with `:enter`, `:leave`, and `:error` stages.

```clojure
(definterceptor audit-log
  {:enter (fn [ctx]
            (js/console.log (str "ENTER: " (:tool-name ctx) " " (pr-str (:args ctx))))
            ctx)
   :leave (fn [ctx]
            (js/console.log (str "LEAVE: " (:tool-name ctx) " took "
                                 (- (js/Date.now) (:start-time ctx)) "ms"))
            ctx)
   :error (fn [ctx]
            (js/console.error (str "ERROR in " (:tool-name ctx) ": " (:error ctx)))
            ctx)})

;; Add to pipeline
(.addMiddleware api audit-log)
```

### 2. Event-Sourced State

Custom reducers for tracking extension state:

```clojure
(defreducer track-file-changes :tool-call-ended [state data]
  (let [tool (:tool-name data)]
    (if (contains? #{"write" "edit"} tool)
      (update state :files-modified (fnil inc 0))
      state)))

;; Register with store
((:register (:store agent)) :tool-call-ended (:reducer track-file-changes))

;; Subscribe to state changes
((:subscribe (:store agent))
  (fn [event-type new-state]
    (when (= event-type :tool-call-ended)
      (js/console.log "Files modified:" (:files-modified new-state)))))
```

### 3. Capability Gating

Declare what your extension needs in `extension.json`:

```json
{
  "namespace": "my-ext",
  "capabilities": ["tools", "events", "ui"]
}
```

Trying to call `api.exec()` without `:exec` capability throws.
Use `"all"` to get everything (default when no manifest).

### 4. Namespace Isolation

Tool and command names are auto-prefixed:

```clojure
(.registerTool api "search" my-tool)
;; Registered as "my-ext__search" — no collisions with other extensions
```

### 5. Data-Driven Schemas

No need to import Zod. Use Clojure maps:

```clojure
(require '[agent.schema :refer [compile-schema]])

(def my-schema
  (compile-schema
    {:query   {:type :string :description "Search query"}
     :limit   {:type :number :description "Max results" :optional true :default 10}
     :filters {:type [:array :string] :description "Filter tags"}}))
```

### 6. Protocols

Implement custom session stores, tool providers, or context builders:

```clojure
(require '[agent.protocols :refer [ISessionStore_session_load ...]])

;; Custom session store backed by SQLite
(defn create-sqlite-session [db-path]
  (let [store {...}]
    (aset store ISessionStore_session_load (fn [_] ...))
    (aset store ISessionStore_session_append (fn [_ entry] ...))
    store))
```

### 7. Extension CLI Flags

Register flags that users can pass via `--ext-flagname=value`:

```clojure
(.registerFlag api "verbose" #js {:type "boolean" :default false :description "Enable verbose output"})
(.registerFlag api "format" #js {:type "string" :default "json" :description "Output format"})

;; Read flag value (uses CLI override if provided, else default)
(let [verbose (.getFlag api "verbose")]
  (when verbose (js/console.log "Verbose mode enabled")))
```

### 8. Timed Dialogs

Dialogs support `timeout` (auto-dismiss after ms) and `signal` (AbortController):

```clojure
;; Auto-dismiss after 5 seconds
(.confirm (.-ui ctx) "Deploy?" #js {:timeout 5000})

;; Cancel via AbortController
(let [ctrl (js/AbortController.)]
  (.select (.-ui ctx) "Choose:" #js ["A" "B"] #js {:signal (.-signal ctrl)})
  ;; Cancel from elsewhere:
  (.abort ctrl))
```

### 9. Inter-Extension Events

Extensions can communicate without going through the main event bus:

```clojure
;; Extension A publishes
(.emit (.-events api) "my-ext/data-ready" #js {:items items})

;; Extension B subscribes
(.on (.-events api) "my-ext/data-ready" (fn [data] (process-items (.-items data))))
```

### 10. Extension Dependencies (load order)

Declare load-order dependencies in `extension.json`:

```json
{
  "namespace": "my-ext",
  "capabilities": ["tools", "events"],
  "dependsOn": ["other-ext"]
}
```

Extensions are loaded in dependency order (topological sort). If a cycle is detected, the loader falls back to filesystem scan order and logs a warning.

### 11. NPM Package Dependencies

Declare required npm packages in the `dependencies` field of `extension.json`:

```json
{
  "namespace": "my-ext",
  "capabilities": ["tools"],
  "dependsOn": [],
  "dependencies": {
    "shell-quote": "^1.8.1",
    "some-other-pkg": "^2.0.0"
  }
}
```

Before loading your extension, the loader checks if each declared package is resolvable from the current working directory. If any are missing, it automatically runs `bun add <package>` to install them. Requirements:

- The project directory must contain a `package.json` (auto-install is skipped otherwise, with a warning)
- Packages are installed into the project's `node_modules/` and persist across restarts
- Since nyma runs on Bun, bare imports like `["shell-quote" :as sq]` resolve from CWD's `node_modules/` even for runtime-compiled extensions

If your extension only uses packages already in the project's `package.json`, you don't need to declare them — Bun resolves them automatically.

## Shaping the LLM Call Pipeline

Five `emit-collect` hooks fire during every run of `agent.loop/run`. Unlike
fire-and-forget events, `emit-collect` awaits each handler and merges its return
value into a single result map — that is what gives extensions the ability to
transform the data the loop is about to use.

Execution order per turn:

```
before_agent_start  ─┐
                     ├─► model_resolve  ─► context_assembly  ─► before_message_send  ─► before_provider_request
                     │                                                                          │
                     │                                                                          ▼
                     │                                                                      streamText
                     │                                                                          │
                     │         (per delta)  ─► stream_filter  ◄──────────────────────────┐     │
                     │                                ▲                                   │     │
                     │                   (on abort: inject + retry, max 2 attempts) ──────┘     │
                     │                                                                          ▼
                     │                                               message_before_store  ─► after_provider_request
```

### `before_message_send` — G20

The final transform after `context_assembly` and before `before_provider_request`
sees the streamText config. Use it when you need to rewrite messages or the
system prompt as the very last step — e.g. per-model message caps, final
sanitization, or stripping system blocks the LLM shouldn't see.

```clojure
(defn ^:export default [api]
  (.on api "before_message_send"
    (fn [data _ctx]
      ;; data is a JS object with :messages :system :model
      (let [msgs (.-messages data)
            n    (.-length msgs)]
        (when (> n 40)
          ;; Drop the oldest messages if we're about to overflow
          #js {:messages (.slice msgs (- n 40))}))))
  (fn [] nil))
```

Return shape: `{messages?: JS[], system?: string}`. Any key you set replaces
the corresponding value in the loop. Return `nil` to pass through unchanged.
Multiple handlers merge last-writer-wins — attach a `priority` (higher runs
first) if you need a specific order.

### `stream_filter` — G1/G2

Fires once per text delta while the model is streaming. Use it to abort the
stream mid-generation when the assistant starts to produce something you don't
want (a secret, a known-bad command, content that trips a TTSR rule) and to
inject corrective system messages before retrying.

```clojure
(def banned-patterns
  [#"\beval\(" #"\bexec\(" #"sk-[A-Za-z0-9]{20,}"])

(defn ^:export default [api]
  (.on api "stream_filter"
    (fn [data _ctx]
      ;; data is a JS object: :delta (accumulated text so far), :chunk (this chunk), :type
      (let [full (.-delta data)]
        (when (some #(re-find % full) banned-patterns)
          #js {:abort  true
               :reason "tripped content filter"
               :inject #js [#js {:role "system"
                                 :content "Your previous output matched a forbidden pattern. Retry without it."}]}))))
  (fn [] nil))
```

Return shape: `{abort: bool, reason?: string, inject?: JS[]}`. When `abort` is
true the loop:

1. Stops reading from `fullStream`
2. Appends every message in `inject` to the agent state
3. Re-runs the LLM call (up to 2 retries; the 3rd attempt exits with whatever
   was accumulated)

`data.delta` is the accumulated text *including* the current chunk, so a simple
`re-find` on `(.-delta data)` catches patterns that span chunk boundaries.

### `ctx.modelId` on tool execute — G18

Inside a tool's `execute(args, ctx)` function the ctx object now carries
`ctx.modelId` — the active model ID string for the LLM call that is about to
use the tool's output. Use it to size truncation, top-k, or verbosity to the
model in play.

```clojure
(.registerTool api "search"
  #js {:description "Search the codebase"
       :execute
       (fn [args ctx]
         (let [model-id (or (.-modelId ctx) "unknown")
               ;; Haiku gets a tight result cap; Opus gets more context
               limit    (cond
                          (.includes model-id "haiku") 5
                          (.includes model-id "opus")  30
                          :else                        15)]
           (run-search (:query args) :limit limit)))})
```

`ctx.modelId` is `"unknown"` if the active model is `nil` (no API key) or
otherwise unavailable. It is always a string, so you can call `.includes` on it
safely without a null check.

### Picking the right hook

| Use case | Hook |
|---|---|
| Add to or replace the system prompt once per turn | `before_agent_start` (adds) or `context_assembly` (replaces) |
| Swap the model based on context | `model_resolve` |
| Final message/system rewrite right before LLM call | `before_message_send` |
| Mutate provider-specific config (`providerOptions`, tool list) | `before_provider_request` |
| Block a run without touching the provider | `before_provider_request` → return `{block: true, reason}` |
| Abort and retry mid-stream | `stream_filter` |
| Adapt a tool's behaviour to the active model | Read `ctx.modelId` inside `execute` |
| Track usage or cache metrics | `after_provider_request` |

## Extension Lifecycle

1. Files discovered in (in order):
   - `dist/agent/extensions/` — built-in extensions (pre-compiled)
   - `~/.nyma/extensions/` — global user extensions
   - `.nyma/extensions/` — project-local user extensions
2. For multi-file dirs (with `extension.json`): only `index.*` is loaded; other files are skipped
3. npm `dependencies` checked; missing packages auto-installed via `bun add`
4. Extensions sorted by `dependsOn` (topological order)
5. If `.cljs` → compiled with Squint at runtime, cached in `~/.nyma/cache/`
6. Default export called with scoped API
7. May return deactivate function for cleanup
8. On `/reload` or process exit, deactivate is called

## System Events

- **`editor_change`** — fired on every keystroke in the editor; payload `{text: string}`. Subscribe to build live previews or debounced analysis widgets.
- **`session_clear`** — fired when `/clear` is invoked; extensions (e.g. agent-shell) use this to send `session/new` to their backend.
- **`before_message_send`** (emit-collect) — final transform between `context_assembly` and the streamText call. Return `{messages?, system?}` to replace either. See §Shaping the LLM Call Pipeline above.
- **`stream_filter`** (emit-collect) — fires per text delta during streaming. Return `{abort: true, reason?, inject?}` to stop the stream and retry with injected messages (max 2 retries). See §Shaping the LLM Call Pipeline above.

The full ordered list of hooks is in the README's Events table.

## Custom Providers

An extension can register a new LLM provider by calling `api.registerProvider`. Declare the `providers` capability in `extension.json`.

```clojure
(defn ^:export default [api]
  (.registerProvider api "my-provider"
    #js {:createModel  (fn [model-id] (create-my-model model-id))
         :baseUrl      "https://api.example.com/v1"
         :apiKeyEnv    "MY_PROVIDER_API_KEY"
         :api          "openai"   ; "openai" or "anthropic"
         :models       (clj->js models)})
  (fn [] (.unregisterProvider api "my-provider")))
```

Each model object needs `{:id "..." :name "..." :contextWindow N}`. The `api` field controls which Vercel AI SDK provider adapter is used under the hood (`"openai"` for OpenAI-compatible endpoints, `"anthropic"` for Anthropic-native).

**Credential resolution** — read from env first, then fall back to `~/.nyma/credentials.json`:

```clojure
(defn resolve-api-key []
  (or (aget js/process.env "MY_PROVIDER_API_KEY")
      (when-let [home (aget js/process.env "HOME")]
        (try
          (let [creds (-> (str home "/.nyma/credentials.json")
                         (fs/readFileSync "utf8")
                         js/JSON.parse)]
            (aget creds "my-provider"))
          (catch :default _ nil)))))
```

See `src/agent/extensions/custom_provider_minimax/` and `src/agent/extensions/custom_provider_claude_native/` for complete reference implementations.

## Squint Gotchas

- `fn ^:async` does NOT work — use `defn ^:async` instead
- Keywords are strings at runtime — `(= :foo "foo")` is true
- Sets are not callable — use `(contains? my-set x)` not `(my-set x)`
- Objects are not callable — use `(get obj :key)` not `(obj :key)`
- No `js->clj` — objects are already plain JS. Use `(js->clj obj)` only for arrays
- `default` is a reserved JS word — `(defn ^:export default [api] ...)` compiles correctly to `export default default$`. In tests, access it with `(.-default my-module)` not `my-module/default` (the latter compiles to `my_module.default$` which doesn't match the actual ES default export)
- Inter-extension bus events carry JS objects — extract properties with `(.-prop data)` not `(:prop data)`. Use `(js/JSON.parse (js/JSON.stringify obj))` or manual extraction instead of `js->clj`
