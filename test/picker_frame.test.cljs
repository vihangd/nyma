(ns picker-frame.test
  "Unit tests for the shared picker-frame renderer. Every branch of
   render-frame gets an assertion — focus prefix placement, empty
   state, above/below overflow markers, item formatter plumbing."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.ui.picker-frame :refer [render-frame pad-lines truncate-to
                                           fit-lines overlay-max-width]]))

(defn- render [opts]
  (render-frame (merge {:title         "Test"
                        :prompt-prefix "> "
                        :filter-text   ""
                        :items         ["a" "b" "c"]
                        :selected-idx  0
                        :max-visible   12
                        :render-item   (fn [item _focused?] (str item))
                        :no-match-text "nothing here"}
                       opts)))

(describe "render-frame: structure"
          (fn []
            (it "emits the title on the first line"
                (fn []
                  ;; pad-lines right-pads every row to the widest line,
                  ;; so the title now has trailing spaces before the
                  ;; newline — assert on prefix only.
                  (-> (expect (.startsWith (render {}) "Test")) (.toBe true))
                  ;; And the title line should end with a newline somewhere
                  ;; before any list row is emitted.
                  (let [first-nl (.indexOf (render {}) "\n")]
                    (-> (expect (pos? first-nl)) (.toBe true)))))

            (it "shows the prompt prefix + filter text on the second line"
                (fn []
                  (-> (expect (.includes (render {:filter-text "foo"}) "> foo")) (.toBe true))))

            (it "renders every item in the list when it fits"
                (fn []
                  (let [out (render {:items ["apple" "banana" "cherry"]})]
                    (-> (expect (.includes out "apple"))  (.toBe true))
                    (-> (expect (.includes out "banana")) (.toBe true))
                    (-> (expect (.includes out "cherry")) (.toBe true)))))

            (it "prepends a focus arrow only to the selected row"
                (fn []
                  (let [out (render {:items ["one" "two" "three"] :selected-idx 1})]
          ;; Focused row has the arrow; others have blank spaces.
                    (-> (expect (.includes out "\u25b6 two")) (.toBe true))
          ;; Focus arrow is on the selected row and not a different one.
                    (-> (expect (.includes out "\u25b6 one")) (.toBe false))
                    (-> (expect (.includes out "\u25b6 three")) (.toBe false)))))

            (it "uses the custom render-item formatter"
                (fn []
                  (let [fmt (fn [item _focused?] (str "[" item "]"))
                        out (render {:items ["x" "y"] :render-item fmt})]
                    (-> (expect (.includes out "[x]")) (.toBe true))
                    (-> (expect (.includes out "[y]")) (.toBe true)))))

            (it "passes focused? to the render-item formatter"
                (fn []
        ;; The formatter observes whether the item is selected so it
        ;; can style the row differently if it wants.
                  (let [fmt (fn [item focused?]
                              (if focused? (str "FOCUS:" item) (str "blur:" item)))
                        out (render {:items ["a" "b"] :selected-idx 1 :render-item fmt})]
                    (-> (expect (.includes out "FOCUS:b")) (.toBe true))
                    (-> (expect (.includes out "blur:a")) (.toBe true)))))))

(describe "render-frame: empty list"
          (fn []
            (it "shows the no-match text when items is empty"
                (fn []
                  (let [out (render {:items [] :no-match-text "No skills match"})]
                    (-> (expect (.includes out "No skills match")) (.toBe true)))))

            (it "does not crash when the filter is also empty"
                (fn []
                  (let [out (render {:items [] :filter-text ""})]
                    (-> (expect (string? out)) (.toBe true)))))

            (it "no focus arrow is emitted for an empty list"
                (fn []
                  (let [out (render {:items []})]
                    (-> (expect (.includes out "\u25b6")) (.toBe false)))))))

