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

  (it "returns :python for $ prefix"
    (fn []
      (-> (expect (detect-mode "$1 + 2")) (.toBe "python"))))

  (it "ignores leading whitespace"
    (fn []
      (-> (expect (detect-mode "  !ls")) (.toBe "bash"))
      (-> (expect (detect-mode "\t$expr")) (.toBe "python"))))

  (it "!! counts as bash (double prefix is still a prefix)"
    (fn []
      (-> (expect (detect-mode "!!ls")) (.toBe "bash"))))

  (it "a plain prompt starting with alpha is :normal"
    (fn []
      (-> (expect (detect-mode "do something")) (.toBe "normal"))))))

;;; ─── border-color-for-mode ─────────────────────────────

(describe "border-color-for-mode" (fn []
  (it "uses warning color for bash"
    (fn []
      (-> (expect (border-color-for-mode :bash test-theme))
          (.toBe "#e0af68"))))

  (it "uses primary color for python"
    (fn []
      (-> (expect (border-color-for-mode :python test-theme))
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

  (it "returns 'python' for :python"
    (fn []
      (-> (expect (mode-label :python)) (.toBe "python"))))

  (it "returns nil for :normal"
    (fn []
      (-> (expect (mode-label :normal)) (.toBe js/undefined))))))
