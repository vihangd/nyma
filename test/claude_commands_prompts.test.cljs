(ns claude-commands-prompts-test
  "Claude Code's `.claude/commands/*.md` load as prompts, so existing commands
   work in nyma unchanged. nyma's own `.nyma/prompts` wins a name clash."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.resources.loader :as loader]))

(defn- write! [dir rel content]
  (let [f (path/join dir rel)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/writeFileSync f content)))

(defn ^:async test-claude-commands []
  (let [d    (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cc-cmds-"))
        prev (js/process.cwd)]
    (write! d ".claude/commands/review.md" "---\ndescription: Review the diff\n---\nReview $ARGUMENTS carefully.")
    (write! d ".claude/commands/both.md" "from claude")
    (write! d ".nyma/prompts/both.md" "from nyma")
    (js/process.chdir d)
    (try
      (let [prompts (:prompts (js-await (loader/discover {})))]
        (-> (expect (:template (get prompts "review"))) (.toBe "Review $ARGUMENTS carefully."))
        (-> (expect (:description (get prompts "review"))) (.toBe "Review the diff"))
        (-> (expect (.trim (:template (get prompts "both")))) (.toBe "from nyma")))
      (finally (js/process.chdir prev)
               (fs/rmSync d #js {:recursive true :force true})))))

(describe ".claude/commands"
          (fn [] (it "load as prompts; .nyma/prompts wins a clash" test-claude-commands)))
