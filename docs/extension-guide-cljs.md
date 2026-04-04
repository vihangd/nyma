# Writing Nyma Extensions in ClojureScript

Nyma extensions can be written in ClojureScript (.cljs) using Squint.
This guide covers nyma-only superpowers not available in pi-mono.

## Quick Start

```clojure
;; ~/.nyma/extensions/my-extension.cljs
(ns my-extension)

(defn activate [api]
  (.on api "agent_start" (fn [event ctx] (js/console.log "Agent started!")))
  ;; Return deactivate function
  (fn [] (js/console.log "Extension deactivated")))

;; Default export
(set! (.-default js/module) activate)
```

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
;; Registered as "my-ext/search" — no collisions with other extensions
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

### 10. Extension Dependencies

Declare dependencies in `extension.json`:

```json
{
  "namespace": "my-ext",
  "capabilities": ["tools", "events"],
  "dependsOn": ["other-ext"]
}
```

Extensions are loaded in dependency order (topological sort).

## Extension Lifecycle

1. File discovered in `~/.nyma/extensions/` or `.nyma/extensions/`
2. If `.cljs` → compiled with Squint at runtime
3. Default export called with scoped API
4. May return deactivate function for cleanup
5. On `/reload` or process exit, deactivate is called

## Squint Gotchas

- `fn ^:async` does NOT work — use `defn ^:async` instead
- Keywords are strings at runtime — `(= :foo "foo")` is true
- Sets are not callable — use `(contains? my-set x)` not `(my-set x)`
- Objects are not callable — use `(get obj :key)` not `(obj :key)`
- No `js->clj` — objects are already plain JS. Use `(js->clj obj)` only for arrays.
