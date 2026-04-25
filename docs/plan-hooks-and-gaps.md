# Hooks, Events & API Gap Analysis

Comprehensive analysis of nyma's extension surface compared to 12 coding agents: oh-my-pi, Roo Code, OpenCode, Cline, Kilo Code, Claude Code, Aider, Codex CLI, Goose, Continue, Cursor, Windsurf, Gemini CLI, and Amazon Q.

## Implementation Status

| Gap | Status | Notes |
|-----|--------|-------|
| G1/G2 | **Planned** | Retry + stream_filter — see `plan-g18-g1g2-g20.md` |
| G3 | **Done** | `provider_error` emit-collect with retry |
| G4 | **Done** | `session_end_summary` on exit/SIGINT |
| G5 | **Done** | Tool override chaining with `__original` |
| G6 | **Done** | `ui.setEditorValue` / `ui.getEditorValue` |
| G7 | **Done** | Widget priority ordering |
| G8 | **Done** | `before_tool_call` upgraded to emit-collect |
| G9 | **Done** | `tool_complete` emit-collect |
| G10 | **Done** | `input_submit` upgraded to emit-collect |
| G11 | **Done** | `session_ready` emit-async |
| G12 | **Done** | `message_before_store` emit-collect |
| G13 | **Done** | `permission_request` emit-collect interceptor |
| G14 | **Done** | `tool_access_check` emit-collect |
| G15 | **Done** | `.nymaignore` file access restrictions |
| G16 | **Roadmap** | Checkpoint extension — hooks ready, needs extension code |
| G17 | **Roadmap** | Native subagents — largest remaining subsystem |
| G18 | **Planned** | Model context in tools — see `plan-g18-g1g2-g20.md` |
| G19 | **Done** | Declarative `.nyma/hooks.json` |
| G20 | **Planned** | `before_message_send` — see `plan-g18-g1g2-g20.md` |

---

## Part 1: All Identified Gaps (20 total)

### Original 7 Gaps (from Tier 1 plan)

| # | Gap | Current State | Needed For | Source |
|---|-----|--------------|-----------|--------|
| G1 | No retry mechanism in loop | Loop throws on error, no retry path | TTSR, rate-limit fallback, provider failover | oh-my-pi |
| G2 | No abort-and-retry for streams | AbortController exists but no retry queue | TTSR stream interception | oh-my-pi |
| G3 | No `provider_error` event | Errors throw directly in loop.cljs | Multi-credential fallback, graceful degradation | oh-my-pi, OpenCode |
| G4 | No `session_end_summary` event | Session ends silently | Autonomous memory, analytics, learning | oh-my-pi, Windsurf |
| G5 | No tool override chaining | Override replaces entire tool, no `next()` | Hashline edits wrapping existing edit tool | oh-my-pi |
| G6 | Editor value not accessible from extensions | `setEditorValue` not on extension API | Prompt history paste-into-editor, Ctrl+R | oh-my-pi |
| G7 | No widget priority/ordering | Widgets render in arbitrary order | Multiple footer widgets (stats, token preview) | oh-my-pi |

### New Gaps from Cross-Agent Research (13 additional)