(describe "render-frame: inline scroll arrows"
          (fn []
            ;; Borrowed from cc-kit's ListItem scroll-hint pattern
            ;; (packages/ui/src/design-system/ListItem.tsx:109-128):
            ;; an `↑` / `↓` in the same 1-char indicator slot as the
            ;; focus pointer, on the first/last visible row when the
            ;; window is clipped. Focus always wins on the same row.

            (it "shows ↑ on the first visible row when items are clipped above"
                (fn []
                  ;; 10 items, max-visible 3, focus on item 9 → window is
                  ;; [7, 10) with items above it. The first visible row
                  ;; (item 7) should be prefixed with ↑ (since it's not
                  ;; the focused row).
                  (let [items (vec (for [i (range 10)] (str "item" i)))
                        out   (render {:items items :selected-idx 9 :max-visible 3})]
                    (-> (expect (.includes out "\u2191")) (.toBe true)))))

            (it "shows ↓ on the last visible row when items are clipped below"
                (fn []
                  ;; 10 items, max-visible 3, focus on item 0 → window is
                  ;; [0, 3). The last visible row (item 2) should be
                  ;; prefixed with ↓.
                  (let [items (vec (for [i (range 10)] (str "item" i)))
                        out   (render {:items items :selected-idx 0 :max-visible 3})]
                    (-> (expect (.includes out "\u2193")) (.toBe true)))))

            (it "does NOT emit scroll arrows when the window covers everything"
                (fn []
                  (let [out (render {:items ["a" "b"] :max-visible 12})]
                    (-> (expect (.includes out "\u2191")) (.toBe false))
                    (-> (expect (.includes out "\u2193")) (.toBe false)))))

            (it "focus pointer wins over scroll hint on the same row"
                (fn []
                  ;; If the focused item IS the top or bottom visible
                  ;; row AND there's overflow, the focus pointer wins
                  ;; over the arrow — otherwise the user couldn't tell
                  ;; which row is focused.
                  (let [items (vec (for [i (range 10)] (str "item" i)))
                        out   (render {:items items :selected-idx 0 :max-visible 3})]
                    ;; Focused on the first visible row; even though
                    ;; this is the top of the list, there's no ↑ here
                    ;; (start = 0, not clipped above). And item 0 must
                    ;; show the focus pointer.
                    (-> (expect (.includes out "\u25b6 item0")) (.toBe true)))))

            (it "shows idx/total counter on the prompt line"
                (fn []
                  (let [items (vec (for [i (range 24)] (str "item" i)))
                        out   (render {:items items :selected-idx 5 :max-visible 5})]
                    ;; Counter shows 1-indexed selection over total.
                    (-> (expect (.includes out "(6/24)")) (.toBe true)))))))

(describe "render-frame: defensive defaults"
          (fn []
            (it "handles nil filter-text as empty string"
                (fn []
                  (-> (expect (string? (render {:filter-text nil}))) (.toBe true))))

            (it "handles nil prompt-prefix (falls back to '> ')"
                (fn []
                  (-> (expect (.includes (render {:prompt-prefix nil}) "> "))
                      (.toBe true))))

            (it "clamps an out-of-range selected-idx into the items list"
                (fn []
        ;; Regression: stored idx 99 with only 3 items must not blow
        ;; up the render; safe-index inside render-frame clamps it.
                  (let [out (render {:items ["a" "b" "c"] :selected-idx 99})]
          ;; The last item must be the focused row now.
                    (-> (expect (.includes out "\u25b6 c")) (.toBe true)))))))

;;; ─── pad-lines ────────────────────────────────────────
;;; The pad-lines helper keeps the overlay from visually bleeding
;;; into the chat view underneath by making every row the same width.
;;; Without it, a Text wrapper with a backgroundColor paints a ragged
;;; rectangle and the chat content shows through the gaps.

