# Changelog

## 0.13.0 — 2026-11-13

Six weeks and 311 commits. The headline is containment: bash runs inside a
sandbox by default, a judge answers the permission prompts a human should not
have to, and subagents can no longer spend, write or hang beyond what their
parent allowed. Alongside it, nyma speaks pi's protocols and the Agent Client
Protocol, so it runs inside Emacs, Zed and JetBrains unchanged.

### Added
- **Sandboxed bash.** The main session's bash is confined to its cwd on
  Anthropic's sandbox-runtime (Seatbelt on macOS, bubblewrap + seccomp on
  Linux): writes only under the project and temp, no reads of `~/.ssh`,
  `~/.aws`, credentials or nyma's own config, network only to the package
  registries plus `sandbox.allow-hosts`. A command that needs a new host asks;
  only the permission gate can let a command out, and a worktree child never
  gets out. Provider keys are scrubbed from the sandboxed environment, and a
  denial is named in the tool result so the model stops retrying it.
- **A judge for permission asks.** `utils/decide` puts one choice question to
  TypeSafe's Jev and acts on a confident answer: unlisted bash commands, MCP
  calls, `web_fetch` hosts, skill selection and the finalize nudge. Each use is
  opt-in on its own key; writes get a stricter bar; hidden characters and
  look-alike names stay visible to it. Every decision is logged so the judge
  can be checked against what it said.
- **Workspace trust.** A checked-out repo's hooks, commands, MCP servers and
  subagent roles do not run until the user says so, in the TUI, the gateway
  and the SDK alike.
- **pi compatibility.** `--mode rpc` and `--mode json` are pi's protocols;
  `--mode pi-rpc` runs the pi Emacs frontends and other pi RPC clients
  unchanged, including extension dialogs and permission prompts. pi's `.pi`
  skills, prompts and extensions load as they are, pi's CLI flags are accepted,
  and `sessions.pi-mirror` keeps a copy of each session in pi's file format.
- **`--mode acp`**: an Agent Client Protocol server, for Zed, JetBrains and
  any other ACP editor. Plan mode, session allows and model text map onto
  ACP's modes and permission options.
- **Subagents.** `--worktree` and worktree-isolated children with their own
  checkout; a child is a session you can open, show while it runs, and
  continue or steer by id. Roles fall back to the parent's model and honour
  `--settings`. `/review [base]` reviews the change as a read-only subagent;
  an opt-in critic reviews a run's change once at the end.
- **Checkpoints.** Persistent rewind points covering bash changes too;
  `/undo` picker, `/diff` for everything the session changed, `/fork` offers
  to restore files changed after the fork point.
- **Codemode.** Squint scripts that call tools, MCP and OpenAPI as functions,
  with typed returns, helpers, saved scripts and a per-call view of what each
  returned. Scripts run only what was approved and cannot grant themselves
  anything.
- **MCP.** `mcp_search` returns typed signatures (raw schema on request);
  file results are saved to disk; per-server env, timeouts and tool
  allow/deny; `/mcp add`; `readOnlyHint:false` asks. Generic tool deferral in
  token-suite withholds any tool's schema until searched for.
- **Images and documents.** `@shot.png` or ctrl+v attaches an image; PDFs and
  documents reach the model; images travel over rpc, pi-rpc and the gateway
  and are stored beside the session.
- **Rendering.** Markdown via `Bun.markdown.ansi`, laid out to the width,
  code blocks indented instead of framed, line breaks kept in lists and
  quotes. `/copy code [N]` copies a block exactly.
- **Cost and progress.** Live cost, step and cache hit rate on the status
  line; a capped run earns more steps with evidence of progress; a run
  repeating itself is detected and escalated; opt-in fallback model chain per
  role; prices and context windows generated from models.dev.
- **Smaller.** `/commit` drafts a Conventional Commit; `/compact <focus>`;
  `/jobs`; `/permissions` with bash command-pattern grants; `/resume <words>`;
  Claude Code's `.claude/commands` as prompt templates; flat `<name>.md`
  skills; shift+tab cycles the permission mode; alt+enter queues a follow-up
  for after the turn; `--settings <json>`, `--max-steps`,
  `--append-system-prompt`, `-` reads stdin; a notification when a permission
  prompt is waiting; `settings.startup` turns the project card down.

