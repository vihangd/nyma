# Plan: Polish + Extras (post oh-my-pi port)

**Date:** 2026-04-10
**Status:** Draft
**Scope:** Five follow-up items after the Top-10 oh-my-pi UI port is merged.

---

## Items

| # | Item | Effort | Dependencies |
|---|---|---|---|
| P1 | Live git status polling (explicit scope remainder) | ~30 min | none |
| P2 | Bash / Python mode border in the editor | ~45 min | none |
| P3 | CountdownTimer utility | ~30 min | none |
| P4 | HookEditor component (reusable multi-line modal) | ~2 h | P3 (optional) |
| P5 | Ctrl+R history search overlay + action-handler registry | ~2 h | P4 (optional) |

**Total:** ~6 hours.

**Build order:** P1 → P2 → P3 → P4 → P5. Every item is shippable on its own; nothing in P5 blocks P1–P4.

---

## P1 — Live git status polling

### What

`StatusLine` currently calls `read-git-status` once in an initial-fetch `useEffect` and never refreshes. A user staging/unstaging files during a session sees a stale `*` (dirty) suffix on the git segment.

### Files to modify

**`src/agent/ui/status_line.cljs`** — the existing initial-fetch effect.

### Design

Wrap the `read-git-status` call in a `setInterval` (5-second cadence), and keep the initial immediate fetch so the first frame still shows real data. Clear the interval on unmount. Also re-trigger the status fetch inside the `git_branch_changed` handler so branch switches update the dirty indicator instantly rather than waiting for the next tick.

```clojure
(useEffect
  (fn []
    (let [refresh (fn []
                    (-> (read-git-status)
                        (.then (fn [s] (set-git-status (js->clj s :keywordize-keys true))))))]
      (-> (read-git-branch)
          (.then (fn [b] (set-git-branch (or b nil)))))
      (refresh)
      (let [id (js/setInterval refresh 5000)]
        (fn [] (js/clearInterval id)))))
  #js [])
```

### Integration points

None beyond the existing effect. No event subscriptions, no settings plumbing.

### Tests

No new tests — the change is a pure wiring tweak in a React effect. Existing `status_line.test.cljs` still covers the `context-usage-level` pure logic and segment visibility. The behavior is manually verifiable: `touch foo; git add foo; rm foo` should flip the segment within 5 s.

### Risks / notes

- `git status --porcelain` on a large repo can be slow. If the 5 s cadence ever causes visible lag, move the subprocess to a debounced/throttled variant, or gate it behind `settings.status-line.poll-git`.
- Not polling when the terminal is backgrounded (SIGTSTP) is a nice-to-have but not necessary — a 5 s subprocess is cheap.

---

## P2 — Bash / Python mode border

### What

When the editor value starts with `!` the user has entered "bash mode" (command passed through shell). When it starts with `$` they're in "python mode". Oh-my-pi recolors the editor's border to signal this visually. It's ~30 LOC and a very visible polish win.

### Files to create

**`src/agent/ui/editor_mode.cljs`** — pure logic, exported for testing.

```clojure
(defn detect-mode
  "Inspect editor text and return :bash | :python | :normal.
   Leading whitespace is ignored (common user habit)."
  [text]
  (let [t (when text (.trimStart (str text)))]
    (cond
      (or (nil? t) (= t "")) :normal
      (.startsWith t "!") :bash
      (.startsWith t "$") :python
      :else :normal)))

(defn border-color-for-mode
  "Return the theme border color for the given mode."
  [mode theme]
  (case mode
    :bash   (get-in theme [:colors :warning]  "#e0af68")
    :python (get-in theme [:colors :primary]  "#7aa2f7")
    (get-in theme [:colors :border] "#3b4261")))
```

### Files to modify

**`src/agent/ui/editor.cljs`** — consume `detect-mode` + `border-color-for-mode` and override the existing `border-color` branch when streaming/hidden aren't active.

The current logic:

```clojure
border-color (cond
               hidden    muted-color
               streaming (get-in theme [:colors :warning] "#e0af68")
               :else     (get-in theme [:colors :border] "#3b4261"))
```

New logic:

```clojure
border-color (cond
               hidden    muted-color
               streaming (get-in theme [:colors :warning] "#e0af68")
               :else     (border-color-for-mode (detect-mode editorValue) theme))
```

