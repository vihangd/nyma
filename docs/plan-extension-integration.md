# Extension Integration Plan: Apply New Capabilities to Existing Extensions

## Overview

PRs A–G introduced seven new capabilities to nyma:
- `__skip` short-circuit from `before_tool_call`
- Tool-result policy system (`tool_result_policy.cljs`)
- `make-reminder` self-reminder middleware factory
- `questionnaire` extension (model-callable multi-question UI)
- Three-way approval persistence (`allow_always_project`)
- 15+ new event emits (session, UI, keybinding, ACP)
- `api.emitGlobal` for cross-bus events

This plan applies those capabilities to existing extensions that can directly benefit. Changes are grouped into three PRs by difficulty. The "New capabilities enabled" ideas (net-new features that would require significant extension work) are tracked in `docs/extension-ideas.md`.

---

## PR H — Trivial: `bash_suite/security_analysis` — `__skip` instead of `block`

**Why**: `{:block true}` produces a hard cancellation with an error-style result string like `"Tool call 'bash' was cancelled"`. The model sees an error, not an explanation. `{:skip true :result "..."}` passes a clean, informative denial string instead — the model can reason about why and suggest alternatives.

**File**: `src/agent/extensions/bash_suite/security_analysis.cljs`

### Step 1 — Change the block return in `activate`

Around line 241–244, in the `before_tool_call` listener:

```clojure
;; BEFORE:
(when (should-block? result sec-config)
  (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
  #js {:block true
       :reason (str "BLOCKED [" (str (:level result)) "]: "
                    (str/join "; " (:reasons result)))})

;; AFTER:
(when (should-block? result sec-config)
  (swap! shared/suite-stats update-in [:security-analysis :blocked] inc)
  #js {:skip true
       :result (str "Command blocked by security analysis ["
                    (str (:level result)) "]: "
                    (str/join "; " (:reasons result))
                    "\n\nPlease use an alternative approach or ask the user for clarification.")})
```

That is the entire production change.

### Step 2 — Tests in `test/ext_bash_suite.test.cljs`

Add a `describe "security-analysis — __skip semantics"` block with:

1. **Blocked command returns skip map**: mock `before_tool_call` subscriber; fire a destructive command (`rm -rf /`); assert returned map has `{:skip true}` key and result string contains "blocked by security analysis".
2. **Result is a clean explanation string**: assert result string does NOT contain "cancelled" (the old error-marker wording).
3. **`tool_result` event still fires for skipped calls**: add a `tool_result` subscriber; run a blocked command through the full pipeline; assert subscriber was called once.
4. **`:blocked` stat increments**: assert `(get-in @suite-stats [:security-analysis :blocked])` increments by 1 per blocked call.
5. **Non-blocked command proceeds normally**: a safe `ls` command should NOT trigger skip; assert the listener returns nil.

### Step 3 — Compile and verify

```bash
npx squint compile && bun test test/ext_bash_suite.test.cljs
```

Full suite must stay green.

---

## PR I — Small: Two independent changes

### Part 1: `bash_suite/permissions` — `allow_always_project` persistence

**Why**: The `:needs-approval` case currently uses `{:decision "allow"}` and stores the approval in session-only memory. Restarting nyma means re-approving the same commands. Returning `{:decision "allow_always_project"}` persists "bash" to `.nyma/settings.json`; the middleware fast-path then bypasses `permission_request` entirely on subsequent sessions. Security analysis (`before_tool_call`) still runs — only the redundant per-session prompt is eliminated.

**File**: `src/agent/extensions/bash_suite/permissions.cljs`

#### Step 1 — Change `:needs-approval` decision

In `activate`, around line 82–85, the `:needs-approval` case:

```clojure
;; BEFORE:
;; :needs-approval — auto-approve for now
(do (swap! session-approvals assoc cmd :approved)
    (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
    #js {:decision "allow"})

;; AFTER:
;; :needs-approval — persist to project settings for cross-session approval
;; bash is fast-path allowed on the next run; security_analysis still runs via before_tool_call
(do (swap! shared/suite-stats update-in [:permissions :auto-approved] inc)
    #js {:decision "allow_always_project"})
```

Remove the `session-approvals` update for this branch (the middleware now persists at a coarser level).

Note: The `:approved` (allowlist match) and `:remembered` (previously seen this session) paths keep `{:decision "allow"}` unchanged.

#### Step 2 — Tests in `test/ext_bash_suite.test.cljs`

Add a `describe "permissions — allow_always_project"` block:

1. **`:needs-approval` returns `allow_always_project`**: mock handler that doesn't match any allowlist/denylist; assert returned decision is `"allow_always_project"`.
2. **Denylist still returns deny** (regression): command matching denylist returns `{:decision "deny"}`.
3. **Allowlist still returns allow** (regression): command matching allowlist returns `{:decision "allow"}`.
4. **After persistence, second call skips prompt**: create a `settings-manager` with temp dir; run pipeline with `allow_always_project` response; run same command again; assert `permission_request` subscriber fires exactly once (not twice).

---

### Part 2: `agent_shell/features/handoff` — interactive agent picker

**Why**: `/handoff` with no arguments currently prints a usage string. With `api.ui.select`, we can show a live picker of registered agents instead — more discoverable and less typing.

**File**: `src/agent/extensions/agent_shell/features/handoff.cljs`

#### Step 1 — Extract `do-handoff` helper

Lift the existing per-agent handoff logic (the `(pool/disconnect ...)` chain, currently inline in the handler) into a named `^:async do-handoff [api from-key to-key-str custom-msg]` function. No behaviour change yet.

#### Step 2 — Add interactive picker when no args

In the `activate` command handler, replace the `(empty? args)` branch:

```clojure
;; BEFORE:
(empty? args)
(notify api (str "Usage: /handoff <agent> [context message]\n"
                 "Available agents: "
                 (str/join ", " (map shared/kw-name (keys registry/agents))))
        "info")

;; AFTER:
(empty? args)
(if (and (.-ui api) (.-available (.-ui api)))
  (let [agent-keys (keys registry/agents)
        options    (clj->js
                     (mapv (fn [k]
                             (let [def (get registry/agents k)]
                               #js {:value (shared/kw-name k)
                                    :label (or (:name def) (shared/kw-name k))
                                    :description (or (:description def) "")}))
                           agent-keys))]
    (-> (.select (.-ui api) "Select agent to hand off to:" options)
        (.then (fn [selected]
                 (when selected
                   (do-handoff api from-key selected nil))))))
  ;; UI not available — fall back to usage text
  (notify api (str "Usage: /handoff <agent> [context message]\n"
                   "Available agents: "
                   (str/join ", " (map shared/kw-name (keys registry/agents))))
          "info"))
```

#### Step 3 — Tests

Create `test/ext_agent_shell_handoff.test.cljs` or add to the existing agent_shell test file:

1. **With agent arg → no picker**: assert `api.ui.select` is NOT called; `do-handoff` is called with the arg.
2. **No arg + UI available → picker shown**: assert `api.ui.select` is called with option list containing all registered agents.
3. **User selects agent → handoff proceeds**: mock `api.ui.select` resolving to `"agent-b"`; assert `do-handoff` is called with `"agent-b"` and `nil` message.
4. **User cancels (nil) → no handoff**: mock `api.ui.select` resolving to `nil`; assert `do-handoff` is NOT called; no error notification.
5. **No arg + UI unavailable → usage text**: `api.ui.available = false`; assert `notify` is called with usage string.
6. **Unknown agent arg → error** (regression): assert "Unknown agent" error.
7. **Same agent as current → error** (regression): assert "Already connected" error.

---

## PR J — Medium: Truncation consolidation via tool-result policy

### Part 1: `ast_tools` — remove local truncation, use policy

**Why**: `ast_tools/index.cljs` has its own `truncate-output` (lines 14–22) with hardcoded `max-output-chars 5000` and `max-matches 50`. The policy system can centralize this — extensions that register their policy are visible to the UI envelope and can be overridden by users.

**File**: `src/agent/extensions/ast_tools/index.cljs`

#### Step 1 — Add policy import

```clojure
(:require [clojure.string :as str]
          [agent.tool-result-policy :as policy])
```

#### Step 2 — Remove local truncation

Delete:
- `(def ^:private max-output-chars 5000)` (line 11)
- `(def ^:private max-matches 50)` (line 12)
- The entire `truncate-output` function (lines 14–22)

#### Step 3 — Replace call sites

In `ast-grep-execute` and `ast-edit-execute`, replace `(truncate-output output)` with:
```clojure
(policy/model-string (policy/apply-policy output "ast_grep"))
;; and
(policy/model-string (policy/apply-policy output "ast_edit"))
```

#### Step 4 — Register policies at activation

In the `default [api]` export function, before registering tools:
```clojure
(policy/register-policy! "ast_grep" {:max-string-length 5000})
(policy/register-policy! "ast_edit" {:max-string-length 5000})
```

