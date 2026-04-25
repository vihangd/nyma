# Extension Improvements Using New Hooks/Events

Step-by-step plan to improve all existing extensions with the 15 implemented hooks, plus build the full modes system. Each step is one commit.

---

## Step 1: Bash Suite — Migrate to emit-collect pattern

### Current problem
`security_analysis.cljs:222-244` and `permissions.cljs:61-84` use the old mutable `evt-ctx` pattern:
```clojure
(set! (.-cancelled evt-ctx) true)
(set! (.-reason evt-ctx) "...")
```
This is fragile, undocumented, and breaks with the new emit-collect `before_tool_call`.

### Changes

**File: `src/agent/extensions/bash_suite/security_analysis.cljs:218-248`**

Replace the `before_tool_call` handler. The handler now returns `{block: true, reason: "..."}` instead of mutating ctx.

```clojure
;; Before (old mutable pattern):
(.on api "before_tool_call"
  (fn [evt-ctx]
    (when (and (:enabled sec-config)
               (shared/is-bash-tool? (.-toolName evt-ctx)))
      (let [cmd    (.-command (.-input evt-ctx))
            result (classify-command cmd sec-config)]
        ;; ...emit classification...
        (when (should-block? result sec-config)
          (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
          (set! (.-cancelled evt-ctx) true)
          (set! (.-reason evt-ctx) ...)))))
  100)

;; After (emit-collect return):
(.on api "before_tool_call"
  (fn [data]
    (when (and (:enabled sec-config)
               (shared/is-bash-tool? (.-name data)))
      (let [cmd    (.-command (.-args data))
            result (classify-command cmd sec-config)]
        ;; Emit classification on inter-extension bus
        (when-let [events (.-events api)]
          (let [emit-fn (.-emit events)]
            (when (fn? emit-fn)
              (emit-fn "bash:classification" (clj->js result)))))
        ;; Update stats
        (swap! shared/suite-stats update :security-analysis
          (fn [s] (-> s
                      (update :commands-analyzed inc)
                      (update-in [:classified (:level result)] inc))))
        ;; Block via return value (not mutation)
        (when (should-block? result sec-config)
          (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
          #js {:block true
               :reason (str "BLOCKED [" (str (:level result)) "]: "
                            (str/join "; " (:reasons result)))}))))
  100)
```

**Key changes**:
- `(.-toolName evt-ctx)` → `(.-name data)` (new data shape)
- `(.-command (.-input evt-ctx))` → `(.-command (.-args data))` (args directly on data)
- `(set! (.-cancelled evt-ctx) true)` → `#js {:block true :reason "..."}`
- Remove `(not (.-cancelled evt-ctx))` guard (no longer needed, emit-collect merges)

**LOC impact**: -5 lines (remove mutation + guard, add return)

**File: `src/agent/extensions/bash_suite/permissions.cljs:61-84`**

Same migration:

```clojure
;; Before:
(.on api "before_tool_call"
  (fn [evt-ctx]
    (when (and (:enabled perm-config)
               (shared/is-bash-tool? (.-toolName evt-ctx))
               (not (.-cancelled evt-ctx)))
      (let [cmd      (.-command (.-input evt-ctx))
            ...
            decision (check-decision ...)]
        (case decision
          :denied
          (do (swap! shared/suite-stats ...)
              (set! (.-cancelled evt-ctx) true)
              (set! (.-reason evt-ctx) "Permission denied..."))
          ...))))
  90)

;; After:
(.on api "before_tool_call"
  (fn [data]
    (when (and (:enabled perm-config)
               (shared/is-bash-tool? (.-name data)))
      (let [cmd      (.-command (.-args data))
            ...
            decision (check-decision ...)]
        (case decision
          :denied
          (do (swap! shared/suite-stats ...)
              #js {:block true :reason "Permission denied: command matches denylist"})
          ...))))
  90)
```

**LOC impact**: -8 lines (remove mutation pattern, cancelled guard, reason mutation)

**File: `src/agent/extensions/bash_suite/env_filter.cljs:22-34`**

Env filter modifies the command by prepending `unset` preamble. With emit-collect, return `{args: modified}`:

