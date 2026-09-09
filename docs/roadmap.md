# Roadmap — Infrastructure Ready, Deferred, and Watch Items

A living document for anything that's been **built but not yet wired up**, or **deliberately deferred**, so nothing falls through the cracks between sessions. Distinct from `plan-*.md` files, which are per-feature plans. This is the "don't forget" ledger.

Last updated: 2026-08-18 (after escalation + /refine).

---

## 1. Infrastructure ready, not yet wired

Every item in this section is **fully implemented and tested** — the module exists, the unit tests pass, the public API is documented. What's missing is the callsite edit that flips over from the legacy path to the new one. Each is low risk because the old path still works.

### 1a. Chord keybinding resolver — REMOVED (2026-07)

`src/agent/keybinding_resolver.cljs` and `ui/diff_renderer.cljs` (plus their tests) were deleted:
both became unreachable after the Ink→pi-tui migration removed `src/agent/ui/app.cljs` (their
only intended consumer). If chord bindings become a real need, resurrect from git history or
build on pi-tui's own `matchesKey`.

### 1b. CommandRegistry aliases / hidden / enabled in `builtins.cljs`

- **What's ready:** `src/agent/commands/parser.cljs` supports `:aliases`, `:hidden?`, `:enabled?` on any command spec. `autocomplete_builtins.cljs:slash-provider` already consumes the filtered, sorted suggestions list. Full coverage in `test/commands_parser.test.cljs`.
- **What's wired:** the shape is read end-to-end, but `src/agent/commands/builtins.cljs` doesn't declare any aliases, hide any commands, or gate any of them. Existing commands work because the parser treats all three fields as optional.
- **Why we stopped:** mechanical, low leverage until a real need surfaces.
- **When to do it:**
  - Users ask for short aliases like `/cls` → `/clear`, `/q` → `/exit`.
  - We add debug-only or admin-only commands that should be hidden by default.
  - We add commands whose availability depends on the current session state (e.g. `/export` only enabled when there are messages).
- **Rough plan:** add `:aliases`, `:hidden?`, `:enabled?` to individual entries in `(register-builtins …)` — nothing else changes.
- **Files to touch:** `src/agent/commands/builtins.cljs` only.

### 1c. Debug logger adoption across the codebase — CLOSED (2026-09-07)

Rescoped and closed. The raw count looked alarming (90 `console.*` against 12
`dbg/*`) but is legitimate: `rpc`/`pi_rpc` use `console.error` on stderr and say
so in their docstrings, `src/gateway` is a server, and the only TUI-path calls
are `ui/crash_recovery.cljs:110-111`, printing a stack after teardown. The rule
worth keeping is narrower than "adopt dbg everywhere": **prefer `d/warn-quiet`
for anything that runs while the TUI owns the screen**, because stderr during a
render desynchronises pi-tui's differential renderer.

Original entry, for context:

#### 1c (as written)

- **What's ready:** `src/agent/utils/debug.cljs` with env-gated `d/debug`/`d/info` (off by default unless `NYMA_DEBUG=1` or `DEBUG` contains `nyma`) and always-on `d/warn`/`d/error`. `configure-logger!` lets tests capture output without touching globals. Full coverage in `test/utils_debug.test.cljs` (15 tests).
- **What's wired:** exactly one production caller — `settings/manager/load-json` emits `d/warn` on duplicate JSON keys. Everywhere else still uses raw `js/console.log` / `js/console.warn`.
- **Why we stopped:** mechanical migration, not a bug fix. We didn't want to conflate with the cc-kit borrow work.
- **When to do it:** anytime. Low-risk grep-and-replace.
- **Rough plan:**
  1. Grep for `js/console.log`, `js/console.warn`, `js/console.error` under `src/agent/`.
  2. Replace with the appropriate `d/debug` / `d/info` / `d/warn` / `d/error` call and a sensible tag.
  3. Audit the TUI path in particular — `console.log` corrupts Ink's cursor tracking (see `extensions/agent_shell/acp/pool.cljs:59` for the existing comment). All TUI-path logging should go through `d/` helpers which write to stderr.
- **Files to touch (grep target list):**
  - `src/agent/extension_loader.cljs:37`
  - `src/agent/commands/builtins.cljs:21, 28`
  - `src/agent/commands/share.cljs:118`
  - likely others under `extensions/`

### 1d. Per-tool safety metadata → permission flow — CLOSED (2026-09-07)

Wired. `tool_result_policy.cljs` resolves `default-policy → builtin-policies →
tool-metadata :result-policy → ext-policies`; `middleware.cljs:290` maps
`file-editing?` to the `"write"` category; `core.cljs` consumes it at
registration.

Original entry, for context:

#### 1d (as written)

- **What's ready:** `src/agent/tool_metadata.cljs` with `:read-only?` / `:destructive?` / `:requires-confirmation?` / `:network?` / `:long-running?` / `:category` metadata for every built-in tool, plus `register-metadata!` so extensions can declare safety for their own tools. Full coverage in `test/tool_metadata.test.cljs` (18 tests).
- **What's wired:** nothing consumes it yet. The registry is populated and query-able; no code branches on it.
- **Why we stopped:** the natural consumers (permission prompt UI, allow-list-for-sandbox mode, extension capability gating) are bigger features. We'd rather land the metadata contract first and build the consumers against a stable API.
- **When to do it:**
  - We ship a permission-request modal (see §3b below).
  - We add a `--sandbox` / `--read-only` CLI flag that restricts which tools run.
  - An extension wants to auto-refuse `:destructive?` tools in a CI context.
- **Rough plan:**
  1. In `middleware.cljs:execute-tool-fn`, look up `tool-metadata/tool-safety` before running.
  2. If `:requires-confirmation?` and no session-wide always-allow, raise a confirmation prompt via `ui.confirm`.
  3. Short-circuit with a friendly error if the sandbox mode denies the tool's category.
- **Files to touch:** `src/agent/middleware.cljs` for the gating, `src/agent/commands/builtins.cljs` if we add an `/allow-always <tool>` command, extension tools that want custom safety profiles.

### 1e. Shared `ProgressBar` component

- **What's ready:** `src/agent/ui/progress_bar.cljs` with `render-string` (pure) and `ProgressBar` (JSX). Unicode sub-cell resolution (8 block glyphs). Full coverage in `test/progress_bar.test.cljs` (19 tests).
- **What's wired:** nothing. `agent_shell/ui/status_line.cljs:50-53` still has its own private `progress-bar` helper that only uses full/empty blocks, no sub-cell smoothness.
- **Why we stopped:** drop-in replacement, but shipping at the same time as the module creates noise.
- **When to do it:** anytime — swap the private helper for `(render-string ratio width)` in `agent_shell/ui/status_line.cljs`. Also worth considering as the bar for streaming token counters, extension downloads, and compaction progress.
- **Files to touch:** `src/agent/extensions/agent_shell/ui/status_line.cljs` (delete the private helper, require and call `progress-bar/render-string`).

### 1f. `mk-tool-ctx` / `mk-api-mock` test fixtures

- **What's ready:** `test/tool_ctx_fixture.cljs` with `mk-tool-ctx` (a full ext-ctx with middleware enrichment) and `mk-api-mock` (extension activation API with command/notify/event capture atoms). Full coverage in `test/tool_ctx_fixture.test.cljs` (14 tests).
- **What's wired:** nothing yet — existing tests still build their own `#js {:ui ...}` stubs inline.
- **Why we stopped:** fixture landed standalone; migrating existing tests is mechanical churn that would bloat this phase's diff.
- **When to do it:** anytime. Low-risk migration, shrinks every touched file.
- **Migration targets (grep)**:
  - `test/ext_effort_switcher.test.cljs:13-20` (`make-mock-api`)
  - `test/ext_model_switcher.test.cljs:16`
  - `test/clear_session.test.cljs:12`
  - `test/extension_context.test.cljs` (multiple inline ctx constructions)
- **Plan:** replace each local `make-mock-api` with a call to `tool-ctx-fixture/mk-api-mock` + overrides.

### 1g. LSP suite v2 — deferred tools and external-edit watching

`src/agent/extensions/lsp_suite/` is live and tested. These items were explicitly deferred to v2:

- **Call hierarchy / go-to-implementation** — `callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls`, `textDocument/implementation`. Not yet wired; the client layer (`lsp_client.cljs`) already supports arbitrary `request!` calls.
- **Format-on-write** (`textDocument/formatting`) — the file-sync hooks already fire `didChange`/`didSave`; adding a format step is a one-liner in `lsp_tools.cljs`.
- **Rename symbol** (`textDocument/rename`) — needs multi-file edit coordination via the existing `multi_edit` tool.
- **External-edit watching via `chokidar`** — v1 only syncs files touched by nyma's own `read`/`write`/`edit` tools. Files edited in an external editor while nyma is running will desync. Fix: add `chokidar` to `extension.json` dependencies and watch `cwd` for changes.
- **Language server catalog expansion** — current catalog has 6 servers (TypeScript, Python, Rust, Go, Clojure, Ruby). Adding Java (eclipse-jdt), C/C++ (clangd), Lua, Zig, Swift is mechanical: add an entry in `lsp_servers_catalog.cljs`.
- **When to do it:** any of the above individually; external-edit watching is the highest-value item and can land independently.
- **Files to touch:** `lsp_tools.cljs` (new tools), `lsp_servers_catalog.cljs` (more languages), `lsp_manager.cljs` (chokidar watcher).

