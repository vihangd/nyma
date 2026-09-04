# openwiki

AI-maintained, git-aware living documentation for the repo, emitted as an
[OKF v0.2](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle. Ported from [barvhaim/pi-openwiki](https://github.com/barvhaim/pi-openwiki), itself a
port of [langchain-ai/openwiki](https://github.com/langchain-ai/openwiki).

## What this is, and what it isn't

There is no wiki engine here. No index, no link graph, no page parser, no retrieval. The
commands collect git evidence and hand the agent a brief; the agent writes the bundle with its
own read/write/search tools. The output format is specified in `prompts.cljs` as prose, and the
one automated check on it is `shared/conformance-report`, which `save_metadata` runs and reports
back to the model.

This is a **subset** of upstream, not an enrichment of it. Shared: the OKF output format, the
SHA-256 content snapshot, the git-diff update window. Upstream also has Grounded Claims (each
fact pinned to versioned source evidence, resurfaced when that evidence moves), a resumable
page-job queue, diagram validation, connectors, and a graph visualizer. None of those are here.

## Usage

`/openwiki <verb>`:
- `init` — generate the bundle for the repo.
- `update` — refresh docs for files changed since the last run (git-aware), and append to `log.md`.

Docs live under `openwiki/` (no leading dot), configurable via `openwiki.dir`. Off by default;
enable with `"openwiki": {"enabled": true}` in settings or the `--ext-openwiki` flag. Every verb
requires a git repo.

## Scoping a run

Write `<dir>/INSTRUCTIONS.md` and it is inlined verbatim into every init/update prompt, and never
rewritten by the agent. This is the only scope control OpenWiki has — without it, `init` will
happily document build output, benchmark results, and vendored trees.

## Output layout

```
openwiki/
  index.md            entry point; frontmatter is okf_version only
  log.md              dated change history, newest first, no frontmatter
  INSTRUCTIONS.md     yours; never touched
  <section>/*.md      YAML frontmatter with a non-empty `type`, plus title/description/tags
  .last-update.json   {command, model, updatedAt, gitHead, snapshotHash, okfVersion, sections}
```

## Settings

```json
{ "openwiki": { "enabled": true, "dir": "openwiki",
                "sections": ["architecture", "workflows", "domain", "operations", "testing"] } }
```

`sections` is the taxonomy offered to the model and described in the AGENTS.md block. There is
no `model` key: the verbs never call a model themselves.

## Note on cost

The verbs build a prompt and enqueue it as a follow-up, so the **active** agent writes the docs
with its own tools and model — whatever you are running. An `init` on a large repo is a full
agent task, not a cheap background job, and it is one unbounded turn: there is no page-job
checkpointing to resume from if it is interrupted.

`update` always advances `gitHead`, even when the run changes no documentation. It has to: an
earlier version skipped the metadata write when content was unchanged, which pinned the diff
window so every subsequent `update` replayed the same commits and primed another full run.
