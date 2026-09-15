# Changelog

## 0.9.0 — unreleased

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
