(ns project-card.test
  "The block a session opens with. Pure `render` over a values map, the same
   split `resumed-banner` uses so it is testable without a TUI.

   The rule every case below enforces: a missing fact produces a MISSING line,
   never a zero. `0 skills` reads as a broken install; silence reads as nothing
   to say. And a clean repo in sync shows only its branch — the absence of
   markers is the information."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.project-card :as card]))

;;; ─── the git line ──────────────────────────────────────────────

(describe "project-card: git line" (fn []

  (it "a clean branch in sync is just the branch"
      (fn []
        (-> (expect (card/git-line {:branch "main" :staged 0 :unstaged 0
                                    :untracked 0 :conflicted 0 :ahead 0 :behind 0}))
            (.toBe "main"))))

  (it "uses the sigils git status -sb already taught"
      (fn []
        (-> (expect (card/git-line {:branch "main" :staged 2 :unstaged 3
                                    :untracked 8 :ahead 1 :behind 4}))
            (.toBe "main +2 ~3 ?8 ↑1 ↓4"))))

  (it "a conflict leads, because mid-merge it is the only number that matters"
      (fn []
        (-> (expect (card/git-line {:branch "main" :conflicted 2 :staged 1}))
            (.toBe "main !2 +1"))))

  (it "no upstream shows neither arrow, rather than ↑0"
      (fn []
        ;; nil ahead/behind means unknown; 0/0 means in sync. Both render bare,
        ;; but only because a marker would be a claim we cannot make.
        (-> (expect (card/git-line {:branch "main"})) (.toBe "main"))))

  (it "a detached HEAD says so instead of naming a branch"
      (fn []
        ;; --abbrev-ref returns the string "HEAD" there, which is not a branch
        (-> (expect (card/git-line {:branch nil :detached? true :unstaged 1}))
            (.toBe "detached ~1"))))

  (it "outside a repo there is no line at all"
      (fn []
        (-> (expect (card/git-line {})) (.toBeNil))
        (-> (expect (card/git-line {:staged 3})) (.toBeNil))))))

;;; ─── the other lines ───────────────────────────────────────────

(describe "project-card: loaded / prompt / index lines" (fn []

  (it "names what the agent picked up"
      (fn []
        (-> (expect (card/loaded-line {:extensions 40 :skills 24 :mcp-servers 5 :mcp-tools 63}))
            (.toBe "40 extensions · 24 skills · 5 MCP (63 tools)"))))

  (it "omits a zero rather than printing it"
      (fn []
        (-> (expect (card/loaded-line {:extensions 40 :skills 0 :mcp-servers 0}))
            (.toBe "40 extensions"))))

  (it "nothing loaded is no line"
      (fn []
        (-> (expect (card/loaded-line {})) (.toBeNil))))

  (it "a server with no tools yet omits the tool count"
      (fn []
        (-> (expect (card/loaded-line {:mcp-servers 2 :mcp-tools 0})) (.toBe "2 MCP"))))

  (it "the prompt line names what a request costs before you type"
      (fn []
        (-> (expect (card/prompt-line {:agents-md-tokens 9750})) (.toBe "AGENTS.md ~9.8k tok"))))

  (it "no AGENTS.md is no line"
      (fn []
        (-> (expect (card/prompt-line {})) (.toBeNil))
        (-> (expect (card/prompt-line {:agents-md-tokens 0})) (.toBeNil))))

  (it "says indexed, not files, because the count is filtered and depth-capped"
      (fn []
        ;; repo_map skips node_modules/dist/.git, caps depth at 5 and filters by
        ;; extension — calling it a file count would be wrong
        (-> (expect (card/index-line {:indexed-files 1240 :indexed-symbols 8300}))
            (.toBe "1.2k files indexed · 8.3k symbols"))))

  (it "no index is no line"
      (fn []
        (-> (expect (card/index-line {:indexed-files 0})) (.toBeNil))))))

;;; ─── helpers ───────────────────────────────────────────────────

(describe "project-card: helpers" (fn []

  (it "abbreviates the home prefix, which is noise"
      (fn []
        (-> (expect (card/abbrev-path "/Users/me/projects/x" "/Users/me")) (.toBe "~/projects/x"))))

  (it "leaves a path outside home alone"
      (fn []
        (-> (expect (card/abbrev-path "/tmp/x" "/Users/me")) (.toBe "/tmp/x"))))

  (it "survives a missing home dir"
      (fn []
        (-> (expect (card/abbrev-path "/tmp/x" nil)) (.toBe "/tmp/x"))))

  (it "compacts counts to fit one line"
      (fn []
        (-> (expect (card/compact-count 999)) (.toBe "999"))
        (-> (expect (card/compact-count 1240)) (.toBe "1.2k"))
        (-> (expect (card/compact-count 12400)) (.toBe "12k"))
        (-> (expect (card/compact-count 1240000)) (.toBe "1.2M"))
        (-> (expect (card/compact-count 0)) (.toBe "0"))))))

;;; ─── the whole card ────────────────────────────────────────────

(describe "project-card: render" (fn []

  (it "puts the header first and indents the body"
      (fn []
        (let [out (card/render {:version "0.11.0" :cwd "/Users/me/p/nyma" :home-dir "/Users/me"
                               :branch "main" :unstaged 1 :extensions 40 :skills 24
                               :agents-md-tokens 9750 :indexed-files 1240})
              lines (.split out "\n")]
          (-> (expect (aget lines 0)) (.toBe "nyma 0.11.0  ·  ~/p/nyma"))
          (-> (expect (aget lines 1)) (.toBe "  main ~1"))
          (-> (expect (.-length lines)) (.toBe 5)))))

  (it "a bare directory with nothing loaded renders nothing"
      (fn []
        ;; header alone is not worth a card — it would just repeat the prompt
        (-> (expect (card/render {:version "0.11.0" :cwd "/tmp/x"})) (.toBeNil))))

  (it "one fact is enough for a card"
      (fn []
        (let [out (card/render {:version "0.11.0" :cwd "/tmp/x" :branch "main"})]
          (-> (expect (.includes out "main")) (.toBe true))
          (-> (expect (.-length (.split out "\n"))) (.toBe 2)))))

  (it "an unknown version still renders"
      (fn []
        (-> (expect (.includes (card/render {:branch "main"}) "nyma ?")) (.toBe true))))))
