# Implementation Plan: All 20 Hooks & Events

Exact file paths, line numbers, code changes, and tests for each gap. Each step is a single commit.

---

## Step 1: G8 — Make `before_tool_call` emit-collect with cancellation

### Why first
Every permission system, sandbox, mode, and safety check depends on cancelling tool calls via merged returns instead of mutable side-effects.

### Changes

**File: `src/agent/middleware.cljs:65-91`** — Replace `before-hook-compat-enter`

Current: Uses `emit-async` + mutable `evt-ctx` with `.cancelled`/`.blocked` fields.
New: Uses `emit-collect` and checks merged result for `block`/`cancel`/`args`.

```clojure
(defn ^:async before-hook-compat-enter
  "Enter stage: emit-collect before_tool_call. Handlers return
   {block: true, reason: '...'} to cancel, or {args: modified} to transform."
  [events ctx]
  (let [data #js {:name     (:tool-name ctx)
                  :toolName (:tool-name ctx)
                  :args     (clj->js (:args ctx))
                  :execId   (:exec-id ctx)}
        result (js-await ((:emit-collect events) "before_tool_call" data))]
    (cond
      (or (get result "block") (get result "cancel"))
      (assoc ctx :cancelled true
                 :cancel-reason (or (get result "reason") "Blocked by extension"))

      ;; Allow arg transformation
      (get result "args")
      (assoc ctx :args (get result "args"))

      :else ctx)))
```

**Backward compat**: Extensions using the old `(.on "before_tool_call" (fn [ctx] (set! (.-cancelled ctx) true)))` pattern will break. They must return `#js {:block true}` instead. Document in AGENTS.md.

### Tests: `test/before_tool_call_v2.test.cljs` (~8 tests)
- Handler returning `{block: true}` cancels tool
- Handler returning `{block: true, reason: "X"}` sets cancel reason
- Handler returning `{args: {...}}` transforms args
- Handler returning nil → no effect
- Multiple handlers: one blocks → tool blocked (boolean OR merge)
- Tool executes normally when no handler blocks
- Cancel reason propagates to result string
- Backward compat: old emit-async `tool_call` event still fires

---

## Step 2: G9 — Structured `tool_complete` emit-collect event

### Why
PostToolUse hooks, checkpoints, LSP feedback injection, analytics all need structured post-execution data with result modification capability.

### Changes

**File: `src/agent/middleware.cljs:137-163`** — In `tool-tracking-leave`, after `tool-result-leave` and before `tool_execution_end` emit:

Add between line 140 (after `tool-result-leave`) and line 147 (`tool_execution_end` emit):

```clojure
;; tool_complete — structured emit-collect, extensions can modify result
(let [complete-data #js {:toolName   (:tool-name ctx)
                         :toolCallId (:exec-id ctx)
                         :args       (clj->js (:args ctx))
                         :result     (:result ctx)
                         :duration   duration
                         :isError    (boolean (:result-is-error ctx))
                         :details    (:result-details ctx)}
      complete-result (js-await ((:emit-collect events) "tool_complete" complete-data))
      ctx (if-let [mod-result (get complete-result "result")]
            (assoc ctx :result (str mod-result))
            ctx)]
  ...)
```

The `tool-tracking-leave` function must become `^:async` (it already returns a promise via `tool-result-leave`).

### Tests: `test/tool_complete.test.cljs` (~6 tests)
- `tool_complete` fires after tool execution with correct data shape
- Handler returning `{result: "modified"}` replaces result
- Duration is positive number
- `isError` reflects tool error state
- `args` contains original arguments
- No handler → result unchanged

---

## Step 3: G3 — `provider_error` emit-collect with retry

### Why
Any provider error currently crashes the turn. Multi-credential fallback and graceful degradation need this.

### Changes

**File: `src/agent/loop.cljs:186`** — Wrap `streamText` call in try/catch

Replace:
```clojure
(let [result (js-await (streamText st-config))]
```

With:
```clojure
(let [result (try
               (js-await (streamText st-config))
               (catch :default e
                 (let [err-result (js-await
                                   ((:emit-collect events) "provider_error"
                                     #js {:error   e
                                          :message (.-message e)
                                          :model   model-id
                                          :config  st-config}))]
                   (if (get err-result "retry")
                     ;; Extension handled the error (e.g., switched credential) — retry once
                     (js-await (streamText st-config))
                     (throw e)))))]
```