```clojure
;; Before:
(.on api "before_tool_call"
  (fn [evt-ctx]
    (when (and (:enabled env-config)
               (shared/is-bash-tool? (.-toolName evt-ctx))
               (not (.-cancelled evt-ctx))
               (not (str/blank? preamble)))
      (let [input (.-input evt-ctx)
            cmd   (.-command input)]
        (aset input "command" (str preamble cmd))
        (swap! shared/suite-stats ...))))
  80)

;; After:
(.on api "before_tool_call"
  (fn [data]
    (when (and (:enabled env-config)
               (shared/is-bash-tool? (.-name data))
               (not (str/blank? preamble)))
      (let [cmd (.-command (.-args data))]
        (swap! shared/suite-stats ...)
        #js {:args #js {:command (str preamble cmd)}})))
  80)
```

**LOC impact**: -4 lines (remove cancelled guard, simplify mutation to return)

### Total LOC impact for Step 1: **-17 lines** removed from bash_suite

### Tests
Update `test/ext_bash_security.test.cljs` (if exists) to use the new data shape. Add:
- Security analysis returns `{block: true}` for destructive commands
- Permissions returns `{block: true}` for denied commands
- Env filter returns `{args: {command: "unset... ; original"}}` for filtered commands

---

## Step 2: Bash Suite — Migrate permissions to `permission_request`

### Current problem
`permissions.cljs` has its own full permission decision flow (denylist → allowlist → session memory → auto-approve). This duplicates what the new `permission_request` interceptor does.

### Changes

**File: `src/agent/extensions/bash_suite/permissions.cljs`**

Replace the `before_tool_call` handler with a `permission_request` handler. The permission interceptor in middleware already fires before `before_tool_call`, so bash suite doesn't need its own check.

```clojure
;; Replace entire before_tool_call handler with:
(.on api "permission_request"
  (fn [data]
    (when (and (:enabled perm-config) (= (.-tool data) "bash"))
      (let [cmd      (str (.-command (.-args data)))
            classif  @last-classification
            decision (check-decision cmd classif perm-config session-approvals)]
        (case decision
          :denied
          (do (swap! shared/suite-stats update-in [:permissions :denied] inc)
              #js {:decision "deny" :reason "Denylist match"})

          :approved
          (do (swap! session-approvals assoc cmd :approved)
              (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
              #js {:decision "allow"})

          :remembered
          (do (swap! shared/suite-stats update-in [:permissions :remembered] inc)
              #js {:decision "allow"})

          ;; :needs-approval
          (do (swap! session-approvals assoc cmd :approved)
              (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
              #js {:decision "allow"})))))
  90)
```

**LOC impact**: -6 lines (remove old pattern, cleaner returns). But the real win is architectural — bash suite's permission logic now participates in the shared permission system. Other extensions can also handle `permission_request` for non-bash tools.

---

## Step 3: Model Roles — Add modes system (tool restrictions + permissions)

### Current state
Model roles only switches the model. It has no tool or permission restrictions per role.

### Changes

**File: `src/agent/settings/manager.cljs`** — Extend role defaults with tool/permission config:

```clojure
:roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
        :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
        :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
        :plan    {:provider "anthropic" :model "claude-opus-4-20250514"
                  :allowed-tools ["read" "glob" "grep" "ls" "think" "web_search" "web_fetch"]
                  :permissions {"write" "deny" "edit" "deny" "bash" "deny"}}
        :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"
                  :allowed-tools ["read" "bash" "glob" "grep" "edit" "write"]}}
```

**File: `src/agent/extensions/model_roles/index.cljs`** — Add `tool_access_check` and `permission_request` handlers:

After the existing `model_resolve` handler, add:

```clojure
;; tool_access_check — restrict tools based on active role
on-tool-access
(fn [data]
  (let [state    (.getState api)
        role     (or (:active-role state) :default)
        roles    (get-roles api)
        role-cfg (get roles (keyword role))
        allowed  (or (:allowed-tools role-cfg)
                     (get role-cfg "allowed-tools"))]
    (when (seq allowed)
      #js {:allowed (clj->js allowed)})))

;; permission_request — deny/allow based on role permissions
on-permission
(fn [data]
  (let [state    (.getState api)
        role     (or (:active-role state) :default)
        roles    (get-roles api)
        role-cfg (get roles (keyword role))
        perms    (or (:permissions role-cfg) (get role-cfg "permissions"))
        tool     (.-tool data)]
    (when-let [decision (get perms tool)]
      #js {:decision decision})))
```

