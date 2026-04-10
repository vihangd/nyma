(ns diff-renderer.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.diff-renderer :refer [visualize-indent
                                             render-intra-line-diff
                                             parse-diff-line
                                             render-diff-block]]))

(def INVERSE "\u001b[7m")
(def NO-INVERSE "\u001b[27m")
(def DIM "\u001b[2m")

;;; ─── visualize-indent ───────────────────────────────────

(describe "visualize-indent" (fn []
  (it "leaves unindented text unchanged"
    (fn []
      (-> (expect (visualize-indent "hello")) (.toBe "hello"))))

  (it "converts leading spaces to dim dots"
    (fn []
      (let [out (visualize-indent "  hello")]
        (-> (expect (.includes out "\u00b7\u00b7")) (.toBe true))
        (-> (expect (.includes out DIM)) (.toBe true)))))

  (it "converts leading tabs to dim arrows"
    (fn []
      (let [out (visualize-indent "\thello")]
        (-> (expect (.includes out "\u2192")) (.toBe true))
        (-> (expect (.includes out DIM)) (.toBe true)))))

  (it "expands non-leading tabs to 4 spaces"
    (fn []
      (let [out (visualize-indent "a\tb")]
        (-> (expect (.includes out "a    b")) (.toBe true)))))

  (it "handles empty string"
    (fn []
      (-> (expect (visualize-indent "")) (.toBe ""))))

  (it "handles nil"
    (fn []
      (-> (expect (visualize-indent nil)) (.toBe ""))))))

;;; ─── render-intra-line-diff ─────────────────────────────

(describe "render-intra-line-diff" (fn []
  (it "wraps the changed word in ANSI inverse on both lines"
    (fn []
      (let [{:keys [removed-line added-line]}
            (render-intra-line-diff "foo bar baz" "foo QUX baz")]
        ;; 'bar' appears inverted on the removed line
        (-> (expect (.includes removed-line (str INVERSE "bar" NO-INVERSE)))
            (.toBe true))
        ;; 'QUX' appears inverted on the added line
        (-> (expect (.includes added-line (str INVERSE "QUX" NO-INVERSE)))
            (.toBe true)))))

  (it "leaves identical lines without any inverse markers"
    (fn []
      (let [{:keys [removed-line added-line]}
            (render-intra-line-diff "same" "same")]
        (-> (expect (.includes removed-line INVERSE)) (.toBe false))
        (-> (expect (.includes added-line INVERSE)) (.toBe false)))))

  (it "handles replacing an entire line"
    (fn []
      (let [{:keys [removed-line added-line]}
            (render-intra-line-diff "alpha" "omega")]
        (-> (expect (.includes removed-line "alpha")) (.toBe true))
        (-> (expect (.includes added-line "omega")) (.toBe true)))))))

;;; ─── parse-diff-line ────────────────────────────────────

(describe "parse-diff-line" (fn []
  (it "parses an added line"
    (fn []
      (let [r (parse-diff-line "+123| new content")]
        (-> (expect (:type r)) (.toBe "added"))
        (-> (expect (:line-num r)) (.toBe 123))
        (-> (expect (:content r)) (.toBe " new content")))))

  (it "parses a removed line"
    (fn []
      (let [r (parse-diff-line "-42| old")]
        (-> (expect (:type r)) (.toBe "removed"))
        (-> (expect (:line-num r)) (.toBe 42)))))

  (it "parses a context line"
    (fn []
      (let [r (parse-diff-line " 7| context")]
        (-> (expect (:type r)) (.toBe "context"))
        (-> (expect (:line-num r)) (.toBe 7)))))

  (it "returns nil on a non-matching line"
    (fn []
      (-> (expect (parse-diff-line "not a diff line")) (.toBe js/undefined))))

  (it "returns nil on empty string"
    (fn []
      (-> (expect (parse-diff-line "")) (.toBe js/undefined))))))

;;; ─── render-diff-block ──────────────────────────────────

(describe "render-diff-block" (fn []
  (it "parses every matching row"
    (fn []
      (let [diff "-1| foo\n+1| bar\n 2| baz"
            rows (render-diff-block diff)]
        (-> (expect (count rows)) (.toBe 3))
        (-> (expect (:type (get rows 0))) (.toBe "removed"))
        (-> (expect (:type (get rows 1))) (.toBe "added"))
        (-> (expect (:type (get rows 2))) (.toBe "context")))))

  (it "applies intra-line marks to an adjacent 1:1 pair"
    (fn []
      (let [diff "-1|hello world\n+1|hello WORLD"
            rows (render-diff-block diff)
            removed (:content (get rows 0))
            added   (:content (get rows 1))]
        ;; The changed word appears inside an inverse run on each side
        (-> (expect (.includes removed INVERSE)) (.toBe true))
        (-> (expect (.includes added INVERSE)) (.toBe true)))))

  (it "does NOT apply intra-line marks to N:M blocks"
    (fn []
      ;; two removed then one added — not a 1:1 pair
      (let [diff "-1|a\n-2|b\n+1|c"
            rows (render-diff-block diff)]
        (doseq [r rows]
          (-> (expect (.includes (str (:content r)) INVERSE)) (.toBe false))))))

  (it "runs visualize-indent on non-intra-line rows"
    (fn []
      (let [diff " 1|  indented"
            rows (render-diff-block diff)
            content (:content (get rows 0))]
        (-> (expect (.includes content "\u00b7")) (.toBe true)))))

  (it "ignores non-diff lines interspersed in the text"
    (fn []
      (let [diff "--- file header\n-1|a\n+1|b\n@@ chunk @@\n 2|c"
            rows (render-diff-block diff)]
        ;; Only 3 matching rows (-1|, +1|, 2|)
        (-> (expect (count rows)) (.toBe 3)))))))