### Fixed
181 fixes. The ones that changed behaviour you would notice:
- **Permissions.** "Allow always" no longer covers interpreters, wrappers,
  verbs that run their arguments, or a tool's exec flags; "Allow for this
  session" ends with the session; a tool that declares no metadata no longer
  walks past the gate; a `web_fetch` grant is an origin with no query.
- **bash-suite.** Chained commands, redirects, backgrounded commands, inline
  interpreter scripts and shells invoked by path all reach the classifier; a
  quoted heredoc to `cat`/`tee` is content, not commands; unlisted commands
  ask instead of running.
- **Subagents.** A child asks through the parent's gate, runs the parent's
  tool hooks, is billed to the parent, cannot exceed the parent's limits or
  depth, cannot hang the parent, and is not a success when its worktree died.
- **Sessions.** `/new` and `/fork` actually start and branch; disk is written
  before memory; failed tool calls are kept; totals follow the session they
  describe; failed compactions no longer leave the conversation in `$TMPDIR`.
- **Loop.** A steered message reaches the model in the same run; no provider
  retry once a tool has run; context overflow recovered where it arrives;
  `max_tokens` stays inside the context window; a response gets the model's
  own output limit; no temperature sent to models that reject sampling (Opus
  4.7+, Sonnet 5+, Fable), which also silences one SDK warning per request.
- **Thinking.** Adaptive thinking with effort on current Claude models; `off`
  leaves always-thinking models at their default instead of dropping them to
  low.
- **web_fetch.** http(s) only; private and link-local addresses refused on
  every hop, IPv4 hidden in IPv6 included; a cross-host redirect goes back to
  the model; a refused URL reaches no fallback provider.
- **CLI.** `-p` no longer blocks on a pipe nobody closes; SIGTERM and SIGHUP
  run the shutdown sequence, and `nohup` works; `--extension`/`--skill`
  survive `/reload`; a compiled nyma resolves the packages its extensions
  import; ctrl+v image paste works in the binary.
- `.nymaignore` covers every filesystem tool; 16-colour terminals get
  distinct semantic colours; a space no longer reopens the `@` popup.

### Performance
- Startup: heavy modules and sandbox-runtime load on first use.
  `--version` 0.48–0.65 s → 0.41–0.53 s under bun.
- Streaming: only the tail of a reply is lexed per chunk. 40 KB in 200 B
  chunks 1373 ms → 89 ms; worst frame 52.8 → 9.3 ms.
- No synchronous git on the streaming path: the status poller and
  edit-diff's snapshots spawn asynchronously; the repo root is cached per cwd.
- The first turn waits at most 2 s (`mcp.join-timeout-ms`) for MCP servers
  still starting; the model is told which are late instead of the turn
  stalling 15–18 s behind nine servers.
- Checkpoints save touched files once per bash command.

### Notes
- Dependencies: ai 7.0.127, pi-tui 1.0, squint 0.14.210, marked 18. Binaries
  compile with `--no-orphans`. Bun is pinned to 1.4.2 in CI.
- Three settings keys nothing read were dropped (`status-line.preset`,
  `status-line.separator`, `subagent.max-depth`); ~110 lines of unreachable
  code went with them. docs/roadmap.md lists what this sweep deferred.

## 0.12.0 — 2026-09-30

Project awareness. Opening a project now tells you where you are, and fifteen
status-line segments that were implemented but unreachable can finally be
selected.

### Added
- A project card at session start: branch and working-tree state, what the agent
  loaded, and what the standing prompt costs.

      nyma 0.12.0  ·  ~/projects/pers/nyma
        main ~3 ?9 ↑2
        40 extensions · 24 skills
        AGENTS.md ~11k tok

  The AGENTS.md figure is the one most worth having — measured at ~11k tokens in
  this repo, the bulk of the system prompt, and nothing surfaced it before. Every
  line is optional: a missing fact omits its line rather than printing a zero,
  because `0 skills` reads as a broken install and silence reads as nothing to
  say. A clean repo in sync shows only its branch.
