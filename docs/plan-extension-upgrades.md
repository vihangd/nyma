# Implementation Plan: Extension Upgrades Using New Hooks

Exact file paths, line numbers, old → new code for every change. 10 steps, each one commit.

---

## Step 1: Bash Security Analysis — emit-collect returns

**File**: `src/agent/extensions/bash_suite/security_analysis.cljs:218-248`

Replace the `before_tool_call` handler:

```
OLD (lines 222-244):
    (.on api "before_tool_call"
      (fn [evt-ctx]
        (when (and (:enabled sec-config)
                   (shared/is-bash-tool? (.-toolName evt-ctx)))
          (let [cmd    (.-command (.-input evt-ctx))
                result (classify-command cmd sec-config)]
            ;; Emit on inter-extension bus for permissions
            (when-let [events (.-events api)]
              (let [emit-fn (.-emit events)]
                (when (fn? emit-fn)
                  (emit-fn "bash:classification" (clj->js result)))))
            ;; Update stats
            (swap! shared/suite-stats update :security-analysis
              (fn [s] (-> s
                          (update :commands-analyzed inc)
                          (update-in [:classified (:level result)] inc))))
            ;; Block if configured
            (when (should-block? result sec-config)
              (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
              (set! (.-cancelled evt-ctx) true)
              (set! (.-reason evt-ctx)
                (str "BLOCKED [" (str (:level result)) "]: "
                     (str/join "; " (:reasons result))))))))
      100)

NEW:
    (.on api "before_tool_call"
      (fn [data]
        (when (and (:enabled sec-config)
                   (shared/is-bash-tool? (.-name data)))
          (let [args   (.-args data)
                cmd    (or (.-command args) (aget args "command"))
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
            ;; Block via return value
            (when (should-block? result sec-config)
              (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
              #js {:block true
                   :reason (str "BLOCKED [" (str (:level result)) "]: "
                                (str/join "; " (:reasons result)))}))))
      100)
```

**Changes**:
- `(.-toolName evt-ctx)` → `(.-name data)`
- `(.-command (.-input evt-ctx))` → `(.-command (.-args data))`
- `(set! (.-cancelled evt-ctx) true)` + `(set! (.-reason evt-ctx) ...)` → `#js {:block true :reason "..."}`

**Tests**: Update any test referencing old mutable pattern. Add to `test/hooks_events.test.cljs`:
- Security analysis returns `{block: true}` for `rm -rf /`
- Security analysis returns nil for `ls -la`

---

## Step 2: Bash Permissions — emit-collect returns + permission_request

**File**: `src/agent/extensions/bash_suite/permissions.cljs:61-84`

Replace the `before_tool_call` handler with a `permission_request` handler:

```
OLD (lines 61-84):
    (.on api "before_tool_call"
      (fn [evt-ctx]
        (when (and (:enabled perm-config)
                   (shared/is-bash-tool? (.-toolName evt-ctx))
                   (not (.-cancelled evt-ctx)))
          (let [cmd      (.-command (.-input evt-ctx))
                classif  @last-classification
                decision (check-decision cmd classif perm-config session-approvals)]
            (case decision
              :denied
              (do (swap! shared/suite-stats update-in [:permissions :denied] inc)
                  (set! (.-cancelled evt-ctx) true)
                  (set! (.-reason evt-ctx) "Permission denied: command matches denylist"))

              :approved
              (do (swap! session-approvals assoc (str cmd) :approved)
                  (swap! shared/suite-stats update-in [:permissions :auto-approved] inc))

              :remembered
              (swap! shared/suite-stats update-in [:permissions :remembered] inc)

              ;; :needs-approval — auto-approve for now, track as auto-approved
              (do (swap! session-approvals assoc (str cmd) :approved)
                  (swap! shared/suite-stats update-in [:permissions :auto-approved] inc))))))
      90)

NEW:
    (.on api "permission_request"
      (fn [data]
        (when (and (:enabled perm-config) (= (.-tool data) "bash"))
          (let [args     (.-args data)
                cmd      (str (or (.-command args) (aget args "command") ""))
                classif  @last-classification
                decision (check-decision cmd classif perm-config session-approvals)]
            (case decision
              :denied
              (do (swap! shared/suite-stats update-in [:permissions :denied] inc)
                  #js {:decision "deny" :reason "Permission denied: command matches denylist"})

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

**Changes**:
- Event: `before_tool_call` → `permission_request`
- Data: `(.-toolName evt-ctx)` → `(.-tool data)`
- Args: `(.-command (.-input evt-ctx))` → `(.-command (.-args data))`
- Returns: `{decision: "deny"|"allow"}` instead of `set! (.-cancelled)`
- Remove `(not (.-cancelled evt-ctx))` guard

---

## Step 3: Bash Env Filter — emit-collect arg transform

**File**: `src/agent/extensions/bash_suite/env_filter.cljs:22-34`

Replace the `before_tool_call` handler:

```
OLD (lines 22-34):
    (.on api "before_tool_call"
      (fn [evt-ctx]
        (when (and (:enabled env-config)
                   (shared/is-bash-tool? (.-toolName evt-ctx))
                   (not (.-cancelled evt-ctx))
                   (not (str/blank? preamble)))
          (let [input (.-input evt-ctx)
                cmd   (.-command input)]
            (aset input "command" (str preamble cmd))
            (swap! shared/suite-stats update :env-filter
              (fn [s] (-> s
                          (update :vars-stripped + (count (:strip-vars env-config)))
                          (update :commands-filtered inc)))))))
      80)