### Tests: `test/provider_error.test.cljs` (~5 tests)
- `provider_error` fires when streamText throws
- Handler returning `{retry: true}` causes retry
- Handler returning nil → error re-thrown
- Error data includes model ID and message
- Only retries once (no infinite loop)

---

## Step 4: G10 — Make `input_submit` emit-collect

### Why
Input validation, content filtering, auto-translation need to transform or cancel user input.

### Changes

**File: `src/agent/ui/app.cljs:86-88`** — In `do-submit`, change the `input_submit` emit

Replace:
```clojure
((:emit (:events agent)) "input_submit" #js {:text text :timestamp (js/Date.now)})
(set-streaming true)
(add-user-msg! set-messages text)
```

With:
```clojure
(let [submit-result (js-await
                      ((:emit-collect (:events agent)) "input_submit"
                        #js {:text text :timestamp (js/Date.now)}))]
  (when-not (get submit-result "cancel")
    (let [text (or (get submit-result "text") text)]
      (set-streaming true)
      (add-user-msg! set-messages text)
```

Close the `let`/`when-not` at the appropriate point (end of the `:else` branch).

### Tests: `test/input_submit_v2.test.cljs` (~5 tests)
- Handler returning `{cancel: true}` prevents message from being sent
- Handler returning `{text: "modified"}` transforms input
- Handler returning nil → original text used
- Timestamp included in event data
- Multiple handlers: cancel wins (boolean OR merge)

---

## Step 5: G6 — Expose editor value in extension API

### Why
Prompt history Ctrl+R picker needs to paste text into editor. Extensions currently can't read or set editor content.

### Changes

**File: `src/agent/ui/app.cljs:253-254`** — After UI wiring, add editor accessors:

```clojure
(set! (.-setEditorValue ui) (fn [text] (set-editor-value text)))
(set! (.-getEditorValue ui) (fn [] editor-value))
```

**File: `src/agent/extensions.cljs:309-323`** — Add to the default `ui` object stub:

```clojure
:setEditorValue nil
:getEditorValue nil
```

### Tests: inline in existing `test/ui_pure.test.cljs`
- `ui.setEditorValue("foo")` updates editor state
- `ui.getEditorValue()` returns current text

---

## Step 6: G7 — Widget priority ordering

### Why
Multiple dashboard widgets (stats, token preview) need predictable ordering.

### Changes

**File: `src/agent/ui/widget_container.cljs:9`** — Sort filtered widgets by priority:

Replace:
```clojure
(let [filtered (filter (fn [[_ w]] (= (:position w) position)) widgets)]
```

With:
```clojure
(let [filtered (->> widgets
                    (filter (fn [[_ w]] (= (:position w) position)))
                    (sort-by (fn [[_ w]] (- (or (:priority w) 0)))))]
```

**File: `src/agent/ui/app.cljs:203-208`** — Update `setWidget` to accept priority:

Replace:
```clojure
(set! (.-setWidget ui)
  (fn [id lines & [pos]]
    (set-widgets
      (fn [w]
        (assoc w id {:lines (if (array? lines) (vec lines) lines)
                     :position (or pos "below")})))))
```

With:
```clojure
(set! (.-setWidget ui)
  (fn [id lines & [pos priority]]
    (set-widgets
      (fn [w]
        (assoc w id {:lines (if (array? lines) (vec lines) lines)
                     :position (or pos "below")
                     :priority (or priority 0)})))))
```

### Tests: `test/widget_priority.test.cljs` (~4 tests)
- Widgets with higher priority render first
- Default priority is 0
- Equal priority preserves insertion order
- Priority works for both "above" and "below" positions

---

## Step 7: G11 — `session_ready` event

