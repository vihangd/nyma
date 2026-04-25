# Implementation Plan: Tier 1 Features

Detailed step-by-step plan for 5 features inspired by oh-my-pi, plus an analysis of hooks/events that should be extended to support these and future features.

---

## Part 0: New Hooks & Events Required

Before building features, we should extend nyma's event/hook surface to make these features (and future oh-my-pi-inspired ones) natural to build. These are small, surgical additions.

### 0.1 — `model_resolve` event (emit-collect)

**Where**: `loop.cljs:106` — currently inline: `(or (:runtime-model @state) (:model config))`

**Problem**: Model resolution is hardcoded. Model roles, fallback chains, and per-task model routing all need to intercept this decision.

**Change**: Replace the inline resolution with an emit-collect event that extensions can participate in:

```clojure
;; In loop.cljs, replace:
;;   (or (:runtime-model @state) (:model config))
;; With:
(let [resolve-result (js-await
                       ((:emit-collect events) "model_resolve"
                         #js {:default   (or (:runtime-model @state) (:model config))
                              :context   "generation"  ;; "generation" | "commit" | "plan" | etc
                              :turnCount (or (:turn-count @state) 0)}))
      active-model (or (get resolve-result "model") ;; extension override
                       (or (:runtime-model @state) (:model config)))]
  ...)
```

**Used by**: Model Roles, TTSR (retry with same/different model), fallback chains

### 0.2 — `stream_filter` event (emit-collect, per-delta)

**Where**: `loop.cljs:188` — inside the streaming loop

**Problem**: Currently `message_update` is fire-and-forget (`emit`). No extension can abort the stream based on content. This blocks TTSR entirely.

**Change**: For `message_update` events specifically, switch to emit-collect and check for abort:

```clojure
;; In the streaming loop, after line 188:
(let [chunk-val (.-value chunk)
      evt-type  (event-type chunk-val)]
  (if (= evt-type "message_update")
    ;; Stream filter — extensions can request abort
    (let [filter-result (js-await
                          ((:emit-collect events) "stream_filter"
                            #js {:delta   (.-textDelta chunk-val)
                                 :text    accumulated-text  ;; running buffer
                                 :type    evt-type}))]
      ((:emit events) evt-type chunk-val)
      (when (get filter-result "abort")
        ;; Break out of stream loop, set retry flag
        ...))
    ((:emit events) evt-type chunk-val)))
```

**Note**: This is NOT needed for Tier 1 features. Add it when implementing TTSR. Documenting here so the loop structure accounts for it.

### 0.3 — `input_submit` event (emit, after user presses Enter)

**Where**: `app.cljs` (or wherever `onSubmit` is wired) — the point where editor text becomes a user message.

**Problem**: No event fires when the user submits a prompt. Extensions can't capture input for history, analytics, or transformation.

**Change**: Emit `input_submit` with the raw text before it enters the agent loop:

```clojure
((:emit events) "input_submit" #js {:text user-text :timestamp (js/Date.now)})
```

**Used by**: Prompt History (captures every submission), analytics, input transformation hooks

### 0.4 — `render_messages` event (emit-collect, in ChatView)

**Where**: `chat_view.cljs:80-87` — the map-indexed over messages

**Problem**: Grouped tool display requires pre-processing the message list before rendering. Currently each message renders independently.

**Change**: Before rendering, emit an event that lets extensions transform the message list:

```clojure
;; In ChatView, before map-indexed:
(let [grouped (or (get (emit-collect-sync "render_messages" #js {:messages messages}) "messages")
                  messages)]
  (map-indexed ... grouped))
```

**Alternative (simpler)**: Do the grouping directly in ChatView without an event. This is a rendering concern, not an extension concern. The event is only worth adding if we want extensions to customize grouping logic.

**Recommendation**: Skip the event for now. Implement grouped display as a built-in rendering optimization in ChatView. Add the event later if extensions need it.

### 0.5 — SQLite `prompt_history` table

**Where**: `sessions/storage.cljs` — add a new table to the schema

**Change**: Add to `schema-sql`:

```sql
CREATE TABLE IF NOT EXISTS prompt_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT NOT NULL,
  session_file TEXT,
  timestamp INTEGER,
  token_estimate INTEGER
)
CREATE INDEX IF NOT EXISTS idx_prompt_history_ts ON prompt_history(timestamp DESC)
```

**Used by**: Prompt History, Stats Dashboard (prompts/session metrics)

### Summary of Hook Changes

| Hook | Type | Location | Needed For | Priority |
|------|------|----------|-----------|----------|
| `model_resolve` | emit-collect | loop.cljs | Model Roles, TTSR, fallbacks | **Build now** |
| `input_submit` | emit | app.cljs | Prompt History, analytics | **Build now** |
| `stream_filter` | emit-collect | loop.cljs | TTSR (Tier 2) | Defer |
| `render_messages` | emit-collect | chat_view | Grouped display | Skip (do inline) |
| `prompt_history` table | SQLite | storage.cljs | Prompt History, Stats | **Build now** |

**Total prep work**: ~50 LOC across 3 files. Do this first as a single commit.

---

## Feature 1: Model Roles

### Overview
Named model presets that map role names to provider/model pairs. `/role fast` switches to a cheap model, `/role deep` to a reasoning model. Roles are defined in settings; the active role affects model resolution via the new `model_resolve` event.

### Step-by-Step

#### Step 1: Define role schema in settings

**File**: `src/agent/settings/manager.cljs`

Add to defaults:
```clojure
:roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
        :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
        :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
        :plan    {:provider "anthropic" :model "claude-opus-4-20250514"}
        :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"}}
:active-role :default
```

Users override in `~/.nyma/settings.json` or `.nyma/settings.json`:
```json
{
  "roles": {
    "fast": {"provider": "openai", "model": "gpt-4o-mini"},
    "deep": {"provider": "anthropic", "model": "claude-opus-4-20250514"}
  }
}
```

#### Step 2: Add `:active-role` to agent state

**File**: `src/agent/core.cljs`

Add `:active-role :default` to initial state map.

#### Step 3: Create `src/agent/extensions/model_roles/index.cljs`

Extension logic (~80 LOC):

```clojure
(defn ^:export default [api]
  ;; Read roles from settings
  ;; Subscribe to model_resolve event:
  ;;   - Look up active-role in state
  ;;   - If role has a model mapping, resolve it via provider registry
  ;;   - Return {:model resolved-model} to override default resolution
  ;; Register /role command:
  ;;   - /role → show current role and available roles
  ;;   - /role <name> → switch active role, resolve model, call api.setModel()
  ;;   - /role reset → revert to :default
  ;; Register /roles command:
  ;;   - List all defined roles with their model assignments
  ;;   - Mark active role
  ;; Return cleanup function
  )
```

#### Step 4: Wire `model_resolve` in loop.cljs

Replace line 106:
```clojure
active-model (or (:runtime-model @state) (:model config))
```
With the emit-collect pattern from section 0.1.

#### Step 5: Add context hint for role

In `before_agent_start` handler, inject a hint like `"Current model role: fast (gpt-4o-mini)"` so the LLM knows its operational mode.

#### Step 6: Tests

**File**: `test/model_roles.test.cljs` (~15 tests)

- Role defined in settings resolves correct model
- `/role fast` switches model and updates state
- `/role` with no args shows current role
- Unknown role shows error
- `model_resolve` event returns role's model
- Fallback to default when role has no mapping
- Settings cascade (project overrides global)

### Files Changed
- `src/agent/settings/manager.cljs` — add role defaults
- `src/agent/core.cljs` — add `:active-role` to state
- `src/agent/loop.cljs` — add `model_resolve` event
- `src/agent/extensions/model_roles/index.cljs` — **new** extension
- `test/model_roles.test.cljs` — **new** tests

---

## Feature 2: Grouped Tool Display

### Overview
When multiple consecutive tool calls of the same type appear (e.g., 3 reads, 5 greps), collapse them into a single compact group showing a file tree or summary line instead of N separate blocks.

### Step-by-Step

#### Step 1: Add message grouping logic

**File**: `src/agent/ui/chat_view.cljs`

