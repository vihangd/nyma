(ns agent.extensions.spec-driven.skill-content
  "Embedded `SKILL.md` content for the companion `spec-driven-dev`
   skill. Stored as a string constant so the extension can install it
   into `~/.nyma/skills/` (or any agentskills.io-compatible folder)
   on demand via `/spec install-skill` — no build-step file copy
   required.

   Frontmatter uses ONLY the agentskills.io baseline fields. Vendor-
   specific glob hints go under `metadata.nyma.activation-paths` so
   the file loads cleanly in Claude Code, Cursor, OpenCode, Codex
   CLI, and any other agentskills.io-conformant client without
   schema errors."
  (:require ["node:fs"   :as fs]
            ["node:path" :as path]))

(def skill-md-content
  "---
name: spec-driven-dev
description: Spec-driven development workflow following spec-kit v0.8.x
  conventions. Use when the user runs /spec commands, works under
  specs/, .specify/, .kiro/specs/, edits a spec.md, plan.md,
  requirements.md, design.md, tasks.md, data-model.md, quickstart.md,
  research.md, contracts/*, or memory/constitution.md, or asks to
  write specs / plans / tasks / clarifications in spec-kit style.
license: MIT
compatibility: Designed for nyma; portable across Claude Code, Cursor,
  OpenCode, Codex CLI, Gemini CLI, Copilot — any agentskills.io-compatible
  client. Aligned with github/spec-kit v0.8.x conventions.
metadata:
  nyma:
    activation-paths: \"specs/**, .specify/**, .kiro/specs/**, **/spec.md, **/requirements.md, **/plan.md, **/design.md, **/tasks.md, **/data-model.md, **/quickstart.md, **/research.md, contracts/**, .specify/memory/constitution.md, .kiro/steering/**\"
    spec-kit-version: \"0.8.x\"
---

# Spec-Driven Development Workflow

You are working in a project that uses spec-driven development per
[github/spec-kit](https://github.com/github/spec-kit) conventions
(or the older Kiro shape, which uses different filenames but the
same ideas). Treat the spec docs as the **durable plan** — the
source of truth that survives chat compaction.

## The pipeline

spec-kit defines a 9-command pipeline. nyma's `/spec` extension
flattens these into a smaller set with the same outputs:

| spec-kit command         | nyma equivalent | What it produces / does |
|--------------------------|-----------------|-------------------------|
| `/speckit.constitution`  | `/spec scaffold constitution` | `.specify/memory/constitution.md` |
| `/speckit.specify`       | `/spec new` (or `/spec import`) | `spec.md` (and templates) |
| `/speckit.clarify`       | `/spec clarify`                 | Adds `## Clarifications` to `spec.md` |
| `/speckit.plan`          | (edit `plan.md` directly)       | `plan.md` |
| `/speckit.tasks`         | (edit `tasks.md` directly)      | `tasks.md` |
| `/speckit.analyze`       | `/spec analyze`                 | Console-only consistency report |
| `/speckit.implement`     | `/spec start` + chat           | Activate spec, do the work |

In a non-nyma client, you may see the spec-kit slash commands
directly. Either way, the **artifacts** below are what matter.

## The conventions you must respect

When authoring or editing spec docs, keep these conventions exactly:

### IDs

- **`FR-001`, `FR-006`, `FR-200`** — Functional Requirements.
  Three-or-more-digit, zero-padded.
- **`SC-001`, …** — Success Criteria. Same shape.
- Never invent an ID out of order. If you add a new FR, use the
  next free number.

### Inline ambiguity markers

Wherever a requirement is under-specified, leave a marker:

  `FR-006: System MUST authenticate via [NEEDS CLARIFICATION: auth method - email/password, SSO, OAuth?]`

`/spec clarify` (or `/speckit.clarify`) prioritizes these markers
when generating questions. When you resolve a marker, **replace
the marker text** with the answer — don't leave a duplicate ambiguous
statement next to a clarified one.

### Clarifications log

Live record of resolved ambiguities. Goes in `spec.md` just after
the Overview section:

```
## Clarifications

### Session 2026-05-05
- Q: What auth method? → A: OAuth 2.0 with PKCE
- Q: Maximum upload size? → A: 25 MB

### Session 2026-05-12
- Q: Concurrency model? → A: Last-writer-wins
```

Append-only (never edit prior sessions). Body text MAY change as
clarifications propagate.

### Tasks

`tasks.md` uses markdown checkboxes:

```
- [ ] Wire OAuth callback handler
- [x] Add login button
- [ ] [P] Stripe webhook handler   ← [P] = parallelizable
```

Treat checkbox state as durable AND your responsibility. When you
finish implementing a task, **edit tasks.md in the same turn**:
replace `- [ ]` with `- [x]` on that task's line, before moving on.
Do not wait for the user to mark it.

Exception: during clarify or analyze sessions (which seed an
explicit Q&A workflow), follow the seed instructions — those phases
don't touch task checkboxes.

### Constitution

`.specify/memory/constitution.md` (or `.kiro/steering/*.md`) holds
project-wide governing principles. **MUST violations are auto-CRITICAL
in `/spec analyze`.** Whenever you propose changes that conflict with
a MUST principle, raise it explicitly — don't silently work around
it.

## The clarify workflow

When `/spec clarify` is in progress (you'll see a system message
seeded by the slash command):

- Ask **at most 5 questions**, one at a time.
- Each question is multiple-choice (2-5 mutually exclusive options
  rendered as a Markdown table with one row marked `**(Recommended)**`)
  OR a `≤ 5 words` free-form prompt.
- Accept `yes` / `recommended` / `suggested` as shortcuts to take
  the recommendation. Stop on `done` / `good` / `no more`.
- After EACH answer:
  1. Append the Q+A to `## Clarifications` → `### Session YYYY-MM-DD`
     in spec.md (`- Q: ... → A: ...`).
  2. Replace any matching `[NEEDS CLARIFICATION: ...]` marker with
     the resolved text.
  3. Save spec.md to disk before asking the next question.

Eleven priority categories (use to prioritize Q candidates):

  1. Functional Scope & Behavior
  2. Domain & Data Model
  3. Interaction & UX Flow
  4. Non-Functional Quality (perf, scale, reliability, observability,
     security, compliance)
  5. Integration & External Dependencies
  6. Edge Cases & Failure Handling
  7. Constraints & Tradeoffs
  8. Terminology & Consistency
  9. Completion Signals (testable acceptance criteria)
 10. Misc / Placeholders (vague adjectives — 'fast', 'scalable', etc.)
 11. Inline `[NEEDS CLARIFICATION: …]` markers (always priority 1)

## The analyze workflow

When `/spec analyze` runs, you produce a **read-only Markdown report**.
Six detection passes, 4-level severity (CRITICAL / HIGH / MEDIUM / LOW),
50-finding cap, stable IDs (A1, D1, …) for diffability across runs.

  - **A. Duplication** — overlapping FRs
  - **B. Ambiguity** — vague adjectives + unresolved markers
  - **C. Underspecification** — FRs missing measurable outcomes; tasks
    referencing files not in `plan.md`
  - **D. Constitution Alignment** — MUST violations → auto-CRITICAL
  - **E. Coverage Gaps** — FRs with no tasks; tasks with no FR
  - **F. Inconsistency** — terminology drift, stack contradictions

Output schema is fixed (Findings table + Coverage Summary + Constitution
Alignment + Unmapped Tasks + Metrics). Don't deviate.

## When this skill is active

- **Don't invent FR-### or SC-### IDs out of order.** Read the existing
  IDs first.
- **Prefer body-replacement over body-duplication** when clarifying.
  An ambiguous statement and its resolution should not coexist.
- **Treat `tasks.md` checkboxes as durable.** Suggest `/spec done`
  rather than editing the file directly.
- **Suggest `/spec scaffold <kind>`** when an optional artifact would
  help (`data-model`, `quickstart`, `research`, `contracts`,
  `constitution`).
- **Surface MUST violations as CRITICAL.** Never silently route around
  the constitution.
- **Prefer concrete, measurable language.** \"99.9% uptime\" not
  \"reliable\". \"p95 < 200ms\" not \"fast\". This is the bar `/spec
  analyze` enforces.

## Cross-vendor notes

This skill loads in any agentskills.io-compatible client. In nyma it's
backed by the native `/spec` extension which automates scaffolding,
discovery, hooks, and the clarify/analyze pipelines. In other clients
(Claude Code, Cursor, OpenCode, Codex CLI), this skill provides the
**conventions and behaviors** but the user must run spec-kit's CLI
(`specify init`, `/speckit.*` commands) for the automation.
")

(defn install-skill!
  "Write the SKILL.md to `<dir>/spec-driven-dev/SKILL.md`. Returns
   {:ok? true :path <abs-path>} or {:ok? false :error <string>}.

   By default writes to `~/.nyma/skills/spec-driven-dev/SKILL.md` —
   the global location nyma's skill loader scans. Refuses to clobber
   an existing file unless `clobber?` is true."
  ([home-dir] (install-skill! home-dir false))
  ([home-dir clobber?]
   (let [skill-dir (path/join home-dir ".nyma" "skills" "spec-driven-dev")
         skill-md  (path/join skill-dir "SKILL.md")]
     (cond
       (and (fs/existsSync skill-md) (not clobber?))
       {:ok? false
        :error (str "Already exists: " skill-md
                    "\nDelete it first or pass --force to overwrite.")}

       :else
       (try
         (fs/mkdirSync skill-dir #js {:recursive true})
         (fs/writeFileSync skill-md skill-md-content)
         {:ok? true :path skill-md}
         (catch :default e
           {:ok? false
            :error (str "Failed to write: " (or (.-message e) (str e)))}))))))
