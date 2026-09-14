# Changelog

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
