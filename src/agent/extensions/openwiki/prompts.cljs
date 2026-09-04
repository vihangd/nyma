(ns agent.extensions.openwiki.prompts
  "The prompts that do the actual work.

   OpenWiki has no wiki engine: no index, no link graph, no page parser, no
   retrieval. The commands collect git evidence and hand the agent a brief; the
   agent writes the bundle with its own read/write/search tools. So everything
   about the OUTPUT FORMAT is specified here, in prose, and the only automated
   check on it is shared/conformance-report, which save_metadata runs.

   The target format is an OKF v0.2 bundle (GoogleCloudPlatform/knowledge-catalog
   okf/SPEC.md) — the same format upstream langchain-ai/openwiki emits. This is
   a much smaller subset of upstream: no Grounded Claims, no page-job queue."
  (:require [clojure.string :as str]
            [agent.extensions.openwiki.shared :as shared]))

(defn- okf-rules [dir]
  (str "**Output format — OKF v" shared/okf-version " (required):**\n"
       "- Every page except `index.md` and `log.md` MUST open with a YAML frontmatter block:\n"
       "  ```\n"
       "  ---\n"
       "  type: <short kind, e.g. architecture | workflow | domain-concept | operations | testing>\n"
       "  title: <human-readable name>\n"
       "  description: <one sentence>\n"
       "  tags: [<a>, <b>]\n"
       "  ---\n"
       "  ```\n"
       "  `type` is the one always-required field and must be non-empty.\n"
       "- `" dir "/index.md` is the entry point. It carries NO frontmatter except\n"
       "  `okf_version: \"" shared/okf-version "\"` — and that is the only index in the bundle allowed any\n"
       "  frontmatter at all. Its body is sections of markdown links with one-line descriptions.\n"
       "- `index.md` files in subdirectories are optional and carry no frontmatter.\n"
       "- Broken links and missing optional fields are tolerated by the spec — do not invent\n"
       "  filler to avoid them.\n"))

(defn- common-rules []
  (str "**Quality rules:**\n"
       "- Explain WHAT and WHY, not file listings. Capture business logic and design decisions.\n"
       "- Agent-optimized markdown: clear headings, short summaries, cross-links between pages — not human prose.\n"
       "- **Cite sources** as clickable `path/to/file.ext:line` so every claim is verifiable.\n"
       "- **Include mermaid diagrams** (```mermaid fences) for architecture, component relationships, and data flows.\n"
       "  Verify each diagram parses; if you are unsure of the syntax, use a plain ```text fence instead of\n"
       "  shipping a block that renders as an error.\n"
       "- Be selective — read representative files per area; do NOT read every file.\n"
       "- No thin stubs; each page must earn its place.\n"))

(defn- instructions-block
  "User-authored scope, inlined verbatim. Never rewritten by the agent."
  [instructions]
  (when-not (str/blank? (str instructions))
    (str "# Scope instructions (author-written — obey these over your own judgement, "
         "and never edit this file)\n\n" instructions "\n\n")))

(defn- evidence [context]
  (str "# Repository context\n\n"
       "## Git status (`git status --short`)\n" (:status context) "\n\n"
       "## Recent history (`git log --oneline -20`)\n" (:log context) "\n\n"
       "## Uncommitted files (`git diff --name-status HEAD` — FILE NAMES ONLY, not a patch;\n"
       "read the files if you need the changes themselves)\n" (:diff context) "\n\n"
       "## Repository shape\n" (:tree context) "\n\n"))

(defn init-prompt
  "Full-generation prompt. `context` = {:status :log :diff :tree}, `sections`
   = subfolder names, `instructions` = <dir>/INSTRUCTIONS.md or nil."
  [dir sections context instructions]
  (str "You are OpenWiki — an expert technical writer, software architect, and product analyst.\n\n"
       "Inspect this repository and produce documentation in `" dir "/` that is excellent for both "
       "humans and future coding agents.\n\n"
       "**Structure:**\n"
       "- `" dir "/index.md` is the entry point.\n"
       "- Organize into logical sections: " (str/join ", " sections) " (create subfolders as needed).\n"
       "- Link pages for navigation.\n\n"
       (okf-rules dir)
       "\n"
       (common-rules)
       "\n**Discovery:** start from package/build files, README, and entrypoints; use git history to "
       "understand why code exists; read representative files per domain.\n\n"
       "**Final steps (required):**\n"
       "1. Call `openwiki__ensure_agents_md` to point AGENTS.md/CLAUDE.md at the wiki.\n"
       "2. Call `openwiki__save_metadata` with command=\"init\" and the model name. If it reports OKF\n"
       "   violations, fix them and call it again.\n"
       "3. Summarize what you created.\n\n"
       (instructions-block instructions)
       (evidence context)
       "---\nBegin by exploring the structure, then write comprehensive documentation in `" dir "/`."))

(defn update-prompt
  "Incremental-refresh prompt. `changes` = git name-status since last run."
  [dir metadata changes context instructions]
  (str "You are OpenWiki in UPDATE mode. Refresh the existing docs in `" dir "/` to reflect recent "
       "git changes — surgical edits only, preserve good content.\n\n"
       "**Process:** read the existing docs first; identify which pages the changes affect; update "
       "only those; add pages only for genuinely new functionality. Keep mermaid diagrams and "
       "`file:line` citations current.\n\n"
       "**Also append to `" dir "/log.md`** (create it if absent): an ISO `YYYY-MM-DD` heading, "
       "newest first, with one bullet per change starting in bold — `**Update**`, `**Creation**`, "
       "`**Deprecation**`. No frontmatter in this file.\n\n"
       (okf-rules dir)
       "\n**Final steps (required):**\n"
       "1. If needed, call `openwiki__ensure_agents_md`.\n"
       "2. Call `openwiki__save_metadata` with command=\"update\" and the model name. If it reports OKF\n"
       "   violations, fix them and call it again.\n"
       "3. Summarize what changed.\n\n"
       (instructions-block instructions)
       "# Update context\n\n"
       "## Last update\n" (js/JSON.stringify metadata nil 2) "\n\n"
       "## Commits since last update\n" changes "\n\n"
       (evidence context)
       "---\nReview the changes, then update the affected documentation in `" dir "/`."))