| # | Gap | Current State | Needed For | Source Agent(s) |
|---|-----|--------------|-----------|----------------|
| G8 | `before_tool_call` is fire-and-forget | `emit` (not `emit-collect`), can set `cancelled` on mutable context but no merged returns | Cancellable tool calls, permission system, sandboxing | Cline (PreToolUse), Kilo, Claude Code |
| G9 | No `tool_complete` event with structured data | `tool_execution_end` exists but is fire-and-forget `emit` | PostToolUse hooks, checkpoints, analytics, LSP-after-edit | Cline (PostToolUse), OpenCode (tool.execute.after) |
| G10 | `input_submit` is fire-and-forget | We added `emit` but extensions can't transform/cancel input | Input validation, auto-translation, content filtering | Cline (UserPromptSubmit), Codex CLI |
| G11 | No `session_ready` event after full init | `session_start` exists but fires before extensions load | Dynamic context injection at session start, TaskStart hooks | Cline (TaskStart), Codex CLI (SessionStart) |
| G12 | No `message_before_store` event | Messages stored directly, no interception | Content filtering, redaction, annotation, memory tagging | OpenCode (chat.message) |
| G13 | No `permission_request` event | Bash suite handles permissions internally | Custom approval UIs, programmatic approval, audit logging | OpenCode (permission.ask), Kilo (per-tool permissions) |
| G14 | No per-mode tool filtering | All tools always available to all contexts | Modes system, read-only plan mode, restricted agent personas | Roo Code (modes), Cline (Plan/Act), Kilo (agents) |
| G15 | No file access restrictions | Any tool can read/write any file | Security boundaries, mode restrictions, sensitive file protection | Roo Code (.rooignore + fileRegex), Kilo (.kilocodeignore) |
| G16 | No checkpoint/snapshot system | No automatic state preservation | Undo/revert after tool calls, safe exploration, rollback | Cline (checkpoints), Windsurf, Roo Code |
| G17 | No native subagent spawning | ACP shell for external agents only | Parallel research, task delegation, orchestrator pattern | Cline (subagents), Kilo (task tool), OpenCode |
| G18 | No `model_context` in tool execution | Tools don't know which model is running | Per-model edit format selection, model-aware tool behavior | Aider (edit format per model) |
| G19 | No hook script execution | Extension code only, no shell script hooks | Declarative hook configs, CI integration, non-code automation | Claude Code (hooks), Codex CLI (hooks.json), Gemini CLI |
| G20 | No `before_message_send` event | Messages go directly to LLM via AI SDK | Content injection, guardrails, message transformation | OpenCode (chat.params), Claude Code |

---

## Part 2: Priority Classification

### Critical (blocks multiple features, small to implement)

| Gap | LOC | Enables |
|-----|-----|---------|
| **G8**: Make `before_tool_call` cancellable | ~20 | Permissions, sandboxing, modes, checkpoints |
| **G9**: Structured `tool_complete` event | ~15 | PostToolUse hooks, checkpoints, LSP feedback, analytics |
| **G3**: `provider_error` event | ~20 | Multi-credential fallback, graceful degradation |
| **G6**: Expose editor value in extension API | ~10 | Prompt history, command paste, input manipulation |
| **G10**: Make `input_submit` cancellable/transformable | ~15 | Input validation, filtering, transformation hooks |

### High (enables important feature categories)

| Gap | LOC | Enables |
|-----|-----|---------|
| **G14**: Per-mode tool filtering | ~60 | Modes system, Plan/Act, agent personas |
| **G4/G11**: Session lifecycle events | ~30 | Memory pipeline, analytics, dynamic context |
| **G1/G2**: Retry mechanism | ~80 | TTSR, provider failover, resilience |
| **G12**: Message interception | ~25 | Content filtering, memory tagging, redaction |
| **G7**: Widget priority | ~15 | Multiple dashboard widgets |

### Medium (enables advanced features)

| Gap | LOC | Enables |
|-----|-----|---------|
| **G15**: File access restrictions | ~80 | Security, mode restrictions |
| **G5**: Tool override chaining | ~40 | Hashline edits, edit format wrappers |
| **G13**: Permission request event | ~30 | Custom approval UIs, audit logging |
| **G16**: Checkpoint system | ~150 | Safe exploration, revert |
| **G19**: Hook script execution | ~100 | Declarative hooks, CI integration |

### Lower (nice to have, large scope)

| Gap | LOC | Enables |
|-----|-----|---------|
| **G17**: Native subagents | ~300 | Task delegation, parallel research |
| **G18**: Model context in tools | ~20 | Per-model edit optimization |
| **G20**: Before message send | ~25 | Guardrails, injection |

---

## Part 3: Step-by-Step Implementation Plan

### Phase 1: Critical Hook Fixes (~80 LOC, 1 commit)

These are small surgical changes to existing event emission points.

#### G8: Make `before_tool_call` emit-collect with cancellation

**File**: `src/agent/middleware.cljs` — in `before-hook-compat` interceptor

**Current**: Emits `before_tool_call` via `(:emit events)` and checks a mutable `cancelled` field on the context object.

