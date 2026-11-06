(ns subagent-steps.test
  "The child's step budget was hardcoded to 20 and nothing could pass one,
   though run-isolated-agent already accepted :max-steps. Borrowed from
   apprentice's `turns` argument: the parent sizes the budget to the task."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.subagent.index :refer [steps-arg steps-ceiling worktree-args child-permission-mode]]))

(describe "subagent:steps-arg" (fn []
                                 (it "accepts a positive integer, floors a float"
                                     (fn []
                                       (-> (expect (steps-arg 8)) (.toBe 8))
                                       (-> (expect (steps-arg 3.7)) (.toBe 3))
                                       (-> (expect (steps-arg "12")) (.toBe 12))))

                                 (it "rejects zero, negatives, junk and absence"
                                     (fn []
                                       (-> (expect (steps-arg 0)) (.toBeNil))
                                       (-> (expect (steps-arg -4)) (.toBeNil))
                                       (-> (expect (steps-arg "lots")) (.toBeNil))
                                       (-> (expect (steps-arg nil)) (.toBeNil))
                                       (-> (expect (steps-arg js/undefined)) (.toBeNil))))

  (it "is clamped to a ceiling the user set, never one the model made up"
      (fn []
        (-> (expect (steps-arg 100000 30)) (.toBe 30))
        (-> (expect (steps-arg 8 30)) (.toBe 8))
        (-> (expect (steps-ceiling {:max-steps 40})) (.toBe 40))
        (-> (expect (steps-ceiling {:max-steps 40 :subagent {:max-steps 12}})) (.toBe 12))
        (-> (expect (steps-ceiling {})) (.toBe 100))))))

(describe "subagent:extensions" (fn []
  (it "a worktree child cannot earn steps past its parent's budget"
      (fn []
        (let [args (vec (worktree-args {:model "m" :sys "s" :steps 5}))
              i    (.indexOf args "--settings")]
          (-> (expect (js/JSON.parse (nth args (inc i)))) (.toEqual #js {"max-extensions" 0})))))))

(describe "subagent:worktree-permissions" (fn []
  (it "a child gets no more than its parent"
      (fn []
        (-> (expect (child-permission-mode "default")) (.toBe "accept-edits"))
        (-> (expect (child-permission-mode nil)) (.toBe "accept-edits"))
        (-> (expect (child-permission-mode "accept-edits")) (.toBe "accept-edits"))
        (-> (expect (child-permission-mode "plan")) (.toBe "plan"))
        (-> (expect (child-permission-mode "full-auto")) (.toBe "full-auto"))))
  (it "reaches the child's flags"
      (fn []
        (let [args (vec (worktree-args {:model "m" :sys "s" :mode "default"}))]
          (-> (expect (nth args (inc (.indexOf args "--permission-mode")))) (.toBe "accept-edits")))))))