- `settings.status-line` works again and now selects which built-in segments
  appear, on which side, and **in what order**. Twenty exist; four could render.
  `git`, `path`, `pr`, `session`, `hostname`, `token-in/out/total/rate`,
  `context-total`, `cache-read`, `cache-write`, `model`, `role` and `time` were
  registered and unreachable, and the key that would have selected them was
  marked inert. Ordering was previously `sort-by :id` alone, so `context-pct`
  could never sit right of `cost`.

      "status-line": {
        "left-segments":  ["activity", "path", "git"],
        "right-segments": ["context-pct", "compact-in", "cost", "time-spent"]
      }

  Unset reproduces the previous four exactly. An unknown id warns once at startup
  and the rest of the line still renders. Extension segments are untouched — they
  manage their own placement.
- `compact-in`: how much room is left before the session compacts. `ctx:%` says
  how full the window is, not when the conversation will be rewritten under you —
  on a 65k window compaction fires at 49,152, so `ctx:73%` is already nearly
  there. Hidden when compaction is disabled or already past the point.
- Live git on the status line, from a 5s poller rather than a render.
  `settings.status-line.poll-git` turns it off entirely (no timer, no first poll).
- LSP diagnostic counts, and the last verify-gate result, as segments.

### Fixed
- Child `git` stderr reached the terminal. `execSync` inherits stderr unless told
  otherwise, and `rev-list @{upstream}` fails on any branch without an upstream —
  the common case. At a 5s poll that is twelve `fatal: no upstream configured`
  writes a minute landing mid-render and corrupting the TUI. Both git helpers
  ignore it now.
- `NO_COLOR=1` left bare `\e[0m`, `\e[1m` and `\e[2m` in the status line. `fg`
  honoured the setting; three hardcoded escape constants did not. 35 escapes to 0
  on a fully-populated line.
- `git status --porcelain` was parsed after `run-git` trimmed both ends of stdout,
  which eats the leading space of the first line — so ` M path`, a worktree-only
  change, read as staged. `run-git-raw` trims only the trailing newline. The
  index and worktree columns are independent, and a conflict (`UU`/`AA`/`DD`) is
  no longer counted as an ordinary staged change.
- A home-prefix path check without a separator turned `/Users/meg/work` into
  `~g/work` for a user whose home is `/Users/me`.
- `{"left-segments": []}` silently restored the default segments instead of
  meaning "none".

### Notes
- Benchmark cross-check on a second model and machine (Qwen3.8-27B on a DGX
  Spark): the 0.11.0 small-model fixes hold — 92% → 100% pass, 65% fewer tokens,
  51% fewer turns, 80% fewer shell calls. The deferral arm moved the *other* way
  on pass rate there than on the Mac, which is the flaky-task variance 0.11.0
  documented rather than a regression.

## 0.11.0 — 2026-09-25

A silent data-loss bug in the write path, the override chaining defect underneath it, and
the first measurements that show what any of it costs. Measured on 25 JavaScript tasks
against a local Qwen3.6-35B, on the tasks that passed in every arm: per-request cost fell
from 23,249 to about 7,300 tokens, and the small-model profile from 111,271 to 43,289
tokens per task with 63% fewer shell calls. It now costs less to run than the plain agent
did.

### Fixed
- `write` could report `Wrote N bytes` while writing nothing. token-suite's result
  compression rebuilt every edit/write result from the tool's ARGUMENTS without checking
  what the tool returned, so read-guard's refusal — deliberately a string, not a throw —
  was replaced by a byte count. The model believed it, tests kept failing against an
  untouched file, and one benchmark task spent 600 seconds re-writing, re-reading and then
  debugging the write tool before working around it through bash. Only a success the native
  tool reports is compressed now; anything else passes through.
- `overrideTool` silently dropped an earlier extension's wrapper. The registry kept one
  write-once slot per tool name, so a second overrider chained past the first to the true
  native and unregistering restored the native rather than the layer beneath. With
  mcp-client overriding `read` and `edit` after small-model had wrapped them, read-guard's
  path recorder never ran, its edit guard was discarded entirely, and its write guard
  consulted a permanently empty set — refusing every write onto an existing file, all
  session. The registry keeps a stack now and each override unwinds exactly its own layer.
- Tool names are matched the way they are spelled. `registerTool` namespaces an extension's
  tool as `<ns>__<name>`, so matching a bare name against the live set found only native
  tools: an `editStrategy` could not reach the `multi_edit` it routes onto, and
  `gateway-tool-names` — which exists so an allowlist written before MCP deferral cannot
  strand the deferred route — was protecting only `retrieve_result`.