---

## 2. Deferred cc-kit audit findings

These were identified in the Phase 12 audit but deliberately **not** implemented. Each has a revisit trigger. Source file references are to the cc-kit repo at `/Users/vihangd/projects/pers/cc-kit/packages/`.

### 2a. React/ink-renderer-specific patterns (blocked on npm-ink vs custom reconciler)

cc-kit ships a custom `ink-renderer` package; nyma uses npm `ink` v6. These patterns live in cc-kit's reconciler layer and don't drop in:

| Finding | cc-kit location | Revisit when |
|---|---|---|
| **#1** Shared animation clock with `keepAlive` subscription | `packages/ink-renderer/src/components/ClockContext.tsx:11-69` | We add >2 animated UI elements and see flicker or re-render cascades |
| **#2** `useLayoutEffect` for raw-mode setup | `packages/ink-renderer/src/hooks/use-input.ts:45-59` | Users report keystroke echoes or cursor flashes at startup |
| **#3** `useEventCallback` for stable listener ordering | `packages/ink-renderer/src/hooks/use-input.ts:66-89` | We introduce modal-inside-modal input capture and see order bugs |
| **#9** `useTerminalSize` error-boundary hook | `packages/ui/src/hooks/useTerminalSize.ts:9-17` | We want explicit invariant-fail on context misuse |
| **#14** Separate `ClockProvider`/`FocusProvider` to isolate re-renders | `packages/ink-renderer/src/components/ClockContext.tsx:73-107` | We grow >5 root-level providers and see cascading re-renders |

### 2b. Patterns skipped for low leverage today

| Finding | Why deferred | Revisit when |
|---|---|---|
| **#4** Lazy Zod schema caching (`packages/ui/src/keybindings/schema.ts:8-14`) | Nyma's Zod schemas are module-level constants — already built once | We see allocation hot-paths in a profiling session |
| **#5** Full 5-pass keybinding validator (`packages/ui/src/keybindings/validate.ts`) | Nyma's bindings are simpler (no contexts, no config chords yet) | Users start hand-editing `keybindings.json` and reporting cryptic errors |
| **#6** Contextual "this binding is risky" validation | Same as #5 | Same as #5 |
| **#8** Precise terminal width calculation with emoji/CJK/ANSI (`packages/shared/src/stringWidth.ts`) | Needs vendoring 3 dependencies (`strip-ansi`, `emoji-regex`, `get-east-asian-width`); no reported truncation bug today | A user reports misaligned columns on a message with emoji or CJK, or the status line looks wrong in a non-ASCII branch name |
| **#15** Named constants for action/context identifiers (`packages/ui/src/keybindings/schema.ts:19-65`) | Small win, natural to adopt incrementally | Next time we touch `default-actions` |
| **#16** Documented context descriptions co-located with the context list | Small win | When we build a `/help keys` interactive viewer |

### 2c. Tools & UI audit (phases 13–15) — deferred items

