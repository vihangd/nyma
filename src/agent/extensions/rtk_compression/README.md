# rtk-compression

> Route `bash` commands through [rtk](https://github.com/rtk-ai/rtk) to cut tool output by 60–90% before it reaches the model.

## What it does

RTK (Rust Token Killer) understands the output format of popular CLI tools — `git`, `cargo`, `npm`, `pytest`, `rg`, `diff`, etc. — and rewrites each command so its output stays semantically complete but dramatically smaller. A noisy `cargo test` drops from thousands of tokens to a few hundred without losing the failing-test list, line numbers, or error messages.

Instead of compressing output *after* the fact (which would require piping through `rtk` via stdin — unsupported), this extension follows the canonical pattern used by every agent integration (Claude Code, OpenCode, Cursor, Windsurf, Cline): **rewrite the command before execution**. The LLM asks for `git status`; a middleware hook rewrites it to `rtk git status`; `rtk` runs `git` itself and prints the compressed view.

If the `rtk` binary isn't on `PATH`, the extension logs a single warning at startup and does nothing — bash commands run unmodified.

## How it works

1. At activation, `which rtk` runs in the background. On success, a gate opens; on failure, the gate stays closed and the middleware is a no-op forever.
2. The middleware runs in the `bash` tool's `:enter` phase — **after** the permission check, **before** `execute-tool`. This order is important: your allowlist still sees `git status`, not `rtk git status`.
3. For every bash command, the middleware:
   - Parses with `shell-quote`
   - Bails if the command contains pipes (`|`), `&&`, `||`, `;`, `&`, redirects (`>`, `<`), subshells (`$()`, backticks), or anything rtk can't safely wrap
   - Looks up the first token in a closed whitelist
   - If matched and not user-disabled, rewrites `args.command` in place to `rtk <original>`
4. Bash runs the rewritten command. `bash-suite/output_handling` still runs in the leave phase as a safety net for the rare case where rtk's output is still large.

The LLM's message history keeps the original `git status` it asked for, so follow-up turns aren't confused by the rewritten form. Nyma's telemetry (`tool_complete`) does show the rewritten command — intentional, so savings are visible in logs.

## Supported commands

Built-in whitelist: `git`, `cargo`, `npm`, `pnpm`, `pytest`, `ruff`, `tsc`, `eslint`, `rg`, `grep`, `find`, `ls`, `cat`, `head`, `tail`, `diff`.

Any other command is passed through untouched. Unknown tools are never wrapped.

## Extensibility

Other extensions can contribute rewriters by emitting an event during their own activation:

```clojure
(.emit api "rtk-compression:register-rewriter" #js {:id "pulumi"})
```

After this, `pulumi up` rewrites to `rtk pulumi up`. The `:id` is both the match prefix (first command token) and the rtk subcommand. User `:disabled-rewriters` always wins over registered entries.

## Configuration

Under `.nyma/settings.json`, key `rtk-compression`:

```json
{
  "rtk-compression": {
    "enabled": true,
    "rtk-binary": "rtk",
    "disabled-rewriters": [],
    "log-rewrites": false
  }
}
```

| Key | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch. `false` skips activation entirely. |
| `rtk-binary` | `"rtk"` | Name or absolute path of the rtk executable. |
| `disabled-rewriters` | `[]` | Per-command opt-out, e.g. `["ls", "cat"]` to leave those alone. |
| `log-rewrites` | `false` | Log every rewrite to stdout for debugging. |

## Safety guards

- **Binary check at activation** — if `which rtk` fails, the middleware stays a no-op for the whole session.
- **Operator bailout** — any shell operator, redirect, or subshell disables rewriting for that command.
- **Closed whitelist** — unknown commands are never wrapped, so a user's local `foo` can't get unexpectedly redirected.
- **Disabled-rewriters list** — hard opt-out even for whitelisted commands.
- **Coexists with `bash-suite/output-handling`** — rtk-compression is declared `dependsOn: ["bash-suite"]`, so head/tail truncation remains as the final safety net.

## Capabilities

`middleware`, `exec`, `events`

## Dependencies

- External binary: [`rtk`](https://github.com/rtk-ai/rtk) — install via `cargo install rtk` or your package manager. Not installed automatically.
- npm: [`shell-quote`](https://www.npmjs.com/package/shell-quote) `^1.8.1` — auto-installed on first load (also used by `bash-suite`).

## See also

- [rtk](https://github.com/rtk-ai/rtk) — upstream project, supported tools, benchmark numbers
- [`bash-suite`](../bash_suite/) — complementary bash tool middleware (runs after this extension in the leave phase)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