**Change**: Switch to `(:emit-collect events)` and check for `{block: true}` or `{cancel: true}` in the merged result.

```clojure
;; Before (current):
((:emit events) "before_tool_call" ctx)
(if (.-cancelled ctx) ...)

;; After:
(let [result (js-await ((:emit-collect events) "before_tool_call"
               #js {:name tool-name :args args :execId exec-id}))]
  (if (or (get result "block") (get result "cancel"))
    {:cancelled true :reason (or (get result "reason") "Blocked by extension")}
    {:cancelled false :args (or (get result "args") args)}))
```

**Extension API change**: Handlers now return `{block: true, reason: "..."}` to cancel, or `{args: modified}` to transform args. No mutation needed.

**Why critical**: Every permission system, sandbox, mode restriction, and safety check depends on being able to cancel tool calls. Currently only works via mutable side-effect on the context object — fragile and undocumented.

#### G9: Structured `tool_complete` emit-collect event

**File**: `src/agent/middleware.cljs` — in `tool-tracking` leave phase

**Current**: `tool_execution_end` is emitted via `(:emit events)` (fire-and-forget).

**Change**: Also emit `tool_complete` via `(:emit-collect events)` with structured data, checking for result modifications:

```clojure
(let [complete-result (js-await
                        ((:emit-collect events) "tool_complete"
                          #js {:toolName    tool-name
                               :toolCallId  exec-id
                               :args        original-args
                               :result      result
                               :duration    duration-ms
                               :isError     is-error
                               :details     details}))]
  ;; Allow extensions to modify the result (e.g., LSP appends diagnostics)
  (let [final-result (or (get complete-result "result") result)]
    ...))
```

**Why critical**: This is the primary hook point for:
- Checkpoint creation (snapshot after each tool call)
- LSP feedback injection (append diagnostics after file edits)
- Analytics collection
- Tool result transformation

#### G3: `provider_error` event before throwing

**File**: `src/agent/loop.cljs` — around the `streamText` call

**Change**: Wrap the streamText call in try/catch, emit `provider_error` before re-throwing:

```clojure
(let [result (try
               (js-await (streamText st-config))
               (catch :default e
                 (let [error-result (js-await
                                     ((:emit-collect events) "provider_error"
                                       #js {:error   e
                                            :model   (str active-model)
                                            :retry   false
                                            :config  st-config}))]
                   (if (get error-result "retry")
                     ;; Extension requested retry (e.g., switched credential)
                     (js-await (streamText st-config))
                     (throw e)))))]
  ...)
```

**Why critical**: Without this, any provider error crashes the entire turn. Multi-credential round-robin, rate-limit fallback, and graceful degradation all need this hook.

#### G6: Expose editor value in extension API

**File**: `src/agent/extensions.cljs` + `src/agent/ui/app.cljs`

**Change**: Add `setEditorValue` and `getEditorValue` to the UI object in the extension API:

```clojure
;; In app.cljs, during UI wiring:
(set! (.-setEditorValue ui) (fn [text] (set-editor-value text)))
(set! (.-getEditorValue ui) (fn [] editor-value))
```

**Why critical**: Prompt history Ctrl+R picker needs to paste selected text into the editor. Without this, the extension has no way to set editor content.

#### G10: Make `input_submit` emit-collect

**File**: `src/agent/ui/app.cljs` — in `do-submit`

**Change**: Switch from `(:emit ...)` to `(:emit-collect ...)` and check for transformation/cancellation:

```clojure
;; Before:
((:emit (:events agent)) "input_submit" #js {:text text :timestamp (js/Date.now)})

;; After:
(let [submit-result (js-await
                      ((:emit-collect (:events agent)) "input_submit"
                        #js {:text text :timestamp (js/Date.now)}))]
  (when-not (get submit-result "cancel")
    (let [text (or (get submit-result "text") text)]
      ;; continue with possibly-transformed text
      ...)))
```

**Why critical**: Input validation, content filtering, and auto-translation all need to intercept user input before it reaches the LLM.

---

### Phase 2: Session Lifecycle Events (~30 LOC, 1 commit)