A second audit (this one covering cc-kit's `packages/tools/`, `packages/ui/`, and layout files) surfaced 16 findings. Three landed (phases 13–15 above). The rest fall into these buckets:

**Needs verification first, not borrow-ready:**

| Finding | Gap | Revisit action |
|---|---|---|
| **T2** Does `bash_suite` actually wire `abortSignal` → `child.kill()`? | cc-kit's `bash.ts:37-102` uses `child.kill()` on signal.aborted. Nyma plumbs `abortSignal` into ext-ctx via `middleware.cljs:34` but the bash_suite tool may or may not actually consume it. | Grep `src/agent/extensions/bash_suite/*.cljs` for `abortSignal` / `.abort` / `.kill`. If missing, wire it. |
| **T14** Parallel-tool concurrency test | cc-kit's `tests/parallel-tools.test.ts` verifies that read-only tools run in parallel via timestamp overlap. Nyma's `loop.cljs` / `middleware.cljs` don't show `Promise.all` — tools may run sequentially. | Determine whether nyma parallelises tool execution itself or relies on the Vercel AI SDK. If former, add a concurrency test. If latter, skip (we'd be testing someone else's code). |

**Future feature work, not simple borrows:**

| Finding | What it is | Revisit when |
|---|---|---|
| **T3** Subagent tool composition | cc-kit's `subagent.ts:37-78` spawns a child agent inside a tool. | Multi-agent workflows become a real use case. |
| **T7** Rich MessageList content types + live tool status | cc-kit models messages as a union of `text / tool_use / thinking / diff / code / error`. Nyma already has `tool_execution.cljs` + `tool_status.cljs`, but could borrow cc-kit's thinking-block collapse and per-content-type dispatch. | We want collapsible thinking blocks or per-tool-type inline previews. |
| **T8** Width-aware `MarkdownTable` | cc-kit's `MarkdownTable.tsx` wraps ANSI-aware, switches to vertical layout below a row threshold. | We start rendering tables from LLM output and they look bad. |
| **T11** `PermissionRequest` modal UI | cc-kit's `PermissionRequest.tsx` is a three-button (deny / allow / always-allow) modal with optional diff preview. | We implement the per-tool safety-metadata consumer from §1d above — the modal is the natural UI for `:requires-confirmation?` prompts. |

**Ink-renderer-specific (won't port to npm-ink v6):**

| Finding | Why not |
|---|---|
| **T13** cc-kit's `App.tsx` context providers (TerminalSizeContext, StdinContext, ClockProvider, etc.) | Lives in cc-kit's custom reconciler — npm ink v6 provides equivalent hooks via its own API. |

**Already done in earlier phases (not re-borrowing):**

| Finding | Already landed as |
|---|---|
| **T1** Tool result truncation | `tools.cljs:275` (`truncate-text`) + `bash_suite/output_handling.cljs` (middle-truncation + retrieve-full) |
| **T2** AbortSignal plumbing | `core.cljs:40` abort-controller + `middleware.cljs:34` enrichment + Esc abort landed in `modes/interactive.cljs` (2026-07) + core `bash` kills its child on the run's abortSignal |
| **T9** DiffView parser | Nyma's `ui/diff_renderer.cljs` parses a different custom format (`+123\| content`); cc-kit's unified-diff parser only helps if we start rendering `git diff` output directly — different feature, not a missing borrow |
| **T12** REPL layout | `app.cljs` already uses flex layout equivalently |
| **T15** Pure-function input logic tests | Phase 9 — `picker_math`, `picker_input` |

### 2d. Patterns already landed (for the avoidance of double work)

For the record, these cc-kit findings **have** been borrowed in phases 8–15 and do **not** need revisiting:

- Canonical `key-name` mapper with escape-meta quirk (phase 8 — `keybinding_registry/key-name`)
- `FuzzyPicker` clamp/step/window math (phase 9 — `ui/picker_math`)
- `CommandRegistry.parse()` + `getSuggestions()` (phase 10 — `commands/parser`)
- `resolveKeyWithChordState` (phase 11 — `keybinding_resolver`)
- Picker input dispatcher consolidation + shared frame rendering (phase 12 — `ui/picker_input`, `ui/picker_frame`)
- Env-gated debug logger with pluggable sink (phase 12 — `utils/debug`)
- Structured validation warnings with `{severity, type, message, suggestion}` shape (phase 12 — `utils/validation`)
- Duplicate-key detection in JSON settings loader (phase 12 — `settings/manager/detect-duplicate-keys`)
- Per-tool safety metadata (`:read-only?`, `:destructive?`, `:requires-confirmation?`, `:network?`, `:long-running?`, `:category`) (phase 13 — `tool_metadata`)
- Unicode sub-cell `ProgressBar` (phase 14 — `ui/progress_bar`)
- `makeCtx` / `makeLocalCommand` fixture pattern extended to `mk-tool-ctx` / `mk-api-mock` (phase 15 — `test/tool_ctx_fixture`)
- Pure-function testing with factory helpers (applied throughout — `mk-key`, `mk-cmd`, `make-harness`, etc.)

---

## 3. Bug classes to watch

**Closed classes are kept, marked CLOSED with the evidence** — a class that
looks solved is worth recognising if it comes back, and 3b came back wearing a
different hat (3k).

New since 2026-09-05: 3g–3k. Every one of them shipped past a green suite,
because each lived in a **seam** between two components that were each tested in
isolation. The lints added for them all carry a floor check and a detector
self-test, and read their rules from production rather than copying them.

Patterns we've hit repeatedly this session. If any of these shapes shows up again, the fix is usually well-understood.

### 3a. "Registry written but nobody reads it"

A feature that declares a registry-backed API but has nothing consuming the registry at render time. We hit this with `message-renderers` (dead code, removed in Phase 1) and with the ACP `registerStatusSegment` → `StatusLine` auto-append chain (fixed by adding auto-append support).

**Test smell:** unit test registers an item and checks the atom; no test asserts the item appears in a rendered frame. Every future registry-backed API should have a matching end-to-end test in `test/extension_registration_e2e.test.cljs` or similar.

**Swept 2026-09-07**, after `:display` formatters turned out to be the same
shape (13 formatters, zero of them ever called). Every registry-backed API
walked from producer to consumer:

| registry | producers | consumer | state |
|---|---|---|---|
| `registerShortcut` + keybindings.json | prompt_history `ctrl+r`, model_roles cycle-key, every user binding | **none** — `interactive.cljs` had listeners for Esc and Ctrl+C only | **FIXED** — `keybindings/dispatch-shortcut!`, pinned by `test/keybindings_dispatch.test.cljs` |
| `registerStatusSegment` | 4 (spec_driven, model_roles, mcp_client, agent_shell) | `status_bar.cljs:36` | works |
| `registerCompletionProvider` / `mention-providers` | mention_files only | **none** — `autocomplete_provider/complete-all` has zero callers; the editor uses pi-tui's `CombinedAutocompleteProvider`, which does slash + `@file` natively | dead, no user-visible loss. `ac-builtins/register-all!` fills a registry nobody queries |
| `registerContextProvider` | none | **none** — `:provide` is never invoked | dead |
| `registerBlockRenderer` / `registerToolRenderer` | none | none | dead |

The last three are dead code, not bugs — do NOT write a test pinning "this has
no consumer", it locks the deadness in. Deleting the four APIs (and
`autocomplete_provider`, `tool_renderer_registry`) is a separate call.

**Swept again 2026-09-09**, widening the shape from "registry" to any
producer/consumer pair (nil UI slots, plain state keys, session-store fns):

| store / slot | producers | consumer | state |
|---|---|---|---|
| `api.ui.setHeader` / `setFooter` / `setTitle` / `setStatus` / `setEditorComponent` / `onTerminalInput` | `agent_shell/shared.cljs:403` (header, guarded); orphan factories `agent_shell/ui/header.cljs:45`, `agent_shell/ui/status_line.cljs:62` | **none** — declared `nil` at `extensions.cljs:576-592` and assigned NOWHERE. `interactive.cljs:508-536` sets 6 slots, `overlay_host/install!` sets 5 more; these 6 are never among them | **dead slot, worse than a dead registry**: producer code exists and crashed once (`api.ui.setHeader is not a function`, `shared.cljs:392-405`). Neither `ui/header.cljs` nor `ui/status_line.cljs` is required by any ns — orphan files |
| `state :tool-calls` | `middleware.cljs:293,319` dispatch `:tool-call-started` / `:tool-call-ended` on EVERY tool call | **none** — `state.cljs:77-88` are the only two occurrences in the repo | dead sink **and** an unbounded leak: full `:args` + `:result` of every call retained for the session's life |
| session `:set-label` → `:get-label` | none | none — `entry-labels` (`manager.cljs:83`) written by `set-label`, read only by `get-label`, which has zero callers. `api.setLabel` has zero producers | dead both ends; labels are in-memory only and never persisted |
| `/name` → `listing/explicit-name` | **none** — `set-session-name` (`manager.cljs:165`) only `reset!`s an atom | `listing.cljs:44` filters entries with role `"session-name"`; nothing in the repo ever appends one | **inverse mismatch**: consumer with no producer. `/name` is lost on restart and session rows still fall back to first-user-message. Fix is NOT one line — appending a new entry role touches `load-fn`, `build-context-fn`, compaction and token estimation |

**Resolved 2026-09-09** (see the commits, not this table, for the diffs):

- `api.ui` dead slots — **deleted**. `setStatus`/`setFooter`/`setHeader`/`setTitle`/`setEditorComponent`/`onTerminalInput` are gone from `extensions.cljs`, along with `agent_shell/shared.cljs`'s `header-factory` + `setup-ui!` + `footer-set?`, its three call sites, the orphan `ui/header.cljs` and `ui/status_line.cljs`, and `test/agent_shell_setup_ui.test.cljs` — 100 lines of tests pinning a mechanism that installed into a slot nothing implements. Zero behavior change: every producer already guarded on the slot, and `status_segments` puts agent/model/mode on the status line, a slot that exists. pi-tui has no header or footer slot to wire, only `Terminal.setTitle`, which nothing asked for.
- `state :tool-calls` — **deleted**. Reducers, the `core.cljs` init key, both `middleware.cljs` dispatches and their four tests. `:tool-execution-started/ended` stays: `:active-executions` is read by `extension_context` and `waitForIdle`.
- `/name` — **fixed**. `set-session-name` now also appends a `"session-name"` entry and `load-fn` restores the atom from it. `build-context-fn` whitelists conversation roles, so the entry is invisible to the model, compaction and token counts; the tree viewer shows it as `[session-name] …`.
- `setLabel` / `get-label` — **kept, deliberately**. Inert in both directions and it costs nothing at runtime (nothing calls `setLabel`, so `entry-labels` never grows), but it is published API in `manifest_capabilities` and the capability lint map, so removing it breaks out-of-repo extensions for no gain. Do not build on it: a label is neither persisted nor rendered. Delete it only alongside the other dead extension APIs above, in one breaking pass.

Not defects, do not "fix": `registerModelInfo`, `appendEntry`,
`get/setSessionName` have zero in-repo producers but working consumer paths.
That is unused public API, not a broken wire.

**Blast radius for any deletion here:** `extension_scope.cljs:85-99` (the
`gate` forwards) and `test/extension_capability_lint.test.cljs:42-45` (the
method→capability map) must be edited in the same commit or the lint test
fails. Deleting `registerToolRenderer` also orphans
`ui/tool_renderer_registry.cljs` + its test.

### 3b. "Scoped API forgot to forward a method" — CLOSED (2026-09-07)

Verified closed: 65 methods forwarded against 60 on the base API, and the
apparent gaps are nested config keys (`tokenEstimate` and friends) that no
extension calls. `test/scoped_api_parity.test.cljs` holds the line.

**But the class moved rather than died** — see 3k. `api.ui` is not a `gate`d
method; it is an `Object.defineProperty` getter returning
`#js {:available false}` when the capability is absent, so three extensions lost
their UI silently. Parity checks on the method list could not see it.

Original entry, for context:

#### 3b (as written)

`extension_scope.cljs/create-scoped-api` manually re-exports each method from `base-api`. Forgetting a forward produces a silent `undefined` — an extension calls it, nothing happens, no error. We hit this with `registerStatusSegment`, `registerToolRenderer`, and `registerCompletionProvider` in the ACP debug session.

**Mitigation:** `test/extension_scope.test.cljs` has at least one test per forwarded method. When adding a new method to the base API, add its forwarding test at the same time.

### 3c. "Lifecycle event fires before UI mounts"

`session_ready` emits from `cli.cljs:153` before `interactive/start` mounts the ink app, so any extension handler that reads `api.ui` at `session_ready` time finds it `undefined`. We hit this with `agent_shell/setup-ui!` — the fix was to also call it from user-driven code paths (`agent_switcher`, `handoff`) that run after mount.

**Mitigation:** anything that depends on a live `api.ui` object should run from a user-interaction handler OR use a "try later" guard + retry. Document the contract in any new extension that uses the UI surface.

**2026-08 — second confirmed instance, and it was load-bearing.** `cli.cljs:437` emits `session_start` about 96 lines and one extension-load phase before `interactive/start` (`cli.cljs:533`) registers a handler for it. The event bus has no replay or stickiness, so that emit reaches an empty handler list — for the UI *and* for every extension (`mcp_client`, `claude_hook_bridge`, `openwiki` all subscribe to it and never see the startup emit). Startup resume works only because `interactive/start` seeds the pane directly, by a separate code path.

**Mitigation:** an emit that fires before subscribers exist is dead, not merely early. Either move the emit after extension load and UI mount, or give the bus a replay for lifecycle events. Until then, treat `session_start` at startup as unreliable and seed from state directly.

### 3d. "Picker silently swallows a key"

Every picker we ship reimplements `onInput`. We hit `key.delete` vs `key.backspace` (macOS), we hit `/agent qwen` arg-swallowing, and we hit the Ctrl+P/N navigation gap. Phase 12 consolidated all four pickers through a single `dispatch-input` so the next one-branch fix only happens once.

**Mitigation:** new pickers should use `src/agent/ui/picker_input.cljs` + `src/agent/ui/picker_frame.cljs`. Any branch not covered by the dispatcher (e.g. Shift+Tab for a second `onTab`) means we should extend the dispatcher, not roll a one-off cond.

**2026-08 — the same class, one layer down: the ENCODING, not the branch.** Typing did nothing in any overlay, and Enter did nothing either. pi-tui never leaves the terminal sending bare characters: it negotiates the Kitty keyboard protocol and falls back to xterm `modifyOtherKeys` (`terminal.js:128-138`), so a plain `a` arrives as `ESC[97u` or `ESC[27;1;97~`. `printable-char` tested `(= (count data) 1)` and returned nil for both. Arrows and Enter kept working via `matchesKey`, which understands every encoding — that asymmetry is why it presented as "the overlay is dead" rather than "typing is dead". Separately, pi-tui's own plain-Enter branch (`keys.js:719-725`) returns before ever reaching its `matchesModifyOtherKeys` call, so plain Enter under that mode is unrecognised upstream too.

**Mitigation:** never hand-roll "is this a printable character". Use pi-tui's `decodePrintableKey`, and treat any new key predicate as owing a test in *every* encoding — the existing overlay tests drove `handleInput` with hand-written legacy bytes and were green throughout. `overlay_host/enter-key?` and `test/overlay_input.test.cljs` are the pattern.

### 3f. "Two units for one number"

The most productive bug class of 2026-08 — five distinct instances, every one a silent wrong answer rather than a crash:

- `picker_frame` sized rows with `count` (characters) against a **column** budget → 81 columns emitted inside an 80-column box.
- `status_bar` measured its right half with `count` on an ANSI-stripped string while cutting the left half in columns → 85 columns at width 80, and unlike the overlays it is a base child, so pi-tui throws and the session dies.
- `truncate-tail` stepped by **code unit** rather than code point → lone surrogates from a cut emoji.
- `fuzzy_scorer` hit the same split *inside squint*: `count` on a string is `.length` (code units) while `nth` indexes code points, so any label containing an emoji ran the scan off the end and threw — killing the process on one keystroke.
- A tab is measured 0 / 1 / 3 / 4-8 columns by four different layers, so a line containing one has no true width.

**Mitigation:** state the unit in the name or the docstring of anything holding a width, and measure with the function whose verdict matters — `visibleWidth` for anything pi-tui will render, code points (`Array.from`) for anything that slices text. Assert widths in tests with `visibleWidth`, never `count`: several tests asserted `count` and passed while the rendered row overflowed.

### 3e. "JSON silently drops data"

`JSON.parse` keeps the last value when a key repeats. We had this latent in `settings/manager/load-json`; the Phase 12 fix scans for duplicates before parsing and emits a `d/warn`. Anywhere else we parse user JSON, we should consider the same scan.

**Known callsites worth auditing:** `keybindings.cljs` (user keybindings file), `.nymaignore` if it's JSON, extension configs loaded from `~/.nyma/`. Not urgent unless a user reports a silently-dropped setting.

---


### 3g. "Read with no writer"

**2026-09-07.** `:runtime-model` was read in eight places — including
`loop.cljs`'s model-resolution fallback and the status line — and written
nowhere. `setModel` writes `config.model`; the reducer stores `:model`. Every
read was `(or (:runtime-model …) (:model …))`, so it degraded to silence rather
than failing, and two comments documented the mechanism as if it existed.

Auditing for the shape found two that were **not** harmless: `gateway/loop.cljs`
and `pi_rpc.cljs` both read `.-textDelta` off `message_update`, which carries the
AI SDK v7 fullStream part (field `text`). The gateway's entire streaming path had
never fired; every pi-rpc `text_delta` was `""`.

**Mitigation:** `test/stream_delta_field.test.cljs` for that field. A general
state-key lint (reads without writers) is still owed.

### 3h. "Guard makes config inert"

**2026-09-07.** `model_roles/on-resolve` guards `(not= role "default")`, so a
`default` entry in `settings.roles` can never take effect — verified against the
compiled module: zero `setModel` calls. The setting is accepted, documented by
implication, and silently ignored.

Related and larger: **six settings keys were parsed and read by nothing**
(`steering-mode`, `follow-up-mode`, `tool-display`, `tool-display-max-lines`,
`scrollback-mode`, `status-line`). Two were never built; four were live before
`acee030` and deleted with the Ink UI, leaving the key, the README entry, and —
for scrollback — a doc comment naming two files that no longer exist.

**Mitigation:** `settings/manager.cljs` now owns `inert-keys`, warns at startup
(pre-TUI, where stderr is safe), and `test/settings_reader_lint.test.cljs` READS
that map rather than mirroring it. Every key must have a reader or a written
reason; the allowlist fails if an entry revives or disappears.

### 3i. "Command advertises a command that does nothing"

**2026-09-06.** `/spec start` printed "Use /spec next to advance". `/spec next`
found the task, emitted its hook, printed it — and dispatched nothing. Both
commands had passing tests. Separately `/spec` advertised 13 of its 14
subcommands, leaving `install-skill` undiscoverable.

**Mitigation:** `test/command_surface_lint.test.cljs` diffs the advertised
subcommand list against the dispatcher's `case` arms, both directions. It
catches *absence*; the **does-nothing** half needs an integration test per
command that claims to cause work.

### 3j. "Seed list drifts from the live catalogue"

**2026-09-07.** `opencode-zen` seeds four model ids the provider no longer
serves and hides thirteen it does. `deepseek` declared a 131k context for a 1M
model, which drove compaction ~8x too early — the window feeds
`compaction-point`, so this was behavioural, not cosmetic. `openlux` seeds
`glm-4.7`, absent from its 110-model catalogue.

**Mitigation:** cannot run in CI (needs credentials). A manual
`bin/check-seeds.mjs` diffing seeds against each provider's `/models` is owed.
Until then: verify against the live endpoint before trusting a seed list.

### 3k. "Documented channel that was never wired"

**2026-09-07.** `spec_driven`'s README claimed `spec_task_start`/`_complete`
"ride the same `claude_hook_bridge` channel that PreToolUse/PostToolUse use".
The bridge subscribes a fixed list with no `spec_*` entry, and Claude Code has no
hook name they could map to. `spec_phase_enter` and `spec_task_start` are the
sole occurrences of those names in the repo — emitted into the void.

Same shape: `api.ui` is a getter returning `#js {:available false}` when the
capability is absent, not the thrower `gate` installs — so `token_suite`'s token
widget, `workspace_config`'s entire `/alias` output and `claude_hook_bridge`'s
mode detection had all silently stopped working.

**Mitigation:** the capability lint now covers the `ui` *property*, not just
gated methods. For events, prefer a subscriber in-tree or say plainly in the
docs that the event has no consumer.


## 3.5 Deferred, with a known reason (2026-08)

Each of these was found while fixing something else, understood, and left. They are not "unknown unknowns".

- **`truncate-tail` is not ANSI-safe — blocks per-item colour in pickers.** It drops leading code points one at a time, so it eats an opening SGR and can cut inside `ESC[38;2;r;g;bm`, leaving `8;2;122;162;247m` as literal text — which inflates the measured width into the class of violation that kills the TUI (§3f). `two-col-row:117` routes the left column through it, so item labels and descriptions must stay plain. Fixing it unblocks muted descriptions, a dimmed input placeholder, and fuzzy-match highlighting.

- **`/new` does not switch session files.** It clears the store and emits `session_start` but leaves the session manager pointing at the same JSONL, so the next message parent-links to the pre-clear conversation and a later `nyma -c` replays both as one. Fixing it means giving `/new` a fresh session path, which also makes "the pane always mirrors the current branch" uniformly true and removes the one `:reason "new"` special case in the `session_start` handler.

- **`tool_call` entries are not rendered on resume.** `session->seed-messages` keeps only `user`/`assistant`, so a resumed transcript reads as a conversation with no evidence of the work — the newest session on disk is 214 `tool_call` entries against 10 messages. Matches what the model sees on resume, which is why it was left, but it is a real asymmetry with the live view.

- **Upstream pi-tui report, still unfiled.** Three findings worth one issue: (1) plain Enter under `modifyOtherKeys` is unrecognised — `keys.js:719-725` returns before reaching its own `matchesModifyOtherKeys` call; (2) `fullRender` (`tui.js:759-789`) does no width check at all, so an over-wide line on first render/resize/forced render soft-wraps and desynchronizes the differential renderer instead of throwing — the silent twin of the loud crash; (3) `truncateToWidth` re-emits pending SGR but never closes it. Blocked on there being no release since 2026-05-07; the ask is a release, not a patch.

---

## 4. House-keeping / low-priority

- **Structural cleanup of `overlay_picker_integration.test.cljs`** — phase 7 fixed the 4 broken describes but didn't audit other test files for the same formatter damage. Worth a one-line grep: `grep -l '(fn \[\])' test/*.test.cljs` to find any empty-fn describes that slipped past.
- **Test file consolidation** — we now have 110 test files. A few are very small (`picker_math.test.cljs`, `picker_input.test.cljs`, `picker_frame.test.cljs`). Could merge into `picker.test.cljs` once the trio is stable.
- **`plan-*.md` archival** — multiple historical planning docs in `docs/`. After each is either implemented or dropped, move it to `docs/archive/` so the docs dir stays scannable.

---

## 5. External prior-art borrows (caveman / dirac)

Items identified in the caveman/dirac research (`/Users/vihangd/.claude/plans/dynamic-bouncing-aho.md`) that are **infrastructure-level**, not user-facing features. User-facing borrows live in `extension-ideas.md` items #27-33; the Tier 1 plan for the top five (D2/D22/C10/D33/D15) lives in `plan-borrows-caveman-dirac.md`. Everything else lands here so nothing falls through the cracks.

### 5a. Hook system hardening

| Item | Current state | Gap | Revisit when |
|---|---|---|---|
| **D4** Hook timeout + cancel + SIGINT | `hooks.cljs:27,42-43,63` has a per-script timeout (default **5000ms**) via `js/setTimeout` + `.kill proc`. | (i) no AbortController for cooperative cancel, (ii) no SIGINT exit-130 special case, (iii) default is 6× shorter than dirac's 30s. Bumping the default and distinguishing "user-cancelled" from "timeout" is a small fix. | We hit a hook script that exceeds 5s in production, or we want a hook to cooperatively abort on parent Esc. |
| **D5** Streaming hook output with source prefixes | `run-hook-script` at `hooks.cljs:25-50` buffers stdout and parses once at exit. | When multiple global + project hooks run on the same event, there's no streaming UI and no source prefix to disambiguate output. Dirac's `hook-executor.ts` streams with a per-script prefix. | We ship a hook system with >2 concurrent scripts per event and their diagnostic output is unreadable. |
| **D9** Cross-platform hook templates | `/new-extension` scaffolds extension code but no hook scripts. | Auto-generate a bash template on Unix + PowerShell on Windows when a user runs a hypothetical `/new-hook` command (or when `/new-extension` declares hooks). Dirac's `hook-executor.ts` has `templates.ts` with the shapes. | We add `/new-hook` or extend `/new-extension` to scaffold hook scripts. |
| **D6** PreToolUse UI message reordering | Hook-injected messages are appended in emission order. | Dirac reorders the PreToolUse UI message to appear **above** the pending tool box so users see the hook's note before the tool output. Small polish. | Users report confusion about when a hook message applies to the tool call below or the one above. |
| **Hook discovery cache** (dirac's `HookDiscoveryCache.ts`) | `hooks.cljs:10` reads `.nyma/hooks.json` on demand. | Performance optimization only. Not urgent. | We profile and find hook loading is a measurable startup cost. |

### 5b. Command parsing and slash-command UX

| Item | Current state | Gap | Revisit when |
|---|---|---|---|
| **D10** Slash commands mid-message and inside XML tags | `commands/parser.cljs` recognizes slash commands at start-of-line. | Dirac matches `/cmd` anywhere in the message body and inside `<task>`, `<feedback>`, `<answer>`, `<user_message>` XML tags via a single regex. Unlocks prompts like `git diff \| nyma "review and /compact when done"`. Small parser tweak. | We ship extension idea #33 (piped stdin) or find users naturally typing commands mid-message. |

### 5c. Multi-instance coordination

| Item | Current state | Gap | Revisit when |
|---|---|---|---|
| **D26** SQLite lock manager for multi-instance | No lock on `~/.nyma/sessions/` or project session dir. | Two nyma instances in the same project can race on session writes. Dirac has `SqliteLockManager.ts` + `FolderLockUtils.ts` for exactly this. | A user reports a corrupt session after running two nymas in parallel. |

### 5d. Settings and installer helpers

| Item | Current state | Gap | Revisit when |
|---|---|---|---|
| **C8** Idempotent `settings.json` merge helper | `/new-extension` assumes a clean directory; writes assume no conflicts. | Borrow caveman's pattern: a helper that merges into `~/.nyma/settings.json`, writes a `.bak`, detects prior installation, refuses to clobber user-set fields. Useful for any future "publish extension" flow. | We add an install flow that needs to mutate `settings.json` automatically. |
| **C9** Missing-config onboarding nudge | `session_start` fires but doesn't inspect whether the project/global settings have been configured at all. First-time users get no prompt to run setup. | Borrow caveman's pattern: on `session_start`, detect that essential config is absent (no `~/.nyma/settings.json`, no API key resolvable) and emit a one-shot system-prompt addition nudging the model to offer setup. Distinct from `/init` — this is a conversational onboarding cue, not a command. | We start getting user reports of confusion on first run or after a reinstall. |

### 5e. Context-window error handling

| Item | Current state | Gap | Revisit when |
|---|---|---|---|
| **D24** Provider-specific context-window errors → auto-compact | Provider errors propagate up from `loop.cljs` / `core.cljs`. Verify whether nyma catches "context length exceeded" class errors and triggers compaction before failing. | Dirac's `context-error-handling.ts` has per-provider error matchers. If nyma relies only on pre-emptive compaction (at 85% threshold), an unexpectedly long prompt can exceed the window without triggering a compact. | A user reports "session failed after a long tool output" — likely a context-exceeded crash that should have auto-compacted. |

### 5f. Testing infrastructure

| Item | Current state | Gap | Revisit when |
|---|---|---|---|
| **D34** Session-replay test harness | `test/cli_integration.test.cljs` covers end-to-end scenarios, but there's no deterministic replay of full LLM-in-the-loop sessions. | Dirac's `controller/grpc-recorder/` records and replays controller traffic. For nyma the equivalent would be a fixture that captures `generateText` request/response pairs and replays them in tests. Useful for regression testing agent behavior across refactors. | We want to regression-test end-to-end agent behavior and snapshot-based tool assertions aren't enough. |
| **C11** Three-arm eval harness (baseline / terse / skill) | No eval harness for skills/extensions today. | Borrow caveman's `evals/` pattern — run each skill against three conditions (no prompt / generic-terse prompt / skill prompt) to isolate whether a skill actually adds value beyond generic brevity. Useful before shipping any behavior-modifying skill. | We add a second behavior-modifying skill and need to validate it doesn't just duplicate a terse-prompt effect. |

### 5g. Skill/extension authoring conventions (docs only)

Two patterns from caveman that are **not code** — they're authoring conventions to document in `docs/extension-guide-cljs.md` and the skill frontmatter spec:

- **C4 "Auto-Clarity" overrides**: an optional `overrides:` block in skill/extension frontmatter listing named contexts where the skill's behavior rule temporarily suspends (security warnings, irreversible action confirmations, multi-step sequences, user confused or repeating question). Cleaner language than "be careful."
- **C5 "Boundaries"**: an optional `boundaries:` block listing contexts where the rule must **never** apply (code blocks, commit messages, diffs). Prevents a style rule from bleeding into code output.

**Action:** next time we touch `docs/extension-guide-cljs.md`, add a section documenting these as recommended optional frontmatter fields. No parser work needed — the skill system should simply preserve unknown frontmatter fields so skill authors can declare them and reference them in their body prose.

### 5h. Already covered

These items showed up in the research but are already planned or already done — no roadmap entry needed:

- **D13** Subagent system → extension-ideas #22 (augmented with dirac's specific design points)
- **D14** Custom-agent-as-dynamic-tool → extension-ideas #22
- **D20** Reversible context updates → extension-ideas #8 (augmented)
- **D25** Worktree controllers → extension-ideas #30
- **D27** Plan/Act mode → extension-ideas #27
- **D11** Workflows directory → extension-ideas #28
- **C2/C3** Skill triggers + filtering → extension-ideas #29
- **D29** Piped stdin → extension-ideas #33
- **D32** Per-model prompt variants → extension-ideas #31
- **D35** /deep-planning with variants → extension-ideas #32
- **D36** Hash-anchored edits → extension-ideas #26 (augmented)
- **D30** Yolo mode → extension-ideas #11 (approval profiles)
- **D22/C10/D2/D33/D15** Tier 1 plan → `docs/plan-borrows-caveman-dirac.md`

---

## 6. Deferred /spec evolution

### 6a. Per-task orchestrated implementation mode (`/spec drive`)

**Problem this solves:** today `/spec start` is a passive context flag — it activates `:active-spec` and adds a `context_assembly` hook that appends spec/plan/tasks to the system prompt. The model is then expected to implement the entire feature in free-form turns. Two failure modes:
1. Big features blow through `max-steps` (currently 100), forcing the user to manually type "continue" repeatedly.
2. The model treats tasks.md as documentation and forgets to mark items `[x]` as it goes (compounded by the `skill_content.cljs` guidance that explicitly tells it not to flip checkboxes).

The two fixes landed alongside this entry — skill-prompt flip + next-task callout (Pain 2), and `finish_reason` exposure + auto-continue on `length` (Pain 1) — close most of the gap. They don't solve the underlying mismatch: a free-form mega-run is the wrong control flow for a feature whose tasks are already enumerated. Other agents (spec-kit, Aider, Plandex) orchestrate per-task.

**Design sketch:**

A new mode (`/spec drive [name]` or `/spec start --orchestrated`) that actively pumps the work:

1. Find next open task via existing `next-open-task` (`spec_driven/index.cljs:268`).
2. Enqueue a focused follow-up: `"Implement: <task-text>. When done, mark it [x] in tasks.md and stop. Do NOT start the next task — the orchestrator handles that."`
3. On `agent_end`, if the spec still has open tasks: enqueue the next one. If no open tasks: emit `spec_drive_complete`.
4. Each task gets a fresh step budget (max-steps resets per loop entry — already true, just unused for this purpose).
5. Soft cap on iterations (e.g., 20 tasks/session) with a "continue driving?" prompt.

**Wiring:** the building blocks already exist:
- `spec_task_start` and `spec_task_complete` events are already emitted by `/spec next` and `/spec done` (`index.cljs:1221–1281`). The orchestrator subscribes.
- `agent.loop/follow-up` is the canonical cross-mode injection path (proven by `/spec import` and recently-shipped advisor extension).
- After landing items 1+2 above, `agent_end` carries `finishReason` so the orchestrator can distinguish natural stop from budget hit and decide whether to continue the same task or advance.

**When to do it:**
- After items 1+2 ship, watch real sessions for "/spec start drives a 12-task feature smoothly" vs "still hitting limits / still forgetting tasks." If the prompt-only fixes don't hold, /spec drive is the durable answer.
- Concrete trigger: 3+ user reports of "/spec start ran out partway through" after the prompt fixes are in.

**What's NOT planned:** auto-applying changes without per-task user review, parallel task execution, or any sub-agent / worktree integration. Those are independent ideas (extension-ideas #22, #30); /spec drive is sequential and stays inside the current agent loop.

### 6b. Make the phase model do something (phase-tagged tasks)

**The finding (2026-09-04).** The phase currently affects exactly one thing —
which role is bound — and it changes almost never. Three facts, each from the
code as it stands after the phase loop shipped:

1. `continue-prompt` is one constant string in all four phases (*"do the NEXT
   unchecked task"*). Deliberately constant: a varying prompt costs ~2x on a
   cold cache, measured.
2. `build-spec-context` never tells the model which phase it is in, and
   `spec_phase_enter` has no consumer outside `spec_driven`.
3. `tasks.md` is a flat ordered checklist — the import seed asks for exactly
   that — so no task belongs to a phase.

Combined with `decide` returning `:continue` for `:in-progress`, a phase
advances only at **100%** completion. So the entry phase is not the first of
four, it is the ONLY one for the whole run, followed by three instant advances
through phases with nothing left to do. `routed` is not a plan → execute →
verify → ship pipeline; it is "pick one role, then say the other names on the
way out".

Entering at `execute` for imported plans (shipped) makes the common path cheap,
but it does not make the model real.

**Design sketch.** Sections in `tasks.md`, one per phase:

```markdown
## execute
- [ ] Add TokenStore in src/auth/store.ts
- [ ] Wire the callback in src/auth/routes.ts

## verify
- [ ] Review the store for per-request scope
```

- the import/decomposition seed emits the sections
- `parse-tasks` attributes each task to the heading above it
- `progress` is computed per phase; `decide` advances when a SECTION completes
- **fallback is today's behaviour**: an unsectioned file means every task
  belongs to the current phase, so a cheap model that ignores the format
  degrades rather than breaks

Only in this shape does `deep` actually review what `fast` wrote, which is the
thing the profile table has always implied.

**Caution before building it.** `verify_gate` already holds the loop when the
build is red, in every phase. So a `verify` phase is not "run the tests" — it
is "have a better model review what the cheap one wrote". Worth having, but a
different claim, and worth being explicit about rather than letting the phase
name imply the gate.

**When to do it:** after a captured plan has been run end-to-end on a cheap
model at least once. Building a richer phase model on top of one just found to
be inert is the wrong order, and the cold-execution gate (can a cheap model
follow a captured plan at all?) is still unrun.

### 6c. Or delete phases entirely

The honest fallback if 6b turns out to be more ceremony than value on real
work: a profile becomes a single role plus the `verify_gate` escalation that
already exists. Deletes the phase order, `set-phase!`, `entry-phase`, the
advance branch and the phase segment of the status line.

Worth keeping on the table explicitly, because the alternative failure mode is
carrying a four-phase vocabulary that only ever binds one role — which is what
shipped, and what nobody noticed until the roles were traced by hand.

---

## 7. Deferred ACP improvements (`agent_shell` extension)

The `agent_shell` extension implements ACP (Agent Client Protocol — Zed's JSON-RPC stdio standard for editor↔agent integration). Coverage is broad — `initialize` / `session/{new,list,load,prompt,cancel,set_mode,set_config_option,set_model}` / 11 `session/update` notification kinds / 9 reverse-request handlers (`fs/*`, `terminal/*`, `session/{request_permission,elicitation}`) / 6 backends (Claude Code, Gemini CLI, OpenCode, Qwen, Goose, Kiro). Audited in this session against the current ACP spec; gaps below.

**Already shipped in this session:**
- Stderr file logging (`~/.nyma/logs/agent-shell-<agent>.log`) — replaces silent drop in `client.cljs` `setup-stderr-handler`
- NDJSON parse failure + write-failure logging via `dbg/warn`
- Capability gating helper `shared/agent-supports?` + gating on `/sessions list` and `/sessions resume` against the agent's declared `loadSession` capability

### 7a. Diff rendering for `tool_call_update`

- **Status:** content captured (`notifications.cljs:49–81`), rendered as raw text.
- **Problem:** when a backend (Claude Code, OpenCode, etc.) emits a structured file-edit diff in a `tool_call_update`, the user sees a blob instead of a syntax-highlighted unified diff. Big UX miss for code-heavy sessions — most agents using `agent_shell` are *here* to do code edits.
- **Approach:** chat_renderer already has diff infrastructure for built-in tools. Wire the ACP path through it: detect `tool_call_update` payloads with `diff` / `oldText` / `newText` shapes, route to the existing diff renderer.
- **Estimate:** half a day.

### 7b. `session/fork` support

- **Status:** not implemented.
- **What it is:** ACP TS SDK v0.18+ exposes `ForkSessionRequest` — branch an existing session for what-if exploration without polluting the original transcript.
- **Why interesting:** pairs naturally with the `/spec drive` deferred plan (6a) — fork before each task so a failed implementation rolls back cleanly.
- **Approach:** new client method, picker UI for "fork from <session>", persist the fork ID alongside the parent.
- **Estimate:** half a day.

### 7c. Per-tool / per-path permission rules

- **Status:** single global `auto-approve` toggle (`features/permission_ui.cljs`). On or off, applies to all backends.
- **What's missing:** no per-tool, per-mode, per-path-prefix, or regex-based approval rules. Spec lets clients send `allow_always` for repeat patterns; we only ever send `allow_once` (or pick the agent's recommended `allow_always` option as-is).
- **Blocker:** `handlers.cljs:20` carries a long-standing TODO — permission resolution is currently synchronous to meet JSON-RPC response timing, which prevents an `emit-collect`-based pipeline where extensions vote on each request. Refactoring to async resolution is the prerequisite, not the rule engine itself.
- **Approach:**
  1. Add a permission-decision pipeline that runs `emit-collect "acp_permission_request"` and awaits the result before responding.
  2. Define a settings schema for rules: `{tool: <name|regex>, path: <prefix>, mode: <plan|edit|yolo>, decision: allow|deny|prompt}`.
  3. Per-agent `auto-approve` scoping (today it's global — Goose-on-trust + Claude-Code-on-stranger's-repo currently share one toggle).
- **Estimate:** 1–2 days.

### 7d. Session persistence across nyma restarts

- **Status:** session IDs held only in memory via `(:session-id conn)` (`pool.cljs:42, 97`).
- **Problem:** restart nyma → every backend session goes orphan. The selector picker (`features/session_mgmt.cljs`) shows the agent's history but nyma can't auto-reattach the one we were just using.
- **Approach:** persist `{agent-key → session-id}` map under `~/.nyma/agent-shell-sessions.json`; on `session_ready`, attempt `session/load` for the previous session before falling back to `session/new`.
- **Caveats:** must be capability-gated (depends on `loadSession` — already covered by 7.b above and the shared helper we shipped).
- **Estimate:** 1–2 days.

### 7e. Capability-driven behavior beyond `loadSession`

- **Status:** infrastructure ready (`shared/agent-supports?` helper, capabilities recorded at handshake) but only `loadSession` is consulted today.
- **Outstanding gates:**
  - `promptCapabilities` (image / audio / embedded-context blocks) — we currently only send text, but if a future feature wants to forward an image we should refuse on agents that don't claim support rather than letting the agent reject mid-stream.
  - `mcpCapabilities` (`http`, `sse`, `stdio` transports) — today we forward every discovered MCP server regardless of transport. Should filter to what the agent supports.
  - Optional reverse-request handlers — if an agent doesn't implement `terminal/*`, advertise our ability anyway (we do today) but don't expect the agent to ever call it.
- **Estimate:** half a day, mechanical now that the helper exists.

### 7f. Multimodal block handling in `session/update`

- **Status:** non-text blocks (image / document content parts in agent messages) stripped to text/JSON.
- **Lower priority:** few of the supported backends emit multimodal blocks today, and the user impact is "noise in chat" rather than "broken feature." Address if it becomes a frequent complaint.

### 7g. Per-call permission UX (custom titles / descriptions)

- **Status:** permission UI hardcoded to display `(name + kind)` only (`handlers.cljs:25–57`).
- **What's missing:** spec allows agents to attach custom titles + descriptions per permission request. We drop them — user sees less context than the agent intended to send.
- **Trivial fix:** ~5 lines in `handle-permission-request` to pass the description through.
- **Bundle with:** ship alongside the 7c async-permission refactor; doing it standalone wastes the touch.

### 7h. Backends to add

Today's agents (`agents/registry.cljs`): Claude Code, Gemini CLI, OpenCode, Qwen, Goose, Kiro. The Zed ACP registry is growing — Codex CLI, GitHub Copilot CLI, etc. Adding a new backend is mostly a registry entry + per-agent quirks (model-method, init-mode, prompt format). Track requests in `extension-ideas.md` rather than here unless an agent has spec-level peculiarities.

## 2026-07-15 sweep — deferred SOTA items

From the full audit + SOTA research round (bugs and quick wins landed; these did not):

- **Compaction PreCompact event** — let extensions preserve state across compaction; re-inject memory/AGENTS.md after.
- **Sandbox extension** — route bash through `@anthropic-ai/sandbox-runtime` or Gondolin micro-VM with egress allowlist; enables safe full-auto with fewer prompts.
- **MCP hardening** — tool-description pinning (poisoning detection), per-tool permissions, instruction-pattern flagging on untrusted results.
- **Goal loop `/goal`** — run until a written condition passes, small-model grader per turn (small_model ext is the natural grader).
- **Permission modal consuming tool_metadata** — the safety table is still written-but-unread (T11/1d).
- **Extension enable/disable mechanism** — manifests have no honored `enabled`; only NYMA_NO_BUILTIN_EXT global.
- **Live /theme re-render** — watch-theme exists; widgets bake theme at construction.

## 2026-07-22 round-2 audit — deferred

- **Provider factory** — ~700 lines duplicated across 8 OpenAI-compat providers (`read-credentials-file` copied 7×); a shared `openai-compat-provider` factory would collapse each to ~15 lines.
- **Session storage** — JSONL grows unbounded (sync append per tool result), JSONL⊕SQLite double-write with no consistency guard, no multi-instance locking (`--fork/--resume` makes races real).
- **Submit-path dedup** — bash/eval branches in interactive.cljs are ~25 near-identical lines each; extract a side-channel dispatcher.
- **UX** — streaming token counter; collapsible thinking blocks (think_tag_parser parses, renderer has no fold state).
- **ACP (§7)** — all prior gaps re-confirmed open; plus acp_* events emit mixed #js/clj->js shapes (latent interop variant, consumer audit needed).
- **Server-side compaction** — Anthropic `compact-2026-01-12` beta is now the recommended path; needs raw-header injection (consider a pi-style `before_provider_headers` extension hook).
- **Cache-friendly deferred tool loading** — register MCP/rare tools after the cached prefix (validated by Codex tool-search default-on). UPDATE 2026-07-27: Anthropic's `mid-conversation-tool-changes-2026-07-01` beta makes this cache-safe first-party — prototype against it.

## 2026-07-22 review leftovers (structural, not quick fixes)

- **expired_context / smart_compaction hook D are structurally dead** even after the event fix:
  their context_assembly walks look for role tool_call/tool_result messages, but state :messages
  never contains those roles (sessions/manager.cljs:25 invariant) — needs either tool results in
  assembled context or reading from the session store. Also expired_context keys staleness off
  turn_end (per STEP) vs assistant-message counting — two numbering schemes that never align.
- **emit-js! boundary helper** — event-shape stragglers remain (session_before_switch/
  session_switch/session_before_fork kebab; acp_* mixed #js/clj->js; emit-collect collection-keys
  mix kebab and camel). One helper + a payload-shape lint would make the convention structural.
- **Settings-section config reader** — 4th copy landed (budget); extract a shared
  `(section-config settings "name" defaults)` helper; note openwiki's boolean `some?` variant.
- **Budget ledger unification** — budget keeps its own token totals; consider deriving from the
  store's :usage-updated totals (getContextUsage) so caps and /stats agree.

- **Budget via stopWhen** — budget currently aborts via AbortController, indistinguishable from
  Esc-cancel downstream; a loop hook letting extensions contribute stopWhen predicates would give
  clean finishes (finishReason set, turn_finalize normal). Note: /tokens "misses" now counts only
  annotated-prefix-not-served misses (changed semantics, intentional).

## 2026-08-18 — escalation + refine leftovers

Landed: `/refine` (deterministic session mining), escalation (`/escalate` + provider failover in
`model_roles/features/escalate.cljs`), and the review fixes around them. Deferred:

- **Empty-turn detection is hooked where it cannot see the case it names.**
  `quality_monitor`'s empty-turn abort rides `stream_filter`, which only fires on `text-delta`
  parts (`loop.cljs:12-20`) — a turn that emits no text at all never reaches it. Until the
  `textDelta`→`text` fix it was firing on the FIRST delta of every turn instead (both fields were
  empty), so this was masked by being catastrophically wrong in the other direction. The real home
  is the turn boundary, where `:no-op-turns` now counts correctly; moving it there trades the
  stream-abort-and-retry capability for a follow-up message.
- **`escalate.revert: "cooldown"` is failover-only.** The capability tier implements
  `next-request` and `never`; a `cooldown` value at the top level would silently behave as `never`.
  Either implement it or reject the value at config read.
- **opusplan is invisible in the status line.** `plan_mode` swaps the planner model via `setModel`
  while the badge renders the *role* name, so you cannot tell which model answered a planning turn.
  Escalation added `model-roles.escalated` (a segment fed by the overridden model spec) — plan mode
  should reuse that pattern.
- **`escalate` defaults live in the extension, not `settings/manager.cljs`.** Deliberate (one
  source, no drift), but it means the README's "source of truth is `defaults` in manager.cljs" note
  does not cover this section. If a settings-section reader helper is ever extracted (see the
  2026-07-22 leftovers), fold this in.
- **Escalation is main-loop only.** Subagents run on their own bus with their own model objects
  (`subagent/index.cljs:133-160`), so neither the stall trigger nor provider failover applies to a
  child agent — the case where a cheap model flails unattended.

## 2026-08-18 — first measured numbers (`bench/`)

`bench/` now runs Aider-Polyglot exercises and grades them with the exercises' own tests. First clean
baseline, 10 sampled Python tasks, role `build` (minimax/minimax-m2.5), 420s/task:

**80% — 8 pass, 0 fail, 2 timeout, 0 error.** $0.218, 2.14M tokens.

What the number teaches, beyond the number:

- **Zero genuine failures.** Every non-pass was wall clock, not wrong code. The score currently
  measures the latency budget as much as capability; `transpose` finished in 280s on one run and blew
  through 420s on the next.
- **Single-trial numbers swing.** The same command on the same seed scored 100% earlier and 80% here.
  Nothing below ~3 trials should be used to compare two configurations, and the runner now says so
  when it prints a one-trial result.
- **Timeouts are reported separately from failures** in the headline for exactly this reason.
- **Next:** a full 34-task run to find where the model actually breaks (the hard tasks become the
  signal-bearing subset), then flip `small-model.enabled: true` and diff on the same seed. The
  measurement rig now exists, so both are cheap.
- **Blocked for the free tier:** the `fast` role points at `opencode-zen/north-mini-code-free`, which
  the provider rejects ("Model north-mini-code-free is not supported") and which is absent from
  nyma's own catalogue (`custom_provider_opencode_zen/index.cljs:27-31`). Benchmarking the cheap tier
  needs that role repointed at a model that exists.

### Full Python subset: 97.1% — the local loop has no headroom on this model

Whole set, 34 tasks, role `build` (minimax/minimax-m2.5), 420s/task: **33 pass, 0 fail, 1 timeout**
(`zebra-puzzle`). $0.98, 9.5M tokens, 46 min wall, median 40s/task.

**Zero genuine failures across the entire subset.** The 80% sampled baseline an hour earlier was two
tasks running out of wall clock, not two tasks the model got wrong. For minimax-m2.5, Aider-Polyglot's
Python half is solved, which means:

- The fast local loop can detect **regressions** and nothing else. Turning `small-model.enabled` on
  and re-running it would measure nothing, because there is no room above 97%.
- Measuring an improvement needs an un-saturated subject. In order of cost:
  1. **A weaker model** — blocked until the `fast` role points at a model that exists.
  2. **The local models** (`local-models` ds4 / vllm) — the population the small-model layer was
     written for, and the one where its predicates actually fire.
  3. **Terminal-Bench Core via Harbor** — deliberately deferred when the plan was written; this is the
     evidence that it is now the right next investment. little-coder's own progression was Polyglot
     first, Terminal-Bench once Polyglot stopped discriminating.
- Timeouts are the only variance source, and they are latency, not capability: `transpose` took 280s,
  400s and >420s across three runs of the same task.

### small_model measured: 10% → 100% after two one-line shape bugs

Same ten tasks, same seed, `openrouter/qwen/qwen3.6-35b-a3b` pinned to Parasail, 420s/task:

| Config | Score | Breakdown | Wall | Tokens |
|---|---|---|---|---|
| extension OFF | 80% | 8 pass, 0 fail, 1 timeout, 1 error | 31 min | 1.8M |
| ON, before fixes | 10% | 1 pass, 5 fail, 4 timeout | 58 min | 4.9M |
| ON, after fixes | **100%** | 10 pass, 0 fail, **0 timeout** | **13 min** | 1.5M |

The cause was `getAllTools` returning an ARRAY of names while two consumers ran `Object.keys` on it,
producing `#{"0" "1" "2" …}`:

- `quality_monitor` treated every real tool as hallucinated and replaced every tool result with a
  scolding message listing "available tools: 0, 1, 10…".
- `custom_provider_local` fed the same set to the tool-call rescue parser, which validates rescued
  calls against it — so the rescue silently discarded everything, on the local models it exists for.

Lessons worth keeping:

- **The repeat-call fix committed just before this was unreachable code** — the hallucination branch
  matched first, and its `:else` (which populates the signature set) never ran. A fix can be correct,
  tested, and still dead.
- **Its test passed because the mock was an object and production returns an array.** The harness
  disagreed with production on exactly the value that was broken.
- Two independent consumers made the same mistake against the same ambiguous return shape. Either
  `getAllTools` should return one documented shape, or every consumer needs the tolerant helper both
  now use.

**Caveat on the 100%:** single trial, and the OFF baseline's misses were timeouts on tasks that pass
in other runs — so the honest claim is "the layer is no longer harmful and is now at least at
parity", not "+20pp". The task set is also saturated for this model again, so Part B (tool/prompt
compression) cannot be measured here: it needs a weaker model or a harder set.

### The small-model layer helps the capable model, not the flaky one

Same ten tasks, seed 7, 420s/task, after the getAllTools fixes:

| Model | OFF | ON |
|---|---|---|
| `openrouter/qwen/qwen3.6-35b-a3b` (pinned) | 80% (8 pass, 1 timeout, 1 error) | **100%** (10 pass), 13 min, 1.5M tokens |
| `opencode-zen/nemotron-3.5-lightning-free` | 50% (5 pass, **5 timeout**), 48 min | 50% (5 pass, 1 fail, 2 timeout, **2 provider 504**), 38 min |

Nemotron's score did not move, but the failure composition did: five of our own 420s timeouts became two
timeouts, two upstream 504s ("Streaming response failed: [504] Upstream idle timeout exceeded") and one
wrong answer. Two of the four non-passes are the provider dropping a quiet stream, not the scaffold.
Single trial; treat the equality as "no measurable effect on this model", not as proof of none.

Standing conclusions for timeout work:

- **Timeouts are the dominant failure for weak models, and they are not all ours.** Our 420s cap,
  opencode-zen's upstream idle timeout, the provider's own request timeout and per-tool timeouts are
  four unsynchronised clocks. The 504s are only visible at all because the runner now records a reason.
- **Token grinding is real**: on the OFF baseline `grep` PASSED while burning 5.9M tokens, `zipper`
  2.4M. ProjDevBench reports ~4.81M tokens per problem at *project* level; these are 40-line katas.
- **`quality-monitor.max-turns` is the reminder without the cap.** It nags "summarise and stop" every
  turn past 40 and never stops anything. [More with Less](https://arxiv.org/html/2510.16786) measures
  the shape that works: a fixed cap at the 75th percentile of that model's own baseline turn count cuts
  cost 23-68% for 0-5% accuracy, and a dynamic cap (start at the 25th, one-time extension to the 50th)
  saves a further 12-24% at equal or better solve rates — with "you have N turns left" reminders.
- **[BAGEN](https://arxiv.org/html/2606.00198v1)**: agents are systematically optimistic about
  remaining budget (>70% confidence after burning 60%), but acting on a self-declared "impossible"
  saves 28-64% of tokens on failed trajectories for 1.6-4.2pp of success.
- **[Timely Machine](https://arxiv.org/pdf/2601.16486)**: time awareness alone is modest; awareness
  coupled to a real cap is what changes behaviour. So do not ship the injection without the budget.
- Setting percentile-based caps needs per-model turn counts, and **print mode does not report a turn
  count at all** — that is the first blocker.

### Prompt caching: the untested lever, and a number nyma reports as a lie

[Don't Break the Cache](https://arxiv.org/pdf/2601.06007) names three cache killers: system prompts
modified between turns, unstable message formatting, dynamic tool definitions. nyma does all three —
`lsp_suite` diagnostics, `repo_map` (`reindex-on-edit`), `todos`, `memory`, `handoff`, `evidence`,
`self_tune`, `knowledge_inject`, `add_dir`, `bash_suite` and `context_folding` all append to the
**system prompt**, which is the cached prefix (`loop.cljs:117-128`), and `profiles`/`plan_mode`/
`token_suite` reshape the tool list per turn. Reported hit rates elsewhere: Claude Code 92.7%,
OpenClaw 23.8%; typical savings 40-55% of input cost.

**`print.cljs:70` hardcodes `cache_read_input_tokens: 0`** in the result object while `loop.cljs:424`
reads the real figure. So the benchmark cannot see cache activity at all, and any `-p --output-format
json` consumer is told there is none. Fix that before drawing any conclusion about caching.

Design constraint this puts on the timeout work: an elapsed/remaining line injected into the SYSTEM
prompt every turn would break the cache on every turn. Volatile content goes after the cache
breakpoint, at the end of the messages.

### Cache hit rate: measured 76-88%, and the suspects were all innocent

`print.cljs` hardcoded `cache_read_input_tokens: 0`, so this had never been looked at. With it wired:

| Config (python/wordy, qwen3.6-35b-a3b pinned) | steps | hit rate |
|---|---|---|
| baseline | 11 | 77.3% |
| via headroom proxy (CacheAligner + compression) | 11 | 76.4% |
| baseline, repeat | 13 | 88.0% |

Three hypotheses tested and dropped:

1. **Headroom proxy mode does not help here.** It compressed 11 of 12 requests at **4.2% average** (its
   README advertises 47-92% on search results and logs; our context is code and test output already
   pruned by token_suite) and reported `cache_savings_usd: 0.0`. Its CacheAligner normalises
   Anthropic-style `cache_control` breakpoints; OpenRouter's caching here is provider-side at Parasail
   and not ours to align.
2. **Compaction is not invalidating the prefix.** 650 compaction lines in the debug log look alarming
   until you bucket them: exactly 11 per minute, at times matching `bun test` runs. They are the
   compaction test suite. Zero real-session compactions.
3. **The eleven system-prompt injectors are unproven as a cause.** The blunt ablation
   (`NYMA_NO_BUILTIN_EXT=1`) cannot answer it — provider extensions die with them and the agent has no
   model at all ("No model configured"). A targeted ablation needs per-extension disable, which nyma
   does not have.

Run-to-run variance (77.3 vs 88.0 on identical config) is larger than any effect measured so far, so
nothing below ~10pp is worth acting on without `--trials 3`. And 76-88% may simply be the ceiling for
an 11-step task: what cannot be cached is each turn's new tool output, and Claude Code's 92.7% comes
from far longer sessions where the cached prefix dwarfs the new content.

### On "matching little-coder"

Not comparable yet, and no amount of tuning fixes that. Their 45.56% (Qwen3.5-9B) and 78.67%
(Qwen3.6-35B-A3B) are the **full 225-task Aider Polyglot set across six languages**. Ours is a
10-task Python sample on which qwen3.6-35b-a3b already scores 80-100%. To make the numbers mean the
same thing:

- **Python**: 34 tasks, runnable now (stdlib unittest, no install).
- **JavaScript**: 49 tasks, needs `npm install` per exercise (jest + babel).
- **Go, Java, C++**: toolchains present on this machine — adapter work only.
- **Rust**: `rustc` MISSING — install or skip and say so.

That is the honest next step for comparability: expand the runnable set, not tune against a saturated
subset.
