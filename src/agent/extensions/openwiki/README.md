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

Docs live under `.openwiki/`. Gated behind a flag — enable via the `openwiki` flag/settings.
