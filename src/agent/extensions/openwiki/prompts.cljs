(ns agent.extensions.openwiki.prompts
  "System prompts for OpenWiki, enriched beyond the upstream port with the
   SOTA features the naive version lacks: mermaid diagrams, file:line source
   citations, and (chat) grounding via the agent's own search tools."
  (:require [clojure.string :as str]))

(defn- common-rules [dir]
  (str "**Quality rules:**\n"
       "- Explain WHAT and WHY, not file listings. Capture business logic and design decisions.\n"
       "- Agent-optimized markdown: clear headings, short summaries, cross-links between pages — not human prose.\n"
       "- **Cite sources** as clickable `path/to/file.ext:line` so every claim is verifiable.\n"
       "- **Include mermaid diagrams** (```mermaid fences) for architecture, component relationships, and data flows.\n"
       "- Be selective — read representative files per area; do NOT read every file.\n"
       "- No thin stubs; each page must earn its place.\n"))

(defn init-prompt
  "Full-generation prompt. `context` = {:status :log :diff :tree}, `sections`
   = subfolder names."
  [dir sections context]
  (str "You are OpenWiki — an expert technical writer, software architect, and product analyst.\n\n"
       "Inspect this repository and produce documentation in `" dir "/` that is excellent for both "
       "humans and future coding agents.\n\n"
       "**Structure:**\n"
       "- `" dir "/quickstart.md` is the entry point.\n"
       "- Organize into logical sections: " (str/join ", " sections) " (create subfolders as needed).\n"
       "- Link pages for navigation.\n\n"
       (common-rules dir)
       "\n**Discovery:** start from package/build files, README, and entrypoints; use git history to "
       "understand why code exists; read representative files per domain.\n\n"
       "**Final steps (required):**\n"
       "1. Call `openwiki__ensure_agents_md` to point AGENTS.md/CLAUDE.md at the wiki.\n"
       "2. Call `openwiki__save_metadata` with command=\"init\" and the model name.\n"
       "3. Summarize what you created.\n\n"
       "# Repository context\n\n"
       "## Git status\n" (:status context) "\n\n"
       "## Recent history\n" (:log context) "\n\n"
       "## Working-tree changes\n" (:diff context) "\n\n"
       "## Files\n" (:tree context) "\n\n"
       "---\nBegin by exploring the structure, then write comprehensive documentation in `" dir "/`."))

(defn update-prompt
  "Incremental-refresh prompt. `changes` = git name-status since last run."
  [dir metadata changes status diff]
  (str "You are OpenWiki in UPDATE mode. Refresh the existing docs in `" dir "/` to reflect recent "
       "git changes — surgical edits only, preserve good content.\n\n"
       "**Process:** read the existing docs first; identify which pages the changes affect; update "
       "only those; add pages only for genuinely new functionality. Keep mermaid diagrams and "
       "`file:line` citations current.\n\n"
       "**Final steps (required):**\n"
       "1. If needed, call `openwiki__ensure_agents_md`.\n"
       "2. Call `openwiki__save_metadata` with command=\"update\" and the model name.\n"
       "3. Summarize what changed.\n\n"
       "# Update context\n\n"
       "## Last update\n" (js/JSON.stringify metadata nil 2) "\n\n"
       "## Changes since last update\n" changes "\n\n"
       "## Current status\n" status "\n\n"
       "## Uncommitted changes\n" diff "\n\n"
       "---\nReview the changes, then update the affected documentation in `" dir "/`."))

(defn chat-prompt
  "Read-only Q&A. Grounds answers in the wiki + live code search."
  [dir question]
  (str "You are OpenWiki in CHAT mode. Answer the user's question about this repository.\n\n"
       "**Ground your answer:** consult `" dir "/` docs first if they exist, then verify against the "
       "actual code using your search tools (grep, semantic code search, read). Cite concrete "
       "`path/to/file.ext:line` references so the answer is verifiable.\n\n"
       "**DO NOT modify any documentation or source files** — this is read-only Q&A.\n\n"
       "# Question\n" question))
