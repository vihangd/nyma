(ns editor-mode.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.editor-mode :refer [detect-mode
                                          border-color-for-mode
                                          mode-label]]))

(def test-theme
  {:colors {:primary "#7aa2f7"
            :warning "#e0af68"
            :border  "#3b4261"}})

;;; ─── detect-mode ────────────────────────────────────────

(describe "detect-mode" (fn []
                          (it "returns :normal for nil"
                              (fn []
                                (-> (expect (detect-mode nil)) (.toBe "normal"))))

                          (it "returns :normal for empty string"
                              (fn []
                                (-> (expect (detect-mode "")) (.toBe "normal"))))

                          (it "returns :normal for plain prompt"
                              (fn []
                                (-> (expect (detect-mode "hello world")) (.toBe "normal"))))

                          (it "returns :bash for ! prefix"
                              (fn []
                                (-> (expect (detect-mode "!ls -la")) (.toBe "bash"))))

                          (it "returns :bb for $ prefix followed by a form-start char"
                              (fn []
      ;; Phase 23: :python was renamed to :bb (Babashka eval). The
      ;; form-start guard from editor-eval/parse-eval-input is
      ;; reused here, so the border only turns blue for something
      ;; that will actually eval.
                                (-> (expect (detect-mode "$(+ 1 2)")) (.toBe "bb"))
                                (-> (expect (detect-mode "$[1 2 3]")) (.toBe "bb"))
                                (-> (expect (detect-mode "${:a 1}")) (.toBe "bb"))))

                          (it "ignores leading whitespace"
                              (fn []
                                (-> (expect (detect-mode "  !ls"))      (.toBe "bash"))
                                (-> (expect (detect-mode "\t$(+ 1 2)")) (.toBe "bb"))))

                          (it "!! counts as bash (double prefix is still a prefix)"
                              (fn []
                                (-> (expect (detect-mode "!!ls")) (.toBe "bash"))))

                          (it "$$ counts as bb (hidden eval is still eval)"
                              (fn []
                                (-> (expect (detect-mode "$$(+ 1 2)")) (.toBe "bb"))))

                          (it "$PATH-style shell lookalikes stay :normal (form-start guard)"
                              (fn []
      ;; The guard is critical: without it `$HOME` in a prompt
      ;; would flash the blue border as if the user were in eval
      ;; mode, only to fall through as a normal LLM prompt on
      ;; submit. Confusing UX.
                                (-> (expect (detect-mode "$PATH"))        (.toBe "normal"))
                                (-> (expect (detect-mode "$HOME"))        (.toBe "normal"))
                                (-> (expect (detect-mode "$foo-bar"))     (.toBe "normal"))
                                (-> (expect (detect-mode "$123"))         (.toBe "normal"))))

                          (it "a plain prompt starting with alpha is :normal"
                              (fn []
                                (-> (expect (detect-mode "do something")) (.toBe "normal"))))))

;;; ─── border-color-for-mode ─────────────────────────────

(describe "border-color-for-mode" (fn []
                                    (it "uses warning color for bash"
                                        (fn []
                                          (-> (expect (border-color-for-mode :bash test-theme))
                                              (.toBe "#e0af68"))))

                                    (it "uses primary color for :bb"
                                        (fn []
                                          (-> (expect (border-color-for-mode :bb test-theme))
                                              (.toBe "#7aa2f7"))))

                                    (it "uses border color for :normal"
                                        (fn []
                                          (-> (expect (border-color-for-mode :normal test-theme))
                                              (.toBe "#3b4261"))))

                                    (it "falls back on missing theme entries"
                                        (fn []
      ;; Empty theme — all lookups fall back to defaults
                                          (-> (expect (border-color-for-mode :bash {}))
                                              (.toBe "#e0af68"))
                                          (-> (expect (border-color-for-mode :normal {}))
                                              (.toBe "#3b4261"))))))

;;; ─── mode-label ───────────────────────────────────────

(describe "mode-label" (fn []
                         (it "returns 'bash' for :bash"
                             (fn []
                               (-> (expect (mode-label :bash)) (.toBe "bash"))))

                         (it "returns 'bb' for :bb"
                             (fn []
                               (-> (expect (mode-label :bb)) (.toBe "bb"))))

                         (it "returns nil for :normal"
                             (fn []
                               (-> (expect (mode-label :normal)) (.toBe js/undefined))))))
