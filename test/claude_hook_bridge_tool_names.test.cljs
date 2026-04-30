(ns claude-hook-bridge-tool-names.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.claude-hook-bridge.tool-names :refer [cc-name nyma-name]]))

(describe "cc-name" (fn []
                      (it "translates known nyma names to TitleCase"
                          (fn []
                            (-> (expect (cc-name "bash")) (.toBe "Bash"))
                            (-> (expect (cc-name "read")) (.toBe "Read"))
                            (-> (expect (cc-name "edit")) (.toBe "Edit"))
                            (-> (expect (cc-name "write")) (.toBe "Write"))
                            (-> (expect (cc-name "glob")) (.toBe "Glob"))
                            (-> (expect (cc-name "grep")) (.toBe "Grep"))
                            (-> (expect (cc-name "web_fetch")) (.toBe "WebFetch"))
                            (-> (expect (cc-name "web_search")) (.toBe "WebSearch"))))

                      (it "passes mcp__ tools through unchanged"
                          (fn []
                            (-> (expect (cc-name "mcp__memory__write_graph")) (.toBe "mcp__memory__write_graph"))))

                      (it "passes unknown names through unchanged"
                          (fn []
                            (-> (expect (cc-name "custom-tool")) (.toBe "custom-tool"))))

                      (it "handles nil-ish inputs without crashing"
                          (fn []
                            (-> (expect (cc-name nil)) (.toBe ""))))))

(describe "nyma-name" (fn []
                        (it "translates TitleCase back to lowercase"
                            (fn []
                              (-> (expect (nyma-name "Bash")) (.toBe "bash"))
                              (-> (expect (nyma-name "WebFetch")) (.toBe "web_fetch"))
                              (-> (expect (nyma-name "WebSearch")) (.toBe "web_search"))))

                        (it "passes mcp__ tools through unchanged"
                            (fn []
                              (-> (expect (nyma-name "mcp__memory__read")) (.toBe "mcp__memory__read"))))

                        (it "passes unknown names through unchanged"
                            (fn []
                              (-> (expect (nyma-name "Custom")) (.toBe "Custom"))))))

(describe "round-trip" (fn []
                         (it "is bijective for known names"
                             (fn []
                               (doseq [n ["bash" "read" "edit" "write" "glob" "grep" "web_fetch" "web_search" "ls"]]
                                 (-> (expect (nyma-name (cc-name n))) (.toBe n)))))))