(describe "pad-lines: regression for overlay transparency bleed"
          (fn []
            (it "right-pads every line to the width of the widest line"
                (fn []
                  (let [out   (pad-lines "short\nmuch longer line\nmid")
                        lines (str/split out #"\n")
                        widest (reduce (fn [w l] (max w (count l))) 0 lines)]
                    (-> (expect (count lines)) (.toBe 3))
                    ;; Every line must be exactly the widest width.
                    (doseq [l lines]
                      (-> (expect (count l)) (.toBe widest)))
                    ;; Content is preserved — the original strings are
                    ;; still prefixes of their padded versions.
                    (-> (expect (.startsWith (first lines) "short")) (.toBe true))
                    (-> (expect (.startsWith (second lines) "much longer line")) (.toBe true)))))

            (it "preserves a single line unchanged"
                (fn []
                  (-> (expect (pad-lines "hello")) (.toBe "hello"))))

            (it "handles empty string without crashing"
                (fn []
                  (-> (expect (pad-lines "")) (.toBe ""))))

            (it "handles nil as empty string"
                (fn []
                  (-> (expect (pad-lines nil)) (.toBe ""))))

            (it "render-frame output is already uniform-width"
                (fn []
        ;; render-frame calls pad-lines internally — this is the
        ;; end-to-end guarantee that the overlay rectangle is solid.
                  (let [out (render-frame {:title         "Test"
                                           :prompt-prefix "> "
                                           :filter-text   ""
                                           :items         ["apple" "banana with a long label" "c"]
                                           :selected-idx  0
                                           :max-visible   12
                                           :render-item   (fn [item _] (str item))
                                           :no-match-text "none"})
                        lines (str/split out #"\n")
                        widest (reduce (fn [w l] (max w (count l))) 0 lines)]
                    (doseq [l lines]
                      (-> (expect (count l)) (.toBe widest))))))))

;;; ─── truncate-to / fit-lines / overlay-max-width ──────
;;; These lock in the fix for the overflow-wrap bug: a single long
;;; row (e.g. `/agent-shell__agent` with a big parenthesised list of
;;; agents) must not force the overlay to double in height because
;;; ink wrapped every padded row onto two visual lines.

(describe "truncate-to"
          (fn []
            (it "leaves a short line unchanged"
                (fn []
                  (-> (expect (truncate-to "hello" 20)) (.toBe "hello"))))

            (it "returns the exact value when length equals width"
                (fn []
                  (-> (expect (truncate-to "hello" 5)) (.toBe "hello"))))

            (it "truncates and appends an ellipsis when over width"
                (fn []
        ;; Width 5 → 4 chars of content + 1 char ellipsis = 5 total.
                  (-> (expect (truncate-to "hello world" 5)) (.toBe "hell\u2026"))))

            (it "result is exactly width chars long after truncation"
                (fn []
                  (doseq [w [10 20 50]]
                    (let [line (apply str (repeat 200 "x"))
                          out  (truncate-to line w)]
                      (-> (expect (count out)) (.toBe w))))))

            (it "handles nil line"
                (fn []
                  (-> (expect (truncate-to nil 10)) (.toBeNull))))

            (it "handles nil width — returns line unchanged"
                (fn []
                  (-> (expect (truncate-to "anything" nil)) (.toBe "anything"))))

            (it "handles zero / negative width — returns unchanged"
                (fn []
                  (-> (expect (truncate-to "hi" 0))   (.toBe "hi"))
                  (-> (expect (truncate-to "hi" -1))  (.toBe "hi"))))))

(describe "fit-lines"
          (fn []
            (it "every output line is exactly w chars"
                (fn []
                  (let [out (fit-lines "short\nmuch longer line than the cap\nmid" 12)
                        lines (str/split out #"\n")]
                    (doseq [l lines]
                      (-> (expect (count l)) (.toBe 12))))))

            (it "truncates long lines with ellipsis"
                (fn []
                  (let [out (fit-lines "short\nabsolutely enormous line that exceeds" 15)
                        lines (str/split out #"\n")]
                    (-> (expect (.endsWith (second lines) "\u2026")) (.toBe true)))))

            (it "pads short lines to w chars (trailing spaces)"
                (fn []
                  (let [out (fit-lines "hi\nworld" 10)
                        lines (str/split out #"\n")]
                    (-> (expect (count (first lines))) (.toBe 10))
                    (-> (expect (.startsWith (first lines) "hi")) (.toBe true)))))

            (it "falls back to pad-lines when w is nil"
                (fn []
                  (let [out1 (fit-lines "short\nmuch longer line" nil)
                        out2 (pad-lines "short\nmuch longer line")]
                    (-> (expect out1) (.toBe out2)))))

            (it "falls back to pad-lines when w is zero or negative"
                (fn []
                  (-> (expect (fit-lines "a\nbb" 0))  (.toBe (pad-lines "a\nbb")))
                  (-> (expect (fit-lines "a\nbb" -5)) (.toBe (pad-lines "a\nbb")))))))

(describe "overlay-max-width"
          (fn []
            (it "80-col terminal → 42 chars (60% minus 6 chrome)"
                (fn []
        ;; floor(80 * 0.6) = 48, minus 6 = 42.
                  (-> (expect (overlay-max-width 80)) (.toBe 42))))

            (it "120-col terminal → 66 chars"
                (fn []
                  (-> (expect (overlay-max-width 120)) (.toBe 66))))

            (it "clamps to a minimum of 30 on tiny terminals"
                (fn []
                  (-> (expect (overlay-max-width 20)) (.toBe 30))
                  (-> (expect (overlay-max-width 40)) (.toBe 30))))

            (it "defaults terminal-cols to 80 when nil"
                (fn []
                  (-> (expect (overlay-max-width nil)) (.toBe 42))))))

;;; ─── Regression: long row doesn't inflate picker height ─────

(describe "render-frame: overflow-wrap regression"
          (fn []
            (it "with :max-width, every row is exactly max-width chars"
                (fn []
        ;; The bug: one very long row (mimics /agent-shell__agent
        ;; with its 'claude, gemini, opencode, qwen, goose, kiro'
        ;; description) would cause pad-lines to pad every OTHER
        ;; row up to its width, and those padded rows would wrap
        ;; inside the overlay box. With :max-width set, every row
        ;; is exactly that width — no wrapping possible.
                  (let [items (vec (concat
                                    ["short"
                                     "/agent-shell__agent  (Connect to a coding agent (claude, gemini, opencode, qwen, goose, kiro))"
                                     "another short"]))
                        out (render-frame {:title "t"
                                           :prompt-prefix "> "
                                           :filter-text ""
                                           :items items
                                           :selected-idx 0
                                           :max-visible 10
                                           :max-width 42
                                           :render-item (fn [item _] (str item))
                                           :no-match-text "none"})
                        lines (str/split out #"\n")]
                    (doseq [l lines]
                      (-> (expect (count l)) (.toBe 42))))))

            (it "long row is truncated with an ellipsis"
                (fn []
                  (let [items ["/agent-shell__agent  (Connect to a coding agent (claude, gemini, opencode, qwen, goose, kiro))"]
                        out (render-frame {:title "t"
                                           :prompt-prefix "> "
                                           :filter-text ""
                                           :items items
                                           :selected-idx 0
                                           :max-visible 10
                                           :max-width 42
                                           :render-item (fn [item _] (str item))
                                           :no-match-text "none"})]
        ;; The row gets truncated mid-description with an ellipsis.
                    (-> (expect (.includes out "\u2026")) (.toBe true)))))

            (it "without :max-width, falls back to pad-to-widest (the buggy behaviour)"
                (fn []
        ;; Regression safety net: not a bug test, but a guardrail
        ;; documenting that the old behaviour is preserved when
        ;; :max-width is omitted, so existing callers that don't
        ;; know about the cap still work.
                  (let [items ["a" "bbbbbbbbbbbb"]
                        out (render-frame {:title "t"
                                           :prompt-prefix "> "
                                           :filter-text ""
                                           :items items
                                           :selected-idx 0
                                           :max-visible 10
                                           :render-item (fn [item _] (str item))
                                           :no-match-text "none"})
                        lines (str/split out #"\n")]
        ;; Uniform width, but that width is the widest line's width,
        ;; not a caller-supplied cap.
                    (let [widest (reduce (fn [w l] (max w (count l))) 0 lines)]
                      (doseq [l lines]
                        (-> (expect (count l)) (.toBe widest)))))))))