Also add a one-line hint at the top-left when in a non-normal mode, e.g. `"bash"` or `"python"` in muted text, so power users can see the mode.

### Tests

**`test/editor_mode.test.cljs`** — pure logic:
- `detect-mode nil` → `:normal`
- `detect-mode ""` → `:normal`
- `detect-mode "hello"` → `:normal`
- `detect-mode "!ls"` → `:bash`
- `detect-mode "  !ls"` → `:bash` (leading whitespace ignored)
- `detect-mode "$python expr"` → `:python`
- `detect-mode "$$double"` → `:python` (single-char prefix test, still `$`)
- `border-color-for-mode :bash test-theme` → the warning hex
- `border-color-for-mode :normal test-theme` → the border hex

### Integration points

- No shell execution yet — this is purely a visual mode indicator. Actually *running* bash/python when the user submits `!ls` or `$x+1` is a separate feature that would hook into `do-submit` in `app.cljs`. Out of scope for this plan; note as a follow-up.

### Risks / notes

- `$` conflicts with some legitimate text starts (e.g. citing dollar amounts). The heuristic is consistent with oh-my-pi and Claude Code, both of which use these prefixes. If users complain, gate behind `settings.editor.mode-prefixes` defaulting to `true`.

---

## P3 — CountdownTimer utility

### What

Drift-safe countdown that tracks a wall-clock deadline rather than counting ticks. Used by dialogs with auto-dismiss timeouts (`confirm`, `select`, `input`) where the old pattern is to pass `:timeout ms` and the dialog shows a shrinking `(N s)` label.

### Files to create

**`src/agent/ui/countdown_timer.cljs`** — React hook + pure helpers.

```clojure
(ns agent.ui.countdown-timer
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useEffect]]))

(defn seconds-remaining
  "Pure helper — how many whole seconds are left until `deadline-ms`.
   Returns 0 when the deadline has passed."
  [deadline-ms now-ms]
  (max 0 (js/Math.ceil (/ (- deadline-ms now-ms) 1000))))

(defn use-countdown
  "React hook. Given a deadline timestamp (ms since epoch), return the
   whole seconds remaining and re-render once per second until it hits 0.

   Returns nil when deadline-ms is nil, so callers can pass nil to opt
   out."
  [deadline-ms]
  (let [[now set-now] (useState (js/Date.now))]
    (useEffect
      (fn []
        (when deadline-ms
          (let [id (js/setInterval
                     (fn [] (set-now (js/Date.now)))
                     250)]  ;; 250 ms tick — sub-second resolution, keeps render smooth
            (fn [] (js/clearInterval id)))))
      #js [deadline-ms])
    (when deadline-ms
      (seconds-remaining deadline-ms now))))
```

### Files to modify

**`src/agent/ui/dialogs.cljs`** — optional enhancement: when a dialog is constructed with `:timeout 10000`, render the current remaining seconds inline via `use-countdown`. The existing `with-dismissal` (in `app.cljs`) already schedules the dismiss; the hook is purely visual feedback.

Actually, holding the deadline atom in `app.cljs` and passing `deadline-ms` through to the dialog is cleanest. `ConfirmDialog`, `SelectDialog`, `InputDialog` each accept an optional `:deadline-ms` prop; when present they render `(Ns)` in the footer.

### Tests

**`test/countdown_timer.test.cljs`**:
- `seconds-remaining 10000 0` → 10
- `seconds-remaining 10500 0` → 11 (ceiling rounds up)
- `seconds-remaining 5000 5000` → 0
- `seconds-remaining 3000 5000` → 0 (clamped at zero)
- `seconds-remaining 0 0` → 0

No React component tests — follow the existing convention of only unit-testing pure helpers.

### Integration points

- Used by `dialogs.cljs` if we wire the visual countdown (optional).
- Consumed by P4 (HookEditor) if we add a session timeout to modal editors (optional).
- Independently useful for any future feature that needs "wait N seconds then auto-close".

### Risks / notes

- 250 ms tick is fine for visible countdowns (max render budget ~4 frames/sec). Don't drop below 100 ms — Ink reconciles on each setState and a 10 ms interval would burn CPU for no user benefit.

---

## P4 — HookEditor component

### What

A reusable bordered modal multi-line text input. Two submit modes controlled by a prop:

- **hook mode** (`:enter-mode :newline`): Enter inserts a newline, Ctrl+Enter submits. For hook scripts, commit message composition, long-form reasoning.
- **prompt mode** (`:enter-mode :submit`): Enter submits, Shift+Enter inserts a newline. For `/btw` side-conversations, ask-user dialogs.

Additional behavior:

- Esc closes, resolving with `nil`.
- Ctrl+G: suspend TUI, launch `$EDITOR` with current text in a temp file, restore TUI on exit and read the result. Same external-editor pattern oh-my-pi uses.
- Optional title bar, border, and bottom hint line (uses `key-hints` from M1).
- Optional `:deadline-ms` prop that renders the P3 countdown in the hint line and auto-resolves when the deadline hits.

### Files to create

**`src/agent/ui/hook_editor.cljs`** — the Ink component.

Key shape of the component:

```clojure
(defn HookEditor
  [{:keys [title initial-value enter-mode placeholder onSubmit onCancel
           deadline-ms theme]}]
  (let [[value set-value]       (useState (or initial-value ""))
        [cursor set-cursor]     (useState (count (or initial-value "")))
        remaining               (when deadline-ms (use-countdown deadline-ms))
        submit-mode             (or enter-mode :newline)
        border                  (get-in theme [:colors :primary] "#7aa2f7")
        muted                   (get-in theme [:colors :muted]   "#565f89")]

    ;; Auto-resolve on deadline
    (useEffect
      (fn []
        (when (and remaining (zero? remaining))
          (when onCancel (onCancel))))
      #js [remaining])

    (useInput
      (fn [input key]
        (cond
          (.-escape key)    (when onCancel (onCancel))
          ;; Submit/newline routing depends on mode
          (and (.-return key) (= submit-mode :submit) (not (.-shift key)))
          (when onSubmit (onSubmit value))

          (and (.-return key) (= submit-mode :newline) (.-ctrl key))
          (when onSubmit (onSubmit value))

          (.-return key)
          (do (set-value (fn [v] (str v "\n")))
              (set-cursor (fn [c] (inc c))))

          ;; Ctrl+G → external editor
          (and (.-ctrl key) (= input "g"))
          (-> (launch-external-editor value)
              (.then (fn [updated] (set-value updated))))

          ;; Backspace / character insertion — follow the pattern of the
          ;; existing Editor / ink-text-input usage.
          ...)))

    #jsx [Box {:borderStyle "round" :borderColor border
               :flexDirection "column" :padding 1}
          (when title #jsx [Text {:bold true} title])
          [Text value]
          [Box {:marginTop 1}
           [Text {:color muted}
            (str (if (= submit-mode :submit)
                   "Enter to submit, Shift+Enter for newline"
                   "Ctrl+Enter to submit, Enter for newline")
                 "  •  Esc to cancel  •  Ctrl+G for $EDITOR"
                 (when remaining (str "  •  " remaining "s")))]]]))
```

### Files to create: external editor helper

**`src/agent/ui/external_editor.cljs`** — wraps the `$EDITOR` dance:

1. Write `value` to a temp file (`/tmp/nyma-hook-<id>.txt`).
2. Temporarily detach Ink via `useApp().exit()` or by writing raw escape codes to switch out of the alt screen — the pi-mono pattern stops the renderer, runs the editor synchronously, then re-renders.
3. Spawn `$EDITOR /tmp/nyma-hook-<id>.txt` synchronously via `child_process.spawnSync` with `stdio: "inherit"`.
4. Read the temp file contents back.
5. Delete the temp file.
6. Return the content.

Actually: nyma uses Ink's render tree, and pi-mono's "suspend render" trick won't work cleanly with Ink. A simpler path:

- Use `spawnSync` with `stdio: "inherit"` — bun's sync spawn blocks the Node event loop, so Ink's next render is deferred until the editor exits.
- When the spawn returns, Ink re-renders automatically on the next state change.
- The temp file is read synchronously after spawn.

Test this assumption early — if Ink doesn't re-enter raw mode cleanly after a `spawnSync`, fall back to prompting the user to type `:e` or similar to manually trigger the editor.

### Tests

**`test/hook_editor.test.cljs`** — cover the pure helpers only:

- `should-submit-on-return? :submit #js {:shift false}` → true
- `should-submit-on-return? :submit #js {:shift true}` → false (Shift+Enter = newline)
- `should-submit-on-return? :newline #js {:ctrl true}` → true
- `should-submit-on-return? :newline #js {:ctrl false}` → false