And in the returned cleanup function:
```clojure
(fn []
  (policy/unregister-policy! "ast_grep")
  (policy/unregister-policy! "ast_edit")
  (.unregisterTool api "ast_grep")
  (.unregisterTool api "ast_edit"))
```

#### Step 5 — Tests

Create `test/ext_ast_tools.test.cljs`:

1. **Long output → truncated to 5000 chars**: generate 10k string; assert result is ≤5000 + truncation marker.
2. **Short output → passes through unchanged**: 100-char string; assert result equals input.
3. **Policy registered at activation**: after calling `default(api)`, assert `policy/policy-for "ast_grep"` returns `{:max-string-length 5000 ...}`.
4. **Policy unregistered at deactivation**: call cleanup fn; assert `policy/policy-for "ast_grep"` returns default 12000.
5. **No matches → "No matches found." string passes through**: empty string input → policy returns empty envelope → `model-string` returns `""` → execute returns "No matches found." (assert the no-match branch still works after removing old truncation).

---

### Part 2: `bash_suite/output_handling` — register policy, sync limits

**Why**: `output_handling` has its own `max-bytes` config (from `shared/load-config`). The tool-result-policy has a hardcoded `"bash" {:max-string-length 10000}`. These are separate, potentially inconsistent. Registering the policy from `output_handling` at activation time makes `output_handling` the single source of truth for bash output limits, and makes the limit visible in the policy envelope (so UI components can read it).

The save-to-file middle-truncation mechanism is kept — it serves a different purpose (retrieval) than the policy system (model context sizing).

**File**: `src/agent/extensions/bash_suite/output_handling.cljs`

**File**: `src/agent/tool_result_policy.cljs`

#### Step 1 — Add policy import to output_handling

```clojure
(:require ...
          [agent.tool-result-policy :as policy])
```

#### Step 2 — Register policy at activation

In `activate`, after loading config:
```clojure
(defn activate [api]
  (let [config  (shared/load-config)
        oh-cfg  (:output-handling config)]
    
    ;; Register the bash policy so the policy system reflects output_handling's limit.
    ;; This overrides the hardcoded builtin. output_handling's middle-truncation still
    ;; runs first in the leave chain; the policy is for the model-visible envelope.
    (policy/register-policy! "bash" {:max-string-length (:max-bytes oh-cfg)})
    
    ;; ... rest of activate unchanged ...
    
    (fn []
      (policy/unregister-policy! "bash")
      ;; ... rest of deactivate unchanged ...
      )))
```

#### Step 3 — Remove hardcoded bash entry from builtin-policies

In `src/agent/tool_result_policy.cljs`, remove the `"bash"` entry from `builtin-policies`:
```clojure
;; Remove this line:
"bash"       {:max-string-length 10000}
```

Add a comment explaining that bash is handled dynamically by `output_handling` extension when loaded:
```clojure
;; Note: bash policy is registered dynamically by bash_suite/output_handling
;; when that extension is active. Falls back to default-policy (12000) when not.
```

#### Step 4 — Tests

Add to `test/ext_bash_suite.test.cljs`:

1. **Policy registered at activation**: call `activate(api)`; assert `policy/policy-for "bash"` returns `{:max-string-length <configured-max-bytes> ...}`.
2. **Policy uses config value not hardcoded constant**: change `max-bytes` in config to 5000; assert policy reflects 5000.
3. **Policy unregistered at deactivation**: call cleanup; assert `policy/policy-for "bash"` no longer returns the overridden value (falls back to 12000 default).

Add to `test/tool_result_policy.test.cljs`:

4. **Regression — bash policy fallback**: when no extension registers bash policy, `policy-for "bash"` returns default (12000) not 10000 (the removed builtin).

---

## Cross-cutting requirements for all PRs

- `bun test` full suite green before and after each PR.
- No new files committed except test files and the extension file being modified.
- `docs/plan-*.md` files are never staged.
- Each PR is independent and can land in order H → I → J.

## Recommended order

1. **PR H** first — trivial, green immediately, unblocks testing of `__skip` semantics.
2. **PR I Part 1** (permissions persistence) — standalone, no dependencies.
3. **PR I Part 2** (handoff picker) — standalone, no dependencies.
4. **PR J Part 1** (ast_tools) — depends on policy system (already landed in PR C).
5. **PR J Part 2** (output_handling policy sync) — depends on PR J Part 1 being settled (both touch policy).

PRs I Part 1 and I Part 2 can be the same commit or separate. PR J Parts 1 and 2 should be separate commits for easier review.
