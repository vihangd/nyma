# bash-suite

> Security analysis, permission caching, output handling, and stats for the built-in `bash` tool.

## What it does

`bash-suite` wraps the built-in `bash` tool with a complete safety + UX pipeline:

- **Security analysis** classifies every command as `safe`, `read-only`, `write`, `network`, or `destructive`, and outright **blocks** known-bad patterns (`rm -rf /`, `dd` to a block device, fork bombs, `curl … | sh`).
- **Permission cache** remembers per-prefix decisions so the user isn't asked twice for the same command in a session.
- **Working-directory tracker** prepends the right `cd` so the agent doesn't drift between `cwd`s.
- **Environment filter** strips secret-looking env vars before exec.
- **Output handling** saves oversized output to a temp file and returns a truncated summary so a single `grep` doesn't blow the context window.
- **Background job tracking** keeps a state machine for long-running jobs the agent backgrounded.
- Adds a security notice to the system prompt at `before_agent_start` (priority 30) so the model knows the rules up-front.

## Commands

| Command | What it does |
|---|---|
| `/bash-suite__bash-stats` | Show per-session bash stats: classifications, blocked commands, cache hits, output bytes saved |

## Hooks

| Event | Behaviour |
|---|---|
| `before_agent_start` | Inject the bash security notice into the system prompt (priority 30) |

## Sub-modules

| File | Role |
|---|---|
| `security_analysis.cljs` | Classify commands, apply hard-block patterns |
| `permissions.cljs` | Cache per-prefix permission decisions |
| `env_filter.cljs` | Strip sensitive env vars before exec |
| `cwd_manager.cljs` | Track and prepend the correct working directory |
| `background_jobs.cljs` | Background-job state machine |
| `output_handling.cljs` | Truncate large outputs, save to temp files |

## Capabilities

`events`, `context`, `tools`, `middleware`, `commands`, `exec`, `flags`

## Dependencies

- npm: [`shell-quote`](https://www.npmjs.com/package/shell-quote) `^1.8.1` — auto-installed on first load

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide
