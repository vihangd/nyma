# nyma starter skills

A **small, curated, vetted** set of Agent Skills (agentskills.io `SKILL.md` format). Per the 2026
SOTA on agent skills, *curation beats scale* — Anthropic ships small human-reviewed sets, and a
Cisco scan found **26% of 31k community skills carried a vulnerability**. So this pack is
deliberately tiny and **instruction-only** (no bundled scripts to execute).

## What's here

| Skill | Purpose |
|---|---|
| `systematic-debugging` | Reproduce → isolate → hypothesize → verify, instead of guess-patching |
| `conventional-commits` | Well-formed commit messages (type(scope): subject + why) |
| `test-first` | Write the failing check before the fix; leave one runnable check behind |
| `pr-review` | Correctness-first review checklist; one line per finding |

## Installing

nyma discovers skills under these directories (project first, then global), per
`src/agent/resources/loader.cljs`:

```
<project>/.nyma/skills      ~/.nyma/skills
<project>/.agents/skills     ~/.agents/skills
<project>/.claude/skills     ~/.claude/skills
<project>/.cursor/skills     <project>/.codex/skills   (+ global variants)
```

Copy (or symlink) any skill dir into one of those, e.g.:

```bash
mkdir -p ~/.nyma/skills
cp -r skills/systematic-debugging ~/.nyma/skills/
```

Each skill is a directory whose name matches its `SKILL.md` frontmatter `name`. A skill is surfaced
to the model by name+description; `/skill <name>` expands its full body. `triggers:` phrases
auto-activate it; `paths:` scopes it to matching files.

## Adding more packs (safely)

More skills live across the ecosystem — Anthropic's official `anthropics/skills`, and community
collections. **Vet before installing**: read the `SKILL.md`, and never install a skill with
`tools.cljs`/`tools.ts` or `scripts/` you haven't reviewed — those execute in your agent. Prefer
instruction-only skills from sources you trust. Keep the active set small; a bloated skill library
raises retrieval cost and dilutes relevance.
