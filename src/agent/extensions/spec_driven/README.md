# spec-driven

> Surface durable plans from `.specify/specs/<feat>/` (GitHub spec-kit, default) and `.kiro/specs/<feat>/` (Kiro) as in-context structured plans the agent reads and advances.

## What it does

Discovers feature specs in either of the two converged 2026 markdown layouts, lets the user activate one with `/spec start <name>`, and from then on appends the active spec's three documents (spec / plan / tasks for spec-kit, or requirements / design / tasks for Kiro) to the system prompt every turn. Tasks are tracked as markdown checkboxes; the agent (or you) advance them with `/spec next` and `/spec done <pattern>`.

Solves the "did the agent do what I asked?" trust problem by making the plan a durable, reviewable, diff-able artifact instead of an ephemeral chat scrollback. The spec docs survive context compaction and stay in scope for every model turn until you `/spec end`.

## Default shape: spec-kit

As of May 2026, **spec-kit is the cross-agent interop format** — Claude Code, Copilot Workspace, Gemini CLI, Cursor, Windsurf, Codex CLI, Qwen CLI, Devin Terminal, and Antigravity all default to it. Kiro's shape is supported for users in Kiro shops, but spec-kit is what the rest of the ecosystem reads and writes.

The default and the collision-winner are both **settings-driven**, not hardcoded. Out-of-the-box: spec-kit wins. Override in `.nyma/settings.json`:

```json
{
  "spec": {
    "default-shape": "kiro",
    "shape-precedence": ["spec-kit", "kiro"]
  }
}
```

`shape-precedence` is lowest→highest; the **last** entry wins on name collision.

## Supported spec layouts

Both shapes are discovered automatically.

### GitHub spec-kit (`github/spec-kit`) — default

```
.specify/specs/<feature>/
├── spec.md           ← "what + why"
├── plan.md           ← "how"
└── tasks.md          ← actionable checkbox list (`[P]` = parallelizable)
```

### Kiro (`kiro.dev`)

```
.kiro/specs/<feature>/
├── requirements.md   ← "what + why" (user stories, acceptance criteria)
├── design.md         ← "how" (architecture, data flows)
└── tasks.md          ← actionable checkbox list
```

The `[P]` parallelizable marker from spec-kit is preserved in the task text — neither parsed nor stripped — so the agent can see it and decide to fan out work.

## Optional artifacts (auto-included when present)

In addition to the canonical 3 files, when a spec is active these optional sources are read fresh every turn and inlined into the system prompt:

**Per-spec (spec-kit only):**
- `<feat>/data-model.md` — data structures
- `<feat>/quickstart.md` — feature onboarding
- `<feat>/research.md` — design exploration
- `<feat>/contracts/*` — every file in the contracts/ subdir (api-spec.json, signalr-spec.md, etc.) appears as a labeled block

**Project-wide (read once at the top of the active-spec block):**
- spec-kit: `.specify/memory/constitution.md` — governing principles
- Kiro: `.kiro/steering/*.md` — every top-level markdown file in the steering directory

Anything missing is silently skipped — no errors, no empty sections in the prompt. Adding a `data-model.md` mid-session takes effect on the next turn.

The system prompt block looks like:

```
## Active Spec: auth-flow  (spec-kit; 1/3 tasks done)

### Project guidance
#### .specify/memory/constitution.md
…content…

### Spec
…spec.md…

### Plan
…plan.md…

### Tasks
…tasks.md…

### Supporting artifacts
#### data-model.md
…content…
#### quickstart.md
…content…

### Contracts & references
#### contracts/api-spec.json
…content…
```

## Commands

