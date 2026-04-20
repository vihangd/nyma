(ns smart-compaction-prompt.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/extensions/token_suite/smart_compaction.mjs" :refer [structured-prompt]]))

(describe "smart_compaction structured-prompt mirrors compaction schema" (fn []
                                                                           (it "contains all 6 section headers"
                                                                               (fn []
                                                                                 (doseq [h ["## 1. Previous Conversation"
                                                                                            "## 2. Current Work"
                                                                                            "## 3. Key Technical Concepts"
                                                                                            "## 4. Relevant Files and Code"
                                                                                            "## 5. Problem Solving"
                                                                                            "## 6. Pending Tasks and Next Steps"]]
                                                                                   (-> (expect (.includes structured-prompt h)) (.toBe true)))))

                                                                           (it "requires verbatim quotes in section 6"
                                                                               (fn []
                                                                                 (-> (expect (.includes structured-prompt "VERBATIM QUOTE")) (.toBe true))
                                                                                 (-> (expect (.includes structured-prompt "Quote:")) (.toBe true))))

                                                                           (it "warns against continuing the conversation"
                                                                               (fn []
                                                                                 (-> (expect (.includes structured-prompt "Do NOT continue")) (.toBe true))))))
