# spec-driven

> Surface durable plans from `.specify/specs/<feat>/` (GitHub spec-kit, default) and `.kiro/specs/<feat>/` (Kiro) as in-context structured plans the agent reads and advances.

## What it does

Discovers feature specs in either of the two converged 2026 markdown layouts, lets the user activate one with `/spec start <name>`, and from then on appends the active spec's three documents (spec / plan / tasks for spec-kit, or requirements / design / tasks for Kiro) to the system prompt every turn. Tasks are tracked as markdown checkboxes; the agent (or you) advance them with `/spec next` and `/spec done <pattern>`.

Solves the "did the agent do what I asked?" trust problem by making the plan a durable, reviewable, diff-able artifact instead of an ephemeral chat scrollback. The spec docs survive context compaction and stay in scope for every model turn until you `/spec end`.

On top of that, an **opt-in phase loop** (`/spec run`) walks plan → execute → verify → ship,
binding each phase to a `model_roles` role so the model, tool allow-list and permissions change
together — Opus to plan, something cheap to implement, a verifier to check. See
[Phases and the loop](#phases-and-the-loop).

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
| `/spec import <name> [<path>] [--run] [--force] [--kiro]` | Two modes depending on `<path>`. **File mode**: copy as primary doc, scaffold the rest. **Directory mode**: copy every recognized file (`spec.md`/`requirements.md`, `plan.md`/`design.md`, `tasks.md`, `data-model.md`, `quickstart.md`, `research.md`, `contracts/*`), scaffold whatever's missing. Filenames are **shape-translated** — importing `requirements.md` into a spec-kit spec lands as `spec.md`, etc. **`<path>` is optional**: with none, the newest `.nyma/plans/*.md` is used — that is where both `/plan-capture` and native plan mode write, and the filename is a timestamp nobody should retype. **`--run`** activates the spec and arms the phase loop as soon as the decomposition lands (it cannot arm sooner: import scaffolds `tasks.md` from a template, and the queued LLM turn is what fills it in). `--run` starts without `/spec analyze` and says so. **`--force`** replaces an existing spec of the same name — the recovery path when the plan turns out wrong: refine it with the ACP agent, `/plan-capture` again, re-import over the top. It reports how many tasks were already ticked, so replacing real progress is a visible choice. |
| `/spec scaffold <kind> [<name>]` | Fill in an optional spec-kit artifact. `<kind>` is one of `data-model` / `quickstart` / `research` / `contracts` / `constitution`. The first four require a spec name; `constitution` is project-wide and creates `.specify/memory/constitution.md`. Refuses to overwrite existing files. |
| `/spec clarify <name>` | **Drop-in compatible with spec-kit's `/speckit.clarify`.** Seeds the conversation with structured-questioning instructions: model asks up to 5 priority questions one at a time (multi-choice w/ recommended option, or ≤5-word free-form), records each as `- Q: … → A: …` under `## Clarifications` → `### Session YYYY-MM-DD`, and replaces inline `[NEEDS CLARIFICATION: …]` markers with the answer. 11-category taxonomy. User can answer in chat, type `yes`/`recommended` to accept the model's recommendation, or `done`/`good`/`no more` to early-terminate. Re-runnable: each invocation creates a new dated session. |
| `/spec analyze <name>` | **Drop-in compatible with spec-kit's `/speckit.analyze`.** Read-only consistency check across spec/plan/tasks/constitution. Six detection passes: Duplication, Ambiguity, Underspecification, Constitution Alignment (MUST violations auto-CRITICAL), Coverage Gaps, Inconsistency. Renders a Markdown report with finding ID + severity + location. 4-level severity (CRITICAL/HIGH/MEDIUM/LOW), 50-finding cap, stable IDs (A1, D1, etc.) so re-runs are diffable. Caches the run in `.nyma/spec-state.json` for the soft-block on `/spec start`. |
| `/spec start <name> [--force]` | Activate. From this point the spec's docs are appended to the system prompt every turn. **Soft-blocks** if `/spec analyze` has never run for this spec, has unresolved CRITICAL findings, or the spec content has drifted since the last analyze — the warning suggests running analyze first; pass `--force` to start anyway. |
| `/spec install-skill [--force]` | Write the companion `spec-driven-dev` SKILL.md to `~/.nyma/skills/spec-driven-dev/`. The skill teaches the model the spec-kit conventions (FR-### IDs, `[NEEDS CLARIFICATION:]` markers, 11-category clarify taxonomy, 6-pass analyze checks, constitution-as-MUST). Uses agentskills.io baseline frontmatter only — portable across Claude Code, Cursor, OpenCode, Codex CLI, Gemini CLI. Refuses to overwrite an existing install unless `--force`. |
| `/spec next` | Find the first unchecked task in the active spec, narrate it, **emit `spec_task_start` hook**. |
| `/spec done <pattern>` | Case-insensitive substring match against task text → flip `[ ]` to `[x]` in `tasks.md` on disk. **Emits `spec_task_complete` hook**. |
| `/spec phase [<name>]` | Show the current phase, its bound role and the phase order — or move to `<name>`. Setting a phase writes `:active-role`, so the model, allowed-tools and permissions switch together. |
| `/spec profile [<name>]` | Show or switch the phase→role profile. A switch re-binds the current phase immediately, not just at the next transition. |
| `/spec run [--profile=<p>] [--fresh]` | **Arm the phase loop** for the active spec. `off`/`stop` disarms mid-flight; `status` reports arm state, iteration and profile. Never arms itself. |
| `/spec end` | Clear the active spec, phase and profile. Spec docs stop being injected. |

The active spec, phase and profile persist to `.nyma/ext-state/spec-driven.json`, so a restart
resumes mid-feature rather than silently dropping back to phase 1 under the default profile. A
spec deleted between sessions is not resurrected.

Unknown subcommand → usage hint. Pass `--shape=spec-kit` or `--shape=kiro` if you prefer that flag style; both work alongside `--spec-kit` / `--kiro`.

## Status line

When a spec is active, a `spec.active` segment auto-appends to the status line:

```
⏵ auth · execute · 7/11 · fast          # phase, progress, bound role
⏵ oauth-login · decomposing…            # import queued, tasks not written yet
```

It hides entirely when no spec is active. The `decomposing…` state matters:
`/spec import --run` queues the decomposition as a follow-up, so it does not
begin until the next turn ends — without the segment that gap looks like
nothing happening. A notice also fires when the tasks land and the loop arms.

**Where the loop enters.** `/spec import --run` arms at `execute`, not at the
first phase: the plan arrived from outside and the decomposition has already
run, so a planning phase has nothing to do — and because a phase advances only
when *every* task is checked, entering at `plan` would run the whole task list
under the planning role. `/spec new` still enters at the first phase, where the
planning genuinely has not happened. A phase you set yourself always wins, and
the entry point is a dial: `settings#spec.loop.import-phase`, default
`execute`. Naming a phase the profile does not define falls back to the first
and says so.

The pending flag is mirrored to `.nyma/ext-state/`, but a restart deliberately
clears it rather than restoring it: the queued seed lives in the agent's
in-memory follow-queue and dies with the process, so a restored `pending` would
leave the loop waiting on a turn that can never arrive. Instead it says so and
names the command that recovers:

```
spec: the queued decomposition for <name> did not survive the restart.
  Re-run `/spec import <name> --force --run`, then send one message.
```

## Hook events

Emitted through the existing `claude_hook_bridge`, so users can wire pre/post-task automation (run tests, lint, post to Slack) using the same Claude-Code-compatible hook shape they already know.

| Event | Payload | When |
|---|---|---|
| `spec_task_start` | `{ spec, task, line }` | `/spec next` selects a task |
| `spec_task_complete` | `{ spec, task, line }` | `/spec done` flips a checkbox |
| `spec_task_complete` | `{ spec, task, source: "agent" }` | the **agent** ticks a box in `tasks.md` itself |
| `spec_phase_enter` | `{ spec, phase, role }` | a phase is entered, by command or by the loop |

The `source: "agent"` variant matters: the injected instructions tell the model to tick tasks off
as it goes, and that path previously emitted nothing — so automation keyed on task completion saw
only the ones a human typed.

Map cleanly onto Kiro's own `Pre Task Execution` / `Post Task Execution` semantics, providing a clean migration path for users coming from Kiro.

## Phases and the loop

A phase **is** a role. `model_roles` already binds a role to a provider+model, an
`allowed-tools` list and a permission set; entering a phase writes `:active-role`, so all three
switch together. That combination is the point — every comparable framework (GSD, gstack, BMAD,
Superpowers) models phases as prompt personas on one model, and none of them routes per phase.

```
/spec start auth --force
/spec run                      # arm — never self-arms
/spec run --profile=thrifty    # ...or arm with a different profile
/spec run status               # Loop: armed  iteration 3/25 | phase: execute | fresh-context: off
/spec run off                  # stop, mid-flight
```

```
> plan -> execute  role: fast
spec loop stopped: no checkboxes found in tasks file — refusing to treat as complete
```

### Profiles

A profile is a phase→role map. Shipped presets, all naming roles that exist without any user
config:

| profile | plan | execute | verify | ship |
|---|---|---|---|---|
| `routed` *(default)* | `advisor` | `fast` | `deep` | `commit` |
| `thrifty` | `advisor` | `default` | `fast` | `default` |
| `free` | `default` | `default` | `default` | `default` |

Override or add your own; user profiles merge **over** the shipped ones by name, so redefining
`routed` replaces it while `thrifty` survives.

```jsonc
{ "spec": {
    "profiles": { "mine": { "plan": "advisor", "execute": "build", "verify": "deep" } },
    "loop": { "mode": "off", "profile": "routed",
              "max-iterations": 25, "fresh-context": false } },
  // one-word switching, via workspace_config's existing /alias
  "aliases": { "thrifty": "/spec profile thrifty" } }
```

`mode: "on"` arms the loop permanently, without typing `/spec run`.

A role a profile names but your config does not define falls back to `default` **and says so**.
Silent degradation is the failure this deliberately avoids — the advisor tool has exactly that
bug (`advisor/index.cljs:164-168`), quietly handing back the main-loop model.

### What stops the loop

The decision lives in `phases.cljs` as a pure function, so each guard is unit-tested without
running a model. In order:

| guard | why |
|---|---|
| not armed | it only ever runs when you asked |
| iteration cap | the follow-queue drain upstream is an unbounded `recur` (`loop.cljs:586-594`), so the bound has to live here |
| verify is red | **holds** rather than stops — `verify_gate` owns the fix loop and resumes us on `small-model/verify-pass` |
| no checkboxes in `tasks.md` | `parse-tasks` drops non-checkbox lines, so a corrupted or reformatted file yields zero tasks — which naively reads as *done* |
| phase not in the profile's order | reachable by switching profiles mid-run; would otherwise announce "all phases complete" |
| open tasks remain | continue |

**The model's finish reason is never consulted.** An agent reporting `stop` with tasks still
unchecked is premature termination, the documented failure of loops of this shape; the unchecked
count in `tasks.md` is the only completion signal.

### `--fresh` (Ralph-style context reset)

Off by default. When on, the conversation is cleared between tasks and the filesystem carries
state — the spec docs are re-read from disk every turn regardless, which is what makes this work.
Measured on a 60-task run: **$0.043 vs $0.365**, and it never compacts.

Two things to know before turning it on:

- **Anything you say in chat is discarded.** Ralph's premise is that all intent lives in the
  files; it breaks the moment you add "…but skip the OAuth part" in conversation. Arming with
  `--fresh` warns about exactly this.
- **The follow-up prompt is byte-identical every iteration, by construction.** Measured on
  `glm-5.3-flash` via OpenRouter: an identical prompt keeps the cache (3648/3678 tokens), a
  prompt that varies per task drops to **zero** and costs ~2× a cold call. The task to work on
  comes from the file, never from the prompt. A test asserts three consecutive iterations send
  the same string.

Pair with `budget` for unattended runs: its session cap aborts every new submit at its first step
boundary, which is the backstop for a loop that will not settle.

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

`phases.cljs` is entirely pure — no fs, no api, no model — so every loop decision is testable
without running an agent:

| Function | Purpose |
|---|---|
| `config [settings]` | `settings#spec` → `{:profiles :loop}`, user profiles merged over the shipped ones. |
| `resolve-role [cfg profile phase known-roles]` | `{:role :fell-back? :reason}`. Degrades to `default` and always explains why. |
| `progress [parsed raw]` | `{:total :checked :open :status}`. `raw` is what separates *no checkboxes* from *all checked* — both yield an empty open list, only one means done. |
| `decide [m]` | `{:action :continue\|:advance\|:done\|:hold\|:stop :reason}` — the whole loop policy, one guard per failure mode. |
| `phase-order [profile-map]` | Canonical order for known phases, user-declared ones appended. |
| `armed-by-settings? [cfg]` | Whether `loop.mode` opts the loop in permanently. |

## Design choices

- **Opt-in only.** Spec ceremony is invisible until the user runs `/spec start`. Default workflow is unchanged. (Kiro's main pain point in production is forced ceremony for trivial fixes.)
- **Both formats, never one.** The 2026 ecosystem hasn't unified on Kiro's vs spec-kit's filenames. nyma reads both, lets the user keep whichever shape they (or their co-developers) prefer.
- **Markdown only as durable state.** No YAML/JSON sidecar, no shadow `.state` file. The checkbox is the source of truth — `git diff` shows exactly what changed, and the same `tasks.md` is human-editable, agent-editable, and external-tool-compatible (Kiro IDE, spec-kit CLI, etc.).
- **A phase is a role, not a persona.** Every comparable framework (GSD, gstack, BMAD, Superpowers) models phases as prompts on whichever model the session happens to be running. Binding a phase to a `model_roles` role gets the model, the tool allow-list and the permissions in one move — the reason to build this rather than adopt one of them.
- **The loop never arms itself.** `/spec run` is explicit, `settings#spec.loop.mode` defaults to `off`, and `/spec run off` existed before the loop did. An autonomous loop that starts on its own is how people wake up to a spent quota.
- **Markdown is the completion signal.** Not the model's finish reason, which is exactly what premature termination corrupts. A tasks file with no checkboxes is `:no-tasks`, never `:complete` — a corrupted file must stop the loop, not look like a finished one.
- **Fallback is always loud.** An unresolvable role reports `role "X" (phase "verify") is not defined — using default`. The advisor tool does the opposite (`advisor/index.cljs:164-168`) and you never learn your role did nothing.
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
- [`model_roles/README.md`](../model_roles/README.md) — the roles a phase binds to, and `escalate.fallback`
- [`verify_gate/README.md`](../verify_gate/README.md) — the verify phase; publishes `small-model/verify-{fail,pass,exhausted}`
- [`budget/README.md`](../budget/README.md) — token caps; the backstop for an unattended loop
- [GSD](https://github.com/open-gsd/gsd-core), [gstack](https://github.com/garrytan/gstack), [OpenSpec](https://github.com/Fission-AI/OpenSpec) — adjacent phase/role frameworks; none routes models per phase
- ["From Prompt to Process" (arXiv 2606.04967)](https://arxiv.org/abs/2606.04967) — taxonomy scoring six such frameworks on Specification · Context · Roles · Execution · Validation · Portability
