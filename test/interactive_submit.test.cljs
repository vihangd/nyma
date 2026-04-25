(ns interactive-submit.test
  "Tests for the on-submit dispatch logic in interactive mode.
   These are unit tests over the pure parsing layer — they do not mount
   the TUI but verify that each input prefix routes to the right branch.

   Regression guard: when the pi-tui migration stripped the Ink app,
   the !cmd / !!cmd / $expr / $$expr branches were lost from on-submit.
   This file ensures the parse functions are reachable and classify
   inputs correctly, so the dispatch can never silently fall through to
   the LLM path for shell/eval inputs."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.editor-bash :refer [parse-bash-input]]
            [agent.ui.editor-eval :refer [parse-eval-input]]))

;;; ─── !cmd routing ─────────────────────────────────────────────────────────

(describe "on-submit dispatch / !cmd" (fn []
                                        (it "!cmd is classified :run (routes to bash, not LLM)"
                                            (fn []
                                              (let [result (parse-bash-input "!ls -la")]
                                                (-> (expect (:kind result)) (.toBe :run))
                                                (-> (expect (:command result)) (.toBe "ls -la")))))

                                        (it "!!cmd is classified :run-hidden"
                                            (fn []
                                              (let [result (parse-bash-input "!!echo secret")]
                                                (-> (expect (:kind result)) (.toBe :run-hidden))
                                                (-> (expect (:command result)) (.toBe "echo secret")))))

                                        (it "plain text is :not-bash (routes to LLM)"
                                            (fn []
                                              (-> (expect (:kind (parse-bash-input "hello"))) (.toBe :not-bash))))

                                        (it "slash command is :not-bash"
                                            (fn []
                                              (-> (expect (:kind (parse-bash-input "/role plan"))) (.toBe :not-bash))))

                                        (it "empty string is :not-bash"
                                            (fn []
                                              (-> (expect (:kind (parse-bash-input ""))) (.toBe :not-bash))))

                                        (it "nil is :not-bash"
                                            (fn []
                                              (-> (expect (:kind (parse-bash-input nil))) (.toBe :not-bash))))

                                        (it "bare ! with no command is :not-bash"
                                            (fn []
                                              (-> (expect (:kind (parse-bash-input "!"))) (.toBe :not-bash))))

                                        (it "! with only whitespace is :not-bash"
                                            (fn []
                                              (-> (expect (:kind (parse-bash-input "!   "))) (.toBe :not-bash))))

                                        (it "leading whitespace before ! still detects bash"
                                            (fn []
                                              (let [result (parse-bash-input "  !git status")]
                                                (-> (expect (:kind result)) (.toBe :run))
                                                (-> (expect (:command result)) (.toBe "git status")))))))

;;; ─── $expr routing ────────────────────────────────────────────────────────

(describe "on-submit dispatch / $expr" (fn []
                                         (it "$expr is classified :eval (routes to bb, not LLM)"
                                             (fn []
                                               (let [result (parse-eval-input "$(+ 1 2)")]
                                                 (-> (expect (:kind result)) (.toBe :eval))
                                                 (-> (expect (:expr result)) (.toBe "(+ 1 2)")))))

                                         (it "$$expr is classified :eval-hidden"
                                             (fn []
                                               (let [result (parse-eval-input "$$(str \"hi\")")]
                                                 (-> (expect (:kind result)) (.toBe :eval-hidden))
                                                 (-> (expect (:expr result)) (.toBe "(str \"hi\")")))))

                                         (it "plain text is :not-eval (routes to LLM)"
                                             (fn []
                                               (-> (expect (:kind (parse-eval-input "hello"))) (.toBe :not-eval))))

                                         (it "bare $ is :not-eval"
                                             (fn []
                                               (-> (expect (:kind (parse-eval-input "$"))) (.toBe :not-eval))))

                                         (it "$ followed only by whitespace is :not-eval"
                                             (fn []
                                               (-> (expect (:kind (parse-eval-input "$  "))) (.toBe :not-eval))))

                                         (it "nil is :not-eval"
                                             (fn []
                                               (-> (expect (:kind (parse-eval-input nil))) (.toBe :not-eval))))))

;;; ─── priority ordering ────────────────────────────────────────────────────
;;; Verify that if both bash and eval patterns somehow matched the same input,
;;; the inputs that trigger one parser are :not-X for the other.

(describe "on-submit dispatch / no cross-routing" (fn []
                                                    (it "!cmd is :not-eval"
                                                        (fn []
                                                          (-> (expect (:kind (parse-eval-input "!ls"))) (.toBe :not-eval))))

                                                    (it "$expr is :not-bash"
                                                        (fn []
                                                          (-> (expect (:kind (parse-bash-input "$(+ 1 2)"))) (.toBe :not-bash))))

                                                    (it "/slash is :not-bash and :not-eval"
                                                        (fn []
                                                          (-> (expect (:kind (parse-bash-input "/role plan"))) (.toBe :not-bash))
                                                          (-> (expect (:kind (parse-eval-input "/role plan"))) (.toBe :not-eval))))))