NEW:
    (.on api "before_tool_call"
      (fn [data]
        (when (and (:enabled env-config)
                   (shared/is-bash-tool? (.-name data))
                   (not (str/blank? preamble)))
          (let [args (.-args data)
                cmd  (or (.-command args) (aget args "command") "")]
            (swap! shared/suite-stats update :env-filter
              (fn [s] (-> s
                          (update :vars-stripped + (count (:strip-vars env-config)))
                          (update :commands-filtered inc))))
            #js {:args #js {:command (str preamble cmd)}})))
      80)
```

**Changes**:
- `(.-toolName evt-ctx)` → `(.-name data)`
- Remove `(not (.-cancelled evt-ctx))` guard
- `(aset input "command" ...)` → `#js {:args #js {:command ...}}`

---

## Step 4: Model Roles — Full modes system

**File**: `src/agent/settings/manager.cljs:16` — Extend role defaults:

```
OLD:
   :roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
           :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
           :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
           :plan    {:provider "anthropic" :model "claude-opus-4-20250514"}
           :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"}}

NEW:
   :roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
           :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
           :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
           :plan    {:provider "anthropic" :model "claude-opus-4-20250514"
                     :allowed-tools ["read" "glob" "grep" "ls" "think" "web_search" "web_fetch"]
                     :permissions {"write" "deny" "edit" "deny" "bash" "deny"}}
           :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"
                     :allowed-tools ["read" "bash" "glob" "grep" "edit" "write"]}}
```

**File**: `src/agent/extensions/model_roles/index.cljs` — Add `tool_access_check` and `permission_request` handlers.

After line 57 (`(swap! handlers conj ["model_resolve" on-resolve])`), add:

```clojure
    ;; tool_access_check — restrict tools based on active role
    on-tool-access
    (fn [_data]
      (let [state    (.getState api)
            role     (or (:active-role state) :default)
            roles    (get-roles api)
            role-cfg (get roles (keyword role))
            allowed  (or (:allowed-tools role-cfg)
                         (get role-cfg "allowed-tools"))]
        (when (seq allowed)
          #js {:allowed (clj->js allowed)})))

    ;; permission_request — deny/allow based on role permission map
    on-permission
    (fn [data]
      (let [state    (.getState api)
            role     (or (:active-role state) :default)
            roles    (get-roles api)
            role-cfg (get roles (keyword role))
            perms    (or (:permissions role-cfg) (get role-cfg "permissions"))
            tool     (.-tool data)]
        (when-let [decision (or (get perms tool) (get perms (str tool)))]
          #js {:decision decision})))]
```

Register them after the model_resolve registration:

```clojure
    (.on api "tool_access_check" on-tool-access)
    (swap! handlers conj ["tool_access_check" on-tool-access])

    (.on api "permission_request" on-permission)
    (swap! handlers conj ["permission_request" on-permission])
```

Update cleanup to remove new handlers (already handled by the existing `(doseq [[event handler] @handlers]` pattern).

Update `format-role-list` to show tool restrictions:

```
OLD:
             (str "  " (name rname) " → " provider "/" model-id marker)))

NEW:
             (let [allowed (or (:allowed-tools rconf) (get rconf "allowed-tools"))
                   tools-hint (when (seq allowed) (str " [" (count allowed) " tools]"))]
               (str "  " (name rname) " → " provider "/" model-id
                    (or tools-hint "") marker))))
```

**Tests**: `test/model_roles_modes.test.cljs` (~8 tests):
- Plan role `tool_access_check` returns allowed = read-only set
- Plan role `permission_request` returns `{decision: "deny"}` for write tool
- Default role returns nil for both events (no restrictions)
- Custom role with `allowed-tools` filters correctly
- Custom role with `permissions` map returns correct decisions
- Role with no tool config → all tools available
- `/role` display shows `[N tools]` hint for restricted roles
- Role switch updates tool restrictions immediately

---

## Step 5: Desktop Notify — tool_complete + session_end_summary

**File**: `src/agent/extensions/desktop_notify/index.cljs`

After line 62 (after `on-turn-end` definition), add two handlers:

```clojure
        ;; Notify on long-running tool completions (>10s)
        on-tool-complete
        (fn [data _ctx]
          (let [dur (or (.-duration data) 0)]
            (when (and (> dur 10000)
                       (let [flag-val (when (.-getFlag api) (.getFlag api "enabled"))]
                         (if (some? flag-val) flag-val true)))
              (send-notification! "nyma"
                (str (.-toolName data) " completed ("
                     (js/Math.round (/ dur 1000)) "s)")))))

        ;; Notify with session summary on exit
        on-session-end
        (fn [data _ctx]
          (let [enabled (let [flag-val (when (.-getFlag api) (.getFlag api "enabled"))]
                          (if (some? flag-val) flag-val true))]
            (when enabled
              (send-notification! "nyma"
                (str "Session: " (or (.-turnCount data) 0) " turns, $"
                     (.toFixed (or (.-totalCost data) 0) 2))))))
```

Register them after existing event registrations:

```clojure
    (.on api "tool_complete" on-tool-complete)
    (.on api "session_end_summary" on-session-end)
```

Update cleanup:

```clojure
    (fn []
      (.off api "turn_start" on-turn-start)
      (.off api "turn_end" on-turn-end)
      (.off api "tool_complete" on-tool-complete)
      (.off api "session_end_summary" on-session-end))))
```

---

## Step 6: Stats Dashboard — Per-tool metrics via tool_complete

**File**: `src/agent/extensions/stats_dashboard/index.cljs`

Add tool metrics tracking. Replace the `(defn ^:export default [api]` body:

After the existing commands, add a tool-metrics atom and `tool_complete` handler:

```clojure
(defn ^:export default [api]
  (let [tool-metrics (atom {}) ;; {tool-name → {:calls :total-ms :errors}}

        on-tool-complete
        (fn [data]
          (let [tool (str (.-toolName data))
                dur  (or (.-duration data) 0)
                err  (boolean (.-isError data))]
            (swap! tool-metrics update tool
              (fn [m]
                (let [m (or m {:calls 0 :total-ms 0 :errors 0})]
                  (-> m
                      (update :calls inc)
                      (update :total-ms + dur)
                      (cond-> err (update :errors inc))))))))]

    (.on api "tool_complete" on-tool-complete)
```

Add `/stats tools` subcommand to the existing `/stats` handler. Change the handler to check `(first args)`:

```clojure
    (.registerCommand api "stats"
      #js {:description "Show usage statistics. Subcommands: tools"
           :handler
           (fn [args ctx]
             (if (= (first args) "tools")
               ;; Per-tool metrics
               (let [metrics @tool-metrics]
                 (if (empty? metrics)
                   (.notify (.-ui ctx) "No tool calls recorded" "info")
                   (.notify (.-ui ctx)
                     (str "Tool Performance:\n"
                          (str/join "\n"
                            (map (fn [[tname m]]
                                   (str "  " tname ": " (:calls m) " calls, "
                                        (js/Math.round (/ (:total-ms m) (max 1 (:calls m)))) "ms avg"
                                        (when (> (:errors m) 0) (str ", " (:errors m) " errors"))))
                              (sort-by (fn [[_ m]] (- (:calls m))) metrics))))
                     "info")))
               ;; Existing dashboard
               (if-let [store (.-__sqlite-store api)]
                 (let [totals   ((:get-usage-totals store))
                       by-model ((:get-usage-by-model store))
                       by-day   ((:get-usage-by-day store) 14)
                       dashboard (format-dashboard {:totals totals :by-model by-model :by-day by-day})]
                   (if (and (.-ui ctx) (.-showOverlay (.-ui ctx)))
                     (.showOverlay (.-ui ctx) dashboard)
                     (.notify (.-ui ctx) dashboard "info")))
                 (.notify (.-ui ctx) "Stats require SQLite storage" "error"))))})
```

Update cleanup to remove `tool_complete` handler:

```clojure
  (fn []
    (.off api "tool_complete" on-tool-complete)
    (.unregisterCommand api "stats")
    (.unregisterCommand api "stats-session")))
```

---

## Step 7: Prompt History — Fix Ctrl+R paste via ui.setEditorValue

**File**: `src/agent/extensions/prompt_history/index.cljs`

Find the Ctrl+R shortcut handler (around line 83-93) and fix the `setEditorValue` reference path:

```
OLD:
                          (fn [selected]
                            ;; Close overlay (handled by custom component lifecycle)
                            (when (and selected (.-ui api))
                              ;; Set editor value to selected prompt
                              (when-let [set-val (.-setEditorValue api)]
                                (set-val selected))))

NEW:
                          (fn [selected]
                            (when selected
                              ;; Paste into editor via ui.setEditorValue (G6)
                              (when (and (.-ui api) (.-setEditorValue (.-ui api)))
                                (.setEditorValue (.-ui api) selected))))
```

**Change**: `(.-setEditorValue api)` → `(.-setEditorValue (.-ui api))` — the function lives on the `ui` object, not the root API.

---

## Step 8: Agent Shell — session_ready init + remove lazy setup

**File**: `src/agent/extensions/agent_shell/index.cljs`

Add `session_ready` handler for reliable UI init. Replace the lazy auto-connect with `session_ready`:

```
OLD (lines 72-82):
      ;; Auto-connect if configured
      (when (and (:default-agent config) (:auto-connect config))
        (let [agent-key (:default-agent config)]
          (when-not (get @shared/connections agent-key)
            (js/setTimeout
              (fn []
                (when (and (.-ui api) (.-available (.-ui api)))
                  (.notify (.-ui api) (str "Auto-connecting to " (:default-agent config) "...") "info"))
                (when-let [agent-def (registry/get-agent agent-key)]
                  (pool/get-or-create agent-key agent-def api)))
              1000))))

NEW:
      ;; session_ready — UI guaranteed available, setup footer/header and auto-connect
      (.on api "session_ready"
        (fn [_data]
          ;; Setup UI immediately (no lazy check needed)
          (shared/setup-ui!)
          ;; Auto-connect if configured
          (when (and (:default-agent config) (:auto-connect config))
            (let [agent-key (:default-agent config)]
              (when-not (get @shared/connections agent-key)
                (.notify (.-ui api) (str "Auto-connecting to " (name agent-key) "...") "info")
                (when-let [agent-def (registry/get-agent agent-key)]
                  (pool/get-or-create agent-key agent-def api)))))))
```

**File**: `src/agent/extensions/agent_shell/features/agent_switcher.cljs:39`

Remove the lazy `(shared/setup-ui!)` call:

```
OLD:
                (shared/setup-ui!)

NEW:
                ;; UI setup handled by session_ready handler
```

**File**: `src/agent/extensions/agent_shell/features/handoff.cljs:76`

Remove the lazy `(shared/setup-ui!)` call:

```
OLD:
                                 (shared/setup-ui!)

NEW:
                                 ;; UI setup handled by session_ready handler
```

**Changes**: Remove `js/setTimeout` + `(.-available (.-ui api))` guard. Remove 2 lazy `setup-ui!` calls. Add 1 `session_ready` handler.

---

## Step 9: Token Suite — Budget-aware tool filtering

**File**: `src/agent/extensions/token_suite/index.cljs`

Add `tool_access_check` handler after line 72 (after all sub-extension activations):

```clojure
    ;; Budget-aware tool filtering — disable expensive tools when context is near full
    (.on api "tool_access_check"
      (fn [data]
        (when-let [budget (.getTokenBudget api)]
          (let [used    (or (.-tokensUsed budget) 0)
                window  (or (.-contextWindow budget) 100000)
                pct     (/ used window)]
            ;; When >80% full, disable expensive tools
            (when (> pct 0.80)
              (let [tools   (vec (.-tools data))
                    cheap   (vec (remove #{"web_fetch" "web_search"} tools))]
                (when (not= (count tools) (count cheap))
                  #js {:allowed (clj->js cheap)})))))))
```

Update cleanup to remove the handler:

```clojure
    ;; Return combined deactivate
    (fn []
      (doseq [deactivate @deactivators]
        (when (fn? deactivate)
          (deactivate))))))
```

Note: The handler doesn't need explicit cleanup if the extension's deactivator handles all subscriptions. But since we used `.on` directly on the api (not via a sub-module), we should track it:

```clojure
    (let [budget-handler
          (fn [data] ...)]
      (.on api "tool_access_check" budget-handler)

      ;; Return combined deactivate
      (fn []
        (.off api "tool_access_check" budget-handler)
        (doseq [deactivate @deactivators]
          (when (fn? deactivate) (deactivate)))))
```

---

## Step 10: Agent Shell — Unified permissions via permission_request

**File**: `src/agent/extensions/agent_shell/acp/handlers.cljs:21-53`

Add `permission_request` emit before falling through to the UI dialog:

```
OLD (lines 31-33):
    (if (or auto? (not (and api (.-ui api) (.-available (.-ui api)))))
      ;; Auto-approve: pick allow_always > allow_once > first option
      (client/send-response conn (.-id parsed)

NEW:
    ;; Try shared permission_request first
    (let [events    (when api (.-events api))  ;; inter-extension events won't help, need main events
          perm-result (when (and api (.-on api))
                        ;; Emit permission_request for unified approval
                        ;; Note: this is synchronous best-effort since ACP handlers are sync
                        nil)]
      (if (or auto? (not (and api (.-ui api) (.-available (.-ui api)))))
        ;; Auto-approve: pick allow_always > allow_once > first option
        (client/send-response conn (.-id parsed)
```

**Note**: This is a partial integration. The ACP permission handler is synchronous (JSON-RPC response must be sent immediately), but `permission_request` is async (emit-collect returns a Promise). Full integration requires making the ACP handler async. For now, add a comment marking the future integration point:

```clojure
    ;; TODO: integrate with permission_request event when ACP handlers support async
    ;; (let [result (js-await ((:emit-collect events) "permission_request" ...))]
    ;;   (case (get result "decision") ...))
    (if (or auto? ...)
```

This is a placeholder step — the architectural alignment is documented but the actual async wiring is deferred.

---

## Summary

| Step | Extension | Change | LOC Delta |
|------|-----------|--------|-----------|
| 1 | bash_suite/security_analysis | `set! cancelled` → `{block: true}` return | **-5** |
| 2 | bash_suite/permissions | `before_tool_call` → `permission_request` | **-6** |
| 3 | bash_suite/env_filter | `aset input` → `{args: {...}}` return | **-4** |
| 4 | model_roles | Add `tool_access_check` + `permission_request` handlers + settings | **+30** |
| 5 | desktop_notify | Add `tool_complete` + `session_end_summary` handlers | **+18** |
| 6 | stats_dashboard | Add `tool_complete` tracking + `/stats tools` | **+35** |
| 7 | prompt_history | Fix `setEditorValue` path (api → api.ui) | **0** |
| 8 | agent_shell | `session_ready` init, remove 3 lazy setup calls | **-8** |
| 9 | token_suite | Add `tool_access_check` for budget protection | **+12** |
| 10 | agent_shell | Document `permission_request` integration point | **+3** |
| **Total** | | | **+75 net** |

### What's Reduced
- **-23 LOC** from bash_suite (mutable ctx pattern eliminated in 3 files)
- **-8 LOC** from agent_shell (lazy UI setup workaround removed)
- **Architectural debt**: 0 extensions use mutable `evt-ctx` pattern after this

### What's Added
- **+30 LOC** model_roles → **full modes system** (plan=read-only, custom via settings.json)
- **+35 LOC** stats_dashboard → per-tool performance observatory
- **+18 LOC** desktop_notify → long-tool + session-end notifications
- **+12 LOC** token_suite → proactive budget protection
- **+3 LOC** agent_shell → permission integration documented

### Test Files

| File | Tests | New/Updated |
|------|-------|-------------|
| `test/model_roles_modes.test.cljs` | 8 | New |
| `test/hooks_events.test.cljs` | +3 | Updated (bash emit-collect) |
| `test/stats_tools.test.cljs` | 4 | New |
| `test/desktop_notify.test.cljs` | 3 | New |
| **Total new tests** | **18** | |