- read-guard records a read it did not serve, via `tool_complete` rather than its own
  wrapper, so an MCP-overridden `read` still unlocks the subsequent write. An `[ERROR] …`
  string from a failed MCP read no longer counts as a read. Paths are compared by realpath,
  so one file cannot occupy two keys through a symlinked temp dir.
- A supervisor with nothing to say says nothing. The advisor tool always returns a string so
  the executor model can react to a refusal, which meant it reported its own failures as a
  successful call — and `Advisor: call failed — the model service is temporarily
  unavailable` was injected into a small model's context as guidance, three times in one
  task. Failed interventions are still charged against the budget so an unreachable advisor
  cannot retry all run.
- quality-monitor's two follow-up nudges are bounded. The turn-budget warning was delivered
  as a followUp, which is itself a new turn, against an unlatched `>=` — so crossing the
  budget kept re-warning until the outer cap. The empty-turn nudge had no bound at all and
  the counter that looked like one was being cleared by a handler that runs before the one
  reading it.
- small-model's `respond` tool is registered rather than injected into the per-request tool
  map after wrapping, so its interceptor fires and the turn-termination path it documents
  exists for the first time.

### Added
- Tool schemas are withheld when nothing could serve them: the nine LSP tools unless a
  configured server's binary is on PATH *and* handles a file type the workspace contains,
  and `questionnaire` on a host with no interactive prompt. Worth about 2,300 tokens per
  request in a headless run. Settings: `lsp.gate-tools`.
- An extension's own model call is counted and priced by its own model. advisor, and through
  it small-model's supervisor and self-tune, called `generateText` directly and read only
  the text, so a benchmark arm making six paid consults reported the same cost as one making
  none. Pricing uses the caller's model key, not the agent's, which would have billed a
  frontier advisor at a local worker's rate.
- `bench/RESULTS.md`, generated, one row per run with sha, model and task set. Figures are
  recomputed from raw results rather than read from stored fields, because a timed-out task
  reports no usage and dividing by every attempted task understates cost by exactly the
  expensive tail.
- The benchmark samples a vLLM server's prefix-cache counters when the route reports no
  per-request cache tokens, so a token cut cannot be confused with a cache collapse.

### Notes
- Single-trial pass rates on the 25-task subset are not comparable. Across eleven arms, 14
  of 25 tasks never failed and no task failed in every arm — every failure came from the
  same 11 tasks. Use `--trials 3` and read `pass^3`/`pass@3` before drawing a conclusion
  from a pass rate.

## 0.10.0 — 2026-09-23

Research into the apprentice harness, billing groups on New-API relays, and a two-phase
program to open the seams that were closed by hand-rolled code.

### Breaking
- A checked-out project's `.nyma/settings.json` can no longer widen permissions. Its
  `permissions.allow` list, its `permission-mode`, and any `allow` decision in a role's
  policy or permissions are dropped with a warning; deny and ask are still honoured.
  Answering "allow always for this project" now writes to your own settings file under
  `permissions.projects.<project path>.allow`, so a repository you clone cannot pre-approve
  tools on your behalf. Existing project-level allow lists must be moved there by hand.
- The `roles` section of settings merges per role instead of replacing the section. Defining
  one custom role used to erase all twelve built-ins, subagent roles included; it no longer
  does. A role of the same name as a built-in still replaces that one role whole.
- The bash tool's JSON result gains a `filesChanged` field when a command edits tracked
  files. Anything parsing that envelope sees a new key.
- MCP tool descriptions are truncated to 1200 characters by default, with a note; raise it
  with `mcp.max-description-length`.

### Added
- Relay providers take a billing `group`, and `openlux-kiro` / `openlux-codex` ship as
  presets. Relay model costs are read from the relay's pricing endpoint, so relay models are
  priced instead of free-looking.
- Bash results list the files the command changed, with added and removed line counts.
  Setting `bash.edit-diff`.
- Skills activate from the `paths` and `triggers` declared in their frontmatter.
- A `lead` role that may delegate to subagents.
- `registerCompactionStrategy`: an extension can replace summarisation wholesale.
- Dev loop: `/reload <ns>` reloads one extension, `/eval <form>` evaluates against the live
  agent, `/replay` re-reduces the session event log. `bun run dev` reloads extensions on save.
