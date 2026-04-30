(ns claude-hook-bridge-audit.test
  "Tests for the trust audit log."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["node:fs" :as fs]
            [agent.extensions.claude-hook-bridge.audit :as audit]))

(defn- audit-lines []
  (when (fs/existsSync audit/audit-log-path)
    (let [raw (fs/readFileSync audit/audit-log-path "utf8")]
      (vec (filter seq (.split raw "\n"))))))

(defn- audit-line-count []
  (count (audit-lines)))

(beforeEach (fn []
              (audit/reset-seen!)
              ;; Don't delete the existing audit log — just count from
              ;; the current size so tests don't depend on each other.
              ))

(describe "audit/note!" (fn []
                          (it "appends a line on first execution"
                              (fn []
                                (let [before (audit-line-count)
                                      _      (audit/note! "PreToolUse" (str "command:test-" (.now js/Date)))
                                      after  (audit-line-count)]
                                  (-> (expect (- after before)) (.toBe 1)))))

                          (it "does not duplicate lines for the same command twice"
                              (fn []
                                (let [cmd    (str "command:test-dup-" (.now js/Date))
                                      before (audit-line-count)
                                      _      (audit/note! "PreToolUse" cmd)
                                      _      (audit/note! "PreToolUse" cmd)
                                      _      (audit/note! "PreToolUse" cmd)
                                      after  (audit-line-count)]
                                  (-> (expect (- after before)) (.toBe 1)))))

                          (it "logs different commands separately"
                              (fn []
                                (let [base (str "test-" (.now js/Date))
                                      before (audit-line-count)
                                      _      (audit/note! "PreToolUse" (str "command:" base "-a"))
                                      _      (audit/note! "PreToolUse" (str "command:" base "-b"))
                                      after  (audit-line-count)]
                                  (-> (expect (- after before)) (.toBe 2)))))

                          (it "writes parseable JSON per line"
                              (fn []
                                (audit/reset-seen!)
                                (let [cmd (str "command:json-test-" (.now js/Date))]
                                  (audit/note! "PreToolUse" cmd)
                                  (let [last-line (last (audit-lines))
                                        parsed    (js/JSON.parse last-line)]
                                    (-> (expect (.-event parsed)) (.toBe "PreToolUse"))
                                    (-> (expect (.-command parsed)) (.toBe cmd))
                                    (-> (expect (string? (.-sha256 parsed))) (.toBe true))
                                    (-> (expect (.-length (.-sha256 parsed))) (.toBe 64))))))

                          (it "reset-seen! re-arms first-run logging"
                              (fn []
                                (let [cmd (str "command:reset-test-" (.now js/Date))
                                      before (audit-line-count)
                                      _      (audit/note! "PreToolUse" cmd)
                                      _      (audit/note! "PreToolUse" cmd)
                                      after-once (audit-line-count)
                                      _      (audit/reset-seen!)
                                      _      (audit/note! "PreToolUse" cmd)
                                      after-twice (audit-line-count)]
                                  (-> (expect (- after-once before)) (.toBe 1))
                                  (-> (expect (- after-twice after-once)) (.toBe 1)))))))
