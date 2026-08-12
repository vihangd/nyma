# openwiki

AI-maintained, git-aware living documentation for the repo. Ported from barvhaim/pi-openwiki
(itself a port of langchain-ai/openwiki), enriched with mermaid diagrams, `file:line` source
citations, grounded chat, and a SHA-256 content-snapshot no-op guard (unchanged files don't
trigger doc rewrites).

## Usage

`/openwiki <verb>`:
- `init` — generate the wiki for the repo.
- `update` — refresh docs for files changed since the last run (git-aware).
- `chat <question>` — answer questions grounded in the wiki with citations.

Docs live under `openwiki/` (no leading dot) — configurable via `openwiki.dir`, default in
`shared.cljs`. Off by default; enable with `"openwiki": {"enabled": true}` in settings or the
`--ext-openwiki` flag. Every verb requires a git repo.

Note on cost: the verbs do not call a model themselves. Each builds a prompt and enqueues it as
a follow-up (`commands.cljs:20-27`), so the ACTIVE agent writes the docs with its own tools and
model — `"model": null` means "whatever you are running". An `init` on a large repo is a full
agent task, not a cheap background job.