#### G4: `session_end_summary` event

**File**: `src/agent/cli.cljs` (or wherever session cleanup happens)

Emit when the session is about to close:

```clojure
((:emit-async events) "session_end_summary"
  #js {:messages    (clj->js (:messages @state))
       :totalCost   (:total-cost @state)
       :turnCount   (:turn-count @state)
       :duration    (- (js/Date.now) session-start-time)
       :sessionFile session-file})
```

**Used by**: Autonomous memory extraction, analytics, session archival.

#### G11: `session_ready` event after full initialization

**File**: `src/agent/cli.cljs` — after all extensions are loaded and session is attached

```clojure
((:emit-async events) "session_ready"
  #js {:sessionFile session-file
       :cwd         (js/process.cwd)
       :model       model-id
       :extensions  (count loaded-extensions)})
```

**Used by**: Dynamic context injection (Cline's TaskStart), project type detection, initial memory loading.

---

### Phase 3: Per-Mode Tool Filtering (~60 LOC, 1 commit)

#### G14: `tool_access_check` event + mode-aware tool resolution

**File**: `src/agent/context.cljs` — in `get-active-tools`

**Current**: Returns all active tools unconditionally.

**Change**: Emit `tool_access_check` that lets extensions filter the tool set:

```clojure
(defn ^:async get-active-tools-filtered [agent]
  (let [all-tools (get-active-tools agent)
        filter-result (js-await
                        ((:emit-collect (:events agent)) "tool_access_check"
                          #js {:tools       (clj->js (keys all-tools))
                               :activeRole  (:active-role @(:state agent))
                               :context     "generation"}))]
    (if-let [allowed (get filter-result "allowed")]
      (select-keys all-tools (set allowed))
      all-tools)))
```

**File**: `src/agent/loop.cljs` — call `get-active-tools-filtered` instead of `get-active-tools`

**Used by**: A Modes extension can subscribe to `tool_access_check` and return only the tools allowed for the current mode. Example:

```clojure
;; In modes extension:
(.on api "tool_access_check" (fn [data]
  (let [role (.-activeRole data)]
    (case role
      :plan #js {:allowed #js ["read" "glob" "grep" "ls" "think"]}
      :debug #js {:allowed #js ["read" "bash" "grep" "think"]}
      nil)))) ;; nil = no restriction
```

This is the foundation for Roo Code-style modes, Cline's Plan/Act, and Kilo's per-agent tool access.

---

### Phase 4: Retry Mechanism + Stream Filter (~100 LOC, 1 commit)

#### G1 + G2: Retry queue and stream abort/retry

**File**: `src/agent/loop.cljs` — refactor the main loop

Add a `retry-queue` atom to the agent (alongside steer-queue and follow-queue):

```clojure
;; In core.cljs:
retry-queue (atom nil) ;; nil = no retry, {:reason "..." :inject [...]} = retry with injected messages
```

In loop.cljs, wrap the streaming section in a retry loop:

```clojure
(loop [attempt 0]
  (let [result (try (js-await (streamText st-config)) (catch :default e ...))]
    ;; Stream events
    (let [accumulated-text (atom "")]
      (loop []
        (let [chunk (js-await (.next iter))]
          (when-not (.-done chunk)
            (let [chunk-val (.-value chunk)
                  evt-type  (event-type chunk-val)]
              ;; Stream filter for TTSR
              (when (= evt-type "message_update")
                (swap! accumulated-text str (.-textDelta chunk-val))
                (let [filter-result (js-await
                                     ((:emit-collect events) "stream_filter"
                                       #js {:delta @accumulated-text
                                            :type  evt-type}))]
                  (when (get filter-result "abort")
                    ;; Set retry with injected messages
                    (reset! (:retry-queue agent)
                      {:reason  (get filter-result "reason")
                       :inject  (get filter-result "inject")})
                    ;; Abort the stream
                    (.abort (.-signal st-config))
                    ;; Break out — will be caught by retry loop
                    )))
              ((:emit events) evt-type chunk-val))
            (recur)))))
    ;; Check retry queue
    (when-let [retry @(:retry-queue agent)]
      (reset! (:retry-queue agent) nil)
      ;; Inject messages (e.g., TTSR rule)
      (doseq [msg (:inject retry)]
        (swap! state update :messages conj msg))
      (when (< attempt 3) ;; max retries
        (recur (inc attempt))))))
```

**stream_filter event shape**:
```javascript
// Handler receives:
{ delta: "accumulated text so far", type: "message_update" }
// Can return:
{ abort: true, reason: "matched rule X", inject: [{role: "system", content: "..."}] }
```

**Used by**: TTSR (zero-cost rules), content guardrails, output format enforcement.

---

### Phase 5: Message Interception + Widget Priority (~40 LOC, 1 commit)

#### G12: `message_before_store` event

**File**: `src/agent/loop.cljs` — before storing assistant message

```clojure
;; Before storing:
(let [store-result (js-await
                     ((:emit-collect events) "message_before_store"
                       #js {:role "assistant" :content final-text}))
      final-text (or (get store-result "content") final-text)]
  (if-let [store (:store agent)]
    ((:dispatch! store) :message-added {:message {:role "assistant" :content final-text}})
    (swap! state update :messages conj {:role "assistant" :content final-text})))
```

**Used by**: Content redaction, memory tagging, annotation, structured extraction.

#### G7: Widget priority ordering

**File**: `src/agent/ui/widget_container.cljs`

Add `:priority` to widget config (default 0), sort widgets by priority descending:

```clojure
(defn WidgetContainer [{:keys [widgets position]}]
  (let [filtered (->> (vals widgets)
                      (filter #(= (:position %) position))
                      (sort-by #(- (or (:priority %) 0))))]
    ...))
```

**Extension API change** in `app.cljs`:
```clojure
(set! (.-setWidget ui)
  (fn [id lines & [pos priority]]
    (set-widgets (fn [w] (assoc w id {:lines ... :position (or pos "below") :priority (or priority 0)})))))
```

---

### Phase 6: File Access Restrictions (~80 LOC, 1 commit)

#### G15: `.nymaignore` + file access check event

**New file**: `src/agent/file_access.cljs`

```clojure
(ns agent.file-access
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]))

(defn load-ignore-patterns [cwd]
  ;; Load from .nymaignore (gitignore syntax) if exists
  ;; Return a predicate (fn [path] -> boolean) that returns true if access is denied
  ...)

(defn check-access [path patterns active-role]
  ;; Check file against ignore patterns and role restrictions
  ;; Returns {:allowed true/false :reason "..."}
  ...)
```

**Integration point**: In the `before_tool_call` handler (now emit-collect thanks to G8), a built-in handler checks file access:

```clojure
;; Register as a high-priority before_tool_call handler
((:on events) "before_tool_call"
  (fn [data]
    (let [tool-name (.-name data)
          path      (or (get-in (.-args data) [:path]) (get-in (.-args data) ["path"]))]
      (when (and path (#{"read" "write" "edit" "glob" "grep"} tool-name))
        (let [result (check-access path patterns role)]
          (when-not (:allowed result)
            #js {:block true :reason (str "Access denied: " (:reason result))})))))
  100) ;; high priority
```

---

### Phase 7: Permission Request Event (~30 LOC, 1 commit)

#### G13: `permission_request` emit-collect

**File**: `src/agent/middleware.cljs` or new `src/agent/permissions.cljs`

Before executing any tool that requires approval, emit:

```clojure
(let [perm-result (js-await
                    ((:emit-collect events) "permission_request"
                      #js {:tool      tool-name
                           :args      args
                           :category  (categorize-tool tool-name) ;; "read" "write" "exec" "network"
                           :path      (extract-path args)}))]
  (case (get perm-result "decision")
    "allow"  :proceed
    "deny"   {:cancelled true :reason (or (get perm-result "reason") "Denied")}
    "ask"    (prompt-user ...) ;; show UI dialog
    :default :proceed))
```

**Used by**: Custom approval profiles, audit logging, per-project security policies. The bash_suite extension would migrate to use this event instead of its internal approval logic.

---

### Phase 8: Tool Override Chaining (~40 LOC, 1 commit)

#### G5: Support `next()` in tool overrides

**File**: `src/agent/tool_registry.cljs`

When a tool is overridden, store the original and provide a `next` function:

```clojure
;; In register:
(when (get @tools name)
  (swap! overridden assoc name (get @tools name)))

;; When building the tool for execution, attach __original:
(let [tool (get @tools name)]
  (when-let [orig (get @overridden name)]
    (set! (.-__original tool) orig)))
```

**Extension API**: Extensions that override a tool can call `tool.__original.execute(args)` to chain to the original implementation. This enables wrapping patterns like hashline edits wrapping the standard edit tool.

---

### Phase 9: Hook Script Execution (~100 LOC, 1 commit)

#### G19: Declarative hooks via `.nyma/hooks.json`

**New file**: `src/agent/hooks.cljs`

```clojure
(ns agent.hooks
  "Load and execute declarative hook scripts from .nyma/hooks.json.
   Format: {event: [{command: '...', timeout: 5000}]}")

(defn load-hooks [cwd]
  ;; Read .nyma/hooks.json and ~/.nyma/hooks.json
  ;; Return {event-name → [{:command :timeout :cwd}]}
  ...)

(defn register-hooks [events hooks-map]
  ;; For each event → commands, register an event handler that:
  ;; 1. Spawns the command with event data as JSON on stdin
  ;; 2. Reads JSON from stdout
  ;; 3. Returns the parsed result (for emit-collect events)
  ...)
```

**Hook script contract** (same as Claude Code/Cline):
- Receives JSON on stdin: `{event, data}`
- Returns JSON on stdout: `{cancel?, reason?, content?, args?}` (for emit-collect events)
- Non-zero exit code = error (logged, doesn't crash)
- Timeout: default 5s, configurable

**Example `.nyma/hooks.json`**:
```json
{
  "before_tool_call": [
    {"command": "node .nyma/hooks/validate-tool.js", "timeout": 3000}
  ],
  "input_submit": [
    {"command": "python .nyma/hooks/check-secrets.py"}
  ],
  "session_ready": [
    {"command": "bash .nyma/hooks/inject-context.sh"}
  ]
}
```

---

### Phase 10: Checkpoint System (~150 LOC, 1 commit)

#### G16: Git-based checkpoints

**New file**: `src/agent/extensions/checkpoints/index.cljs`

Extension that subscribes to `tool_complete` (G9) and creates git stash-like snapshots:

```clojure
;; On every file-modifying tool completion:
(.on api "tool_complete" (fn [data]
  (when (#{"write" "edit" "bash"} (.-toolName data))
    ;; Create a lightweight checkpoint
    (let [checkpoint-id (str "nyma-cp-" (js/Date.now))]
      ;; git stash push -m "checkpoint-id" --keep-index
      ;; OR: git tag checkpoint-id
      ;; Store checkpoint metadata in SQLite
      ...))))
```

Commands: `/checkpoint list`, `/checkpoint revert <id>`, `/checkpoint diff <id>`

---

## Execution Order Summary

| Phase | Gaps | LOC | Commit |
|-------|------|-----|--------|
| 1. Critical hook fixes | G8, G9, G3, G6, G10 | ~80 | Infrastructure: make before_tool_call/input_submit cancellable, add tool_complete/provider_error events, expose editor API |
| 2. Session lifecycle | G4, G11 | ~30 | Add session_ready and session_end_summary events |
| 3. Tool filtering | G14 | ~60 | Add tool_access_check event for modes/personas |
| 4. Retry + stream filter | G1, G2 | ~100 | Add retry mechanism and stream_filter event for TTSR |
| 5. Message interception + widgets | G12, G7 | ~40 | Add message_before_store event, widget priority |
| 6. File access | G15 | ~80 | Add .nymaignore and file access checking |
| 7. Permission request | G13 | ~30 | Add permission_request emit-collect event |
| 8. Tool chaining | G5 | ~40 | Support next() in tool overrides |
| 9. Hook scripts | G19 | ~100 | Declarative .nyma/hooks.json execution |
| 10. Checkpoints | G16 | ~150 | Git-based checkpoint extension |

**Total: ~710 LOC across 10 commits**

---

## Features These Gaps Unlock (by agent inspiration)

| Feature | Gaps Required | Source Agent |
|---------|--------------|-------------|
| Modes/Personas (Plan, Act, Debug, Architect) | G14, G8, G15 | Roo Code, Cline, Kilo |
| TTSR (stream-reactive rules) | G1, G2 | oh-my-pi |
| Multi-credential fallback | G3 | oh-my-pi, OpenCode |
| Autonomous memory pipeline | G4, G11 | oh-my-pi, Windsurf |
| Checkpoints/revert | G9, G16 | Cline, Windsurf |
| Native subagents | G14, G8, G17 | Cline, Kilo, OpenCode |
| Hashline edits | G5 | oh-my-pi |
| Declarative hooks | G19 | Claude Code, Codex CLI, Gemini CLI |
| Permission profiles | G8, G13 | Kilo, OpenCode, Cline |
| File access restrictions | G15 | Roo Code, Kilo |
| Input validation/filtering | G10 | Cline, Codex CLI |
| Content redaction/memory tagging | G12 | Windsurf, OpenCode |
| Per-model edit optimization | G18, G5 | Aider |
| LSP feedback after edits | G9 | OpenCode |
| Prompt history paste | G6 | oh-my-pi |
| Multi-widget dashboard | G7 | oh-my-pi |

---

## Event Surface After All Gaps Are Fixed

Complete event catalog with emission type:

| Event | Type | Phase | Data Shape |
|-------|------|-------|-----------|
| `session_start` | emit | existing | `{}` |
| `session_ready` | emit-async | **new (P2)** | `{sessionFile, cwd, model, extensions}` |
| `session_end_summary` | emit-async | **new (P2)** | `{messages, totalCost, turnCount, duration}` |
| `session_shutdown` | emit | existing | `{}` |
| `input_submit` | **emit-collect** | **upgraded (P1)** | `{text, timestamp}` → can return `{cancel, text}` |
| `before_agent_start` | emit-collect | existing | `{userMessage, systemPrompt}` |
| `model_resolve` | emit-collect | existing (new) | `{default, context, turnCount}` |
| `context_assembly` | emit-collect | existing | `{messages, systemPrompt, tokenBudget}` |
| `before_provider_request` | emit-collect | existing | st-config (mutable) |
| `provider_error` | **emit-collect** | **new (P1)** | `{error, model, config}` → can return `{retry}` |
| `stream_filter` | **emit-collect** | **new (P4)** | `{delta, type}` → can return `{abort, inject}` |
| `message_start` | emit | existing | chunk |
| `message_update` | emit | existing | chunk |
| `message_end` | emit | existing | chunk |
| `message_before_store` | **emit-collect** | **new (P5)** | `{role, content}` → can return `{content}` |
| `tool_access_check` | **emit-collect** | **new (P3)** | `{tools, activeRole}` → can return `{allowed}` |
| `before_tool_call` | **emit-collect** | **upgraded (P1)** | `{name, args, execId}` → can return `{block, args}` |
| `tool_call` | emit | existing | chunk |
| `tool_execution_start` | emit | existing | `{tool-name, args, exec-id}` |
| `tool_execution_update` | emit | existing | `{exec-id, data}` |
| `tool_execution_end` | emit | existing | `{tool-name, duration, result}` |
| `tool_complete` | **emit-collect** | **new (P1)** | `{toolName, args, result, duration}` → can return `{result}` |
| `tool_result` | emit-collect | existing | `{toolName, result}` |
| `permission_request` | **emit-collect** | **new (P7)** | `{tool, args, category}` → can return `{decision}` |
| `after_provider_request` | emit | existing | `{usage, model, cachedTokens}` |
| `agent_end` | emit | existing | `{text, usage}` |
| `turn_start` | emit | existing | `{}` |
| `turn_end` | emit | existing | step |
| `editor_change` | emit | existing | `{text}` |
| `reload` | emit | existing | `{}` |
| `compact` | emit | existing | `{}` |

**New**: 8 events added, 2 events upgraded from fire-and-forget to emit-collect.