Extract the key-routing logic as a small pure function (`should-submit-on-return?`, `should-insert-newline?`) before wiring it into `useInput`, so the tests don't need to render React.

### Integration points

- **Used by P5** (history search) as the text-input layer? Maybe not — history search doesn't edit, it selects. So HookEditor has no immediate consumer inside this plan.
- **Future consumers:** `/btw` command (side conversation with a different model), commit-message dialog, hook-script editor, any ask-user flow that currently uses the terse `InputDialog`.
- **Who opens it:** extensions via `api.ui.custom(hook-editor-factory)` using the existing overlay protocol, or core code that imports `HookEditor` directly.

### Risks / notes

- **External editor is the risky piece.** `spawnSync` inside a React render effect is unusual; Ink's raw-mode may not restore cleanly. Budget for an hour of debugging + fallback plan (open the editor via a keybinding that the user triggers only once they've saved, rather than mid-component).
- Cursor positioning inside a multi-line text buffer is hard to get right. For the first cut, keep the cursor at the end (append-only), and only add full cursor navigation if users ask. This matches the "ship the pure logic, defer the rich UX" approach from M1–M4.

---

## P5 — Ctrl+R history search overlay

### What

Press Ctrl+R (bound to `app.history.search` in the keybinding registry from M1) to open a fuzzy-searchable overlay showing past prompts from `prompt_history`. Select one with Enter to load it into the editor. The `prompt_history` extension already has `create-history-picker` that returns a pi-mono style component — this ticket wires it to the key chord.

### Core-vs-extension tension

The keybinding registry lives in core, and the action `app.history.search` is registered with a `ctrl+r` default. But the handler (the picker itself) lives in the `prompt_history` extension. Core can't import from an extension — that inverts the dependency graph.

**Solution: add an action-handler registry to core.** Extensions register handlers keyed by action id; core's `useInput` in `app.cljs` dispatches to registered handlers after checking built-in actions.

### Files to create

**`src/agent/action_handlers.cljs`** — pure registry similar to `tool_renderer_registry`:

```clojure
(ns agent.action-handlers
  "Registry mapping action-id → handler fn. Action ids match those in
   the keybinding-registry. Handlers are called with a context map
   {:agent :set-overlay :editor-value :set-editor-value}.")

(def ^:private registry (atom {}))

(defn register-handler [action-id handler]
  (swap! registry assoc (str action-id) handler))

(defn unregister-handler [action-id]
  (swap! registry dissoc (str action-id)))

(defn get-handler [action-id]
  (get @registry (str action-id)))

(defn dispatch
  "Try to dispatch `action-id` through the registry. Returns true when
   a handler existed and was called, false otherwise."
  [action-id ctx]
  (if-let [h (get-handler action-id)]
    (do (h ctx) true)
    false))

(defn reset-registry! [] (reset! registry {}))  ;; test helper
```

### Files to modify

**`src/agent/extensions.cljs`** — add the new API:

```clojure
:registerActionHandler
  (fn [action-id handler]
    (action-handlers/register-handler (str action-id) handler))
:unregisterActionHandler
  (fn [action-id]
    (action-handlers/unregister-handler (str action-id)))
```

**`src/agent/ui/app.cljs`** — after the built-in action cond branches in `useInput`, fall through to the action-handler registry:

```clojure
(useInput
  (fn [input key]
    (let [reg @(:keybinding-registry agent)]
      (cond
        (kbr/matches? reg input key "app.interrupt")  ...
        (kbr/matches? reg input key "app.help")       ...
        (kbr/matches? reg input key "app.model.show") ...
        :else
        ;; Walk the action-handler registry for every other action
        ;; defined in the keybinding registry.
        (doseq [action-id (keys (:actions reg))]
          (when (kbr/matches? reg input key action-id)
            (action-handlers/dispatch action-id
              {:agent agent
               :set-overlay set-overlay
               :editor-value editor-value
               :set-editor-value set-editor-value})))))))
```

Alternative (more efficient than `doseq`): add a single `dispatch-key` fn that looks up the matching action id from the registry's combo index, then calls the handler. The `doseq` above re-checks every action per keystroke — fine for ≤50 actions, nice to optimize later.

**`src/agent/extensions/prompt_history/index.cljs`** — register the handler on extension activation:

```clojure
(.registerActionHandler api "app.history.search"
  (fn [ctx]
    (let [picker (create-history-picker (load-prompts) ...)]
      ((:set-overlay ctx) picker))))
```

### Tests

**`test/action_handlers.test.cljs`**:
- `register-handler` + `get-handler` round-trip
- `dispatch` returns true when handler registered, false otherwise
- `dispatch` calls the handler with the provided context
- `unregister-handler` removes the entry
- `reset-registry!` clears everything

**`test/ext_prompt_history.test.cljs`** (extend existing, if it exists):
- After extension activation, `action-handlers/get-handler "app.history.search"` is defined
- Calling the handler opens an overlay (spy on `set-overlay`)

### Integration points

- Keybinding-registry already has `app.history.search` with `ctrl+r` default.
- `prompt_history` extension already has `create-history-picker`.
- Only the glue — action-handlers registry + registerActionHandler API + app.cljs dispatch — is new.

### Risks / notes

- **`prompt_history` is still untracked in the repo.** This ticket assumes it's committed (or at least its interface is stable) before P5 lands. If it's still local-only, the wiring compiles but the handler is never registered for upstream users.
- **The `doseq` over actions in `useInput`** is O(N) per keystroke. With ≤50 actions it's fine. If nyma grows to hundreds, switch to a precomputed `{combo → action-id}` map rebuilt whenever the registry changes.
- **The existing `registerShortcut` API** does something similar already (flat key-combo → action string). Action-handlers is strictly more capable: it lets extensions bind to *semantic* action ids that the user can remap via `keybindings.json`, rather than hard-coding a raw combo. Deprecate `registerShortcut` in a follow-up once all callers migrate.

---

## Dependency graph

```
P1 (git poll)      P3 (CountdownTimer)
   |                  |
   |                  +── P4 (HookEditor, optional CountdownTimer dep)
   |                             |
P2 (mode border)                  |
                                  |
                      P5 (Ctrl+R history) ── action-handlers (core)
                                              + prompt_history wiring
```

P1 / P2 / P3 are fully independent and can be shipped in any order.
P4 optionally uses P3 (countdown display in the hint line).
P5 is standalone but introduces a new core primitive (action-handlers registry) used by it.

---

## Milestones

### Polish pass (2 h)
- P1: Live git status polling
- P2: Bash/python mode border
- P3: CountdownTimer

After this, the status line stays live, the editor signals shell/python mode, and dialogs have a working countdown primitive. No new APIs.

### Reusable editor (2 h)
- P4: HookEditor + external-editor helper

After this, nyma has a reusable modal multi-line editor. No callers yet — ship with docs and leave future consumers to opt in.

### Keybindable extensions (2 h)
- Core: action-handlers registry + `registerActionHandler` extension API
- P5: Ctrl+R history search overlay wiring

After this, extensions can bind to action ids from the keybinding-registry, and the `prompt_history` extension finally has its promised Ctrl+R overlay.

---

## Risks / open questions

**Q1: Should P4's HookEditor ship without a caller?**
Extensions-oriented; no core consumer inside this plan. Shipping it means one unused file on main until a future feature adopts it. Alternative: defer P4 until a concrete consumer (e.g., `/btw` command) is planned. **Decide before starting P4.**

**Q2: External editor ($EDITOR) compatibility with Ink.**
Unconfirmed whether `spawnSync` + Ink raw-mode restore works cleanly on macOS/Linux terminals. Budget 30 min of time-boxed verification at the start of P4. If it doesn't work, ship HookEditor *without* the external-editor hook and document the limitation.

**Q3: Action-handler dispatch inside `useInput` is a `doseq` over all actions.**
Fine for ≤50 actions; becomes noticeable at ≥500. Not a blocker, but flag it as a future optimization in P5.

**Q4: `prompt_history` extension status.**
P5 relies on the extension being present and activated. If it's still untracked in the repo at merge time, P5 can ship the infrastructure (action-handlers + registerActionHandler + keybinding wiring) without the actual history picker hookup — and the picker wiring lands when `prompt_history` is committed.

**Q5: Bash/python submission behavior.**
P2 only changes the border color. Actually executing `!ls` as a shell command or `$expr` as Python is a separate feature that hooks into `do-submit` in `app.cljs`. Out of scope for this plan; note as a follow-up when users start asking "why doesn't it actually run?"
