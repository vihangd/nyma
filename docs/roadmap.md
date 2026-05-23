# Roadmap — Infrastructure Ready, Deferred, and Watch Items

A living document for anything that's been **built but not yet wired up**, or **deliberately deferred**, so nothing falls through the cracks between sessions. Distinct from `plan-*.md` files, which are per-feature plans. This is the "don't forget" ledger.

Last updated: 2026-04-20 (after LSP suite + submit-guard fixes).

---

## 1. Infrastructure ready, not yet wired

Every item in this section is **fully implemented and tested** — the module exists, the unit tests pass, the public API is documented. What's missing is the callsite edit that flips over from the legacy path to the new one. Each is low risk because the old path still works.

### 1a. Chord keybinding resolver → `app.cljs` global input handler

- **What's ready:** `src/agent/keybinding_resolver.cljs` with `resolve-key` (typed single-key) and `resolve-key-with-chord` (multi-keystroke with `pending` state). Full coverage in `test/keybinding_resolver.test.cljs` (21 tests including the escape-meta regression and every chord path).
- **What's wired:** nothing in production. `src/agent/ui/app.cljs:436` still calls `kbr/matches?` in a cond of single-action checks.
- **Why we stopped:** no binding in `default-actions` uses a chord yet, so rewiring would be infrastructure-for-infrastructure's-sake. We'd rather wait until there's a real use case.
- **When to do it:**
  - We want a second keystroke binding like `ctrl+k ctrl+s`, OR
  - We hit another `matches?`-based bug that a typed result (`:match` / `:unbound` / `:none`) would have caught.
- **Rough plan:**
  1. Add a `pending-chord` atom to the App component's state.
  2. Replace the cond of `matches?` calls in the global `useInput` with a single `(resolve-key-with-chord reg input key @pending-chord)` dispatch.
  3. On `:chord-started`, reset! the atom to `(:pending result)`; on anything else, reset to nil.
  4. Render a chord indicator in the status line when `@pending-chord` is non-nil (cc-kit does this).
- **Files to touch:** `src/agent/ui/app.cljs` around line 432-455, possibly a new status-line segment for the chord indicator.

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

### 1c. Debug logger adoption across the codebase

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

### 1d. Per-tool safety metadata → permission flow

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
| **T2** AbortSignal plumbing | `core.cljs:40` abort-controller + `middleware.cljs:34` enrichment + `app.cljs:437` Esc binding (caveat: verify bash_suite consumes it) |
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

Patterns we've hit repeatedly this session. If any of these shapes shows up again, the fix is usually well-understood.

### 3a. "Registry written but nobody reads it"

A feature that declares a registry-backed API but has nothing consuming the registry at render time. We hit this with `message-renderers` (dead code, removed in Phase 1) and with the ACP `registerStatusSegment` → `StatusLine` auto-append chain (fixed by adding auto-append support).

**Test smell:** unit test registers an item and checks the atom; no test asserts the item appears in a rendered frame. Every future registry-backed API should have a matching end-to-end test in `test/extension_registration_e2e.test.cljs` or similar.

### 3b. "Scoped API forgot to forward a method"

`extension_scope.cljs/create-scoped-api` manually re-exports each method from `base-api`. Forgetting a forward produces a silent `undefined` — an extension calls it, nothing happens, no error. We hit this with `registerStatusSegment`, `registerToolRenderer`, and `registerCompletionProvider` in the ACP debug session.

**Mitigation:** `test/extension_scope.test.cljs` has at least one test per forwarded method. When adding a new method to the base API, add its forwarding test at the same time.

### 3c. "Lifecycle event fires before UI mounts"

`session_ready` emits from `cli.cljs:153` before `interactive/start` mounts the ink app, so any extension handler that reads `api.ui` at `session_ready` time finds it `undefined`. We hit this with `agent_shell/setup-ui!` — the fix was to also call it from user-driven code paths (`agent_switcher`, `handoff`) that run after mount.

**Mitigation:** anything that depends on a live `api.ui` object should run from a user-interaction handler OR use a "try later" guard + retry. Document the contract in any new extension that uses the UI surface.

### 3d. "Picker silently swallows a key"

Every picker we ship reimplements `onInput`. We hit `key.delete` vs `key.backspace` (macOS), we hit `/agent qwen` arg-swallowing, and we hit the Ctrl+P/N navigation gap. Phase 12 consolidated all four pickers through a single `dispatch-input` so the next one-branch fix only happens once.

**Mitigation:** new pickers should use `src/agent/ui/picker_input.cljs` + `src/agent/ui/picker_frame.cljs`. Any branch not covered by the dispatcher (e.g. Shift+Tab for a second `onTab`) means we should extend the dispatcher, not roll a one-off cond.

### 3e. "JSON silently drops data"

`JSON.parse` keeps the last value when a key repeats. We had this latent in `settings/manager/load-json`; the Phase 12 fix scans for duplicates before parsing and emits a `d/warn`. Anywhere else we parse user JSON, we should consider the same scan.

**Known callsites worth auditing:** `keybindings.cljs` (user keybindings file), `.nymaignore` if it's JSON, extension configs loaded from `~/.nyma/`. Not urgent unless a user reports a silently-dropped setting.

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
