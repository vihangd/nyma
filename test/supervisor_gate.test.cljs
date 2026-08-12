(ns supervisor-gate.test
  "The supervisor's pre-commit gate is one of three triggers that decide when a
   second, stronger model gets consulted — and it was silently dead for half
   the tools it claimed to cover.

   `commit-tools` was `#{\"bash\" \"write\"}`, but the body then guarded
   `(when (= tool-name \"bash\") …)`. For a write the `when` yielded nil, so the
   enclosing `and` was falsey and the gate could never fire. The set advertised
   a capability the code did not have, and nothing tested it."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.supervisor :refer [commit-like?]]))

(describe "supervisor pre-commit gate"
          (fn []
            (it "fires on commands that publish work"
                (fn []
                  (doseq [cmd ["git commit -m 'x'"
                               "git push origin main"
                               "git merge develop"
                               "cd /tmp && git commit --amend"]]
                    (-> (expect (commit-like? "bash" #js {:command cmd})) (.toBe true)))))

            (it "ignores ordinary commands"
                (fn []
                  ;; Too loose and every turn summons the advisor.
                  (doseq [cmd ["ls -la" "bun test" "git status" "git diff" "echo commit"]]
                    (-> (expect (commit-like? "bash" #js {:command cmd})) (.toBe false)))))

            (it "returns a boolean for non-bash tools rather than nil"
                (fn []
                  ;; The original returned nil here, and nil in the `and` is
                  ;; what made the whole predicate dead for `write`. Nothing
                  ;; defines a commit-like write, and treating every write as
                  ;; one would consult the advisor on nearly every turn — so
                  ;; false is the answer, explicitly.
                  (doseq [t ["write" "edit" "read" "glob"]]
                    (-> (expect (commit-like? t #js {:command "git commit -m x"}))
                        (.toBe false)))))

            (it "does not throw on missing or malformed args"
                (fn []
                  (-> (expect (commit-like? "bash" nil)) (.toBe false))
                  (-> (expect (commit-like? "bash" #js {})) (.toBe false))
                  (-> (expect (commit-like? nil nil)) (.toBe false))))))
