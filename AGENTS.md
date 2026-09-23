# NYMA — Nyma Yokai Mystic Agent

## Project Overview

NYMA is a minimal, extensible coding agent built with:
- **Squint** — ClojureScript-to-JS compiler (threading, destructuring, plain ES modules out)
- **Bun** — runtime, package manager, native TypeScript loader
- **Vercel AI SDK (`ai`)** — LLM streaming, tool loop, provider abstraction
- **@earendil-works/pi-tui** — terminal UI (chat pane, editor, overlays, status bar), plain string-rendering components

## Architecture

```
@agent/cli → @agent/core → ai (Vercel AI SDK)
                        ↓
              @agent/ui (pi-tui components)
```

Tool execution now flows through the **middleware pipeline** (interceptor chain). Extension namespacing and capability gating isolate plugins. Agent state mutations go through the **event-sourced state store**.

```
user input → loop.cljs → middleware pipeline → tool.execute
                              ↑
                    registered interceptors
                    (logging, before-hook-compat, custom)
```

### Key Namespaces

Generated from each namespace's docstring by `bun run gen:namespaces` (drift-checked by
`test/namespaces_table.test.cljs`). A "—" purpose means the namespace has no docstring yet.

<!-- generated: namespaces (bun run gen:namespaces) -->
| File | Namespace | Purpose |
|------|-----------|---------|
| `src/agent/cli.cljs` | `agent.cli` | Entry point, arg parsing, mode dispatch. |
| `src/agent/commands/builtins.cljs` | `agent.commands.builtins` | Built-in slash command implementations (/help, /model, /clear, /skill, /skills, /resume, /export, etc.). |
| `src/agent/commands/parser.cljs` | `agent.commands.parser` | Pure command-line parsing + suggestion helpers. |
| `src/agent/commands/resolver.cljs` | `agent.commands.resolver` | Command resolution with namespace-prefix fallback. |
| `src/agent/commands/share.cljs` | `agent.commands.share` | Session export to Markdown and HTML. |
| `src/agent/context.cljs` | `agent.context` | Message filtering, context building. |
| `src/agent/core.cljs` | `agent.core` | Agent state, `create-agent` factory. |
| `src/agent/debug.cljs` | `agent.debug` | Unified debug logger for nyma. |
| `src/agent/dev/event_map.cljs` | `agent.dev.event-map` | Generates docs/event-map.md: every core event with its emitters and its listeners, and every extension-API registry with its producers an… |
| `src/agent/dev/ext_watch.cljs` | `agent.dev.ext-watch` | Save-to-live for extensions on disk. |
| `src/agent/events.cljs` | `agent.events` | Typed event bus (sync + async). |
| `src/agent/extension_context.cljs` | `agent.extension-context` | — |
| `src/agent/extension_loader.cljs` | `agent.extension-loader` | Dual .cljs/.ts loader with scoped APIs. |
| `src/agent/extension_scope.cljs` | `agent.extension-scope` | Namespaced + capability-gated extension API. |
| `src/agent/extension_state.cljs` | `agent.extension-state` | Persistent per-extension state stored in .nyma/ext-state/{namespace}.json. |
| `src/agent/extensions.cljs` | `agent.extensions` | Extension API factory. |
| `src/agent/file_access.cljs` | `agent.file-access` | File access restrictions via .nymaignore (gitignore-style patterns). |
| `src/agent/interceptors.cljs` | `agent.interceptors` | Pedestal-style interceptor chain: enter left-to-right, then unwind right-to-left running :leave — or :error while the context carries an… |
| `src/agent/keybinding_registry.cljs` | `agent.keybinding-registry` | Action-ID keybinding registry. |
| `src/agent/keybindings.cljs` | `agent.keybindings` | Loads `~/.nyma/keybindings.json` user key mappings. |
| `src/agent/loop.cljs` | `agent.loop` | `run`, `steer`, `follow-up`. |
| `src/agent/middleware.cljs` | `agent.middleware` | Middleware pipeline for tool execution. |
| `src/agent/middleware/self_reminder.cljs` | `agent.middleware.self-reminder` | Self-reminder helper — injects a text block into the system prompt every N turns when a configurable action-predicate has not fired. |
| `src/agent/model_info.cljs` | `agent.model-info` | — |
| `src/agent/modes/interactive.cljs` | `agent.modes.interactive` | Pi-tui based interactive mode. |
| `src/agent/modes/pi_rpc.cljs` | `agent.modes.pi-rpc` | Adapter mode that speaks the pi-coding-agent Emacs frontend's JSONL-over-stdio RPC protocol, so the existing `pi-coding-agent.el` package… |
| `src/agent/modes/print.cljs` | `agent.modes.print` | Print mode. |
| `src/agent/modes/rpc.cljs` | `agent.modes.rpc` | JSONL stdio RPC mode. |
| `src/agent/modes/sdk.cljs` | `agent.modes.sdk` | Programmatic SDK mode. |
| `src/agent/multimodal.cljs` | `agent.multimodal` | Multimodal (image) tool results — let a tool return an image a vision-capable model can SEE. |
| `src/agent/permissions.cljs` | `agent.permissions` | Extension capability system. |
| `src/agent/pricing.cljs` | `agent.pricing` | Token cost table + `calculate-turn-cost` for all supported models. |
| `src/agent/providers/builtins.cljs` | `agent.providers.builtins` | Default Anthropic/OpenAI/Google provider factories. |
| `src/agent/providers/catalog.cljs` | `agent.providers.catalog` | Enumerate every model nyma knows about, across all registered providers. |
| `src/agent/providers/model_fetch.cljs` | `agent.providers.model-fetch` | Discover a gateway's model list from its OpenAI-shaped `/v1/models` endpoint, with an on-disk cache. |
| `src/agent/providers/oauth.cljs` | `agent.providers.oauth` | — |
| `src/agent/providers/registry.cljs` | `agent.providers.registry` | LLM provider registry (register/resolve by name). |
| `src/agent/resources/loader.cljs` | `agent.resources.loader` | Resource discovery. |
| `src/agent/resources/skills.cljs` | `agent.resources.skills` | Discovery and activation of `SKILL.md` skill packages. |
| `src/agent/schema.cljs` | `agent.schema` | One data form for a tool's parameters, rendered three ways. |
| `src/agent/sessions/archive.cljs` | `agent.sessions.archive` | Compressed session logs. |
| `src/agent/sessions/compaction.cljs` | `agent.sessions.compaction` | Context window compaction. |
| `src/agent/sessions/listing.cljs` | `agent.sessions.listing` | Scans `.jsonl` session files, returns sorted metadata. |
| `src/agent/sessions/manager.cljs` | `agent.sessions.manager` | JSONL tree session storage. |
| `src/agent/sessions/partial.cljs` | `agent.sessions.partial` | Keep the in-flight assistant response on disk while it streams. |
| `src/agent/sessions/project.cljs` | `agent.sessions.project` | Which project does a session belong to? |
| `src/agent/sessions/storage.cljs` | `agent.sessions.storage` | SQLite-backed session entry store with usage tracking. |
| `src/agent/settings/manager.cljs` | `agent.settings.manager` | Two-scope settings. |
| `src/agent/state.cljs` | `agent.state` | Event-sourced state store. |
| `src/agent/thinking.cljs` | `agent.thinking` | Turn the session's thinking level into provider request options. |
| `src/agent/token_estimation.cljs` | `agent.token-estimation` | — |
| `src/agent/tool_metadata.cljs` | `agent.tool-metadata` | Per-tool safety metadata. |
| `src/agent/tool_registry.cljs` | `agent.tool-registry` | Active/inactive tool management. |
| `src/agent/tool_result_policy.cljs` | `agent.tool-result-policy` | Per-tool result normalization and truncation policy. |
| `src/agent/tools.cljs` | `agent.tools` | Built-in tools: read, write, edit, bash. |
| `src/agent/ui/app_reducers.cljs` | `agent.ui.app-reducers` | Pure reducers for the interactive TUI state. |
| `src/agent/ui/chat_pane.cljs` | `agent.ui.chat-pane` | Pi-tui Component for the chat message list. |
| `src/agent/ui/chat_renderer.cljs` | `agent.ui.chat-renderer` | Pure: message map → string[] for pi-tui rendering. |
| `src/agent/ui/crash_recovery.cljs` | `agent.ui.crash-recovery` | Survive a pi-tui render crash instead of losing the session to it. |
| `src/agent/ui/diff_lines.cljs` | `agent.ui.diff-lines` | Line diff for the expanded `edit` view. |
| `src/agent/ui/editor_bash.cljs` | `agent.ui.editor-bash` | Editor bash mode — executes `!cmd` and `!!cmd` typed directly into the prompt editor. |
| `src/agent/ui/editor_eval.cljs` | `agent.ui.editor-eval` | Editor eval mode — evaluates `$expr` and `$$expr` typed directly into the prompt editor via a one-shot `bb -e <expr>` subprocess. |
| `src/agent/ui/editor_exec_util.cljs` | `agent.ui.editor-exec-util` | Shared primitives between editor-bash and editor-eval. |
| `src/agent/ui/file_mentions.cljs` | `agent.ui.file-mentions` | `@path` in the editor: the autocomplete listing behind it and the expansion that runs on submit. |
| `src/agent/ui/fuzzy_scorer.cljs` | `agent.ui.fuzzy-scorer` | Pure fuzzy-matching helpers used by the autocomplete provider. |
| `src/agent/ui/overlay_host.cljs` | `agent.ui.overlay-host` | Backs `api.ui`'s overlay surface with pi-tui's native overlay stack. |
| `src/agent/ui/picker_frame.cljs` | `agent.ui.picker-frame` | Shared string-rendering for filter-picker components. |
| `src/agent/ui/picker_input.cljs` | `agent.ui.picker-input` | Shared keyboard-dispatch for filter-picker-style components. |
| `src/agent/ui/picker_math.cljs` | `agent.ui.picker-math` | Pure boundary math for pickers. |
| `src/agent/ui/skill_picker.cljs` | `agent.ui.skill-picker` | Fuzzy-searchable skill picker component for ui.custom(). |
| `src/agent/ui/status_bar.cljs` | `agent.ui.status-bar` | Pi-tui Component: one-line status bar at the bottom of the screen. |
| `src/agent/ui/status_line_segments.cljs` | `agent.ui.status-line-segments` | Segment registry + 20 built-in segments for the status line. |
| `src/agent/ui/theme_catalog.cljs` | `agent.ui.theme-catalog` | A bundled theme pack: well-known base16 palettes converted to nyma's theme schema. |
| `src/agent/ui/themes.cljs` | `agent.ui.themes` | — |
| `src/agent/ui/think_tag_parser.cljs` | `agent.ui.think-tag-parser` | Parse inline <think>…</think> tags emitted by reasoning models that stream chain-of-thought through the normal content channel (MiniMax M… |
| `src/agent/ui/tree_viewer.cljs` | `agent.ui.tree-viewer` | Session tree browser for `ctx.ui.custom()`. |
| `src/agent/ui/width_guard.cljs` | `agent.ui.width-guard` | Last line of defence between nyma's components and pi-tui's width check. |
| `src/agent/utils/ansi.cljs` | `agent.utils.ansi` | ANSI-aware text utilities (`truncate-text`, `terminal-width`). |
| `src/agent/utils/credentials.cljs` | `agent.utils.credentials` | Read API keys saved by `/login <provider>`. |
| `src/agent/utils/data.cljs` | `agent.utils.data` | Two things every namespace kept re-deriving: reading a config key whose spelling depends on who wrote it, and parsing JSON that may not b… |
| `src/agent/utils/event_json.cljs` | `agent.utils.event-json` | Pure mappers from the AI SDK StepResult nyma hands to `turn_end` into the plain shapes any JSONL consumer (pi-rpc, `-p --output-format st… |
| `src/agent/utils/git_files.cljs` | `agent.utils.git-files` | Synchronous git shell-outs shared by core and extensions. |
| `src/agent/utils/home.cljs` | `agent.utils.home` | The one place nyma asks where HOME is. |
| `src/agent/utils/js_interop.cljs` | `agent.utils.js-interop` | Squint ships `clj->js` but NOT `js->clj`. |
| `src/agent/utils/jsonl_stdin.cljs` | `agent.utils.jsonl-stdin` | Reading a JSONL protocol channel off a stream. |
| `src/agent/utils/markdown.cljs` | `agent.utils.markdown` | — |
| `src/agent/utils/markdown_blocks.cljs` | `agent.utils.markdown-blocks` | Incremental markdown rendering via block-level tokenization. |
| `src/agent/utils/reasoning_request.cljs` | `agent.utils.reasoning-request` | Translate nyma's thinking level into each provider's REQUEST dialect. |
| `src/agent/utils/reasoning_stream.cljs` | `agent.utils.reasoning-stream` | Shared SSE stream rewriter for OpenAI-compatible providers that emit chain-of-thought through provider-specific delta fields the AI SDK d… |
| `src/agent/utils/spill_file.cljs` | `agent.utils.spill-file` | Writing model-visible content to disk, safely. |
| `src/agent/utils/stream_drain.cljs` | `agent.utils.stream-drain` | Draining a subprocess pipe without waiting on processes we do not own. |
| `src/agent/utils/template_args.cljs` | `agent.utils.template-args` | Positional argument substitution for skill bodies and prompt templates. |
| `src/agent/utils/time.cljs` | `agent.utils.time` | Shared time formatting helpers. |
| `src/agent/utils/toolcall_rescue.cljs` | `agent.utils.toolcall-rescue` | Rescue parser — normalize malformed tool-call formats to OpenAI JSON. |
| `src/agent/utils/ui.cljs` | `agent.utils.ui` | Shared UI-capability predicates. |
| `src/agent/utils/validation.cljs` | `agent.utils.validation` | Structured validation warnings with suggestions. |
| `src/agent/version.cljs` | `agent.version` | The version, baked in at build time. |
| `src/gateway/channels/email.cljs` | `gateway.channels.email` | Email channel adapter using imap-simple (IMAP polling) + nodemailer (SMTP). |
| `src/gateway/channels/http.cljs` | `gateway.channels.http` | Generic HTTP webhook channel adapter using Bun's built-in HTTP server. |
| `src/gateway/channels/slack.cljs` | `gateway.channels.slack` | Slack channel adapter using Socket Mode. |
| `src/gateway/channels/telegram.cljs` | `gateway.channels.telegram` | Telegram Bot API channel adapter. |
| `src/gateway/config.cljs` | `gateway.config` | Gateway configuration loading and validation. |
| `src/gateway/core.cljs` | `gateway.core` | Gateway facade — wires channels, session pool, auth pipeline, and agent sessions into a running gateway. |
| `src/gateway/entry.cljs` | `gateway.entry` | nyma-gateway CLI entry point. |
| `src/gateway/loop.cljs` | `gateway.loop` | Gateway message loop — wires inbound messages to the agent and maps agent events back to the response context. |
| `src/gateway/pipelines.cljs` | `gateway.pipelines` | Auth and approval pipelines for the gateway. |
| `src/gateway/protocols.cljs` | `gateway.protocols` | Protocol definitions for the gateway channel system. |
| `src/gateway/session_pool.cljs` | `gateway.session-pool` | Per-conversation session pool with serialized execution lanes, policy-based eviction, and event-idempotency dedup cache. |
| `src/gateway/streaming.cljs` | `gateway.streaming` | Per-channel streaming policies for gateway response contexts. |
| `src/gateway/tools.cljs` | `gateway.tools` | Gateway-common tools available to the agent in gateway mode. |
<!-- /generated: namespaces -->

## Development Workflow

```bash
# Install dependencies
bun install

# Development (two terminals)
npx squint watch          # terminal 1: compile .cljs → .mjs
bun --watch dist/agent/cli.mjs  # terminal 2: run with auto-restart

# Or combined
bun run dev

# Production build
npx squint compile
bun dist/agent/cli.mjs

# Standalone binary (~85MB, ~40ms start vs ~160ms via dist)
bun run bundle            # ./nyma for this machine; runs the compile itself
bun run bundle:all        # all seven targets (macos x2, linux glibc x2, linux musl x2, windows)
bun run gen:builtins      # after ADDING an extension: regenerates the compiled-in registry
# `bun run build` also regenerates src/agent/version.cljs from package.json —
# a compiled binary has no package.json to read, so --version is baked in.

# Run tests (compiles first; a bare `bun test dist` runs stale output — test/dist_freshness catches it)
bun run test

# One-time: pre-commit hook (incremental compile + the lint tests, ~5 s)
bun run hooks:install

# REPL
npx squint repl
```

### Per-extension reload, live eval, event log

- `/reload <ns>` — `extension_loader/reload-one!`: deactivate one loaded extension, re-import it
  from disk (squint recompiles on content change; TS/JS re-imports past the module cache), swap the
  new entry in. `/reload` alone is the full path (`handle-reload`).
- `NYMA_WATCH_EXTENSIONS=1` or settings `dev.watch-extensions` — `agent.dev.ext-watch` watches the
  on-disk extension dirs and reloads the extension whose files changed. Builtins are not watched.
- `/eval <form>` (`NYMA_DEV=1` or `dev.eval`) — `extension_loader/eval-expr!` compiles a form with the
  loader's squint and runs it; the live agent is `js/globalThis.__nyma`.
- `/replay [n]` — tail of the event-sourced store's 500-entry log (`(:history store)`), with counts.

### Tool parameters are data — `agent.schema`

Declare a tool's inputs once as data and render them where needed:

```clojure
(require '[agent.schema :as schema])
(def fields {:path  [:string "File path"]
             :range [:array :number {:length 2 :optional true :doc "Line range"}]
             :mode  [:enum ["text" "markdown"] {:optional true}]})
(tool #js {:inputSchema (schema/->zod fields) …})          ; AI-SDK tools
(clj->js (schema/->json-schema fields))                    ; raw JSON Schema (extension tools)
(schema/->doc-rows fields)                                  ; README bullets
```

Every core tool in `tools.cljs` and the `subagent` tool use it; the remaining
raw-JSON extension tools (memory, todos, ast_tools, lsp_suite, openwiki,
questionnaire, small_model) migrate on touch. `test/all_tools_schema_validation.test.cljs`
sweeps every registered tool through the SDK's `asSchema`.

### Adding a built-in extension

A directory under `src/agent/extensions/<name>/` with `index.cljs` + `extension.json`, then:

```bash
bun run gen:builtins
```

Built-ins are **compiled into** the standalone binary through the generated registry
(`src/agent/builtin_extensions.cljs`), because a single-file binary has no directory to scan — that is
how a bundled nyma once shipped with 2 of 40 extensions, silently, since an empty scan is not an error.
`test/builtin_extensions.test.cljs` fails when the registry and the source tree disagree, so forgetting
the regenerate is a red test rather than a mystery. User extensions in `~/.nyma/extensions` need none
of this; they are still scanned at runtime.

## Squint Conventions & Pitfalls

Squint compiles ClojureScript to JavaScript, but several Clojure idioms do **not** work as expected. These have caused real bugs in this codebase.

### `^:async` goes on the `fn` FORM, not the args vector

`(fn ^:async [x] …)` puts the metadata on the argument vector, where squint never looks; the
result is a plain `function` whose `await` is a syntax error. Put it on the form — or the name —
and an anonymous async fn works everywhere (event handlers, `.then` callbacks, JSX props):

```clojure
;; BROKEN — meta on the args vector is dropped
(.on api "ev" (fn ^:async [e] (js-await (work e))))

;; CORRECT — meta on the form
(.on api "ev" ^:async (fn [e] (js-await (work e))))
(.on api "ev" (fn ^:async on-ev [e] (js-await (work e))))   ; or on the name
(.then p ^:async #(js-await (more %)))

;; defn is unaffected
(defn ^:async handler [x] (js-await (some-promise x)))
```

For years this repo believed "fn ^:async doesn't work" and hoisted every async callback to a
top-level `defn`. Named top-level fns are still fine (and testable); the hoisting is no longer
required.

### `name`, `keyword`, and callable sets DO work (squint 0.14.208)

Earlier notes here said `name`/`keyword` were `ReferenceError`s and sets were
not callable. Both are false on the pinned squint: `squint_core.name` and
`squint_core.keyword` exist, and `(#{"a" "b"} x)` compiles to
`squint_core.get(new Set([...]), x)`. Keywords are still plain strings, so
`(name :k)` returns `"k"` and `(keyword "k")` returns `"k"`. Prefer
`contains?` for sets anyway — it reads as intent — but do not "fix" existing
callable-set sites; an audit that flags them is chasing a ghost.

### Function keys are stringified

`assoc`, `get`, `add-watch` on a plain map/atom stringify a function key, so
two closures with identical source collapse into one entry. Key by identity
with a `js/Map` / `js/WeakMap`, or by a counter token.

### Maps/objects are not callable — use `get`

`(into {} ...)` produces a plain JS object. You cannot call it as a function like a Clojure map.

```clojure
;; BROKEN — TypeError: by-id is not a function
(let [by-id (into {} (map (fn [e] [(:id e) e]) entries))]
  (by-id some-key))

;; CORRECT
(let [by-id (into {} (map (fn [e] [(:id e) e]) entries))]
  (get by-id some-key))
```

**Rule:** Always use `get`, `get-in`, or keyword access (`:key obj`) — never call a map/object as a function.

### `js->clj` does not exist

Squint compiles the symbol `js->clj` but does not provide the function at runtime (`ReferenceError: js__GT_clj is not defined`). This is because squint maps **are** plain JS objects — there is no conversion needed.

```clojure
;; BROKEN — ReferenceError at runtime
(js->clj (js/JSON.parse json-str) :keywordize-keys true)

;; CORRECT — JSON.parse output is already a plain object, keywords are strings
(js/JSON.parse json-str)

;; For deep-cloning a JS object to a plain structure:
(js/JSON.parse (js/JSON.stringify js-obj))
```

`clj->js` **does** work (for converting to JS objects with `JSON.stringify`).

### `array-seq` does not exist — use JS arrays directly

`array-seq` compiles to a bare identifier that is not defined at runtime. In Squint, JS arrays are already seqable — `map`, `filter`, `reduce`, `into`, `vec` all accept them directly. Use `js/Array.from` when you explicitly need a JS array from an array-like (NodeList, `arguments`, etc.).

```clojure
;; BROKEN — ReferenceError: array_seq is not defined
(reduce + 0 (array-seq js-array))

;; CORRECT — reduce works directly on JS arrays
(reduce + 0 js-array)

;; ALSO CORRECT — all seq ops work directly on JS arrays
(into [] js-array)
(map inc js-array)
(into [cmd] (or args []))  ;; args is a JS array — no conversion needed

;; Use js/Array.from only for non-array iterables (NodeList, Set, arguments, etc.)
(js/Array.from (.-childNodes node))
(js/Array.from js-set)  ;; when you need a plain array from a JS Set
```

### `Bun.spawn` requires explicit pipe config

Without `stdout: "pipe"` and `stderr: "pipe"`, `Bun.spawn` does not capture output — it goes directly to the parent process.

```clojure
;; BROKEN — stderr is empty string, output goes to terminal
(js/Bun.spawn #js ["sh" "-c" cmd] #js {:timeout 30000})

;; CORRECT
(js/Bun.spawn #js ["sh" "-c" cmd]
  #js {:timeout 30000 :stdout "pipe" :stderr "pipe"})
```

### Namespace path must match file path

Squint resolves ClojureScript namespace requires by converting the namespace to a file path. `agent.sessions.manager` becomes `./agent/sessions/manager.mjs`. If the file lives at `src/sessions/manager.cljs` (compiled to `dist/sessions/manager.mjs`), the import will fail with "Cannot find package".

**Rule:** The directory structure under `src/` must mirror the namespace prefix. A file declaring `(ns agent.foo.bar ...)` must live at `src/agent/foo/bar.cljs`.

### `clojure.string` must be required explicitly

`clojure.string/join` without a require compiles to `clojure.string.join(...)` — a `ReferenceError` at runtime.

```clojure
;; BROKEN — ReferenceError: clojure is not defined
(clojure.string/join "\n" items)

;; CORRECT
(:require [clojure.string :as str])
(str/join "\n" items)
```

### `process.env.FOO` is a property, not a function

Squint compiles `(js/process.env.HOME)` as a function call `process.env.HOME()` — TypeError at runtime. Use the `..` interop form:

```clojure
;; BROKEN — TypeError: process.env.HOME is not a function
(js/process.env.HOME)

;; CORRECT
(.. js/process -env -HOME)
```

### Props use string keys — kebab-case ≠ camelCase

Squint keywords become string keys. `:on-submit` becomes `"on-submit"`, but `:onSubmit` becomes `"onSubmit"` — these are different keys. If a caller passes `{:onSubmit handler}` and the component destructures `{:keys [on-submit]}`, the prop will be `undefined`.

**Rule:** pick one casing per key and use it on both sides — a component's option map and the destructuring that reads it must agree.

```clojure
;; Caller
(make-editor {:onSubmit handle-submit :streaming streaming})

;; Receiver — must match the caller's casing
(defn make-editor [{:keys [onSubmit streaming theme]}]
  ...)
```

### Property access takes no default argument

`(.-prop x fallback)` compiles to plain property access; the extra argument is silently dropped:

```clojure
;; BROKEN — property access does not take a default argument
(.-textDelta chunk "")

;; CORRECT
(or (.-textDelta chunk) "")
```

### Keyword args in functions — use explicit opts map

Squint's varargs destructuring `[& {:keys [a b]}]` does **not** work reliably. Pass an explicit opts map instead:

```clojure
;; BROKEN — keyword args destructuring silently fails
(defn interceptor [name & {:keys [enter leave]}] ...)
(interceptor :my-int :enter (fn [ctx] ctx))  ; enter is nil

;; CORRECT — explicit opts map
(defn interceptor [name opts]
  {:name name :enter (:enter opts) :leave (:leave opts)})
(interceptor :my-int {:enter (fn [ctx] ctx)})
```

## Testing

Tests are in `test/` as `.cljs` (compiled by squint) or `.ts` files. Run with `bun test`.

For async tests, `^:async` on the `(fn …)` form works (see the Squint notes above); a named `defn ^:async` helper is still the readable choice for a long body:

```clojure
(defn ^:async test-my-async-thing []
  (let [result (js-await (some-async-fn))]
    (-> (expect result) (.toBe "expected"))))

(it "does the async thing" test-my-async-thing)
```

## Interceptor Chain

The interceptor chain (`src/agent/interceptors.cljs`) is the execution engine underlying the middleware pipeline. It's modeled after Pedestal interceptors.

### Concepts

- Each interceptor is a map: `{:name keyword, :enter fn, :leave fn, :error fn}`
- `:enter` stages run **left-to-right** through the chain
- `:leave` stages run **right-to-left** (unwinding)
- `:error` stages run right-to-left from the point of failure
- All stage functions may return Promises (auto-awaited)
- The context map (`ctx`) flows through all stages

### API

```clojure
(require '[agent.interceptors :refer [execute interceptor into-chain]])

;; Create an interceptor
(def my-int
  (interceptor :my-int
    {:enter (fn [ctx] (assoc ctx :started true))
     :leave (fn [ctx] (assoc ctx :finished true))
     :error (fn [ctx] (assoc ctx :recovered true))}))

;; Execute a chain
(js-await (execute [my-int] {:some "data"}))

;; Compose chains
(def combined (into-chain chain-a chain-b extra-int))
```

## Middleware Pipeline

The middleware pipeline (`src/agent/middleware.cljs`) wraps tool execution as an interceptor chain. Each tool call flows through all registered interceptors before and after execution.

### Built-in Interceptors

- **`tool-execution-interceptor`** — terminal interceptor that calls `tool.execute` with args
- **`before-hook-compat`** — bridges the legacy `before_tool_call` event system; emits the event and checks `ctx.cancelled`

### API

```clojure
;; pipeline is part of the agent map: (:middleware agent)
(let [pipeline (:middleware agent)]
  ;; Add custom interceptor
  ((:add pipeline) my-interceptor)
  ((:add pipeline) my-interceptor {:position :first})  ; run before others

  ;; Remove by name
  ((:remove pipeline) :my-interceptor-name)

  ;; Execute directly (loop.cljs does this automatically)
  (js-await ((:execute pipeline) "tool-name" tool-obj args-map)))
```

### Extension API

```javascript
// Register middleware from an extension
api.addMiddleware({
  name: "rate-limiter",
  enter: (ctx) => {
    if (tooManyCallsRecently()) ctx.cancelled = true;
    return ctx;
  }
});

api.removeMiddleware("rate-limiter");
```

## Squint: `defmulti` works — use it for open dispatch

`defmulti`/`defmethod` DO compile on the pinned squint. This file used to say the
opposite, and every registry in nyma became an atom-of-closures because of it.
The shipping proof is `agent.utils.reasoning-request/reasoning-body`
(`src/agent/utils/reasoning_request.cljs:39`): one `defmulti` in core, four
`defmethod`s installed from provider extensions (groq, openrouter, relay,
kimi). Squint compiles `defmethod` to a mutation of the multimethod object
imported from the defining namespace, so a method registers the moment its
module is imported — no explicit registration call, no ordering to get wrong.

When to reach for which:

- **`defmulti`** — a fixed *operation* with an open set of *cases* that other
  namespaces (extensions) contribute: reasoning dialects per provider, wire
  formats per protocol, renderers per message type. Dispatch on a value the
  caller already has.
- **Atom registry** (`tool_result_policy`, provider registry, status segments)
  — when entries must be *listed*, *undone* on `/reload`, or carry data beyond
  a function. A multimethod cannot enumerate or unregister its methods.
- **`cond` / map of closures** — a closed set that lives in one file.

Limits: `defmethod` is global to the process (two extensions defining the same
dispatch value: last import wins, silently), and a method installed by an
extension survives that extension's deactivation. Do not use it for anything
`/reload` must be able to take back.

## Event-Sourced State Store

`src/agent/state.cljs` replaces the bare atom with an event-sourced store. All state mutations go through `dispatch!` with registered reducers. Full history is maintained.

### API

```clojure
(require '[agent.state :refer [create-store create-agent-store]])

(def store (create-agent-store {:messages [] :model "claude-sonnet-4-20250514"}))

;; Dispatch a state event
((:dispatch! store) :message-added {:message {:role "user" :content "hello"}})

;; Read current state
((:get-state store))

;; Register a custom reducer
((:register store) :my-event
  (fn [state data] (assoc state :last-event data)))

;; Subscribe to all state changes
(def unsub ((:subscribe store) (fn [event-type new-state] ...)))
(unsub)  ;; unsubscribe

;; Inspect history
((:history store))  ;; [{:type :message-added :data {...} :timestamp 1234567890}]
```

### Built-in Reducers

| Event type | Effect |
|-----------|--------|
| `:message-added` | `(update state :messages conj (:message data))` |
| `:messages-cleared` | `(assoc state :messages [])` |
| `:tools-changed` | `(assoc state :active-tools (:active-tools data))` |
| `:model-changed` | `(assoc state :model (:model data))` |

### Backwards Compatibility

The store exposes a bare-atom interface so existing code continues to work:

```clojure
;; Still works
((:swap store) update :messages conj msg)
((:reset store) new-state)
((:deref store))
```

### Extension API

```javascript
// Subscribe to state changes
api.onStateChange((eventType, newState) => {
  console.log("State changed:", eventType);
});

// Dispatch custom state events
api.dispatch("custom-event", { key: "value" });

// Read current state
const state = api.getState();
```

## Extension Namespacing & Capabilities

Extensions now run in a scoped API sandbox. Each extension has a **namespace** (derived from its filename or `extension.json` manifest) and a set of **capabilities** that gate which API methods it can call.

### Namespace Prefixing

All tools and commands registered by an extension are automatically prefixed with its namespace:

```clojure
;; Extension "git-tools" calls:
(.registerTool api "status" ...)
;; Tool is registered as "git-tools__status"

;; Extension "git-tools" calls:
(.registerCommand api "log" ...)
;; Command is registered as "git-tools__log"
```

### Capabilities

| Capability | Grants access to |
|-----------|-----------------|
| `:tools` | `registerTool`, `unregisterTool` |
| `:commands` | `registerCommand`, `unregisterCommand` |
| `:shortcuts` | `registerShortcut`, `unregisterShortcut` |
| `:events` | `on`, `off` |
| `:messages` | `sendMessage`, `sendUserMessage` |
| `:state` | `getState`, `dispatch`, `onStateChange` |
| `:ui` | `ui.showOverlay`, `ui.confirm` |
| `:middleware` | `addMiddleware`, `removeMiddleware` |
| `:all` | All of the above |

Extensions default to `:all` unless restricted via manifest.

### Extension Manifest

Place an `extension.json` file alongside the extension:

```json
{
  "namespace": "git-tools",
  "capabilities": ["tools", "events", "commands"]
}
```

Without a manifest, the namespace is derived from the filename (`git_tools.cljs` → `"git-tools"`).

## Event System

All agent lifecycle events flow through the event bus. Handlers are error-isolated — a throwing handler is logged and the next handler continues.

```
session_start / session_end / session_before_switch / session_switch
agent_start / agent_end
turn_start / turn_end
message_start / message_update / message_end
tool_call / tool_result
before_tool_call           ← set ctx.cancelled = true to block execution
context / before_agent_start / input
compact / before_compact
```

**Note:** `tool_execution_start` and `tool_execution_end` are emitted by the middleware tracking interceptor (`middleware.cljs`), not by `loop.cljs`.

### Async Event Emission

The event bus now supports `emit-async` for awaiting all handlers:

```clojure
;; Sync (fire-and-forget, existing behavior)
((:emit bus) "tool_call" data)

;; Async (awaits all handlers, including async ones)
(js-await ((:emit-async bus) "before_compact" data))
```

Use `emit-async` when you need to know if a handler has cancelled or modified the context before proceeding.

### Intercepting Tool Calls

`before_tool_call` fires before each tool executes. The handler receives a mutable context object:

```typescript
api.on("before_tool_call", (ctx) => {
  if (ctx.name === "bash" && ctx.args.command.includes("rm -rf")) {
    ctx.cancelled = true;  // blocks execution
  }
});
```

For middleware-based interception (more structured), use `api.addMiddleware`.

## Settings

Settings are resolved in priority order:
1. Runtime overrides (flags, API)
2. Project settings (`.nyma/settings.json`)
3. Global settings (`~/.nyma/settings.json`)
4. Defaults

## Operational Modes

| Mode | Flag | Description |
|------|------|-------------|
| interactive | (default) | Full TUI (pi-tui) |
| print | `-p` / `--print` | Run once, print result to stdout |
| json | `--mode json` | Run once, output JSON messages |
| rpc | `--mode rpc` | JSONL protocol over stdio |
| pi-rpc | `--mode pi-rpc` | JSONL protocol for the pi Emacs frontend |
| sdk | (import) | Programmatic embedding |

## Quick Reference: DOs and DON'Ts

### Squint / ClojureScript

| ❌ Don't | ✅ Do instead |
|---------|--------------|
| `(fn ^:async [x] ...)` — the tag on the args vector | `^:async (fn [x] ...)` — the tag on the `fn` form, or a `defn ^:async` |
| `(keyword? x)` | `(string? x)` |
| `(my-set :key)` in new code | `(contains? my-set :key)` — callable sets do work; `contains?` reads as intent |
| `(my-map :key)` | `(get my-map :key)` |
| `(js->clj ...)` | Use the value directly — it's already a JS object. For deep clone: `(js/JSON.parse (js/JSON.stringify x))` |
| `(array-seq xs)` | Pass the JS array directly — `map`/`filter`/`reduce`/`into` all accept JS arrays natively; use `(js/Array.from xs)` only for non-array iterables |
| `(clojure.string/join ...)` | `(:require [clojure.string :as str])` then `(str/join ...)` |
| `(js/process.env.HOME)` | `(.. js/process -env -HOME)` |
| `(.-textDelta chunk "")` | `(or (.-textDelta chunk) "")` |
| `:on-submit` passed to a fn destructuring `:onSubmit` | one casing on both sides — keyword keys are plain strings |
| `(js/Bun.spawn ...)` without pipes | Add `#js {:stdout "pipe" :stderr "pipe"}` |
| `(defn f [& {:keys [a]}] ...)` keyword args | `(defn f [opts] ...)` explicit opts map |
| `(defmulti ...)` for a set that must be listed or unregistered | an atom registry — multimethods compile fine, but cannot enumerate or undo their methods (see "`defmulti` works") |

### Extension Development

| ❌ Don't | ✅ Do instead |
|---------|--------------|
| Call `api.ui.showOverlay(...)` unconditionally | Check `api.ui.available` first |
| Forget to clean up event handlers | Return a cleanup fn from your extension's init |
| Register a command and never unregister | Call `api.unregisterCommand` in cleanup |
| Assume tool calls will always proceed | Use `before_tool_call` or middleware for pre-execution checks |
| Subscribe with high-priority for normal work | Reserve high `priority` for security/safety handlers |
| Register tools without namespace awareness | Tools are auto-prefixed: `"my-ext__tool-name"` |
| Pass capabilities you don't need | List only needed capabilities in `extension.json` |

### Testing

| ❌ Don't | ✅ Do instead |
|---------|--------------|
| `(fn ^:async [] ...)` as `it` callback | `^:async (fn [] ...)`, or a named `(defn ^:async test-foo [] ...)` passed by name |
| Leave temp dirs after async tests | Always `.rmSync` in the test body before returning |
| Spy on `console.error` without restoring | Save `orig` first, restore in all branches |
| Test compiled output paths | Test source — squint compile is part of the test run |
| Nest `describe` without `(fn [] ...)` wrappers | Each `describe` body must be `(fn [] ...)` |

## Git Workflow

- Commit messages: Conventional Commits, `type(scope): imperative subject` (e.g., `fix(rpc): stdin-EOF exit is a cli option, not a default`); the body says why
- One logical change per commit