- `gen:namespaces` and `gen:events-doc` generate the AGENTS.md namespace table and the README
  event table; CI fails on drift.

### Changed
- Prompt assembly is ordered for cache reuse, with all per-turn volatile text after a
  boundary so the cacheable prefix stays byte-identical between turns.
- A turn that hits its step cap is nudged to continue rather than stopping silently.
- The interceptor chain is one fold; the event catalogue is one registry map; the scoped
  extension API's capability gates are a table.
- Tool parameters are declared once as data and rendered to zod, JSON Schema and docs.

### Fixed
- Option lookups honour `false` and `0` instead of falling through to the next spelling.
- Cross-extension events reach their listeners; the bus used to prefix the listener's own
  namespace on emit, so they never could.

## 0.9.0 — 2026-09-15

A peer-comparison sweep against Claude Code and pi, then a correctness review of everything
it added.

### Breaking
- Hook-bridge payloads use Claude Code's field names, so a hook script written for Claude Code
  reads unchanged: `tool_result` → `tool_response`; PostToolUseFailure `error_message` →
  `error`; StopFailure `error_type` / `error_message` → `error` / `error_details`;
  SessionStart carries `source` only (its stray `reason` is gone) and SessionEnd `reason`
  only (`source` is gone); Stop gains `stop_hook_active` and `last_assistant_message`;
  PreCompact gains `custom_instructions`, PostCompact `compact_summary`. `session_id` and
  `transcript_path` are the live session's, not the placeholders every event used to send.
- SessionEnd's reason for Ctrl-C at the prompt is `prompt_input_exit` (was `other`); `/exit`
  stays `other`.
- `api.setLabel` is removed; no reader ever existed.
- An SDK / gateway session created without `:system-prompt` gets the same default prompt the
  CLI builds (AGENTS.md, CLAUDE.md, `context-files`) instead of none.
- `agent_end` fires once per run. Listeners that tolerated the raw finish chunk followed by the
  real payload now see only the payload (`{:text :usage :finishReason}`).

### Added
- `-p --output-format stream-json`: one JSON progress event per line while the run is in
  flight, then the `result` object as the last line.
- `/extensions disable|enable <name>`, persisted in settings; a disabled extension's
  dependents are skipped with the reason.
- `context-files` setting: which project files (AGENTS.md, CLAUDE.md, …) join the system
  prompt; `~/CLAUDE.md` is read like `~/AGENTS.md`.
- Skills follow agentskills.io: `/skill:name args` fills `$ARGUMENTS`, `$1`…`$9` and
  `${N:-default}`; `allowed-tools` frontmatter answers the permission ask while the skill is
  active; a `skill` tool lets the model activate one.
- Prompt templates: every `.md` in `~/.nyma/prompts/` or `.nyma/prompts/` is a slash command.
- ctrl+o expands a tool call's output; `edit` shows a diff; bash shows stdout, not its JSON
  envelope.
- `/theme <name>` applies without a restart.
- `@file` mentions with autocomplete; the file (or directory listing) is expanded on submit.
- `/hotkeys` ends with a Conflicts section for keys bound twice.
- `/agent pool` lists live ACP workers; `/agent disconnect <dir>` stops one directory's.
- Every builtin tool declares `:safety`, so the permission gate never files one under "other".
- `home/dir`: one place that resolves the home directory, from `HOME` first.

### Fixed
- `/compact` reports what actually happened: which summariser ran and why the extension
  summary was rejected, instead of "Nothing to compact" after compacting.
- Escape and Ctrl-C mid-stream show `ℹ aborted`, not `✗ unknown error`.
- Session files are `<ms>-<random>.jsonl`; two instances started in the same millisecond no
  longer write to one file.
- Warnings and errors go to `~/.nyma/debug.log` while the TUI is up instead of painting raw
  log lines over the transcript.
- The permission prompt shows the command you typed, not bash-suite's `unset LD_PRELOAD …`
  wrapper; a denied call runs no PostToolUse hook, so the denial reaches the model clean.
- `/planmode cancel` says what to do next; `/mcp-status` lists each candidate config file on
  one line; the "no tools for two turns" stall warning waits for three and for a tool to have
  run at all.
- A turn requested from inside a slash command (a prompt template, `/spec import --run`) no
  longer vanishes under the submit lock; if the lock never clears it is queued as a follow-up.