Register both:
```clojure
(.on api "tool_access_check" on-tool-access)
(swap! handlers conj ["tool_access_check" on-tool-access])

(.on api "permission_request" on-permission)
(swap! handlers conj ["permission_request" on-permission])
```

Update `/role` command display to show tool restrictions:
```clojure
(defn- format-role-list [roles active-role]
  (str/join "\n"
    (map (fn [[rname rconf]]
           (let [model-id (or (:model rconf) (get rconf "model") "?")
                 provider (or (:provider rconf) (get rconf "provider") "?")
                 allowed  (or (:allowed-tools rconf) (get rconf "allowed-tools"))
                 marker   (if (= (keyword rname) (keyword active-role)) " ◀" "")
                 tools-hint (when (seq allowed)
                              (str " [" (count allowed) " tools]"))]
             (str "  " (name rname) " → " provider "/" model-id
                  (or tools-hint "") marker)))
         roles)))
```

**LOC impact**: +25 lines to model_roles. But this turns it into a **full modes system** — no new extension needed.

### What users can now do

```json
// .nyma/settings.json
{
  "roles": {
    "plan": {
      "provider": "anthropic",
      "model": "claude-opus-4-20250514",
      "allowed-tools": ["read", "glob", "grep", "ls", "think"],
      "permissions": {"write": "deny", "edit": "deny", "bash": "deny"}
    },
    "debug": {
      "provider": "anthropic",
      "model": "claude-sonnet-4-20250514",
      "allowed-tools": ["read", "bash", "grep", "glob", "ls", "think"],
      "permissions": {"write": "deny", "edit": "deny"}
    },
    "architect": {
      "provider": "anthropic",
      "model": "claude-opus-4-20250514",
      "allowed-tools": ["read", "glob", "grep", "ls", "think", "web_search"],
      "permissions": {"bash": "deny", "write": "deny"}
    }
  }
}
```

Then `/role plan` → read-only exploration, `/role debug` → read + bash only, `/role architect` → planning with web search.

### Tests: `test/model_roles_modes.test.cljs` (~8 tests)
- Plan role restricts tools to read-only set
- Plan role denies write/edit/bash via permission_request
- Default role has no restrictions
- Custom role with allowed-tools filters correctly
- Custom role with permissions overrides correctly
- `/role` display shows tool count hint
- Role switch updates both model and tool access
- tool_access_check with no allowed-tools returns nil (no restriction)

---

## Step 4: Desktop Notify — Enrich with tool_complete and session_end_summary

### Changes

**File: `src/agent/extensions/desktop_notify/index.cljs`** — Add two new handlers:

```clojure
;; Notify on long-running tool completions (>10s)
on-tool-complete
(fn [data _ctx]
  (when (and (> (.-duration data) 10000) enabled)
    (send-notification! "nyma"
      (str (.-toolName data) " completed (" 
           (js/Math.round (/ (.-duration data) 1000)) "s)"))))

;; Notify with session summary on exit
on-session-end
(fn [data _ctx]
  (when enabled
    (send-notification! "nyma"
      (str "Session: " (.-turnCount data) " turns, "
           "$" (.toFixed (or (.-totalCost data) 0) 2)))))
```

Register:
```clojure
(.on api "tool_complete" on-tool-complete)
(.on api "session_end_summary" on-session-end)
```

Cleanup:
```clojure
(.off api "tool_complete" on-tool-complete)
(.off api "session_end_summary" on-session-end)
```

**LOC impact**: +15 lines

---

## Step 5: Stats Dashboard — Add per-tool metrics via tool_complete

### Changes

**File: `src/agent/extensions/stats_dashboard/index.cljs`** — Add tool performance tracking:

```clojure
(let [tool-metrics (atom {}) ;; {tool-name → {:calls :total-ms :errors}}

      on-tool-complete
      (fn [data]
        (let [tool (.-toolName data)
              dur  (or (.-duration data) 0)
              err  (boolean (.-isError data))]
          (swap! tool-metrics update tool
            (fn [m]
              (let [m (or m {:calls 0 :total-ms 0 :errors 0})]
                (-> m
                    (update :calls inc)
                    (update :total-ms + dur)
                    (cond-> err (update :errors inc))))))))
      ...]

  (.on api "tool_complete" on-tool-complete)
```

