# Nyma

**Nyma** (Nyma Yokai Mystic Agent) is a minimal, extensible AI coding agent built with **Squint** (ClojureScript) + **Bun** + **Vercel AI SDK** + **pi-tui**. Write your agent logic in ClojureScript — macros, threading, destructuring, data-oriented design — and ship plain ES modules with zero runtime overhead.

Nyma compiles ClojureScript to native ES modules, runs on Bun for speed, and renders a terminal UI with pi-tui (`@earendil-works/pi-tui`). It is designed around a **middleware pipeline**, **event-sourced state**, **Pedestal-style interceptor chains**, and a **namespaced extension system** — making it easy to extend without modifying core code.

## vs. pi-mono

Nyma is a spiritual successor to [pi-mono](https://github.com/badlogic/pi-mono) — Mario Zechner's TypeScript coding agent — with a different set of trade-offs:

| | pi-mono | Nyma |
|---|---|---|
| **Language** | TypeScript | ClojureScript (Squint) → ES modules |
| **Extension language** | TypeScript | TypeScript **or** ClojureScript |
| **Schema library** | TypeBox | Zod (data-driven, no imports needed in extensions) |
| **State model** | Mutable atom | Event-sourced store with full history |
| **Tool pipeline** | Linear | Pedestal-style interceptor chain (composable middleware) |
| **Extension isolation** | Shared namespace | Capability-gated, namespaced sandbox |
| **Session storage** | JSONL | JSONL tree + optional SQLite with usage tracking |

**Why Nyma over pi-mono:**

- **Interceptors compose cleanly.** Adding logging, rate-limiting, or permission checks is a chain entry — not a wrapper function.
- **Extensions are safer.** Each plugin gets a scoped API with explicit capability declarations (`tools`, `commands`, `middleware`, `state`, `ui`). No extension can reach outside its declared scope.
- **ClojureScript extensions** get threading (`->`), destructuring and data-oriented idioms — a real ergonomic win over imperative TS for complex agent logic.
- **pi-mono extensions port with minimal changes** — the API surface is intentionally compatible. See `docs/porting-guide-ts.md`.

## Architecture

```
@agent/cli  ─→  @agent/core  ─→  ai (Vercel AI SDK)
                     │
                @agent/ui (pi-tui components)
```

Tool execution flows through the **middleware pipeline** (interceptor chain). Extension isolation uses namespacing and capability gating. State mutations go through an **event-sourced store**.

```
user input → loop.cljs → middleware pipeline → tool.execute
                              ↑
                    registered interceptors
                    (logging, before-hook-compat, custom)
```

| Layer | Namespace | Role |
|-------|-----------|------|
| CLI | `agent.cli` | Entry point, arg parsing, mode dispatch |
| Core | `agent.core` | Agent factory, state management |
| Loop | `agent.loop` | Execution loop via `streamText` |
| Events | `agent.events` | Typed event bus (sync + async `emit-async`) |
| Tools | `agent.tools` | Built-in tools: read, write, edit, bash, ls, glob, grep, think, view_image, web_fetch, web_search, deep_research |
| Registry | `agent.tool-registry` | Tool activation/deactivation |
| Extensions | `agent.extensions` | Extension API for plugins |
| Extension Loader | `agent.extension-loader` | Dual .cljs/.ts loader with scoped APIs |
| Sessions | `agent.sessions.manager` | JSONL tree-based conversation storage |
| Compaction | `agent.sessions.compaction` | Context window summarization |
| Settings | `agent.settings.manager` | Two-scope config (global + project) |
| Resources | `agent.resources.loader` | Discover prompts, skills, themes |
| UI | `agent.ui.*` | pi-tui terminal components |
| Modes | `agent.modes.*` | Interactive, print, RPC, SDK |
| **Interceptors** | `agent.interceptors` | Pedestal-style interceptor chain engine |
| **Middleware** | `agent.middleware` | Middleware pipeline for tool execution |
| **State** | `agent.state` | Event-sourced state store with history |
| **Permissions** | `agent.permissions` | Extension capability system |
| **Extension Scope** | `agent.extension-scope` | Namespaced + capability-gated extension API |

## Prerequisites

- [Bun](https://bun.sh) (v1.0+)
- [Node.js](https://nodejs.org) (v18+ for squint CLI)
- An API key for at least one LLM provider:
  - `ANTHROPIC_API_KEY` for Claude (default)
  - `OPENAI_API_KEY` for OpenAI
  - `GOOGLE_GENERATIVE_AI_API_KEY` for Google

## Install

Every `vX.Y.Z` tag publishes a standalone binary for seven platforms — no Bun or Node needed
on the target machine. Grab one from [the releases
page](https://github.com/vihangd/nyma/releases/latest):

```bash
# macOS (arm64) — swap in your platform's archive name
curl -LO https://github.com/vihangd/nyma/releases/latest/download/nyma-macos-arm64.tar.gz
tar -xzf nyma-macos-arm64.tar.gz
./nyma --version
```

| platform | archive |
|---|---|
| macOS arm64 / x64 | `nyma-macos-arm64.tar.gz` · `nyma-macos-x64.tar.gz` |
| Linux x64 / arm64 (glibc) | `nyma-linux-x64.tar.gz` · `nyma-linux-arm64.tar.gz` |
| Linux x64 / arm64 (musl, e.g. Alpine) | `nyma-linux-x64-musl.tar.gz` · `nyma-linux-arm64-musl.tar.gz` |
| Windows x64 | `nyma-windows-x64.zip` |

Each archive unpacks to a plain `nyma` (or `nyma.exe`), and `SHA256SUMS` covers all of them.
Use the musl build on Alpine — a glibc binary will not run there.

To build from source instead, or to work on nyma itself, read on.

## Quick Start

```bash
# Install dependencies
bun install

# Build (compile ClojureScript to JavaScript)
bun run build

# Credentials — one of these, or `/login <provider>` inside the TUI
export ANTHROPIC_API_KEY=sk-ant-...    # OPENAI_API_KEY / GOOGLE_GENERATIVE_AI_API_KEY
                                       # for the other two built-in providers

# Run the agent
bun run start
```

## Development

### Dev Mode (Watch + Auto-Reload)

```bash
bun run dev
```

This runs two processes concurrently:
1. **`squint watch`** — watches `src/` and `test/` for `.cljs` changes, compiles to `dist/`
2. **`bun --watch`** — watches compiled output and auto-restarts the agent

Extensions on disk (`~/.nyma/extensions`, `.nyma/extensions`) need no restart at all:
`/reload <namespace>` deactivates and re-imports just that one (`/reload` alone still
does everything), and with `NYMA_WATCH_EXTENSIONS=1` (or settings `dev.watch-extensions`)
a saved file under an extension's directory reloads it in place, session intact.
Builtins are compiled into the process; `bun run dev` restarts for those.

Two more dev-only commands: `/eval <form>` compiles a ClojureScript form with the
extension loader's squint and runs it against the live agent (`js/globalThis.__nyma`;
`NYMA_DEV=1` or settings `dev.eval` to enable), and `/replay [n]` prints the tail of the
event-sourced store's log with per-type counts.

### Build

```bash
bun run build
```

Compiles all `.cljs` files from `src/` and `test/` to `.mjs` (ES modules) in `dist/`. JSON resources are copied as-is.

### Standalone binary

```bash
bun run bundle        # ./nyma for this machine
bun run bundle:all    # all seven targets
```

`bun run bundle` runs the squint compile itself, so it is the only command needed. It produces a
single ~85 MB executable that starts in **~40 ms**, against ~160 ms for `bun dist/agent/cli.mjs`. The
binary is gitignored.

**It is a snapshot.** Editing anything under `src/` does not change `./nyma` until you rebuild — while
developing nyma itself, `npx squint compile && bun dist/agent/cli.mjs` remains the faster loop. The
binary is for *using* nyma elsewhere.

The flags are not arbitrary, and `test/bundle_flags.test.cljs` fails if they drift apart:

| flag | why |
|---|---|
| `--compile` | single-file executable with the Bun runtime embedded |
| `--bytecode` | caches the parse: 100 ms → 40 ms startup, at +13 MB |
| `--format=esm` | **load-bearing.** `--bytecode` implies CJS, and out of a CJS binary the extension loader's disk-loaded `.mjs` files cannot resolve their bare npm imports — every extension fails with `Cannot find package 'ai'`, silently, since an empty scan is not an error |
| `--minify` | −5.9 MB; startup unchanged, because bytecode already did the parse |

Extensions in the binary: the 40 builtins are compiled in via the generated registry
(`src/agent/builtin_extensions.cljs` — regenerate with `bun run gen:builtins` after adding one), and
user extensions are still discovered by scanning `~/.nyma/extensions` and `.nyma/extensions` at
runtime. A bundled nyma reports the same 40 extensions and 20 providers as the dist entry point.

#### Releases

Pushing a `vX.Y.Z` tag builds every target on GitHub Actions and attaches the archives to a release
(`.github/workflows/release.yml`). `workflow_dispatch` runs it without tagging.

| target | script | artifact |
|---|---|---|
| macOS arm64 | `bundle` | `nyma-macos-arm64.tar.gz` |
| macOS x64 | `bundle:macos-x64` | `nyma-macos-x64.tar.gz` |
| Linux x64 (glibc) | `bundle:linux-x64` | `nyma-linux-x64.tar.gz` |
| Linux arm64 (glibc) | `bundle:linux-arm64` | `nyma-linux-arm64.tar.gz` |
| Linux x64 (musl) | `bundle:linux-x64-musl` | `nyma-linux-x64-musl.tar.gz` |
| Linux arm64 (musl) | `bundle:linux-arm64-musl` | `nyma-linux-arm64-musl.tar.gz` |
| Windows x64 | `bundle:windows` | `nyma-windows-x64.zip` |

Each archive unpacks to a plain `nyma` (or `nyma.exe`), and `SHA256SUMS` covers all of them. The musl
builds exist because a glibc binary will not run on Alpine.

Two things the workflow does on purpose:

- **It calls the `bundle:*` scripts rather than repeating the `bun build` flags.** The flags above are
  guarded by `test/bundle_flags.test.cljs`, which reads `package.json` — a workflow that re-typed them
  would be a second copy the guard does not cover, and `--format=esm` is exactly the kind of omission
  that ships a binary which starts fine and loads no extensions.
- **The whole suite gates the matrix,** and a tag must match `package.json`'s version. The generated
  files (builtin registry, event map) have drift tests, so a stale registry fails the release instead
  of shipping a nyma missing most of its extensions.

Only the two builds whose runner can execute them (linux-x64, macos-arm64) are smoke-tested; the rest
are cross-compiled and verified by the archive step alone. The smoke test checks `--version` against
`package.json`, which also catches a binary built from a tree where the version generator did not run.

`.github/workflows/ci.yml` runs the same suite on every push and PR, plus a drift check on the three
generated files (`src/agent/version.cljs`, `src/agent/builtin_extensions.cljs`, `docs/event-map.md`)
and one real `--compile` build, since the flag pairing that makes extensions work is only observable
by compiling.

`src/agent/version.cljs` is generated from `package.json` by `bun run build`, so it cannot go stale —
bump the version in `package.json`, never in the generated file. It exists because a compiled binary
has no `package.json` to read.

### REPL

```bash
bun run repl
```

Starts a Squint REPL for interactive ClojureScript development.

### Project Structure

```
src/
  agent/           Core agent modules (all under agent.* namespace)
    cli.cljs       Entry point
    core.cljs      Agent factory
    loop.cljs      Execution loop
    events.cljs    Event bus (sync + async)
    tools.cljs     Built-in tools
    tool_registry.cljs
    extensions.cljs / extension_loader.cljs
    context.cljs
    interceptors.cljs   Interceptor chain engine
    middleware.cljs     Tool execution middleware pipeline
    state.cljs          Event-sourced state store
    permissions.cljs    Extension capability gating
    extension_scope.cljs  Namespaced extension API wrapper
    keybindings.cljs    Loads ~/.nyma/keybindings.json user key mappings
    pricing.cljs        Token cost table + calculate-turn-cost
    commands/      Built-in slash command implementations + session export
    providers/     LLM provider registry (Anthropic, OpenAI, Google)
    utils/         Shared utilities (ANSI text, terminal width)
    modes/         Operational modes (interactive, print, rpc, sdk)
    ui/            pi-tui terminal components
      chat_pane.cljs / chat_renderer.cljs   Message list component + pure renderer
      overlay_host.cljs   api.ui overlays on pi-tui's overlay stack
      status_bar.cljs / status_line_segments.cljs   Status bar + segment registry
      picker_*.cljs / fuzzy_scorer.cljs   Shared picker frame, key dispatch, scoring
      file_mentions.cljs  @path completion and <file> expansion
      editor_bash.cljs / editor_eval.cljs   !cmd and $expr editor modes
      width_guard.cljs / crash_recovery.cljs   Keep pi-tui's width check from killing the session
    sessions/      Session management + compaction
      listing.cljs   Scans .jsonl files, returns sorted metadata
      storage.cljs   SQLite-backed session store with usage tracking
      partial.cljs   Checkpoints the in-flight response to a .partial sidecar
    settings/      Configuration system
    resources/     Resource discovery (prompts, skills, themes)
test/              Test files (.cljs and .ts)
  integration/     Integration tests (tool pipeline, extension lifecycle, state+events)
built-in/themes/   Default dark/light themes
dist/              Compiled output (generated, gitignored)
```

> **Important:** File paths must mirror namespace prefixes. `(ns agent.foo.bar ...)` must live at `src/agent/foo/bar.cljs`. Squint resolves imports by converting namespace dots to path separators.

### Compilation Model

Squint compiles ClojureScript to plain JavaScript:
- Kebab-case identifiers become snake_case (`create-agent` -> `create_agent`)
- Keywords become string keys (`:role` -> `"role"`)
- Atoms compile to mutable wrappers with `deref()`, `swap()`, `reset()`
- `^:async` on `defn` or on the `(fn …)` form generates `async function` (never on the args vector)

Config in `squint.edn`:
```clojure
{:paths      ["src" "test"]
 :output-dir "dist"
 :extension  "mjs"
 :copy-resources #{:json}}
```

## Testing

```bash
bun test
```

This automatically compiles all source and test files (via the `pretest` script) then runs tests with Bun's built-in test runner.

### Test Structure

Tests are written in ClojureScript using `bun:test` via JS interop:

```clojure
(ns my-module.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]))

(describe "my module"
  (fn []
    (it "does the thing"
      (fn []
        (let [bus (create-event-bus)]
          (-> (expect (fn? (:on bus))) (.toBe true)))))))
```

For async tests, use `defn ^:async` (not `fn ^:async`):

```clojure
(defn ^:async test-async-operation []
  (let [result (js-await (some-async-fn))]
    (-> (expect result) (.toBe "expected"))))

(it "async test" test-async-operation)
```

TypeScript tests (`test/*.test.ts`) are also supported and run alongside compiled ClojureScript tests.

### Current Test Coverage

**Total: 11,200+ assertions across 300+ test files** (run `bun run test` to see live counts).

Coverage spans the full stack:

- **Core kernel** — events, interceptors, middleware, protocols, schemas, state store, permissions, tool registry, session manager
- **Agent loop** — full run cycle, hook events (`before_agent_start`, `model_resolve`, `context_assembly`, `before_message_send`, `before_provider_request`, `after_provider_request`, `stream_filter`, `message_before_store`, `provider_error`), retry-state lifecycle
- **Extensions** — extension loader, scope/capabilities, extension API surface, every built-in extension has its own `ext_<name>.test.cljs`
- **Gateway** — config interpolation + validation, session pool with lane serialization, auth/approval pipelines, streaming policies, channel registry, allow-list checks
- **UI** — pi-tui components (status line, header, welcome, dialogs, picker, autocomplete, editor modes, render layout)
- **Integration** — tool pipeline end-to-end, extension lifecycle, state+events, ACP agent shells

See `test/` for the full list. New tests live alongside source code under matching names.

### Benchmarking

`bun test` proves the code does what it claims. It says nothing about whether the
agent *solves problems*. `bench/` runs real Aider-Polyglot exercises and grades
them with the exercises' own tests:

```bash
git clone --depth 1 https://github.com/Aider-AI/polyglot-benchmark bench/tasks
bun bench/run.mjs --count 10 --seed 7 --model build --label baseline
bun bench/run.mjs --diff bench/results/<old>.json bench/results/latest.json
```

Results are committed under `bench/results/` on purpose — the history is what
makes "did that change help?" answerable. Scores are for nyma against itself: the
runnable subset is Python-only and not comparable to published full-set numbers.
See [`bench/README.md`](bench/README.md), particularly the list of things the
harness refuses to score.

## Running the Agent

Nyma ships **two binaries**:

| Binary | Source | Purpose |
|---|---|---|
| `nyma` | `dist/agent/cli.mjs` | Interactive TUI / print / JSON / RPC modes |
| `nyma-gateway` | `dist/gateway/entry.mjs` | Run nyma as a daemon behind chat channels (Telegram, Slack, HTTP, Email) |

`nyma --help` lists every flag; `nyma --version` prints the version alone, so it composes in a
shell substitution.

### Interactive Mode (Default)

```bash
bun run start
```

Launches the full terminal UI with message display, text input, and keyboard shortcuts.
`/hotkeys` lists the keys that are bound. `ctrl+o` expands or collapses the last
tool call's output (rebind it in `~/.nyma/keybindings.json` as
`{"ctrl+t": "app.tools.expand"}`).

In the editor, `@path` mentions a file or directory: on submit each one is appended to your message as a `<file path="…">` block (a directory becomes a one-level `<dir>` listing; files over 200 KB and binary files are marked skipped). A path with spaces is quoted, `@"my notes.txt"`, and an `@` inside a ``` fenced block is left literal. Typing `@` opens fuzzy path completion (Tab accepts) — gitignore-aware via `fd` when it is on your PATH, otherwise from `git ls-files` or a directory walk. Emails and `@scope/pkg` names that resolve to nothing are left alone.

### Print Mode

```bash
bun run start -- -p "Explain this codebase"
```

Runs once with the given prompt, prints the response, and exits. Useful for scripting. The first positional arg after `-p` becomes the prompt.

`--output-format json` prints a single claude-style result object instead (`{type, is_error, result, session_id, total_cost_usd, usage, duration_ms}`). `--output-format stream-json` prints one JSON object per line while the run is in flight, then that same result object as the last line:

```
{"type":"message_start"}
{"type":"message_update","text":"The "}
{"type":"tool_execution_start","toolName":"read","execId":"…","args":{"path":"src/"}}
{"type":"tool_execution_end","toolName":"read","execId":"…","result":"…","isError":false}
{"type":"usage","inputTokens":120,"outputTokens":34}
{"type":"message_end"}
{"type":"result","is_error":false,"result":"…",…}
```

`message_start`/`message_end` bracket each **text block** the model emits, not each message — a turn that writes, calls a tool and writes again produces two pairs. `usage` fires once per **step** (each model call), so sum them for a turn's total; a step whose provider reports no usage emits nothing.

All three exit 1 when the run fails (`is_error: true`), and 2 on an unknown format.

### JSON Mode

```bash
bun run start -- --mode json "What files are in src/"
```

Like print mode but outputs structured JSON.

### RPC Mode

```bash
bun run start -- --mode rpc        # nyma's own minimal JSONL protocol
bun run start -- --mode pi-rpc     # pi-compatible JSONL protocol
```

Both are JSONL over stdio, one command per line, `\n` as the only delimiter.

`--mode pi-rpc` speaks the protocol pi's frontends use, so tools written against
pi can drive nyma. It implements `prompt`, `steer`, `abort`, `fork`,
`clear_queue`, `switch_session`, `set_model`, `set_thinking_level`, the `get_*`
queries, and the event flow pi documents:

```
response/prompt → agent_start → (turn_start → message_* → turn_end)+ → agent_end → agent_settled
```

A turn is one assistant response plus the tool calls it causes, so a run that
uses tools sends several `turn_start`/`turn_end` pairs. They always alternate:
every `turn_end` is preceded by exactly one `turn_start`.

`turn_end` carries the assistant message with its `usage` and `stopReason`, plus
`toolResults` — consumers derive per-turn token accounting from those fields.

`agent_settled` means the run will not continue on its own: it is withheld while
a follow-up is queued, because the loop will recur into another turn. A frontend
can unlock input on it. On a provider error `agent_end` is synthesised before it,
so the pair is always closed.

#### Driving nyma from [pilish](https://github.com/dnouri/pilish) (Emacs)

pilish builds its argv as `<executable> --mode rpc <extra-args>`, and a repeated
`--mode` takes the last value, so no patch is needed on either side:

```elisp
(setq pilish-executable '("/path/to/nyma"))
(setq pilish-extra-args '("--mode" "pi-rpc"))
```

pilish probes `--version` and warns when it is below pi's own `0.85.0`. It is a
warning, not a gate.

### Gateway Mode (`nyma-gateway`)

The gateway runs nyma as a long-lived daemon that listens on chat platforms — Telegram, Slack, HTTP webhooks, IMAP/SMTP email — and routes inbound messages to per-conversation agent sessions.

```bash
# Start with the default config (./gateway.json)
bun dist/gateway/entry.mjs

# Or with a custom config path
bun dist/gateway/entry.mjs --config=/etc/nyma/bot.json

# Validate a config file without starting
bun dist/gateway/entry.mjs validate

# Run interactive setup flows for each channel (where supported)
bun dist/gateway/entry.mjs setup

# Show help
bun dist/gateway/entry.mjs --help
```

Once installed via `npm install -g .` (or `bun link`), the same commands work as the `nyma-gateway` binary directly.

#### Config (`gateway.json`)

```json
{
  "agent": {
    "model":                "claude-sonnet-4-6",
    "modes":                ["gateway"],
    "exclude-capabilities": ["execution"]
  },
  "gateway": {
    "streaming": { "policy": "debounce", "delay-ms": 400 },
    "session":   { "policy": "persistent", "idle-evict-ms": 3600000 },
    "auth":      { "allowed-user-ids": ["U12345"] }
  },
  "channels": [
    {
      "type":   "telegram",
      "name":   "my-bot",
      "config": { "token": "${TELEGRAM_BOT_TOKEN}" }
    },
    {
      "type":   "http",
      "name":   "webhook",
      "config": { "port": 3000 }
    }
  ]
}
```

`${VAR}` tokens are expanded from `process.env` at load time. All config keys use **kebab-case** (`delay-ms`, `allowed-user-ids`) — the gateway reads them as plain Clojure keywords. Streaming policies (`immediate`, `debounce`, `throttle`, `batch-on-end`), session policies (`persistent`, `ephemeral`, `idle-evict`, `capped`), per-channel config, and third-party channel adapter authoring are documented in [`docs/gateway.md`](docs/gateway.md).

#### Built-in channels

| Type | Capabilities | Notes |
|---|---|---|
| `telegram` | text, typing, attachments | Long-polling via Bot API |
| `slack` | text, typing, threads | Socket Mode (no public URL needed) |
| `http` | text | Bun.serve webhook server |
| `email` | text | IMAP polling + SMTP reply, batch-on-end streaming |

Third-party adapters can register via `gateway.core/register-channel-type!` from their own entry point.

### CLI Flags

`nyma --help` is the authoritative list; this table is the same set.

| Flag | Description | Default |
|------|-------------|---------|
| `-m, --model` | LLM model to use | `claude-sonnet-4-20250514` |
| `--provider` | Provider id (`anthropic`, `openai`, `google`, or any extension-registered one) | `anthropic` |
| `--mode` | `interactive` \| `print` \| `json` \| `rpc` \| `pi-rpc`; anything else exits 2 | `interactive` |
| `-p, --print` | Print mode shorthand | — |
| `--output-format` | With `-p`: `text`, `json` (one claude-style result object) or `stream-json` (JSONL progress events, then that object). Every format exits 1 when the run fails; an unknown format exits 2 | `text` |
| `--permission-mode` | `default` \| `accept-edits` \| `plan` \| `full-auto` | `default` (headless: `full-auto`) |
| `-c, --continue` | Continue last session | — |
| `-r, --resume` | Resume a session (numbered picker) | — |
| `--all` | With `-r`: list sessions from every project | — |
| `--tools` | Comma-separated allowlist of built-in tools (`read`, `write`, `edit`, `bash`, `think`, `ls`, `glob`, `grep`, `web_fetch`, `web_search`, `deep_research`, `retrieve_result`, `view_image`, `skill`) | omit = all built-ins |
| `--thinking` | Extended thinking: `off`…`xhigh` | `off` |
| `--session` | Custom session path | a fresh file per launch |
| `--fork` | Branch a copy of an existing session | — |
| `--no-session` | Disable session persistence | — |
| `--discover` | With `-p`: fetch provider catalogues at startup | off in one-shot runs |
| `--ext-<name>[=v]` | Set a flag registered by an extension | — |
| `--debug` | Debug logging to `~/.nyma/debug.log` | — |
| `--approve`, `--no-approve` | Accepted for pi-frontend compatibility and ignored | — |
| `-h, --help` / `-v, --version` | Print help / version and exit | — |

### SDK / Programmatic Usage

```javascript
import { create_session } from "./dist/agent/modes/sdk.mjs";

const session = await create_session({
  model: "claude-sonnet-4-20250514",
  "system-prompt": "You are a helpful assistant."
});

session.on("message_update", (chunk) => {
  process.stdout.write(chunk.textDelta);
});

await session.send("Hello, what can you do?");
```

The SDK returns an object with:
- `send(text)` — send a user message (async)
- `steer(msg)` — inject a steering message mid-turn
- `on(event, handler)` — subscribe to events
- `state()` — get current agent state

## Configuration

### Settings Scopes

Settings are resolved in priority order:

1. **Runtime overrides** (CLI flags)
2. **Project settings** (`.nyma/settings.json`)
3. **Global settings** (`~/.nyma/settings.json`)
4. **Defaults**

### Workspace Config

The built-in `workspace-config` extension reads `.nyma/settings.json` at startup and registers per-project command aliases and flags:

```json
{
  "aliases": {
    "cc": "/model claude-sonnet-4-6",
    "bye": "/exit"
  },
  "flags": {}
}
```

Aliases become `/slash` commands immediately — `/cc` would switch to claude-sonnet-4-6 in the example above. Manage aliases at runtime with `/alias` and reload the file with `/workspace-config__reload`.

### Default Settings

Source of truth is `defaults` in `src/agent/settings/manager.cljs` — check there
before relying on a value here.

```json
{
  "model": "claude-sonnet-4-20250514",
  "provider": "anthropic",
  "thinking": "off",
  "compaction": { "enabled": true, "threshold": 0.85 },
  "retry": { "enabled": true, "max-retries": 5 },
  "max-steps": 100,
  "step-cap-report": true,
  "dev": { "watch-extensions": false, "eval": false },
  "extensions": {},
  "context-files": ["AGENTS.md", "CLAUDE.md"],
  "steering-mode": "one-at-a-time",
  "follow-up-mode": "one-at-a-time",
  "transport": "auto",
  "tool-display": "collapsed",
  "tool-display-max-lines": 40,
  "scrollback-mode": true,
  "status-line": { "preset": "default" },
  "ui": {
    "overlay": {
      "anchor": "bottom-center",
      "width": "100%",
      "min-width": 40,
      "max-height": "70%"
    }
  }
}
```

Larger maps omitted above because they are long, not because they are optional:
`roles` (12 model/permission/tool-shape presets), `plan-mode`, `subagent`. Read them in
`manager.cljs`.

Project settings cannot widen permissions: `permission-mode` and any `allow` decision in a
role's `policy`/`permissions` are ignored (with a warning) when they come from
`.nyma/settings.json`, and honoured only from `~/.nyma/settings.json` or the command line.
Narrowing (`deny`, `ask`) from a project file is kept, and so is its `permissions.deny`;
its `permissions.allow` is ignored. A checked-out repository must not be able to grant
itself shell. Your own "allow always for this project" answers are therefore stored in
`~/.nyma/settings.json` under `permissions.projects.<absolute project path>.allow`, never
in the project file.

`compaction.strategy` names a summarisation strategy an extension registered with
`api.registerCompactionStrategy(name, {systemPrompt, buildUserPrompt(opts)})` (capability
`context`); absent or `"default"` keeps the built-in six-section prompts. The split,
validation and one fix-retry stay in core — a strategy changes what the summariser is
asked, not how the loop trusts the answer.

`bash-suite.edit-diff` (default on) appends "Files changed by this command" with
`+added/-removed` per file to every bash result, from a git snapshot around the command —
so a `sed -i`, a formatter or an `npm install` is no longer invisible.
`mcp.max-description-length` (default 1200) caps each MCP tool description.

`step-cap-report` (default `true`): when a run hits `max-steps`, spend one more
tool-less call asking the model to report what it found or changed, instead of
ending in silence. It is not an auto-continue — the work stops there — and it
is what turns a step-capped subagent's mid-work text into a usable report.

One exception to "source of truth is `manager.cljs`": `escalate` keeps its
defaults in the extension that owns it
(`extensions/model_roles/features/escalate.cljs`), so there is one place to
change rather than two to keep in sync. See
[`model_roles`](src/agent/extensions/model_roles/README.md#escalation).

`tool-display` is `"collapsed"` (one line per tool call, expandable from the
keyboard — see Interactive Mode) or `"expanded"` (every finished call shows its output).
`tool-display-max-lines` caps the expanded body; the rest is summarised as
`… N more lines`. An `edit` call expands to a `-`/`+` line diff of
`old_string` against `new_string`, a `write` to the content it wrote, and a
failed call previews the first lines of its error even when collapsed.

Keys are accepted in either kebab-case or camelCase — `load-json` normalizes
camelCase to kebab before merging, so `maxHeight` and `max-height` are the same
key.

### Overlay placement (`ui.overlay`)

Where pickers, permission prompts and info overlays are drawn. Every overlay in
the repo shares this — only pass per-call `:overlay` options if one genuinely
needs different placement.

| key | default | notes |
| --- | --- | --- |
| `anchor` | `bottom-center` | Any of pi-tui's nine: `center`, `top-left`, `top-center`, `top-right`, `left-center`, `right-center`, `bottom-left`, `bottom-center`, `bottom-right`. An unrecognised value falls back to the default rather than reaching pi-tui. |
| `width` | `"100%"` | Column count or `"N%"`. Full width by default because pi-tui composites an overlay over the base content at a column offset — anything narrower leaves the editor border and status bar showing on both sides. |
| `min-width` | `40` | Floor, applied after the percentage. |
| `max-height` | `"70%"` | Row count or `"N%"`. pi-tui slices overlay lines from the BOTTOM at this height. |

Bottom-anchored so a permission prompt does not cover the transcript you are
reading in order to answer it. A tall overlay therefore covers the editor and
status bar; overlays capture focus, so the editor is inert while one is open.

### Session files

Conversations live in `~/.nyma/sessions/<epoch-ms>.jsonl` — an append-only tree
whose entries link by `parent-id`. Appends are synchronous, one per message, so
a hard kill loses nothing already written.

Alongside a session you may see `<epoch-ms>.jsonl.partial`. That is the
in-flight assistant response, checkpointed as it streams, because the completed
message only reaches the JSONL when the turn ends — without it, a crash
mid-response lost the whole answer. It is deleted the moment the real entry is
written, so a sidecar sitting next to a session means exactly one thing: that
run died mid-response. The next resume folds it back in, marked as cut off, and
appends it to the JSONL so it survives.

A turn that ends without storing a response — a provider error, an interrupt —
gets the same treatment at `turn_finalize`.

### Verify gate (`verify`)

Off unless `cmd` is set. After any turn that edited a file, the configured
command runs; on a non-zero exit its output is fed back as a follow-up so the
agent fixes the break before declaring done. Bounded by `max-attempts`, and it
flags edits to test files or to `settings.json` itself so a suite weakened into
passing is visible.

```json
{ "verify": { "cmd": "bun test", "max-attempts": 2, "timeout-ms": 120000 } }
```

Project-scoped in practice — the command is repo-specific, so it belongs in
`.nyma/settings.json` rather than the global file. See
`src/agent/extensions/verify_gate/`.

## Extensions

Extensions add tools, commands, keyboard shortcuts, middleware, and UI hooks. They can be written in ClojureScript or TypeScript.

### Built-in Extensions

Nyma ships with several extension suites in `src/agent/extensions/`:

<!-- generated: builtin-extensions (bun run gen:builtins) -->
| Extension | Namespace | Purpose |
|-----------|-----------|---------|
| `add_dir` | `add-dir` | Multi-root context — `/add-dir <path>` registers extra project roots |
| `advisor` | `advisor` | Model-as-critic — `/advisor` sends the full transcript to a stronger model for review, no tools |
| `agent_runner_claude_sdk` | `agent-runner-claude-sdk` | In-process Claude Agent SDK runner |
| `agent_shell` | `agent-shell` | Unified frontend for ACP coding agents (Claude Code, Gemini CLI, etc.) |
| `agent_state` | `agent-state` | Reports what nyma is doing (idle, streaming, tool, waiting) to a supervisor process via a command hook |
| `ast_tools` | `ast-tools` | Tree-sitter–backed code search and editing tools |
| `bash_suite` | `bash-suite` | Shell execution helpers, security analysis, output handling, and a per-command edit diff (files a bash command changed) |
| `budget` | `budget` | Per-turn and per-session token caps that abort a runaway run (off unless `budget` is set) |
| `checkpoints` | `checkpoints` | Snapshots a file's pre-turn state; `/rewind` restores it |
| `claude_hook_bridge` | `claude-hook-bridge` | Claude-Code-shape hooks — runs your hook commands and folds their output into the prompt |
| `custom_provider_claude_native` | `custom-provider-claude-native` | Native Anthropic SDK provider (direct API, no OpenAI shim) |
| `custom_provider_deepseek` | `custom-provider-deepseek` | DeepSeek models via `api.deepseek.com/v1` |
| `custom_provider_groq` | `custom-provider-groq` | Groq models via OpenAI-compatible API |
| `custom_provider_kimi` | `custom-provider-kimi` | Moonshot/Kimi models with thinking-model passthrough |
| `custom_provider_local` | `local` | Any OpenAI-compatible local endpoint, registered from the `local-models` setting |
| `custom_provider_minimax` | `custom-provider-minimax` | MiniMax M2.x models via OpenAI-compatible API |
| `custom_provider_opencode_zen` | `custom-provider-opencode-zen` | opencode-zen models via OpenAI-compatible API |
| `custom_provider_openrouter` | `custom-provider-openrouter` | OpenRouter models via OpenAI-compatible API |
| `custom_provider_qwen_cli` | `custom-provider-qwen-cli` | Qwen models via local CLI provider |
| `custom_provider_relay` | `custom-provider-relay` | Any remote OpenAI- or Anthropic-compatible gateway as a provider (presets: openlux, openlux-claude, openlux-kiro, openlux-codex, velona); a New API billing `group` prices the catalogue from the relay's own sheet |
| `desktop_notify` | `desktop-notify` | System desktop notifications on turn completion |
| `handoff` | `handoff` | `/handoff` writes a purpose-built brief of the session to `.nyma/handoff.md` |
| `headroom` | `headroom` | ML context compression via the Headroom proxy (off by default) |
| `lsp_suite` | `lsp-suite` | Code intelligence via LSP: hover, go-to-definition, find-references, symbols, diagnostics |
| `mcp_client` | `mcp-client` | MCP server integration — third-party tools from `.mcp.json` / `settings.mcp`, with a per-tool description budget (`mcp.max-description-length`) |
| `memory` | `memory` | Agent-maintained `MEMORY.md`, injected each run |
| `model_roles` | `model-roles` | Named model presets (`/role fast`, `/role deep`, etc.), plan mode, and escalation — `/escalate` hands a stuck task to a stronger model, and provider errors fail over down a chain |
| `openwiki` | `openwiki` | AI-maintained, git-aware living documentation for the repo (off by default) |
| `prompt_history` | `prompt-history` | SQLite-backed prompt history with picker UI |
| `questionnaire` | `questionnaire` | Structured user input flows for extensions |
| `refine` | `refine` | `/refine` mines the session for stalls, repeated commands and re-reads, writes a report and offers to append it to `MEMORY.md` (no model-facing tools) |
| `small_model` | `small-model` | Adaptation layer for small/local models: quality monitor, per-model profiles, evidence store, self-tuning playbook (off unless enabled or `--ext-small-model`) |
| `spec_driven` | `spec-driven` | Spec-driven development: durable plans surfaced from markdown specs |
| `stats_dashboard` | `stats-dashboard` | Usage stats and cost aggregation dashboard |
| `subagent` | `subagent` | Context-isolated delegation built on roles: parallel fan-out, chains, background jobs |
| `thinking_renderer` | `thinking-renderer` | Collapsible thinking/reasoning widget with token counts, for ACP agents and native reasoning streams |
| `todos` | `todos` | Persistent todo ledger for session-scoped task tracking |
| `token_suite` | `token-suite` | Token optimizations, smart compaction, live cost preview (`/token-preview`) |
| `verify_gate` | `verify-gate` | Runs a configured test/typecheck command after any turn that edited files and feeds failures back (off unless `verify.cmd` is set) |
| `workspace_config` | `workspace-config` | Per-project aliases and flags from `.nyma/settings.json` |
<!-- /generated: builtin-extensions -->

### Extension Locations

- **Global:** `~/.nyma/extensions/`
- **Project:** `.nyma/extensions/`

### Disabling an Extension

Any extension, builtin or user, can be switched off by namespace in settings:

```json
{ "extensions": { "openwiki": false } }
```

Global (`~/.nyma/settings.json`) and project (`.nyma/settings.json`) entries are merged per
namespace, project winning. A disabled extension is never activated — not even its manifest
defaults — and anything that `dependsOn` it is skipped with the reason `depends on disabled
<ns>`. `/extensions` lists what is loaded, disabled and failed;
`/extensions disable <ns>` and `/extensions enable <ns>` edit the global file
(`--project` for the project file) and take effect on `/reload`. This is distinct from the
per-feature `<section>.enabled` switches some extensions declare: those load the extension and
turn its behaviour off.

### Namespacing and Capabilities

Each extension runs in a **scoped API sandbox**. All tools and commands are automatically prefixed with the extension's namespace, preventing collisions:

```
Extension "git-tools" registers "status" → stored as "git-tools__status"
```

Control which API methods the extension can access via a capabilities list in `extension.json`:

```json
{
  "namespace": "git-tools",
  "capabilities": ["tools", "events", "commands"]
}
```

Available capabilities: `tools`, `commands`, `shortcuts`, `events`, `messages`, `state`, `ui`, `middleware`, `exec`, `spawn`, `providers`, `model`, `session`, `flags`, `context`. Use `all` to grant everything. When no manifest is present, extensions get everything except `exec`, `spawn`, `tools-override` and `middleware` — declare those explicitly.

### ClojureScript Extension

```clojure
;; .nyma/extensions/my_ext.cljs
(fn [api]
  (.registerTool api "my-tool"
    #js {:description "Does something"
         :execute     (fn [params] (str "Result: " (:input params)))})

  (.registerCommand api "my-cmd"
    #js {:description "A slash command"
         :handler     (fn [args ctx] (println "Running!" args))})

  ;; Tools registered as "my-ext__my-tool", commands as "my-ext__my-cmd"
  (fn [] (.unregisterTool api "my-tool")))
```

### TypeScript Extension

```typescript
// .nyma/extensions/my_ext.ts
export default function(api) {
  api.registerTool("search", {
    description: "Search the web",
    execute: async ({ query }) => fetch(`https://api.example.com?q=${query}`)
  });

  // Register middleware
  api.addMiddleware({
    name: "rate-limiter",
    enter: (ctx) => {
      if (tooManyCallsRecently()) ctx.cancelled = true;
      return ctx;
    }
  });

  return () => {
    api.removeMiddleware("rate-limiter");
  };
}
```

### Full Extension API

**Event subscriptions:**
- `api.on(event, handler, priority?)` — subscribe; higher priority runs first (default 0)
- `api.off(event, handler)` — unsubscribe

**Tool registration:**
- `api.registerTool(name, tool)` — add an LLM-callable tool (auto-namespaced)
- `api.unregisterTool(name)` — remove a tool

**Command registration:**
- `api.registerCommand(name, opts)` — add a `/slash` command (auto-namespaced)
- `api.unregisterCommand(name)` — remove a command
- `api.getCommands()` — get all registered commands

**Middleware:**
- `api.addMiddleware(interceptor)` — add a tool-execution interceptor
- `api.removeMiddleware(name)` — remove an interceptor by name

**State:**
- `api.getState()` — read current agent state
- `api.dispatch(eventType, data)` — dispatch a state mutation event
- `api.onStateChange(listener)` — subscribe to state changes, returns unsubscribe fn

**Messaging:**
- `api.sendMessage(msg)` — inject a raw message into state
- `api.sendUserMessage(text, opts)` — inject a user message (`{deliverAs: "steer"|"followUp"}`)

**UI (interactive mode only):**
- `api.ui.available` — `false` in print/json/rpc mode, `true` in interactive mode; always check before calling UI methods
- `api.ui.showOverlay(content)` — display modal content
- `api.ui.confirm(msg)` — show Yes/No dialog, returns `Promise<boolean>`

## Events

The event bus provides lifecycle hooks for extensions. Two delivery semantics:

- **`emit`** — fire-and-forget; handlers run in priority order, return values ignored
- **`emit-collect`** — awaits every handler and merges their JS object returns into a single result map; lets extensions transform the data the caller is about to use

<!-- generated: events (bun run gen:events-doc) -->
| Event | Kind | When |
|-------|------|------|
| `session_start` | emit | Session attached (startup, /resume, /fork) |
| `session_end` | emit | Session closing |
| `session_before_switch` | emit | About to switch to another session |
| `session_switch` | emit | Switched session |
| `session_before_fork` | emit | About to fork the session |
| `session_shutdown` | emit | Process shutting down (SIGINT, /exit) |
| `exit` | emit | A request to leave: cli.cljs runs the same async shutdown SIGINT takes |
| `session_ready` | emit-async | Everything loaded, session attached, model resolved; the one point where ui.available is guaranteed. Fires at startup AND after /reload |
| `session_end_summary` | emit | Stats snapshot just before session_end (desktop_notify reads it) |
| `agent_start` | emit-async | A run begins; awaited, so an extension can finish registering tools before the first turn |
| `agent_end` | emit | A turn's provider call finished: `{text, usage, finishReason}` |
| `turn_start` | emit | A provider call is about to start |
| `turn_end` | emit | One model step finished (AI SDK StepResult) |
| `turn_finalize` | emit-async | Awaited post-turn boundary, before the follow-up drain: `{error, toolCalls, noOpTurns, finishReason}` |
| `message_start` | emit | A streamed text block begins |
| `message_update` | emit | A streamed text delta |
| `message_end` | emit | A streamed text block ends |
| `tool_call` | emit | The model requested a tool call |
| `tool_result` | emit | A tool call returned |
| `tool_execution_start` | emit | Middleware: a tool began executing |
| `tool_execution_update` | emit | Middleware: progress from a long-running tool |
| `tool_execution_end` | emit | Middleware: a tool finished |
| `before_tool_call` | emit-collect | Before a tool runs — set `ctx.cancelled = true` to block, or return `{skip: true, result}` to short-circuit |
| `before_provider_request` | emit-collect | Receives the mutable streamText config; mutate in place or return `{block: true, reason}` to skip the LLM call |
| `model_resolve` | emit-collect | Pick which model to use for this turn; return `{model}` to override the agent default |
| `before_message_send` | emit-collect | Final transform after `context_assembly` and before the LLM call; same return shape |
| `provider_error` | emit-collect | Fires on LLM call failure; return `{retry: true}` to retry once |
| `stream_filter` | emit-collect | Per text delta during streaming; receives `{delta, chunk, type}` and may return `{abort: true, reason, inject: [...]}` to abort the stream and re-run with the injected messages (max 2 retries) |
| `message_before_store` | emit-collect | Last chance to rewrite assistant content before it lands in the store |
| `role_change` | emit | Ask model_roles (owner of :active-role) to switch role |
| `before_agent_start` | emit-collect | First step of each run; return `{systemPromptAddition, system-prompt-additions, prompt-sections, volatile-additions, inject-messages}` to shape the run. `volatile-additions` is per-turn text: it lands after a `---` boundary at the END of the system prompt so the stable prefix stays byte-identical for the provider's prompt cache |
| `input` | emit-collect | User input before it becomes a turn |
| `compact` | emit | Compaction happened |
| `before_compact` | emit-async | Compaction about to run; a handler may set `ctx.summary` |
| `before_branch_switch` | emit | Session tree branch about to change |
| `resources_discover` | emit | Resources (skills, prompts, themes) rediscovered |
| `model_select` | emit | The active model changed |
| `user_bash` | emit | A `!command` typed in the editor ran |
| `reload` | emit | /reload finished |
| `context_assembly` | emit-collect | After messages are built; return `{messages, system}` to replace either |
| `after_provider_request` | emit | Fired after a successful LLM call with `{usage, model, cachedTokens, turnCount}` |
| `acp_connect` | emit | ACP agent connected |
| `acp_disconnect` | emit | ACP agent disconnected |
| `acp_message` | emit | ACP agent message |
| `acp_tool_start` | emit | ACP agent tool call started |
| `acp_tool_update` | emit | ACP agent tool call updated |
| `acp_usage` | emit | ACP agent usage report |
| `acp_mode_change` | emit | ACP agent mode changed |
| `acp_thought` | emit | ACP agent thought block |
| `acp_plan` | emit | ACP agent plan update |
| `acp_commands_update` | emit | ACP agent commands changed |
| `reasoning_start` | emit | Provider reasoning block begins |
| `reasoning_delta` | emit | Provider reasoning delta |
| `reasoning_end` | emit | Provider reasoning block ends |
| `editor_change` | emit | User typing in editor — `{text: string}` payload |
| `notification` | emit | A notification was shown |
| `session_clear` | emit | `/clear` invoked — extensions may reset their agent sessions |
| `user_eval` | emit | A `$expr` typed in the editor ran |
| `tool_complete` | emit | A tool call completed with its result (stats, checkpoints) |
| `permission_request` | emit-collect | Per-tool approval; return `{decision: "allow"\|"deny"\|"ask"}` |
| `tool_access_check` | emit-collect | Filter the tool list for the next call; return `{allowed: [name, ...]}` (merged by intersection) |
| `input_submit` | emit | Editor submit |
| `turn_request` | emit | An extension asking for a turn to start: `{text, echo}`; interactive mode dispatches it |
<!-- /generated: events -->

### Tool extension context (`ctx.modelId`)

Tool `execute(args, ctx)` functions receive a context object with the active model ID at `ctx.modelId` — useful for tools that want to adapt truncation, top-k, or output verbosity to the model in play.

### Async Events

Use `emit-async` when handlers need to complete before the caller proceeds:

```clojure
;; In extension code:
((:emit-async bus) "before_compact" data)  ;; awaits all handlers
```

## Resources

### Skills

Two frontmatter fields auto-activate a skill for one turn: `triggers` (phrases matched
against the user's prompt, case-insensitively and on word boundaries, so `the` does not
match `theme`) and `paths` (globs matched against files the session has touched via tool
calls). The skill's body is injected after the cache boundary as `volatile-additions`; a
turn without a match costs nothing. Skills marked `disable-model-invocation: true` never
auto-activate, at most three bodies are injected per turn, and a skill already activated
explicitly is not injected again.

Place a directory with a `SKILL.md` file in `~/.nyma/skills/` or `.nyma/skills/` (the cross-vendor `.claude/skills/`, `.agents/skills/`, `.cursor/skills/` and `.codex/skills/` paths are scanned too). Skills follow the [agentskills.io](https://agentskills.io/specification) layout: YAML frontmatter, then the instructions. Activating one injects the body (not the frontmatter) as a system message and loads an optional `tools.cljs`. A skill's identity is its **directory name**, per the spec; a `name:` in frontmatter that disagrees is reported by `/skills doctor` and ignored.

**Skills from a checked-out repository are instructions-only.** A skill found under the
working directory — in `.nyma/skills/` or any of the cross-vendor paths — contributes its
body and nothing else: its `allowed-tools` is ignored and its `tools.cljs`/`tools.ts` is
never loaded, each refusal warned at activation. A repository you cloned must not be able
to pre-approve `bash` or run code just because you opened it. It may also auto-activate on
a `paths` match, where you are demonstrably working in a file it claims, but not on a
`triggers` match, where the phrase is the skill author's choice. To trust a skill fully,
move it to `~/.nyma/skills/`.

**Activating skills:**
- `/skills` — opens a fuzzy picker to browse and activate available skills
- `/skills doctor` — what each skill costs per request, where it came from, whether its tools and grants are gated, how often it has fired this session, and any spec violations
- `/skill <name> [args]` or `/skill:<name> [args]` — activate by name; `args` fill `$ARGUMENTS`, `$1`…`$9` and `${N:-default}` in the body
- the `skill` tool — the model activates a skill itself when its description fits the task, and reads the body as the tool result

Frontmatter fields that change behaviour:
- `description` — shown in `/skills`, the system prompt's skill list and the `skill` tool
- `allowed-tools` — space-separated tool names the skill may call **without a permission prompt** while it is active (a deny from a permission handler still wins)
- `disable-model-invocation: true` — hidden from the model: not listed in the prompt, not reachable through the `skill` tool, `/skill <name>` only
- `license`, `compatibility`, `metadata` — preserved per the spec and shown by `/skills doctor`

A `references/` directory is the spec's third disclosure level: its files are **named** in
the skill's body on activation and read only if the model asks for one, so bundled
reference material costs nothing until it is wanted.

Descriptions are listed in the system prompt on every request, so each is capped at
`skills.max-description-length` (default 500) with a marker saying how much was cut. The
`skill` tool's own description lists names only — it used to repeat every description, so
each one was sent twice per request.

Active skills are tracked in agent state (`:active-skills`) to prevent duplicate injection.
Deactivating a skill removes its injected message, its tool grants and any tools its
`tools.cljs` registered.

```
~/.nyma/skills/
  git-helper/
    SKILL.md      ← ---\nname: git-helper\ndescription: Automates git workflows\nallowed-tools: bash\n---\n…
    tools.cljs    ← optional extra tools (loaded on activation)
```

### Prompts

Place `.md` files in `~/.nyma/prompts/` or `.nyma/prompts/`. Each one becomes a slash command named after the file: `review.md` is `/review`. Arguments fill `$ARGUMENTS` (all of them), `$1`…`$9` and `${N:-default}`, and the result is sent as your message. Optional frontmatter describes the command in `/help` and the autocomplete picker:

```markdown
---
description: Review a file for bugs
argument-hint: <path>
---
Review $1 for correctness bugs. Focus on ${2:-error handling}.
```

A template never shadows an existing command — a name collision is skipped with a warning. `/reload` picks up new or changed templates.

### Themes

JSON theme files in `~/.nyma/themes/` or `.nyma/themes/`, plus a bundled base16
pack (`nord`, `dracula`, `gruvbox-dark`, `tokyo-night`, …). `/theme` lists them
and `/theme <name>` switches immediately — transcript, status bar, editor and
pickers repaint in place, and the choice is saved to `.nyma/settings.json`.
`/reload` re-resolves the theme too, so an edited theme file takes effect
without a restart. Colour slots: `primary`, `secondary`, `error`, `warning`,
`success`, `muted`, `border`, `info`, `plan`, `editor-border` and the
`context-*` ramp.

### System Prompt

Create a `SYSTEM.md` in `.nyma/` or `~/.nyma/` to provide a custom system prompt. Create an `AGENTS.md` (or a `CLAUDE.md`) at the project root to provide project-specific context.

Context files are read from `~/`, `~/.nyma/`, every ancestor from the repository root down to
the working directory, the working directory and its `.nyma/`, lowest precedence first. At each
of those directories the first name in the `context-files` setting that exists is taken — default
`["AGENTS.md", "CLAUDE.md"]`, so a repo carrying both injects AGENTS.md only. The home level is
`~/` itself, so `~/CLAUDE.md` is read exactly like `~/AGENTS.md` (`~/.claude/CLAUDE.md` is not).
Change the order, or add a name, in settings:

```json
{ "context-files": ["CLAUDE.md", "AGENTS.md", "CONVENTIONS.md"] }
```

## Documentation

- [`docs/extension-guide-cljs.md`](docs/extension-guide-cljs.md) — writing extensions in ClojureScript
- [`docs/porting-guide-ts.md`](docs/porting-guide-ts.md) — porting pi-mono TypeScript extensions
- [`docs/hooks.md`](docs/hooks.md) — Claude-Code-shape hooks reference
- [`docs/mcp.md`](docs/mcp.md) — MCP servers as tool sources
- [`docs/gateway.md`](docs/gateway.md) — gateway mode: channels, streaming and session policies
- [`docs/agent-shell.md`](docs/agent-shell.md) — driving other coding agents over ACP
- [`docs/event-map.md`](docs/event-map.md) — generated map of every event emitter and listener
- [`docs/extension-ideas.md`](docs/extension-ideas.md) — extension and feature ideas by priority
- [`docs/roadmap.md`](docs/roadmap.md) — built-but-unwired infrastructure, deferred work, audit ledger

## Dependencies

| Package | Purpose |
|---------|---------|
| `ai` | Vercel AI SDK — streaming, tool loop, provider abstraction |
| `@ai-sdk/anthropic` / `@ai-sdk/openai` / `@ai-sdk/google` | Built-in LLM providers |
| `@anthropic-ai/claude-agent-sdk` | Claude Code as a subagent runner (`agent_runner_claude_sdk`) |
| `@earendil-works/pi-tui` | Terminal UI: editor, overlays, components, `@` autocomplete |
| `@modelcontextprotocol/sdk` | MCP client for `mcp_client` |
| `headroom-ai` | Tool-output compression (`headroom` extension) |
| `marked` / `marked-terminal` | Markdown rendering in the chat pane |
| `turndown` / `linkedom` | HTML → Markdown for the `web_fetch` tool |
| `shell-quote` | Command tokenising for `bash_suite` security analysis |
| `vscode-jsonrpc` | JSON-RPC transport for `lsp_suite` |
| `zod` | Schema validation for tool parameters |
| `squint-cljs` | ClojureScript-to-JS compiler |

## License

MIT
