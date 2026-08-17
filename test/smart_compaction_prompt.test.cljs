(ns smart-compaction-prompt.test
  (:require ["node:fs" :as fs]
            ["bun:test" :refer [describe it expect]]
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

;;; ─── the summariser must read the metadata-bearing span ─────────────────
;;; extract-file-ops needs :metadata tool-name/args. The hook was reading
;;; messages-to-summarize, which build-context strips of metadata, so it found
;;; nothing: "[no edits yet]" for a session that edited 159 files, and 157
;;; characters of summary for a whole session of work. Asserted on compiled
;;; output because driving the hook end to end needs a session and a provider.

(describe "smart-compaction reads entries-to-summarize"
          (fn []
            (it "prefers the span that still carries metadata"
                (fn []
                  (let [src (str (fs/readFileSync
                                  "dist/agent/extensions/token_suite/smart_compaction.mjs" "utf8"))
                        entries (.indexOf src "entries-to-summarize")
                        msgs    (.indexOf src "messages-to-summarize")]
                    (-> (expect (> entries -1)) (.toBe true))
                    ;; and it is tried FIRST, with the stripped payload as fallback
                    (-> (expect (< entries msgs)) (.toBe true)))))))
