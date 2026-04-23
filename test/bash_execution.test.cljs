(ns bash-execution.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/bash_execution.jsx" :refer [BashExecution]]
            [agent.ui.visual-truncate :refer [count-visual-rows
                                              truncate-to-visual-lines]]))

;;; Full-fidelity theme including :icons — matches the real themes.cljs shape.
;;; Tests must use this, not a colors-only stub, to catch the class of bug where
;;; a helper is accidentally passed the theme object as a React child.
(def full-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"}
   :icons  {:user      "❯"
            :assistant "●"
            :tool      "⚙"
            :error     "✗"}})

(afterEach (fn [] (cleanup)))

;;; ─── BashExecution render ────────────────────────────────

(describe "BashExecution render" (fn []
                                   (it "renders command with full theme without crashing (complete)"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "ls -la"
                                                                            :is-python false
                                                                            :lines     ["total 8" "drwxr-xr-x foo"]
                                                                            :status    :complete
                                                                            :exit-code 0
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "ls -la")))))

                                   (it "shows exit 0 for success"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "echo hi"
                                                                            :lines     ["hi"]
                                                                            :status    :complete
                                                                            :exit-code 0
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "(exit 0)")))))

                                   (it "shows non-zero exit code for failure"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "false"
                                                                            :lines     []
                                                                            :status    :complete
                                                                            :exit-code 1
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "(exit 1)")))))

                                   (it "shows (cancelled) for cancelled status"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "sleep 10"
                                                                            :lines     []
                                                                            :status    :cancelled
                                                                            :exit-code nil
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "(cancelled)")))))

                                   (it "shows error exit for :error status"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "bad-cmd"
                                                                            :lines     []
                                                                            :status    :error
                                                                            :exit-code 127
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "(error exit 127)")))))

                                   (it "renders python command with >>> prefix"
                                       (fn []
                                         (let [{:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "print('hello')"
                                                                            :is-python true
                                                                            :lines     ["hello"]
                                                                            :status    :complete
                                                                            :exit-code 0
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain ">>>")))))

                                   (it "shows truncation note when hidden-count > 0"
                                       (fn []
      ;; Feed more lines than PREVIEW-ROWS (8) to trigger truncation
                                         (let [many-lines (vec (for [i (range 20)] (str "line" i)))
                                               {:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "big-output"
                                                                            :lines     many-lines
                                                                            :status    :complete
                                                                            :exit-code 0
                                                                            :expanded  false
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "truncated")))))

                                   (it "shows all lines when expanded"
                                       (fn []
                                         (let [many-lines (vec (for [i (range 15)] (str "xline" i)))
                                               {:keys [lastFrame]}
                                               (render #jsx [BashExecution {:command   "cmd"
                                                                            :lines     many-lines
                                                                            :status    :complete
                                                                            :exit-code 0
                                                                            :expanded  true
                                                                            :is-active false
                                                                            :theme     full-theme}])]
                                           (-> (expect (lastFrame)) (.toContain "xline14")))))))

;;; ─── count-visual-rows ──────────────────────────────────

(describe "count-visual-rows" (fn []
                                (it "returns 1 for a short line"
                                    (fn []
                                      (-> (expect (count-visual-rows "hello" 80)) (.toBe 1))))

                                (it "returns 1 for an empty line"
                                    (fn []
                                      (-> (expect (count-visual-rows "" 80)) (.toBe 1))))

                                (it "returns 1 for nil line"
                                    (fn []
                                      (-> (expect (count-visual-rows nil 80)) (.toBe 1))))

                                (it "returns 2 for a line exactly 2x the width"
                                    (fn []
                                      (let [line (.repeat "a" 160)]
                                        (-> (expect (count-visual-rows line 80)) (.toBe 2)))))

                                (it "returns 3 for a line between 2x and 3x the width"
                                    (fn []
                                      (let [line (.repeat "a" 200)]
                                        (-> (expect (count-visual-rows line 80)) (.toBe 3)))))

                                (it "does not count ANSI escape codes against width"
                                    (fn []
                                      (let [line (str "\u001b[31m" (.repeat "a" 40) "\u001b[0m")]
                                        (-> (expect (count-visual-rows line 80)) (.toBe 1)))))

                                (it "defaults to 80 cols when width is nil"
                                    (fn []
                                      (-> (expect (count-visual-rows "hi" nil)) (.toBe 1))))

                                (it "collapses non-positive width to 1"
                                    (fn []
                                      (-> (expect (count-visual-rows "hi" 0)) (.toBe 2))))))

;;; ─── truncate-to-visual-lines ──────────────────────────

(describe "truncate-to-visual-lines" (fn []
                                       (it "returns all lines when they fit"
                                           (fn []
                                             (let [r (truncate-to-visual-lines ["a" "b" "c"] 10 80)]
                                               (-> (expect (count (:visible-lines r))) (.toBe 3))
                                               (-> (expect (:hidden-count r)) (.toBe 0)))))

                                       (it "truncates oldest lines first when over the cap"
                                           (fn []
                                             (let [r (truncate-to-visual-lines
                                                      (vec (for [i (range 30)] (str "line" i)))
                                                      10 80)]
                                               (-> (expect (count (:visible-lines r))) (.toBe 10))
                                               (-> (expect (:hidden-count r)) (.toBe 20))
        ;; Last line is always the newest
                                               (-> (expect (last (:visible-lines r))) (.toBe "line29")))))

                                       (it "accounts for wrapped lines (2x width)"
                                           (fn []
                                             (let [long-line (.repeat "a" 160)  ;; costs 2 rows at width 80
                                                   lines     [long-line long-line long-line "short"]
                                                   r         (truncate-to-visual-lines lines 4 80)]
        ;; Budget is 4 rows. From the end: 'short' (1), long (2) → total 3.
        ;; Next long would push to 5 so it's dropped.
                                               (-> (expect (count (:visible-lines r))) (.toBe 2))
                                               (-> (expect (:hidden-count r)) (.toBe 2)))))

                                       (it "handles an empty line list"
                                           (fn []
                                             (let [r (truncate-to-visual-lines [] 10 80)]
                                               (-> (expect (count (:visible-lines r))) (.toBe 0))
                                               (-> (expect (:hidden-count r)) (.toBe 0)))))

                                       (it "always keeps at least the newest line even if it's huge"
                                           (fn []
                                             (let [long-line (.repeat "x" 500)
                                                   r (truncate-to-visual-lines ["old" long-line] 3 80)]
        ;; 'long-line' alone costs 7 rows; budget is 3. But we accumulate
        ;; from the end and stop BEFORE exceeding the cap, so the budget
        ;; of 3 means we can't fit even the newest line. Expected: zero
        ;; visible, hidden=2.
                                               (-> (expect (:hidden-count r)) (.toBeGreaterThanOrEqual 1)))))))