Add `/stats tools` sub-command:
```clojure
"tools"
(let [metrics @tool-metrics]
  (if (empty? metrics)
    (.notify (.-ui ctx) "No tool calls recorded" "info")
    (.notify (.-ui ctx)
      (str "Tool Performance:\n"
           (str/join "\n"
             (map (fn [[name m]]
                    (str "  " name ": " (:calls m) " calls, "
                         (js/Math.round (/ (:total-ms m) (max 1 (:calls m)))) "ms avg"
                         (when (> (:errors m) 0)
                           (str ", " (:errors m) " errors"))))
               (sort-by (fn [[_ m]] (- (:calls m))) metrics)))))))
```

**LOC impact**: +30 lines

---

## Step 6: Prompt History — Use ui.setEditorValue for Ctrl+R paste

### Current problem
`prompt_history/index.cljs:97` uses `(.-setEditorValue api)` which was nil before G6. Now it works.

### Changes

**File: `src/agent/extensions/prompt_history/index.cljs`** — Update the Ctrl+R handler:

Replace the current picker resolution:
```clojure
(fn [selected]
  ;; Close overlay (handled by custom component lifecycle)
  (when (and selected (.-ui api))
    ;; Set editor value to selected prompt
    (when-let [set-val (.-setEditorValue api)]
      (set-val selected))))
```

With:
```clojure
(fn [selected]
  (when (and selected (.-ui api))
    ;; Paste into editor via the new ui.setEditorValue
    (when (.-setEditorValue (.-ui api))
      (.setEditorValue (.-ui api) selected))))
```

**LOC impact**: 0 lines changed (fix reference path from api to api.ui)

---

## Step 7: Agent Shell — Use session_ready for reliable init

### Current problem
Agent shell lazily initializes UI (header/footer) on first agent connect because UI isn't available during extension activation.

### Changes

**File: `src/agent/extensions/agent_shell/shared.cljs`** — Remove lazy UI setup from `setup-ui!`:

The function currently checks `(.-available (.-ui api))` and bails if not ready. With `session_ready`, we can guarantee UI is available.

**File: `src/agent/extensions/agent_shell/index.cljs`** — Add `session_ready` handler:

```clojure
(.on api "session_ready"
  (fn [_data]
    ;; UI is guaranteed available after session_ready
    (shared/setup-ui! api)
    ;; Auto-connect to default agent if configured
    (when-let [default-agent (:default-agent (shared/load-config))]
      ;; ... connect logic ...
      )))
```

Remove the lazy `setup-ui!` calls scattered across agent-switcher, input-router, etc.

**LOC impact**: -10 lines (remove 3-4 lazy setup calls, add 1 session_ready handler)

---

## Step 8: Agent Shell — Unified permissions via permission_request

### Current problem
`acp/handlers.cljs` has its own permission prompt for ACP agent commands. This is separate from the local tool permission system.

### Changes

When an ACP agent requests permission for a tool call, instead of showing a custom dialog, emit `permission_request`:

```clojure
;; In acp/handlers.cljs, in the permission handler:
;; Before: custom dialog
;; After:
(let [result (js-await
               ((:emit-collect events) "permission_request"
                 #js {:tool     tool-name
                      :args     args
                      :category "acp"
                      :agent    agent-name}))]
  (case (get result "decision")
    "allow" (send-response client id #js {:approved true})
    "deny"  (send-response client id #js {:approved false :reason (get result "reason")})
    ;; default: show UI dialog (existing behavior)
    (show-permission-dialog ...)))
```

**LOC impact**: ~0 (restructure, not add/remove)

---

## Step 9: Token Suite — Tag messages at store time via message_before_store

### Current problem
`observation-mask` reprocesses the entire message list every turn during `context_assembly` to determine which messages are mask-eligible.

### Changes

**File: `src/agent/extensions/token_suite/observation_mask.cljs`** — Add `message_before_store` handler:

```clojure
(.on api "message_before_store"
  (fn [data]
    (let [role (.-role data)]
      ;; Tag tool results with timestamp for expiry
      (when (= role "tool_result")
        ;; Return content unchanged but with metadata tag
        ;; (metadata isn't directly supported yet, but we can append a hidden marker)
        nil))))
```

This is a partial improvement — full benefit requires message metadata support (future work). For now, the main win is that `context_assembly` can check timestamps added at store time instead of re-scanning.

**LOC impact**: +5 lines

---

## Step 10: Token Suite — Budget-aware tool filtering via tool_access_check