### Why
Dynamic context injection at session start (Cline's TaskStart). Extensions need to know when everything is fully initialized.

### Changes

**File: `src/agent/cli.cljs:137`** — After keybindings loaded, before mode dispatch:

Add after line 137 (after `apply-keybindings`):
```clojure
;; Emit session_ready — all extensions loaded, session attached, model resolved
(js-await ((:emit-async (:events agent)) "session_ready"
  #js {:cwd         (js/process.cwd)
       :model       (str (or model "unknown"))
       :extensions  (count @extensions-atom)
       :sessionFile (when session (:get-file-path session))}))
```

### Tests: `test/session_ready.test.cljs` (~3 tests)
- `session_ready` fires after extensions load
- Event data includes cwd, model, extension count
- Handlers complete before mode dispatch (emit-async)

---

## Step 8: G4 — `session_end_summary` event

### Why
Autonomous memory extraction, analytics, session archival need end-of-session context.

### Changes

**File: `src/agent/cli.cljs:140`** — In the process exit handler:

Replace:
```clojure
(.on js/process "exit" (fn [] (deactivate-all @extensions-atom)))
```

With:
```clojure
(.on js/process "exit" (fn []
  ;; Emit session_end_summary synchronously (best-effort in exit handler)
  (try
    ((:emit (:events agent)) "session_end_summary"
      #js {:totalCost    (:total-cost @(:state agent))
           :turnCount    (:turn-count @(:state agent))
           :inputTokens  (:total-input-tokens @(:state agent))
           :outputTokens (:total-output-tokens @(:state agent))
           :messageCount (count (:messages @(:state agent)))})
    (catch :default _ nil))
  (deactivate-all @extensions-atom)))
```

Note: `process.exit` handlers are synchronous, so we use `emit` (fire-and-forget). For async cleanup, we'd need `beforeExit` or a SIGINT handler.

**Also add a SIGINT handler** for graceful async cleanup:
```clojure
(.on js/process "SIGINT" (fn []
  (-> ((:emit-async (:events agent)) "session_end_summary"
        #js {:totalCost    (:total-cost @(:state agent))
             :turnCount    (:turn-count @(:state agent))
             :inputTokens  (:total-input-tokens @(:state agent))
             :outputTokens (:total-output-tokens @(:state agent))
             :messageCount (count (:messages @(:state agent)))})
      (.then (fn [_] (deactivate-all @extensions-atom)))
      (.finally (fn [] (js/process.exit 0))))))
```

### Tests: `test/session_lifecycle.test.cljs` (~4 tests)
- `session_end_summary` fires with correct totals
- Data includes cost, turns, token counts
- Works when state has zero usage

---

## Step 9: G12 — `message_before_store` emit-collect

### Why
Content filtering, redaction, memory tagging, annotation before messages enter storage.

### Changes

**File: `src/agent/loop.cljs:198-205`** — Before storing assistant message:

Replace:
```clojure
;; Append assistant message via event store
(if store
  ((:dispatch! store) :message-added {:message {:role "assistant" :content final-text}})
  (swap! state update :messages conj {:role "assistant" :content final-text}))
```

With:
```clojure
;; message_before_store — extensions can modify content before storage
(let [store-result (js-await
                     ((:emit-collect events) "message_before_store"
                       #js {:role "assistant" :content final-text
                            :model model-id :turnCount (or (:turn-count @state) 0)}))
      stored-text (or (get store-result "content") final-text)]
  (if store
    ((:dispatch! store) :message-added {:message {:role "assistant" :content stored-text}})
    (swap! state update :messages conj {:role "assistant" :content stored-text})))
```

### Tests: `test/message_before_store.test.cljs` (~4 tests)
- Handler returning `{content: "redacted"}` modifies stored message
- Handler returning nil → original content stored
- Event includes role, model, turnCount
- Works with both store and direct state paths

---

## Step 10: G14 — `tool_access_check` emit-collect for modes

### Why
Foundation for Roo Code-style modes, Cline's Plan/Act, per-agent tool restrictions.

### Changes

**File: `src/agent/context.cljs`** — Add `get-active-tools-filtered`:

```clojure
(defn ^:async get-active-tools-filtered
  "Return active tools, filtered by tool_access_check event.
   Extensions (e.g., modes) return {allowed: ['read', 'grep', ...]} to restrict."
  [agent]
  (let [all-tools (get-active-tools agent)
        events    (:events agent)
        state     @(:state agent)
        result    (js-await
                    ((:emit-collect events) "tool_access_check"
                      #js {:tools      (clj->js (vec (keys all-tools)))
                           :activeRole (or (:active-role state) :default)
                           :context    "generation"}))]
    (if-let [allowed (get result "allowed")]
      (let [allowed-set (set (map str allowed))]
        (into {} (filter (fn [[k _]] (contains? allowed-set k)) all-tools)))
      all-tools)))
```

**File: `src/agent/loop.cljs:75-79`** — Use filtered tools:

Replace:
```clojure
tools    (if middleware
           (wrap-tools-with-middleware
             (get-active-tools agent) middleware events)
           (wrap-tools-with-before-hook
             (get-active-tools agent) events))
```

With:
```clojure
raw-tools (js-await (get-active-tools-filtered agent))
tools     (if middleware
            (wrap-tools-with-middleware raw-tools middleware events)
            (wrap-tools-with-before-hook raw-tools events))
```

Update the require in `loop.cljs` to include `get-active-tools-filtered`.

### Tests: `test/tool_access_check.test.cljs` (~5 tests)
- Handler returning `{allowed: ["read", "grep"]}` restricts tools
- No handler → all tools available
- Handler returning `{allowed: []}` blocks all tools
- Multiple handlers: allowed lists concatenated (collection merge)
- Works with modes extension returning role-specific restrictions

---

## Step 11: G13 — `permission_request` emit-collect

### Why
Custom approval UIs, audit logging, programmatic allow/deny for dangerous operations.

### Changes

**File: `src/agent/middleware.cljs`** — Add a new interceptor `permission-check`:

```clojure
(defn ^:async permission-check-enter
  "Check permission_request before tool execution.
   Returns: {decision: 'allow'|'deny'|'ask', reason?: string}"
  [events ctx]
  (let [tool-name (:tool-name ctx)
        args      (:args ctx)
        category  (cond
                    (#{"bash"} tool-name)                   "exec"
                    (#{"write" "edit"} tool-name)           "write"
                    (#{"read" "glob" "grep" "ls"} tool-name) "read"
                    (#{"web_fetch" "web_search"} tool-name) "network"
                    :else                                    "other")
        result    (js-await
                    ((:emit-collect events) "permission_request"
                      #js {:tool     tool-name
                           :args     (clj->js args)
                           :category category
                           :path     (or (get args :path) (get args "path"))}))]
    (case (get result "decision")
      "deny" (assoc ctx :cancelled true
                        :cancel-reason (or (get result "reason") "Permission denied"))
      ;; "allow" or "ask" or nil → proceed (ask would need UI integration)
      ctx)))

(defn permission-check-interceptor [events]
  {:name  :permission-check
   :enter (fn [ctx] (permission-check-enter events ctx))})
```

**File: `src/agent/middleware.cljs:230-233`** — Add to pipeline chain:

In `create-pipeline`, insert `permission-check-interceptor` before `before-hook-compat`:

```clojure
full (vec (concat @chain
                  [prepare-arguments-interceptor
                   (permission-check-interceptor events)  ;; NEW
                   (before-hook-compat events)
                   tool-execution-interceptor]))
```

### Tests: `test/permission_request.test.cljs` (~5 tests)
- Handler returning `{decision: "deny"}` blocks tool
- Handler returning `{decision: "allow"}` permits tool
- Handler returning nil → tool proceeds
- Category correctly set for bash/write/read/network tools
- Path extracted from args when present

---

## Step 12: G15 — File access restrictions (`.nymaignore`)

### Why
Security boundaries, sensitive file protection. Foundation for mode-level file restrictions.

### Changes

**New file: `src/agent/file_access.cljs`** (~60 LOC)

```clojure
(ns agent.file-access
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn load-ignore-patterns
  "Load patterns from .nymaignore (gitignore syntax). Returns vector of pattern strings."
  [cwd]
  (let [ignore-path (path/join cwd ".nymaignore")]
    (if (fs/existsSync ignore-path)
      (->> (str/split-lines (fs/readFileSync ignore-path "utf8"))
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           vec)
      [])))

(defn- matches-pattern?
  "Simple gitignore-style pattern matching (supports * and ** globs)."
  [file-path pattern]
  (let [regex-str (-> pattern
                      (.replace "." "\\.")
                      (.replace "**" "<<GLOBSTAR>>")
                      (.replace "*" "[^/]*")
                      (.replace "<<GLOBSTAR>>" ".*"))
        regex (js/RegExp. (str "^" regex-str "$"))]
    (.test regex file-path)))

(defn check-access
  "Check if a file path is allowed. Returns {:allowed bool :reason string}."
  [file-path patterns]
  (let [denied (some #(matches-pattern? file-path %) patterns)]
    (if denied
      {:allowed false :reason (str "Blocked by .nymaignore: " file-path)}
      {:allowed true})))
```

**Integration**: Register a high-priority `before_tool_call` handler in cli.cljs (after extensions load):

```clojure
(let [patterns (file-access/load-ignore-patterns (js/process.cwd))]
  (when (seq patterns)
    ((:on (:events agent)) "before_tool_call"
      (fn [data]
        (let [tool-name (.-name data)
              path-arg  (or (.-path (.-args data)) nil)]
          (when (and path-arg (#{"read" "write" "edit" "glob" "grep"} tool-name))
            (let [result (file-access/check-access path-arg patterns)]
              (when-not (:allowed result)
                #js {:block true :reason (:reason result)})))))
      100))) ;; priority 100 = runs before extension handlers
```

### Tests: `test/file_access.test.cljs` (~6 tests)
- `*.env` pattern blocks `.env` file
- `node_modules/**` blocks nested files
- Non-matching path allowed
- Empty patterns → all allowed
- Comment lines and blank lines ignored
- Multiple patterns checked

---

## Step 13: G5 — Tool override chaining with `__original`

### Why
Hashline edits wrapping standard edit, per-model edit format wrappers.

### Changes

**File: `src/agent/tool_registry.cljs:16-20`** — In `register-fn`, attach `__original`:

Replace:
```clojure
(when (and (contains? @tools name) (not (contains? @overridden name)))
  (swap! overridden assoc name (get @tools name)))
(swap! tools assoc name t)
```

With:
```clojure
(when (and (contains? @tools name) (not (contains? @overridden name)))
  (swap! overridden assoc name (get @tools name)))
;; Attach __original so overriding tools can chain
(when-let [orig (get @overridden name)]
  (set! (.-__original t) orig))
(swap! tools assoc name t)
```

### Tests: `test/tool_override_chain.test.cljs` (~4 tests)
- Overriding tool gets `__original` reference
- `__original.execute(args)` calls original implementation
- Second override chains through both
- Unregistering restores original (no `__original`)

---

## Step 14: G18 — Model context in tool execution

### Why
Per-model edit format selection (Aider's approach), model-aware tool behavior.

### Changes

**File: `src/agent/middleware.cljs:29-32`** — In `execute-tool-fn`, add model info to extension context:

After the existing `aset ext-ctx "toolCallId"` block, add:
```clojure
(aset ext-ctx "modelId"
  (str (or (.-modelId (:model (:config (when-let [a (:agent ctx)] a)))) "unknown")))
```

**File: `src/agent/middleware.cljs:225-229`** — Pass agent ref into execution context:

In `create-pipeline`'s `:execute` function, add `:agent` to the context:
```clojure
ctx {:tool-name        tool-name
     :tool             tool
     :args             args
     :cancelled        false
     :result           nil
     :extension-context ext-ctx
     :events           events
     :agent            agent       ;; NEW — for model access
     :abort-controller (when agent (:abort-controller agent))}
```

### Tests: inline addition to tool pipeline tests
- Extension context includes `modelId` during tool execution

---

## Step 15: G1 + G2 — Retry mechanism and stream filter

### Why
TTSR (Time Traveling Streamed Rules), provider failover, stream-reactive guardrails.

### Changes

**File: `src/agent/core.cljs`** — Add `:retry-state` atom:

```clojure
retry-state (atom nil) ;; nil = no retry, {:reason :inject [...]} = retry
```

Include in agent map: `:retry-state retry-state`

**File: `src/agent/loop.cljs:183-195`** — Wrap streaming in retry loop with stream filter:

Replace the streaming section:
```clojure
(let [result (js-await (streamText st-config))]
  (let [iter ...]
    (loop []
      (let [chunk (js-await (.next iter))]
        (when-not (.-done chunk)
          ((:emit events) (event-type (.-value chunk)) (.-value chunk))
          (recur)))))
```

With:
```clojure
(loop [attempt 0]
  (reset! (:retry-state agent) nil)
  (let [result (js-await (streamText st-config))
        accumulated (atom "")
        aborted     (atom false)]

    ;; Stream events with filter check
    (let [iter (.call (aget (.-fullStream result) js/Symbol.asyncIterator)
                      (.-fullStream result))]
      (loop []
        (let [chunk (js-await (.next iter))]
          (when (and (not (.-done chunk)) (not @aborted))
            (let [chunk-val (.-value chunk)
                  evt-type  (event-type chunk-val)]
              ;; Stream filter for text deltas
              (when (= evt-type "message_update")
                (swap! accumulated str (.-textDelta chunk-val))
                (let [filter-result (js-await
                                     ((:emit-collect events) "stream_filter"
                                       #js {:delta @accumulated :type evt-type}))]
                  (when (get filter-result "abort")
                    (reset! aborted true)
                    (reset! (:retry-state agent)
                      {:reason (get filter-result "reason")
                       :inject (get filter-result "inject")}))))
              (when-not @aborted
                ((:emit events) evt-type chunk-val)))
            (when-not @aborted (recur))))))

    ;; Check retry
    (if-let [retry @(:retry-state agent)]
      (do
        (reset! (:retry-state agent) nil)
        ;; Inject messages (e.g., TTSR rule)
        (doseq [msg (:inject retry)]
          (if-let [store (:store agent)]
            ((:dispatch! store) :message-added {:message msg})
            (swap! state update :messages conj msg)))
        (when (< attempt 2) ;; max 2 retries
          (recur (inc attempt))))

      ;; Normal completion — continue with final text
      (let [final-text (js-await (.-text result))
            ...]
        ...))))
```

### Tests: `test/stream_filter.test.cljs` (~6 tests)
- `stream_filter` fires on each `message_update`
- Handler returning `{abort: true}` stops streaming
- Injected messages appear in state after abort
- Max 2 retries enforced
- Normal stream completes when no filter aborts
- `delta` accumulates across chunks

---

## Step 16: G19 — Declarative hook scripts (`.nyma/hooks.json`)

### Why
Claude Code, Codex CLI, and Gemini CLI all support shell-script-based hooks. Non-code automation, CI integration.

### Changes

**New file: `src/agent/hooks.cljs`** (~80 LOC)

```clojure
(ns agent.hooks
  "Load and execute declarative hook scripts from .nyma/hooks.json.
   Scripts receive JSON on stdin, return JSON on stdout."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

(defn load-hooks
  "Load hooks from .nyma/hooks.json (project) and ~/.nyma/hooks.json (global).
   Returns {event-name → [{:command :timeout :cwd}]}."
  [cwd]
  (let [project-path (path/join cwd ".nyma" "hooks.json")
        global-path  (path/join (os/homedir) ".nyma" "hooks.json")
        load-file    (fn [p]
                       (when (fs/existsSync p)
                         (try (js/JSON.parse (fs/readFileSync p "utf8"))
                              (catch :default _ nil))))]
    ;; Merge global + project (project wins on conflict)
    (let [global (or (load-file global-path) #js {})
          project (or (load-file project-path) #js {})]
      (js/Object.assign #js {} global project))))

(defn ^:async run-hook-script
  "Execute a hook script. Sends event data as JSON on stdin, reads JSON from stdout.
   Returns parsed result or nil on error/timeout."
  [command input-data timeout-ms cwd]
  (let [proc (js/Bun.spawn (.split command " ")
               #js {:cwd    cwd
                    :stdin  "pipe"
                    :stdout "pipe"
                    :stderr "pipe"})
        _    (.write (.-stdin proc) (js/JSON.stringify (clj->js input-data)))
        _    (.end (.-stdin proc))
        ;; Race between process completion and timeout
        result (js-await
                 (js/Promise.race
                   #js [(.-exited proc)
                        (js/Promise. (fn [resolve]
                          (js/setTimeout #(do (.kill proc) (resolve :timeout))
                                         (or timeout-ms 5000))))]))]
    (when (and (number? result) (zero? result))
      (try
        (let [stdout (js-await (.text (.-stdout proc)))]
          (when (> (count stdout) 0)
            (js/JSON.parse stdout)))
        (catch :default _ nil)))))

(defn register-hooks
  "Register all hooks from a hooks map. For each event → commands list,
   registers an event handler that runs scripts and returns merged results."
  [events hooks-map cwd]
  (let [registered (atom [])]
    (doseq [event-name (js/Object.keys hooks-map)]
      (let [commands (aget hooks-map event-name)
            handler  (fn ^:async [data]
                       (let [results (atom nil)]
                         (doseq [cmd-config commands]
                           (let [command  (or (.-command cmd-config) (str cmd-config))
                                 timeout  (or (.-timeout cmd-config) 5000)
                                 result   (js-await (run-hook-script command
                                                      {:event event-name :data data}
                                                      timeout cwd))]
                             (when result
                               (reset! results result))))
                         @results))]
        ((:on events) event-name handler)
        (swap! registered conj [event-name handler])))
    ;; Return cleanup function
    (fn []
      (doseq [[event handler] @registered]
        ((:off events) event handler)))))
```

**File: `src/agent/cli.cljs`** — Load hooks after extensions:

After `resolve-ext-flags`, add:
```clojure
;; Load declarative hooks from .nyma/hooks.json
(let [hooks-map (hooks/load-hooks (js/process.cwd))]
  (when hooks-map
    (hooks/register-hooks (:events agent) hooks-map (js/process.cwd))))
```

### Tests: `test/hooks_scripts.test.cljs` (~5 tests)
- Hook script receives event data as JSON on stdin
- Script returning `{block: true}` blocks tool call
- Script timing out → nil (no effect)
- Non-zero exit code → nil
- Multiple scripts for same event run in sequence

---

## Step 17: G16 — Checkpoint system (extension)

### Why
Safe exploration, undo/revert after tool calls. Based on Cline/Windsurf pattern.

### Changes

**New file: `src/agent/extensions/checkpoints/index.cljs`** (~100 LOC)

```clojure
(ns agent.extensions.checkpoints
  "Git-based checkpoints after file-modifying tool calls.
   /checkpoint list, /checkpoint revert <id>, /checkpoint diff <id>"
  (:require [clojure.string :as str]))

(def ^:private file-modifying-tools #{"write" "edit" "bash"})

(defn ^:export default [api]
  (let [checkpoints (atom []) ;; [{:id :timestamp :tool :args :message}]
        handlers    (atom [])

        create-checkpoint
        (fn ^:async [tool-name args]
          (let [id  (str "nyma-cp-" (js/Date.now))
                msg (str tool-name ": " (subs (str args) 0 60))]
            (try
              (let [result (js-await (.exec api "git" #js ["stash" "create" "-m" msg]))]
                (when (> (count (str (.-stdout result))) 0)
                  (let [sha (str/trim (.-stdout result))]
                    ;; Tag the stash for persistence
                    (js-await (.exec api "git" #js ["tag" id sha]))
                    (swap! checkpoints conj
                      {:id id :sha sha :timestamp (js/Date.now)
                       :tool tool-name :message msg}))))
              (catch :default _ nil))))

        on-tool-complete
        (fn [data]
          (when (contains? file-modifying-tools (.-toolName data))
            (create-checkpoint (.-toolName data) (.-args data))))]

    (.on api "tool_complete" on-tool-complete)
    (swap! handlers conj ["tool_complete" on-tool-complete])

    ;; /checkpoint list
    (.registerCommand api "checkpoint"
      #js {:description "Manage checkpoints: list, revert <id>, diff <id>"
           :handler
           (fn [args ctx]
             (let [subcmd (first args)]
               (case subcmd
                 "list"
                 (let [cps @checkpoints]
                   (if (empty? cps)
                     (.notify (.-ui ctx) "No checkpoints" "info")
                     (.notify (.-ui ctx)
                       (str "Checkpoints:\n"
                            (str/join "\n"
                              (map (fn [cp]
                                     (str "  " (:id cp) " — " (:message cp)))
                                   (reverse cps)))))))

                 "revert"
                 (let [target-id (second args)]
                   (if-let [cp (first (filter #(= (:id %) target-id) @checkpoints))]
                     (-> (.exec api "git" #js ["stash" "apply" (:sha cp)])
                         (.then (fn [_] (.notify (.-ui ctx) (str "Reverted to " target-id))))
                         (.catch (fn [e] (.notify (.-ui ctx) (str "Revert failed: " (.-message e)) "error"))))
                     (.notify (.-ui ctx) (str "Unknown checkpoint: " target-id) "error")))

                 "diff"
                 (let [target-id (second args)]
                   (if-let [cp (first (filter #(= (:id %) target-id) @checkpoints))]
                     (-> (.exec api "git" #js ["diff" (:sha cp) "HEAD"])
                         (.then (fn [result] (.showOverlay (.-ui ctx) (.-stdout result)))))
                     (.notify (.-ui ctx) (str "Unknown checkpoint: " target-id) "error")))

                 ;; Default: show usage
                 (.notify (.-ui ctx) "Usage: /checkpoint list|revert <id>|diff <id>"))))})

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterCommand api "checkpoint"))))
```

### Tests: `test/checkpoints.test.cljs` (~5 tests)
- Checkpoint created on file-modifying tool_complete
- No checkpoint for read-only tools
- Checkpoint list shows all created checkpoints
- Checkpoint data includes tool name and timestamp
- Checkpoint ID format is `nyma-cp-{timestamp}`

---

## Step 18: G20 — `before_message_send` event

### Why
Content injection, guardrails, message-level transformation before LLM receives messages.

### Changes

This is effectively already covered by `context_assembly` (which can replace the entire message list) and `before_agent_start` (which can inject messages). The specific gap is about transforming individual messages.

**File: `src/agent/loop.cljs`** — After `context_assembly`, before building `st-config`:

Add between the assembly result and st-config construction:
```clojure
;; before_message_send — transform final message list before LLM call
(let [send-result (js-await
                    ((:emit-collect events) "before_message_send"
                      #js {:messages (clj->js messages)
                           :system   effective-prompt
                           :model    model-id}))
      messages (if-let [msgs (get send-result "messages")]
                 (vec (map (fn [m]
                             (if (map? m) m
                               {:role (.-role m) :content (.-content m)}))
                           msgs))
                 messages)
      effective-prompt (or (get send-result "system") effective-prompt)]
  ...)
```

### Tests: `test/before_message_send.test.cljs` (~4 tests)
- Handler returning `{messages: [...]}` replaces message list
- Handler returning `{system: "new prompt"}` replaces system prompt
- Handler returning nil → no change
- Fires after context_assembly

---

## Execution Summary

| Step | Gap(s) | File(s) | LOC | Tests |
|------|--------|---------|-----|-------|
| 1 | G8 | middleware.cljs | ~25 | 8 |
| 2 | G9 | middleware.cljs | ~15 | 6 |
| 3 | G3 | loop.cljs | ~15 | 5 |
| 4 | G10 | app.cljs | ~12 | 5 |
| 5 | G6 | app.cljs, extensions.cljs | ~8 | 2 |
| 6 | G7 | widget_container.cljs, app.cljs | ~10 | 4 |
| 7 | G11 | cli.cljs | ~8 | 3 |
| 8 | G4 | cli.cljs | ~20 | 4 |
| 9 | G12 | loop.cljs | ~12 | 4 |
| 10 | G14 | context.cljs, loop.cljs | ~25 | 5 |
| 11 | G13 | middleware.cljs | ~35 | 5 |
| 12 | G15 | file_access.cljs (new), cli.cljs | ~70 | 6 |
| 13 | G5 | tool_registry.cljs | ~5 | 4 |
| 14 | G18 | middleware.cljs | ~5 | 1 |
| 15 | G1+G2 | core.cljs, loop.cljs | ~60 | 6 |
| 16 | G19 | hooks.cljs (new), cli.cljs | ~90 | 5 |
| 17 | G16 | checkpoints ext (new) | ~100 | 5 |
| 18 | G20 | loop.cljs | ~15 | 4 |
| **Total** | **20 gaps** | **12 files** | **~530** | **80** |

### Dependency Order

Steps 1-6 have no dependencies on each other — they can be done in any order.

Steps 7-8 (session lifecycle) should follow step 1 (they need events infrastructure stable).

Steps 9-14 depend on step 1 (G8 — cancellable `before_tool_call` is the foundation for G13, G15).

Steps 15-16 are standalone but large — do after core hooks are solid.

Steps 17-18 depend on step 2 (G9 — `tool_complete` event).
