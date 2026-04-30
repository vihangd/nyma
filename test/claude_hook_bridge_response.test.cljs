(ns claude-hook-bridge-response.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.claude-hook-bridge.response :refer [parse-one merge-many]]))

;;; ─── parse-one ───────────────────────────────────────────────────────────

(describe "parse-one/exit-2-no-json" (fn []
                                       (it "produces deny + reason from stderr"
                                           (fn []
                                             (let [r (parse-one {:exit-code 2 :stdout "" :stderr "  blocked because dangerous  "})]
                                               (-> (expect (:permission-decision r)) (.toBe "deny"))
                                               (-> (expect (:permission-reason r)) (.toBe "blocked because dangerous"))
                                               (-> (expect (:decision-block? r)) (.toBe true)))))))

(describe "parse-one/exit-0-plain-text" (fn []
                                          (it "treats plain stdout as additional-context"
                                              (fn []
                                                (let [r (parse-one {:exit-code 0 :stdout "hello world\n" :stderr ""})]
                                                  (-> (expect (:additional-context r)) (.toBe "hello world"))
                                                  (-> (expect (:permission-decision r)) (.toBeNil)))))

                                          (it "ignores plain-text stdout when exit-code is non-zero"
                                              (fn []
                                                (-> (expect (parse-one {:exit-code 1 :stdout "noise" :stderr ""})) (.toBeNil))))))

(describe "parse-one/json-decision-block" (fn []
                                            (it "parses top-level decision: block"
                                                (fn []
                                                  (let [r (parse-one {:exit-code 0
                                                                      :stdout "{\"decision\":\"block\",\"reason\":\"nope\"}"
                                                                      :stderr ""})]
                                                    (-> (expect (:decision-block? r)) (.toBe true))
                                                    (-> (expect (:decision-block-reason r)) (.toBe "nope")))))))

(describe "parse-one/hookSpecificOutput" (fn []
                                           (it "extracts permissionDecision and reason"
                                               (fn []
                                                 (let [json "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"policy violation\"}}"
                                                       r    (parse-one {:exit-code 0 :stdout json :stderr ""})]
                                                   (-> (expect (:permission-decision r)) (.toBe "deny"))
                                                   (-> (expect (:permission-reason r)) (.toBe "policy violation")))))

                                           (it "extracts updatedInput"
                                               (fn []
                                                 (let [json "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"updatedInput\":{\"command\":\"rtk git status\"}}}"
                                                       r    (parse-one {:exit-code 0 :stdout json :stderr ""})]
                                                   (-> (expect (.-command (:updated-input r))) (.toBe "rtk git status")))))

                                           (it "extracts additionalContext"
                                               (fn []
                                                 (let [json "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"additionalContext\":\"Branch: main\"}}"
                                                       r    (parse-one {:exit-code 0 :stdout json :stderr ""})]
                                                   (-> (expect (:additional-context r)) (.toBe "Branch: main")))))))

(describe "parse-one/continue-false" (fn []
                                       (it "captures continue: false and stopReason"
                                           (fn []
                                             (let [json "{\"continue\":false,\"stopReason\":\"shutdown requested\"}"
                                                   r    (parse-one {:exit-code 0 :stdout json :stderr ""})]
                                               (-> (expect (:continue? r)) (.toBe false))
                                               (-> (expect (:stop-reason r)) (.toBe "shutdown requested")))))))

;;; ─── merge-many precedence ──────────────────────────────────────────────

(describe "merge-many/permission-precedence" (fn []
                                               (it "deny beats allow"
                                                   (fn []
                                                     (let [m (merge-many [{:permission-decision "allow"}
                                                                          {:permission-decision "deny" :permission-reason "no"}])]
                                                       (-> (expect (:permission-decision m)) (.toBe "deny"))
                                                       (-> (expect (:permission-reason m)) (.toBe "no")))))

                                               (it "deny beats ask"
                                                   (fn []
                                                     (-> (expect (:permission-decision
                                                                  (merge-many [{:permission-decision "ask"}
                                                                               {:permission-decision "deny"}])))
                                                         (.toBe "deny"))))

                                               (it "deny beats defer"
                                                   (fn []
                                                     (-> (expect (:permission-decision
                                                                  (merge-many [{:permission-decision "defer"}
                                                                               {:permission-decision "deny"}])))
                                                         (.toBe "deny"))))

                                               (it "defer beats ask"
                                                   (fn []
                                                     (-> (expect (:permission-decision
                                                                  (merge-many [{:permission-decision "ask"}
                                                                               {:permission-decision "defer"}])))
                                                         (.toBe "defer"))))

                                               (it "ask beats allow"
                                                   (fn []
                                                     (-> (expect (:permission-decision
                                                                  (merge-many [{:permission-decision "allow"}
                                                                               {:permission-decision "ask"}])))
                                                         (.toBe "ask"))))

                                               (it "winner's reason is used regardless of order"
                                                   (fn []
                                                     (let [m (merge-many [{:permission-decision "deny" :permission-reason "first"}
                                                                          {:permission-decision "allow" :permission-reason "second"}])]
                                                       (-> (expect (:permission-decision m)) (.toBe "deny"))
                                                       (-> (expect (:permission-reason m)) (.toBe "first")))))))

(describe "merge-many/additional-context" (fn []
                                            (it "concatenates context from multiple hooks in order"
                                                (fn []
                                                  (let [m (merge-many [{:additional-context "first line"}
                                                                       {:additional-context "second line"}])]
                                                    (-> (expect (:additional-context m)) (.toBe "first line\nsecond line")))))))

(describe "merge-many/updated-input" (fn []
                                       (it "last writer wins"
                                           (fn []
                                             (let [a #js {:command "a"}
                                                   b #js {:command "b"}
                                                   m (merge-many [{:updated-input a} {:updated-input b}])]
                                               (-> (expect (.-command (:updated-input m))) (.toBe "b")))))))

(describe "merge-many/decision-block" (fn []
                                        (it "first block wins and stays"
                                            (fn []
                                              (let [m (merge-many [{:decision-block? true :decision-block-reason "first"}
                                                                   {:decision-block? true :decision-block-reason "second"}])]
                                                (-> (expect (:decision-block? m)) (.toBe true))
                                                (-> (expect (:decision-block-reason m)) (.toBe "first")))))

                                        (it "no-block when no hook blocks"
                                            (fn []
                                              (let [m (merge-many [{:additional-context "hi"}])]
                                                (-> (expect (:decision-block? m)) (.toBe false)))))))

(describe "merge-many/continue-false" (fn []
                                        (it "captures continue: false from any hook"
                                            (fn []
                                              (let [m (merge-many [{:continue? true} {:continue? false :stop-reason "halt"}])]
                                                (-> (expect (:continue? m)) (.toBe false))
                                                (-> (expect (:stop-reason m)) (.toBe "halt")))))))

(describe "merge-many/empty" (fn []
                               (it "produces a clean empty resolution for no hooks"
                                   (fn []
                                     (let [m (merge-many [])]
                                       (-> (expect (:permission-decision m)) (.toBeNil))
                                       (-> (expect (:decision-block? m)) (.toBe false))
                                       (-> (expect (:additional-context m)) (.toBeNil)))))))