Add a `group-messages` function that scans the message list and collapses consecutive same-type tool pairs (tool-start + tool-end) into group entries:

```clojure
(defn- group-messages [messages]
  ;; Walk messages, detect consecutive tool-start/tool-end pairs with same tool-name
  ;; Replace sequences of 2+ same-tool pairs with a single :tool-group message:
  ;;   {:role "tool-group"
  ;;    :tool-name "read"
  ;;    :items [{:args {...} :duration N :result "..."} ...]
  ;;    :count 5}
  ;; Leave single tool calls and non-tool messages untouched
  )
```

Grouping rules:
- Only group consecutive pairs of the same tool (e.g., 3 reads in a row)
- Minimum 2 calls to trigger grouping
- Group across: `read`, `glob`, `grep`, `ls` (file-browsing tools)
- Don't group: `bash`, `write`, `edit` (side-effect tools — show individually)

#### Step 2: Add `ToolGroupStatus` component

**File**: `src/agent/ui/tool_status.cljs` (or new `tool_group.cljs`)

Render grouped tools as a compact tree:

```
⚙ read ×3 (0.4s total)
  ├ src/agent/loop.cljs
  ├ src/agent/core.cljs
  └ src/agent/events.cljs
```

For grep/glob, show the patterns:
```
⚙ grep ×2 (0.2s)
  ├ "model_resolve" → 3 files
  └ "emit-collect" → 7 files
```

Component props: `{:tool-name, :items [{:args, :duration, :result}], :theme}`

#### Step 3: Wire into ChatView

In `ChatView`, wrap messages with `group-messages` before rendering:

```clojure
(defn ChatView [{:keys [messages theme block-renderers]}]
  (let [grouped (useMemo #(group-messages messages) #js [messages])]
    #jsx [Box ...
      (map-indexed
        (fn [i msg]
          (case (:role msg)
            "tool-group" #jsx [ToolGroupStatus {:key i ...}]
            #jsx [MessageBubble {:key i ...}]))
        grouped)]))
```

#### Step 4: Extract display info from tool args

For the tree display, extract the meaningful identifier from each tool's args:
- `read` → `(:path args)`
- `glob` → `(:pattern args)`
- `grep` → `(:pattern args)` + result file count
- `ls` → `(:path args)`

**File**: `src/agent/ui/tool_status.cljs` — add `tool-group-label` function.

#### Step 5: Tests

**File**: `test/grouped_tool_display.test.cljs` (~12 tests)

- 3 consecutive reads → 1 group of 3
- Mixed tools (read, write, read) → no grouping across write
- Single tool call → no grouping
- Group includes total duration
- Non-groupable tools (bash, write) stay individual
- Interleaved assistant message breaks grouping

### Files Changed
- `src/agent/ui/chat_view.cljs` — add `group-messages`, wire into render
- `src/agent/ui/tool_status.cljs` — add `ToolGroupStatus` component + `tool-group-label`
- `test/grouped_tool_display.test.cljs` — **new** tests

---

## Feature 3: AST Tools (ast-grep)

### Overview
Two new LLM-callable tools that wrap the `sg` (ast-grep) binary for syntax-aware code search and structural editing. Much more precise than regex grep for code patterns.

### Step-by-Step

#### Step 1: Create extension directory

**File**: `src/agent/extensions/ast_tools/index.cljs`

Extension registers two tools on activation:

```clojure
(defn ^:export default [api]
  ;; Check if `sg` binary exists (exec "which sg")
  ;; If not, log warning and register tools that return "ast-grep not installed" message
  ;; Register ast_grep and ast_edit tools
  ;; Return cleanup function that unregisters both tools
  )
```

#### Step 2: `ast_grep` tool

Schema:
```clojure
{:description "Search code using AST patterns (syntax-aware, not regex).
               Uses ast-grep (sg) for structural matching across files."
 :schema {:pattern  {:type :string :description "ast-grep pattern (e.g., 'console.log($$$)')"}
          :path     {:type :string :description "Directory or file to search" :optional true}
          :language {:type :string :description "Language hint (js, ts, py, go, rust, etc.)" :optional true}
          :json     {:type :boolean :description "Return structured JSON matches" :optional true}}}
```