- `/review` with no arguments sends an empty `$ARGUMENTS`, not the literal text; a skill body
  read with no arguments still keeps its quoted `echo $1`.
- Ctrl-C during a turn aborts it and says how to exit; the second press exits.
- The hook bridge's PreCompact matcher is `manual` for `/compact` (it was always `auto`) and
  PostCompact's `tokens_removed` is the real before − after (it was always 0).
- `/history` and `/stats` find the SQLite store, whose tables are now created on first launch.
- Gateway: an ephemeral session's extension handlers are torn down when it is dropped; the
  reply is sent once on channels that post rather than edit; sync HTTP replies carry the text;
  polling an async job before it finishes gets 202; Slack socket-mode messages are handled;
  every `<` in an email message id is rewritten.
- Every path under `~/.nyma` resolves `HOME` per call, so a `HOME` set after start (tests,
  a future `--home`) is honoured; the tests no longer read or write the developer's real
  `~/.nyma`.
- ctrl+o output survives terminal wrapping and appended PostToolUse hook text.
- RPC: a JSON record containing U+2028/U+2029 arrives whole; `on-close` runs once; stdin-EOF
  exit is a CLI option, not a default; stream-json no longer writes a duplicate `agent_end`.
- OAuth reads `~/.nyma/auth` from `HOME` at call time; no `TERM` means full colour, not 16.
- An unknown `--mode` is refused up front with exit 2 instead of crashing in mode dispatch.
- `/new` and `/clear` end active skills; `/reload` re-registers the `skill` tool and prompt
  templates after `session_ready`; `/extensions enable X` says when a project setting still
  disables it; `/add-dir` refreshes the `@file` listing.

## 0.8.0 — 2026-09-14

Three review passes over the whole tree — the core↔extension seam, maintainability, and the
user experience — and everything they found that was worth fixing.

### Extension system
- Every registration an extension makes through its api is recorded and swept at unload, so
  `/reload` no longer stacks handlers (five builtins leaked on every reload).
- The permission gate decides on the arguments that will actually run, gates extension tools
  by their declared `:safety`, and no longer grants a manifest-less extension everything.
- `extension.json` declares `settings` defaults; `api.settings` replaces the guarded reads.
- `model_roles` owns `:active-role`; other extensions ask via `role_change`.
- A user extension with a builtin's namespace replaces the builtin.
- Dead code out: the macro DSL, unused protocols, the data→Zod compiler, two orphan
  namespaces. The SQLite store behind `prompt_history` and `/stats` is wired for the first time.

### Interface
- Status line shows cost, context fill and an elapsed clock; slash commands show busy, Escape
  aborts them, failures are named; `/compact` reports its effect.
- The permission prompt shows the command, path or first changed line and the policy reason;
  "Allow for this session" added.
- Failed tools render `✗`; notifications keep their level; extension and provider errors reach
  the transcript with what to do about them.
- `NO_COLOR` and 16/256-colour terminals; ASCII icons for non-UTF-8 locales; unified picker
  keys; editor border colours by prefix.
- Autocomplete, `/help` and "did you mean" share one vocabulary; `/help` is grouped;
  `/hotkeys` lists what is bound; `/extensions` names disabled extensions and how to enable
  them; `/hooks` lists resolved hooks.
- `/plan` is gone: `/planmode` (native) and `/agent mode plan` (ACP). `/roles` and `/mode`
  no longer share entries. `/handoff` works again; `/q`, `/quit`, `/?` work for the first time.

### Command line
- `nyma "prompt"` seeds the first turn. Ctrl-C interrupts the turn; a second press exits.
- `-p` prints the last assistant message and exits 1 on error in both output formats; nothing
  but the answer reaches stdout.
- Startup errors are one line; unknown flags point at `--help`; `--help` documents the
  environment; a half-written session no longer crashes `-c`; credentials are written 0600.
- One line at startup while extensions load, one at exit with cost and the session file.

### Under the hood
- `^:async` on an anonymous fn works when the meta is on the form — the folklore said it
  never did. AGENTS.md corrected.
- Tests: scratch HOME, module globals restored per file, stale or orphaned `dist/` fails,
  dead-namespace lint, per-builtin residue test, pre-commit hook (`bun run hooks:install`).

## 0.7.1

Fixes found by the first CI that ever ran this suite off one machine.
