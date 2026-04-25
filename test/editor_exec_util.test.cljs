(ns editor-exec-util.test
  "Tests for truncate-output and parse-exec-json."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.editor-exec-util :refer [truncate-output parse-exec-json max-output-bytes]]))

;;; ─── truncate-output ──────────────────────────────────────────────────────

(describe "truncate-output" (fn []
                              (it "returns empty string for nil"
                                  (fn []
                                    (-> (expect (truncate-output nil)) (.toBe ""))))

                              (it "returns empty string for empty string"
                                  (fn []
                                    (-> (expect (truncate-output "")) (.toBe ""))))

                              (it "returns short strings unchanged"
                                  (fn []
                                    (-> (expect (truncate-output "hello")) (.toBe "hello"))))

                              (it "returns string of exactly max-output-bytes unchanged"
                                  (fn []
                                    (let [s (.repeat "x" max-output-bytes)]
                                      (-> (expect (truncate-output s)) (.toBe s)))))

                              (it "truncates strings longer than max-output-bytes"
                                  (fn []
                                    (let [s     (.repeat "x" (+ max-output-bytes 100))
                                          result (truncate-output s)]
                                      (-> (expect (.startsWith result (.repeat "x" max-output-bytes))) (.toBe true)))))

                              (it "appends byte-count note when truncated"
                                  (fn []
                                    (let [extra  500
                                          s      (.repeat "x" (+ max-output-bytes extra))
                                          result (truncate-output s)]
                                      (-> (expect (.includes result "500 bytes truncated")) (.toBe true)))))

                              (it "truncated output starts with the first max-output-bytes chars"
                                  (fn []
                                    (let [s      (str (.repeat "a" max-output-bytes) (.repeat "z" 10))
                                          result (truncate-output s)]
                                      (-> (expect (.startsWith result (.repeat "a" max-output-bytes))) (.toBe true))
                                      ;; "z" never appears in truncation note so this confirms no overflow
                                      (-> (expect (.includes result "z")) (.toBe false)))))))

;;; ─── parse-exec-json ──────────────────────────────────────────────────────

(describe "parse-exec-json" (fn []
                              (it "parses valid stdout/stderr/exitCode"
                                  (fn []
                                    (let [result (parse-exec-json "{\"stdout\":\"out\",\"stderr\":\"err\",\"exitCode\":0}")]
                                      (-> (expect (:stdout result)) (.toBe "out"))
                                      (-> (expect (:stderr result)) (.toBe "err"))
                                      (-> (expect (:exit-code result)) (.toBe 0)))))

                              (it "defaults missing stdout to empty string"
                                  (fn []
                                    (-> (expect (:stdout (parse-exec-json "{\"exitCode\":0}"))) (.toBe ""))))

                              (it "defaults missing stderr to empty string"
                                  (fn []
                                    (-> (expect (:stderr (parse-exec-json "{\"exitCode\":0}"))) (.toBe ""))))

                              (it "defaults missing exitCode to 0"
                                  (fn []
                                    (-> (expect (:exit-code (parse-exec-json "{\"stdout\":\"x\"}"))) (.toBe 0))))

                              (it "returns error sentinel for malformed JSON"
                                  (fn []
                                    (let [result (parse-exec-json "not json at all")]
                                      (-> (expect (:exit-code result)) (.toBe -1))
                                      (-> (expect (.includes (:stderr result) "unparseable")) (.toBe true)))))

                              (it "returns error sentinel for nil input"
                                  (fn []
                                    (let [result (parse-exec-json nil)]
                                      (-> (expect (:exit-code result)) (.toBe -1)))))

                              (it "returns error sentinel for empty string"
                                  (fn []
                                    (let [result (parse-exec-json "")]
                                      (-> (expect (:exit-code result)) (.toBe -1)))))

                              (it "handles non-zero exit codes"
                                  (fn []
                                    (-> (expect (:exit-code (parse-exec-json "{\"exitCode\":127}"))) (.toBe 127))))))
