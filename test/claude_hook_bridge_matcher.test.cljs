(ns claude-hook-bridge-matcher.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.claude-hook-bridge.matcher :refer [matches?]]))

(describe "matches?/wildcard" (fn []
                                (it "true for nil"
                                    (fn [] (-> (expect (matches? nil "Bash")) (.toBe true))))
                                (it "true for empty string"
                                    (fn [] (-> (expect (matches? "" "Bash")) (.toBe true))))
                                (it "true for *"
                                    (fn [] (-> (expect (matches? "*" "Bash")) (.toBe true))))))

(describe "matches?/exact" (fn []
                             (it "matches exact tool name"
                                 (fn [] (-> (expect (matches? "Bash" "Bash")) (.toBe true))))
                             (it "rejects different tool name"
                                 (fn [] (-> (expect (matches? "Bash" "Read")) (.toBe false))))
                             ;; Simple-shape matchers are case-tolerant since (a) — see the
                             ;; case-tolerant-simple block below.
                             ))

(describe "matches?/pipe-list" (fn []
                                 (it "matches first member of a pipe list"
                                     (fn [] (-> (expect (matches? "Edit|Write" "Edit")) (.toBe true))))
                                 (it "matches last member"
                                     (fn [] (-> (expect (matches? "Edit|Write" "Write")) (.toBe true))))
                                 (it "rejects non-member"
                                     (fn [] (-> (expect (matches? "Edit|Write" "Read")) (.toBe false))))
                                 (it "supports three or more entries"
                                     (fn [] (-> (expect (matches? "A|B|C|D" "C")) (.toBe true))))))

(describe "matches?/regex" (fn []
                             (it "matches by anchored prefix"
                                 (fn [] (-> (expect (matches? "^Notebook" "NotebookEdit")) (.toBe true))))
                             (it "rejects when prefix doesn't match"
                                 (fn [] (-> (expect (matches? "^Notebook" "EditNotebook")) (.toBe false))))
                             (it "matches MCP wildcard"
                                 (fn [] (-> (expect (matches? "mcp__memory__.*" "mcp__memory__write_graph")) (.toBe true))))
                             (it "MCP wildcard rejects different server"
                                 (fn [] (-> (expect (matches? "mcp__memory__.*" "mcp__filesystem__read")) (.toBe false))))
                             (it "fail-safes on invalid regex (does not match all)"
                                 (fn [] (-> (expect (matches? "[" "Bash")) (.toBe false))))))

(describe "matches?/case-tolerant-simple" (fn []
                                            (it "lowercase matcher matches TitleCase value"
                                                (fn []
                                                  (-> (expect (matches? "bash" "Bash")) (.toBe true))))
                                            (it "TitleCase matcher matches lowercase value"
                                                (fn []
                                                  (-> (expect (matches? "Bash" "bash")) (.toBe true))))
                                            (it "uppercase matcher matches mixed-case value"
                                                (fn []
                                                  (-> (expect (matches? "BASH" "Bash")) (.toBe true))))
                                            (it "case-tolerant pipe-list"
                                                (fn []
                                                  (-> (expect (matches? "edit|write" "Edit")) (.toBe true))
                                                  (-> (expect (matches? "edit|write" "Write")) (.toBe true))
                                                  (-> (expect (matches? "edit|write" "Read")) (.toBe false))))
                                            (it "still rejects different names regardless of case"
                                                (fn []
                                                  (-> (expect (matches? "Bash" "Read")) (.toBe false))
                                                  (-> (expect (matches? "bash" "Read")) (.toBe false))))
                                            (it "regex branch stays strict (no case-folding)"
                                                (fn []
                                                  ;; ^Bash falls to the regex branch (^ is not in
                                                  ;; the simple-shape charset [A-Za-z0-9_|]), and the
                                                  ;; regex matcher stays strict — case-tolerance is
                                                  ;; ONLY for the simple shape. Users who want
                                                  ;; case-insensitive regex must build it explicitly
                                                  ;; in the pattern (e.g. character classes), since
                                                  ;; JS RegExp doesn't support inline (?i).
                                                  (-> (expect (matches? "^Bash" "Bash")) (.toBe true))
                                                  (-> (expect (matches? "^Bash" "bash")) (.toBe false))))))

(describe "matches?/edge-cases" (fn []
                                  (it "false for nil value"
                                      (fn [] (-> (expect (matches? "Bash" nil)) (.toBe false))))
                                  (it "wildcard wins over nil value"
                                      (fn [] (-> (expect (matches? "*" nil)) (.toBe true))))))