Execute: shell out to `sg run --pattern <pattern> [--lang <lang>] [--json] <path>`

Parse output: If JSON mode, parse and format as structured results with file:line:match. If text mode, return raw output (already formatted).

Truncation: Limit output to 50 matches or 5000 chars, whichever is first. Append "(truncated, N more matches)" if over limit.

#### Step 3: `ast_edit` tool

Schema:
```clojure
{:description "Apply structural code transformations using ast-grep rules.
               Rewrites matching AST patterns to a replacement pattern."
 :schema {:pattern     {:type :string :description "ast-grep pattern to match"}
          :replacement {:type :string :description "Replacement pattern (use $VAR for captures)"}
          :path        {:type :string :description "File or directory to transform"}
          :language    {:type :string :description "Language hint" :optional true}
          :dry-run     {:type :boolean :description "Preview changes without applying" :optional true}}}
```

Execute: `sg run --pattern <pattern> --rewrite <replacement> [--lang <lang>] <path>`

For dry-run: add `--update-all --dry-run` (or capture diff output).

Safety: Always show a diff of changes in the result. Never apply to more than 20 files without user confirmation (return a warning and ask the LLM to confirm).

#### Step 4: Tool display metadata

Both tools should define `display` for compact chat rendering:
```clojure
{:formatArgs (fn [name args] (str (:pattern args) " in " (or (:path args) ".")))
 :formatResult (fn [result] (let [lines (count (str/split-lines result))]
                               (str lines " matches")))
 :icon "🌳"}
```

#### Step 5: Tests

**File**: `test/ast_tools.test.cljs` (~10 tests)

- ast_grep returns "not installed" message when sg missing
- ast_grep builds correct command for pattern + path
- ast_grep truncates large output
- ast_edit builds correct command with --rewrite
- ast_edit dry-run flag adds correct args
- Tool display metadata formats correctly
- Language hint passed through

### Files Changed
- `src/agent/extensions/ast_tools/index.cljs` — **new** extension
- `src/agent/extensions/ast_tools/extension.json` — **new** manifest
- `test/ast_tools.test.cljs` — **new** tests

---

## Feature 4: Prompt History + Ctrl+R Search

### Overview
Every prompt the user submits is persisted to SQLite. Ctrl+R opens a fuzzy search overlay to find and re-submit past prompts. Up/Down arrows in the editor cycle through recent history.

### Step-by-Step

#### Step 1: Add `prompt_history` table to SQLite schema

**File**: `src/agent/sessions/storage.cljs`

Add to `schema-sql`:
```clojure
"CREATE TABLE IF NOT EXISTS prompt_history (
   id INTEGER PRIMARY KEY AUTOINCREMENT,
   text TEXT NOT NULL,
   session_file TEXT,
   timestamp INTEGER
 )"
"CREATE INDEX IF NOT EXISTS idx_prompt_ts ON prompt_history(timestamp DESC)"
```

Add storage API functions:
```clojure
:insert-prompt (fn [text session-file] ...)
:search-prompts (fn [query limit] ...)      ;; LIKE search, ordered by recency
:recent-prompts (fn [limit] ...)            ;; Most recent N prompts
```

#### Step 2: Emit `input_submit` event

**File**: `src/agent/ui/app.cljs` (wherever `onSubmit` is handled)

After the user presses Enter and before the text enters the agent loop:
```clojure
((:emit events) "input_submit" #js {:text text :timestamp (js/Date.now)})
```

#### Step 3: Create prompt history extension

**File**: `src/agent/extensions/prompt_history/index.cljs` (~120 LOC)

```clojure
(defn ^:export default [api]
  ;; Get SQLite store reference from agent
  ;; Subscribe to input_submit event → insert-prompt
  ;; Register Ctrl+R shortcut → open history search overlay
  ;; Expose history-nav API for editor up/down arrow integration
  ;; Return cleanup
  )
```

#### Step 4: History search overlay component

**File**: `src/agent/ui/history_picker.cljs` (~90 LOC)