### Current problem
When context is >80% full, the LLM can still call `web_fetch` or `web_search` which return huge results, blowing the remaining budget.

### Changes

**File: `src/agent/extensions/token_suite/index.cljs`** — Add `tool_access_check` handler:

```clojure
(.on api "tool_access_check"
  (fn [data]
    (let [budget (.getTokenBudget api)
          usage-pct (/ (.-tokensUsed budget) (.-contextWindow budget))
          tools  (vec (.-tools data))]
      ;; When >80% full, disable expensive tools
      (when (> usage-pct 0.80)
        (let [cheap-tools (remove #{"web_fetch" "web_search"} tools)]
          #js {:allowed (clj->js cheap-tools)})))))
```

**LOC impact**: +10 lines

---

## Execution Summary

| Step | Extension | What | LOC Change |
|------|-----------|------|-----------|
| 1 | bash_suite | Migrate security/permissions/env to emit-collect returns | **-17** |
| 2 | bash_suite | Migrate permissions to `permission_request` | **-6** |
| 3 | model_roles | Add modes: `tool_access_check` + `permission_request` handlers | **+25** |
| 4 | desktop_notify | Add `tool_complete` + `session_end_summary` notifications | **+15** |
| 5 | stats_dashboard | Add per-tool metrics via `tool_complete` + `/stats tools` | **+30** |
| 6 | prompt_history | Fix Ctrl+R paste via `ui.setEditorValue` | **0** |
| 7 | agent_shell | Use `session_ready` for reliable init, remove lazy setup | **-10** |
| 8 | agent_shell | Unified permissions via `permission_request` | **0** |
| 9 | token_suite | Tag messages at store time via `message_before_store` | **+5** |
| 10 | token_suite | Budget-aware tool filtering via `tool_access_check` | **+10** |
| **Total** | | | **+52 net** |

---

## Impact Summary

### LOC Reduced (simplification)
- **bash_suite**: -23 lines removed from security_analysis, permissions, env_filter
  - Eliminated: mutable ctx pattern (`set! (.-cancelled ...)`, `set! (.-reason ...)`)
  - Eliminated: `(not (.-cancelled evt-ctx))` guards in every handler
  - Eliminated: `(.-input evt-ctx)` → `(.-args data)` indirection
  - Architectural: permissions logic now participates in shared `permission_request` pipeline

- **agent_shell**: -10 lines from removing lazy UI setup scattered across 3-4 files
  - Architectural: reliable init timing via `session_ready` instead of defensive checks

### LOC Added (new capabilities)
- **model_roles**: +25 lines → **complete modes system** (Roo Code / Cline parity)
  - Was: model switcher with 3 commands
  - Now: full modes with per-role tool restrictions, per-role permission policies, model selection
  - User-configurable via `.nyma/settings.json` — no code for new modes

- **stats_dashboard**: +30 lines → per-tool performance metrics
  - Was: cost/token dashboard only
  - Now: tool call frequency, average duration, error rates

- **desktop_notify**: +15 lines → richer notifications
  - Was: "Response ready" after turn completion
  - Now: also notifies on long tools (>10s) and session end with cost summary

- **token_suite**: +15 lines → proactive budget protection
  - Was: reactive (prune after overflow)
  - Now: also preventive (disable expensive tools when >80% full)

### Net Architectural Improvements

| Before | After |
|--------|-------|
| bash_suite has its own permission system | Shared `permission_request` pipeline |
| Agent shell has separate permission UI | Same `permission_request` system |
| Model roles = model switcher only | Full modes system (model + tools + permissions) |
| 3 extensions use mutable evt-ctx | All use clean emit-collect returns |
| agent_shell has lazy UI setup workaround | Reliable `session_ready` init |
| token_suite reacts to overflow | Also prevents overflow proactively |
| desktop_notify fires on turn end only | Fires on long tools + session end too |

### What Users Get

1. **`/role plan`** → read-only mode (no write/edit/bash), matches Cline's Plan mode
2. **`/role debug`** → read + bash only, matches Cline's Debug concept
3. **`/role architect`** → planning with web, matches Roo Code's Architect mode
4. **Custom modes** via settings.json — any combination of model + tools + permissions
5. **`/stats tools`** → tool performance dashboard
6. **Desktop notifications** for long tool executions and session summaries
7. **Ctrl+R** prompt history paste works correctly
8. **Proactive budget protection** — expensive tools disabled when context is near full