| Command | What it does |
|---|---|
| `/spec list` *(or bare `/spec`)* | List every spec found under `cwd` with progress (`auth-flow [spec-kit] — 1/3 tasks done ◀ active`). Active spec is marked. |
| `/spec new <name> [--kiro]` | Scaffold a new spec in the default shape (spec-kit) with starter templates. Add `--kiro` for the Kiro layout instead. Validates the name against the agentskills.io pattern (lowercase, digits, hyphens; no traversal, no consecutive hyphens). Refuses to clobber an existing spec dir. |
| `/spec import <name> <path> [--kiro]` | Two modes depending on `<path>`. **File mode**: copy as primary doc, scaffold the rest. **Directory mode**: copy every recognized file (`spec.md`/`requirements.md`, `plan.md`/`design.md`, `tasks.md`, `data-model.md`, `quickstart.md`, `research.md`, `contracts/*`), scaffold whatever's missing. Filenames are **shape-translated** — importing `requirements.md` into a spec-kit spec lands as `spec.md`, etc. |
| `/spec scaffold <kind> [<name>]` | Fill in an optional spec-kit artifact. `<kind>` is one of `data-model` / `quickstart` / `research` / `contracts` / `constitution`. The first four require a spec name; `constitution` is project-wide and creates `.specify/memory/constitution.md`. Refuses to overwrite existing files. |
| `/spec clarify <name>` | **Drop-in compatible with spec-kit's `/speckit.clarify`.** Seeds the conversation with structured-questioning instructions: model asks up to 5 priority questions one at a time (multi-choice w/ recommended option, or ≤5-word free-form), records each as `- Q: … → A: …` under `## Clarifications` → `### Session YYYY-MM-DD`, and replaces inline `[NEEDS CLARIFICATION: …]` markers with the answer. 11-category taxonomy. User can answer in chat, type `yes`/`recommended` to accept the model's recommendation, or `done`/`good`/`no more` to early-terminate. Re-runnable: each invocation creates a new dated session. |
| `/spec analyze <name>` | **Drop-in compatible with spec-kit's `/speckit.analyze`.** Read-only consistency check across spec/plan/tasks/constitution. Six detection passes: Duplication, Ambiguity, Underspecification, Constitution Alignment (MUST violations auto-CRITICAL), Coverage Gaps, Inconsistency. Renders a Markdown report with finding ID + severity + location. 4-level severity (CRITICAL/HIGH/MEDIUM/LOW), 50-finding cap, stable IDs (A1, D1, etc.) so re-runs are diffable. Caches the run in `.nyma/spec-state.json` for the soft-block on `/spec start`. |
| `/spec start <name> [--force]` | Activate. From this point the spec's docs are appended to the system prompt every turn. **Soft-blocks** if `/spec analyze` has never run for this spec, has unresolved CRITICAL findings, or the spec content has drifted since the last analyze — the warning suggests running analyze first; pass `--force` to start anyway. |
| `/spec install-skill [--force]` | Write the companion `spec-driven-dev` SKILL.md to `~/.nyma/skills/spec-driven-dev/`. The skill teaches the model the spec-kit conventions (FR-### IDs, `[NEEDS CLARIFICATION:]` markers, 11-category clarify taxonomy, 6-pass analyze checks, constitution-as-MUST). Uses agentskills.io baseline frontmatter only — portable across Claude Code, Cursor, OpenCode, Codex CLI, Gemini CLI. Refuses to overwrite an existing install unless `--force`. |
| `/spec next` | Find the first unchecked task in the active spec, narrate it, **emit `spec_task_start` hook**. |
| `/spec done <pattern>` | Case-insensitive substring match against task text → flip `[ ]` to `[x]` in `tasks.md` on disk. **Emits `spec_task_complete` hook**. |
| `/spec end` | Clear the active spec. Spec docs stop being injected. |

Unknown subcommand → usage hint. Pass `--shape=spec-kit` or `--shape=kiro` if you prefer that flag style; both work alongside `--spec-kit` / `--kiro`.

## Hook events

Emitted through the existing `claude_hook_bridge`, so users can wire pre/post-task automation (run tests, lint, post to Slack) using the same Claude-Code-compatible hook shape they already know.

| Event | Payload | When |
|---|---|---|
| `spec_task_start` | `{ spec, task, line }` | `/spec next` selects a task |
| `spec_task_complete` | `{ spec, task, line }` | `/spec done` flips a checkbox |

Map cleanly onto Kiro's own `Pre Task Execution` / `Post Task Execution` semantics, providing a clean migration path for users coming from Kiro.

## Context injection

When a spec is active, the extension subscribes to `context_assembly` and appends a structured block to the system prompt:

```text
## Active Spec: auth-flow  (Kiro; 1/3 tasks done)

### Requirements
…requirements.md content…

### Design / Plan
…design.md content…

### Tasks
…tasks.md content…
```

The model sees this block on every turn until `/spec end`. Because it's read fresh from disk each turn, edits to `tasks.md` (via `/spec done` or external) propagate immediately.

## Tasks parser

Recognizes any of:

```markdown
- [ ] dash variant
- [x] dash done
- [X] capital-X done (also valid)
* [ ] asterisk variant
1. [ ] numbered list
12. [x] multi-digit numbered done
```

Anything that isn't a checkbox line is left alone — only checkbox lines are surfaced as tasks; everything else stays in `tasks.md` as supporting prose.

## Capabilities

`events`, `commands`, `state`, `ui`

## Pure helpers (re-usable)

The extension exports several pure functions that other extensions or tests can build on:

| Function | Purpose |
|---|---|
| `discover-specs [cwd]` | All specs keyed by name. |
| `parse-tasks [content]` | Markdown checkboxes → `[{text, checked?, line-idx, prefix, raw}]`. |
| `next-open-task [tasks]` | First unchecked task, or nil. |
| `find-task [tasks pattern]` | Case-insensitive substring match. |
| `mark-task-done [content line-idx]` | Pure string transform — flip `[ ]` to `[x]` at a line. Caller writes to disk. |
| `task-progress [tasks]` | `{:done :total}` summary. |

## Design choices

- **Opt-in only.** Spec ceremony is invisible until the user runs `/spec start`. Default workflow is unchanged. (Kiro's main pain point in production is forced ceremony for trivial fixes.)
- **Both formats, never one.** The 2026 ecosystem hasn't unified on Kiro's vs spec-kit's filenames. nyma reads both, lets the user keep whichever shape they (or their co-developers) prefer.
- **Markdown only as durable state.** No YAML/JSON sidecar, no shadow `.state` file. The checkbox is the source of truth — `git diff` shows exactly what changed, and the same `tasks.md` is human-editable, agent-editable, and external-tool-compatible (Kiro IDE, spec-kit CLI, etc.).
- **Reuses Claude-Code hook shape.** No custom hook event names — `spec_task_start`/`_complete` ride the same `claude_hook_bridge` channel that PreToolUse/PostToolUse use. Users get pre/post-task automation without nyma-specific config.

## Usage example

### Starting fresh — `/spec new`

```
> /spec new auth-flow
ℹ ✓ Created spec-kit spec: auth-flow
    .specify/specs/auth-flow/spec.md
    .specify/specs/auth-flow/plan.md
    .specify/specs/auth-flow/tasks.md

  Edit the files, then `/spec start auth-flow` to activate.

(open the three .md files, fill in the template, list the tasks)
```

### Already have a spec markdown file? — `/spec import`

```
> /spec import auth-flow ~/notes/auth-spec.md
ℹ ✓ Imported spec-kit spec: auth-flow
    .specify/specs/auth-flow/spec.md  ← ~/notes/auth-spec.md
    .specify/specs/auth-flow/plan.md (placeholder)
    .specify/specs/auth-flow/tasks.md (placeholder)

  Review the files, edit tasks.md to list actionable items,
  then `/spec start auth-flow` to activate.
```

### Then activate and work

```
> /spec list
ℹ Specs:
    auth-flow [spec-kit] — 0/3 tasks done

> /spec start auth-flow
ℹ Active spec: auth-flow.
   Docs are now appended to the system prompt for every turn.
   Use /spec next to advance, /spec end to clear.

> /spec next
ℹ Next task in auth-flow:
   • Wire OAuth callback handler

(model now knows the full spec; user prompts the implementation work)

> implement the OAuth callback per the spec
● <model reads design.md, edits files, returns>

> /spec done oauth callback
ℹ ✓ Marked done: Wire OAuth callback handler

> /spec list
ℹ Specs:
    auth-flow [spec-kit] — 1/3 tasks done ◀ active
```

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — `context_assembly` hook docs
- [agents.md spec](https://agents.md) — for writing project-wide AGENTS.md alongside specs
- [Kiro Specs documentation](https://kiro.dev/docs/specs/) — original Kiro shape
- [GitHub spec-kit](https://github.com/github/spec-kit) — alternative shape with broader agent integration