Similar to `skill_picker.cljs` — a fuzzy-searchable list:

```
Search history (type to filter, Enter to select, Esc to cancel)
> deploy
  ▶ deploy the new auth service to staging       (2h ago)
    fix the deployment script for CI              (yesterday)
    how do we deploy to production?               (3 days ago)
```

Component: `{render, onInput, dispose}` — same interface as skill picker.

Features:
- Fuzzy match on prompt text
- Show relative timestamps
- Truncate long prompts to one line
- Scroll window for many results
- Enter pastes selected prompt into editor (doesn't submit)

#### Step 5: Wire Ctrl+R shortcut

In the extension's `default` function:
```clojure
(.registerShortcut api "ctrl+r"
  (fn []
    (let [picker (create-history-picker
                   recent-prompts
                   (fn [selected]
                     (when selected
                       (.setEditorValue ... selected))))]
      (.custom (.-ui api) picker))))
```

#### Step 6: Up/Down arrow history in editor

**File**: `src/agent/ui/editor.cljs`

This requires the editor component to accept a `history` prop (array of past prompts) and track a history index. When the editor is empty or at position 0:
- Up arrow → load previous prompt from history
- Down arrow → load next prompt (or clear if at end)

Add props: `{:history [...] :onHistoryNav (fn [direction] ...)}`

The extension populates this via the UI API or by passing history through the event system.

**Alternative (simpler)**: Skip editor Up/Down for now. Ctrl+R search covers 90% of the use case. Add Up/Down as a follow-up.

#### Step 7: Tests

**File**: `test/prompt_history.test.cljs` (~14 tests)

- Prompt inserted on input_submit event
- Search returns matching prompts ordered by recency
- Empty search returns most recent prompts
- Duplicate consecutive prompts deduplicated
- History picker renders correct format
- History picker fuzzy match works
- Ctrl+R shortcut opens picker
- Selected prompt populates editor (doesn't submit)
- History table created on schema init

### Files Changed
- `src/agent/sessions/storage.cljs` — add `prompt_history` table + API
- `src/agent/ui/app.cljs` — emit `input_submit` event
- `src/agent/extensions/prompt_history/index.cljs` — **new** extension
- `src/agent/ui/history_picker.cljs` — **new** UI component
- `src/agent/ui/editor.cljs` — (optional) add history prop for Up/Down
- `test/prompt_history.test.cljs` — **new** tests

---

## Feature 5: Stats Dashboard

### Overview
`/stats` command that queries SQLite usage data and renders a usage dashboard: cost over time, tokens per turn, model breakdown, session summary.

### Step-by-Step

#### Step 1: Add aggregate queries to SQLite storage

**File**: `src/agent/sessions/storage.cljs`

Add query functions:
```clojure
:get-usage-by-model
;; SELECT model, SUM(input_tokens), SUM(output_tokens), SUM(cost_usd), COUNT(*)
;;   FROM usage GROUP BY model ORDER BY SUM(cost_usd) DESC

:get-usage-by-day
;; SELECT date(timestamp/1000, 'unixepoch') as day,
;;        SUM(input_tokens), SUM(output_tokens), SUM(cost_usd), COUNT(*)
;;   FROM usage GROUP BY day ORDER BY day DESC LIMIT 30

:get-usage-totals
;; SELECT SUM(input_tokens), SUM(output_tokens), SUM(cost_usd),
;;        COUNT(*), COUNT(DISTINCT session_file)
;;   FROM usage

:get-recent-turns
;; SELECT model, input_tokens, output_tokens, cost_usd, timestamp
;;   FROM usage ORDER BY timestamp DESC LIMIT 20
```

#### Step 2: Create stats formatter

**File**: `src/agent/extensions/stats_dashboard/formatter.cljs` (~60 LOC)

Format query results into a readable dashboard string:

```
─── Usage Stats ───────────────────────────────

  Totals
    Sessions:  47        Turns:  312
    Input:     2.4M tok  Output:  890k tok
    Cost:      $12.47

  By Model (last 30 days)
    claude-sonnet-4   $8.20   (204 turns)
    claude-opus-4     $3.15   (18 turns)
    gpt-4o-mini       $1.12   (90 turns)

  Daily (last 7 days)
    Apr 10  $2.10  ████████████
    Apr 09  $1.85  ██████████
    Apr 08  $0.45  ███
    Apr 07  $3.20  █████████████████
    ...

  Recent Turns
    sonnet  1.2k→450  $0.01  (2m ago)
    sonnet  3.4k→890  $0.02  (15m ago)
    opus    12k→2.1k  $0.34  (1h ago)
```

Use simple ASCII bar charts. Format tokens with `format-tokens` from pricing.cljs.

#### Step 3: Register `/stats` command

**File**: `src/agent/extensions/stats_dashboard/index.cljs` (~40 LOC)

```clojure
(defn ^:export default [api]
  ;; Get SQLite store reference
  ;; Register /stats command:
  ;;   - Query all aggregate data
  ;;   - Format with formatter
  ;;   - Show in overlay (ui.showOverlay)
  ;; Register /stats today command:
  ;;   - Filter to today only
  ;; Register /stats session command:
  ;;   - Current session only
  ;; Return cleanup
  )
```

#### Step 4: Optional — stats widget

Register a footer widget showing running session cost:
```
$0.47 | 12 turns | sonnet
```

This piggybacks on the existing `after_provider_request` event to update.

#### Step 5: Tests

**File**: `test/stats_dashboard.test.cljs` (~12 tests)

- Aggregate queries return correct sums
- By-model grouping works
- By-day grouping works
- Formatter produces expected output structure
- Empty usage table returns zeros
- `/stats` command calls showOverlay
- Bar chart scales correctly
- Token formatting (k, M suffixes)

### Files Changed
- `src/agent/sessions/storage.cljs` — add aggregate query functions
- `src/agent/extensions/stats_dashboard/index.cljs` — **new** extension
- `src/agent/extensions/stats_dashboard/formatter.cljs` — **new** formatter
- `test/stats_dashboard.test.cljs` — **new** tests

---

## Execution Order

Build in this order to minimize dependencies:

### Phase 1: Infrastructure (1 commit)
1. Add `model_resolve` event to `loop.cljs`
2. Add `input_submit` event to `app.cljs`
3. Add `prompt_history` table to `storage.cljs`
4. Add aggregate query functions to `storage.cljs`
5. Add `:active-role` to initial state in `core.cljs`

### Phase 2: Features (1 commit each)
6. **Model Roles** extension — depends on `model_resolve` event
7. **Grouped Tool Display** — pure UI, no dependencies
8. **AST Tools** — standalone extension, no dependencies
9. **Prompt History** — depends on `input_submit` event + SQLite table
10. **Stats Dashboard** — depends on aggregate queries in SQLite

### Phase 3: Polish
11. Add role hints to system prompt
12. Add stats footer widget
13. Add editor Up/Down history navigation

---

## Extension API Gaps Identified

Beyond the hooks above, these API additions would help future oh-my-pi features:

| Gap | Current State | Needed For | Suggestion |
|-----|--------------|-----------|-----------|
| No retry mechanism | Loop doesn't support retry-on-failure | TTSR, rate-limit fallback | Add `retry` flag to stream loop context |
| No abort-and-retry | AbortController exists but no retry path | TTSR | Add retry queue alongside steer/follow queues |
| No `provider_error` event | Errors throw, no event | Multi-credential fallback | Emit `provider_error` before throwing in loop |
| No `session_end_summary` event | Session ends silently | Autonomous Memory | Emit with accumulated context when session closes |
| No tool override chaining | Override replaces, doesn't chain | Hashline edits wrapping existing edit tool | Support `next()` in tool override middleware |
| Editor value not accessible from extensions | `setEditorValue` not in extension API | Prompt history paste-into-editor | Expose `setEditorValue` + `getEditorValue` on `ui` |
| No widget priority/ordering | Widgets render in arbitrary order | Multiple footer widgets (stats, token preview) | Add `:priority` to widget config |

These are not blocking for Tier 1 but should be on the radar for Tier 2+.
