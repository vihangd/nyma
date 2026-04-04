(ns ansi.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.ansi :refer [terminal-width string-width wrap-ansi truncate-text]]))

(describe "string-width" (fn []
  (it "returns 0 for nil"
    (fn []
      (-> (expect (string-width nil)) (.toBe 0))))

  (it "counts plain ASCII correctly"
    (fn []
      (-> (expect (string-width "hello")) (.toBe 5))))

  (it "ignores ANSI escape codes in width"
    (fn []
      (-> (expect (string-width "\u001b[31mhello\u001b[0m")) (.toBe 5))))

  (it "counts wide characters correctly"
    (fn []
      ;; CJK characters are 2 columns wide each
      (-> (expect (string-width "\u4f60\u597d")) (.toBe 4))))))

(describe "wrap-ansi" (fn []
  (it "returns empty string for nil"
    (fn []
      (-> (expect (wrap-ansi nil 80)) (.toBe ""))))

  (it "wraps plain text at column boundary"
    (fn []
      (let [text "aaaa bbbb cccc"
            result (wrap-ansi text 9)]
        ;; "aaaa bbbb" fits in 9, "cccc" wraps
        (-> (expect (.includes result "\n")) (.toBe true)))))

  (it "preserves ANSI codes across wrap"
    (fn []
      (let [text "\u001b[31mhello world this is a long red text\u001b[0m"
            result (wrap-ansi text 15)]
        ;; Should wrap but still contain ANSI codes
        (-> (expect (.includes result "\u001b[31m")) (.toBe true))
        (-> (expect (.includes result "\n")) (.toBe true)))))

  (it "respects hard break option"
    (fn []
      (let [text "abcdefghijklmnop"
            result (wrap-ansi text 5 {:hard true})]
        ;; Should break the long word
        (-> (expect (.includes result "\n")) (.toBe true)))))

  (it "passes through text shorter than column width"
    (fn []
      (let [text "short"]
        (-> (expect (wrap-ansi text 80)) (.toBe "short")))))))

(describe "truncate-text" (fn []
  (it "returns empty string for nil"
    (fn []
      (-> (expect (truncate-text nil 10 80)) (.toBe ""))))

  (it "passes through text within line limit"
    (fn []
      (let [text "line1\nline2\nline3"]
        (-> (expect (truncate-text text 5 80)) (.toBe text)))))

  (it "truncates and shows remaining count"
    (fn []
      (let [text "a\nb\nc\nd\ne\nf\ng\nh\ni\nj"
            result (truncate-text text 3 80)]
        (-> (expect (.startsWith result "a\nb\nc\n")) (.toBe true))
        (-> (expect (.includes result "7 more lines")) (.toBe true)))))

  (it "wraps long lines before counting"
    (fn []
      ;; One very long line that should wrap into multiple at width 10
      (let [text "abcdefghijklmnopqrstuvwxyz"
            result (truncate-text text 2 10)]
        ;; Should be truncated since the line wraps to 3+ lines at width 10
        (-> (expect (.includes result "more lines")) (.toBe true)))))

  (it "preserves ANSI codes in truncated output"
    (fn []
      (let [text "\u001b[31mline1\u001b[0m\n\u001b[32mline2\u001b[0m\n\u001b[33mline3\u001b[0m"
            result (truncate-text text 2 80)]
        ;; First two lines should still have ANSI codes
        (-> (expect (.includes result "\u001b[31m")) (.toBe true))
        (-> (expect (.includes result "\u001b[32m")) (.toBe true)))))))

(describe "terminal-width" (fn []
  (it "returns a positive integer"
    (fn []
      (-> (expect (terminal-width)) (.toBeGreaterThan 0))))))
