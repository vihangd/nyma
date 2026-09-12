(ns picker-frame.test
  "Unit tests for the shared picker-frame renderer. Every branch of
   render-frame gets an assertion — focus prefix placement, empty
   state, above/below overflow markers, item formatter plumbing."
  (:require ["bun:test" :refer [describe it expect]]
            ["@earendil-works/pi-tui" :refer [visibleWidth]]
            [clojure.string :as str]
            [agent.ui.picker-frame :refer [render-frame pad-lines truncate-to
                                           fit-lines overlay-max-width
                                           truncate-tail two-col-row]]))

(def ^:private ESC (js/String.fromCharCode 27))

(defn- strip
  "Rendered frames now carry colour. These tests are about STRUCTURE — where
   the title, the pointer and the rows land — so they assert on the text a
   user actually sees. Width is asserted separately, in columns."
  [s]
  (.replace (str s) (js/RegExp. (str ESC "\\[[0-9;]*m") "g") ""))

(describe "picker-frame/truncate-tail"
          (fn []
            (it "keeps the string when it fits"
                (fn []
                  (-> (expect (truncate-tail "short" 10)) (.toBe "short"))))

            ;; The tail is what distinguishes model ids — head-truncating
            ;; openrouter/nvidia/... makes siblings indistinguishable.
            (it "keeps the END and marks the cut"
                (fn []
                  (let [out (truncate-tail "openrouter/nvidia/nemotron-3-super-120b-a12b:free" 20)]
                    (-> (expect (count out)) (.toBe 20))
                    (-> (expect out) (.toStartWith "…"))
                    (-> (expect out) (.toEndWith "a12b:free")))))

            (it "degenerate widths don't throw"
                (fn []
                  (-> (expect (truncate-tail "abcdef" 1)) (.toBe "…"))
                  (-> (expect (truncate-tail "abcdef" 0)) (.toBe "abcdef"))))))

(describe "picker-frame/two-col-row"
          (fn []
            (it "pads to exactly the given width"
                (fn []
                  (doseq [w [20 36 54 72 108]]
                    (-> (expect (count (two-col-row "left" "right" w))) (.toBe w)))))

            (it "puts the right column flush at the end when both fit"
                (fn []
                  (let [out (two-col-row "anthropic/claude-sonnet-5" "200.0k · $3/$15" 72)]
                    (-> (expect out) (.toContain "anthropic/claude-sonnet-5"))
                    (-> (expect (str/trimr out)) (.toEndWith "200.0k · $3/$15")))))

            ;; Narrow row: identifying the model matters more than its price.
            (it "drops the right column rather than gutting the left"
                (fn []
                  (let [out (two-col-row "openrouter/nvidia/nemotron-3-super-120b-a12b:free"
                                         "200.0k · $2.5/$10" 36)]
                    (-> (expect out) (.not.toContain "200.0k"))
                    (-> (expect (count out)) (.toBe 36))
                    ;; still identifiable by its tail
                    (-> (expect (str/trimr out)) (.toEndWith "a12b:free")))))

            (it "handles an empty right column"
                (fn []
                  (-> (expect (count (two-col-row "solo" "" 30))) (.toBe 30))))))

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
                  (-> (expect (.startsWith (strip (render {})) "Test")) (.toBe true))
                  ;; And the title line should end with a newline somewhere
                  ;; before any list row is emitted.
                  (let [first-nl (.indexOf (strip (render {})) "\n")]
                    (-> (expect (pos? first-nl)) (.toBe true)))))

            (it "shows the prompt prefix + filter text on the second line"
                (fn []
                  (-> (expect (.includes (strip (render {:filter-text "foo"})) "> foo")) (.toBe true))))

            (it "renders every item in the list when it fits"
                (fn []
                  (let [out (render {:items ["apple" "banana" "cherry"]})]
                    (-> (expect (.includes out "apple"))  (.toBe true))
                    (-> (expect (.includes out "banana")) (.toBe true))
                    (-> (expect (.includes out "cherry")) (.toBe true)))))

            (it "prepends a focus arrow only to the selected row"
                (fn []
                  (let [out (strip (render {:items ["one" "two" "three"] :selected-idx 1}))]
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
                        out   (strip (render {:items items :selected-idx 0 :max-visible 3}))]
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
                        out   (strip (render {:items items :selected-idx 0 :max-visible 3}))]
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
                  (let [out (strip (render {:items ["a" "b" "c"] :selected-idx 99}))]
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
                        ;; COLUMNS, not characters: the frame carries colour
                        ;; now, and escapes are zero-width. `count` would see
                        ;; the escape bytes and report ragged lines that render
                        ;; perfectly square.
                        widest (reduce (fn [w l] (max w (visibleWidth l))) 0 lines)]
                    (doseq [l lines]
                      (-> (expect (visibleWidth l)) (.toBe widest))))))))

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
                  ;; Asserted on VISIBLE WIDTH, not `count`: pi-tui's truncator
                  ;; emits a reset escape alongside the indicator, so characters
                  ;; outnumber columns. Columns are what kills the session.
                  (let [out (truncate-to "hello world" 5)]
                    (-> (expect (visibleWidth out)) (.toBe 5))
                    (-> (expect (.startsWith out "hell")) (.toBe true))
                    (-> (expect (.endsWith out "\u2026")) (.toBe true)))))

            (it "result is exactly width columns after truncation"
                (fn []
                  (doseq [w [10 20 50]]
                    (let [line (apply str (repeat 200 "x"))
                          out  (truncate-to line w)]
                      (-> (expect (visibleWidth out)) (.toBe w))))))

            (it "keeps the tail inside the width without splitting a glyph"
                (fn []
                  ;; truncate-tail stepped by code UNIT, so it could stop
                  ;; between the halves of an emoji's surrogate pair and emit
                  ;; a lone surrogate — measurable at w=10, 20 and 30. The
                  ;; width was right either way; the row just rendered
                  ;; garbled.
                  ;; Built from code points: squint mangles \uXXXX escapes
                  ;; inside a string literal, which silently turned an earlier
                  ;; version of this pattern into replacement characters that
                  ;; matched nothing — the test passed against the bug.
                  (let [cc   (fn [n] (js/String.fromCharCode n))
                        hi   (str "[" (cc 0xd800) "-" (cc 0xdbff) "]")
                        lo   (str "[" (cc 0xdc00) "-" (cc 0xdfff) "]")
                        lone (js/RegExp. (str hi "(?!" lo ")|(?<!" hi ")" lo))
                        s    (str (apply str (repeat 20 "\ud83c\udf89")) "/tail-id")]
                    (doseq [w [30 20 10 5 2 1]]
                      (let [out (truncate-tail s w)]
                        (-> (expect (visibleWidth out)) (.toBeLessThanOrEqual w))
                        (-> (expect (.test lone out)) (.toBe false)))))))

            (it "never exceeds the width for wide glyphs"
                (fn []
                  ;; The bug this file had throughout: `count` equals columns
                  ;; only for ASCII. One CJK glyph is two columns and an emoji
                  ;; up to four, so character-based truncation emitted 2-4x the
                  ;; budget — measured at 81 columns inside an 80-column box.
                  (doseq [line [(apply str (repeat 50 "\u6f22"))
                                (apply str (repeat 30 "\ud83c\udf89"))
                                (apply str (repeat 20 "\ud83c\uddef\ud83c\uddf5"))]
                          w    [40 20 10 5 2 1]]
                    (-> (expect (visibleWidth (truncate-to line w)))
                        (.toBeLessThanOrEqual w)))))

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
            (it "every output line is exactly w columns"
                (fn []
                  (let [out (fit-lines "short\nmuch longer line than the cap\nmid" 12)
                        lines (str/split out #"\n")]
                    (doseq [l lines]
                      (-> (expect (visibleWidth l)) (.toBe 12))))))

            (it "wide glyphs never push a line past w"
                (fn []
                  ;; A row of CJK padded to 12 "chars" is 24 columns — wider
                  ;; than the box, which pi-tui turns into a dead session.
                  (doseq [w [40 20 12 6]]
                    (let [out (fit-lines (str "\u6f22\u5b57\u30c6\u30b9\u30c8 \ud83c\udf89 plain\n"
                                              "\ud83c\uddef\ud83c\uddf5 flag row\nascii") w)]
                      (doseq [l (str/split out #"\n")]
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))

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
            (it "with :max-width, every row is exactly max-width columns"
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
                      (-> (expect (visibleWidth l)) (.toBe 42))))))

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
                    (let [widest (reduce (fn [w l] (max w (visibleWidth l))) 0 lines)]
                      (doseq [l lines]
                        (-> (expect (visibleWidth l)) (.toBe widest)))))))))

;;; ─── colour ──────────────────────────────────────────────────────────────
;;; The picker had no styling at all — one `▶` glyph marked the selection and
;;; nothing separated the overlay from the transcript it floats over.
;;;
;;; The width constraint is the whole risk here: escapes must be zero-width in
;;; practice, and every styled line must close, or the padding and the row
;;; below inherit an open colour.

(defn- has-ansi? [s] (.includes (str s) ESC))

(describe "render-frame colour"
          (fn []
            (it "emphasises the focused row and dims the rest of the frame"
                (fn []
                  (let [out   (render {:items ["one" "two" "three"] :selected-idx 1})
                        lines (str/split out #"\n")]
                    ;; Title, prompt line and the focused row all carry style.
                    (-> (expect (has-ansi? (first lines))) (.toBe true))
                    (-> (expect (has-ansi? (second lines))) (.toBe true))
                    (-> (expect (some (fn [l] (and (.includes (strip l) "▶ two")
                                                   (has-ansi? l)))
                                      lines))
                        (.toBe true)))))

            (it "closes every styled line"
                (fn []
                  ;; truncateToWidth re-emits pending SGR but never closes it,
                  ;; and fit-lines pads AFTER truncating — so an unclosed line
                  ;; bleeds its colour through the padding into the next row.
                  (doseq [w [120 80 40 20]]
                    (doseq [l (str/split (render {:items ["alpha" "beta" "gamma"]
                                                  :selected-idx 1
                                                  :max-width w})
                                         #"\n")]
                      (when (has-ansi? l)
                        (-> (expect (.endsWith l (str ESC "[0m"))) (.toBe true)))))))

            (it "adds no columns at any width, with wide glyphs"
                (fn []
                  ;; The load-bearing property. An escape that measured as
                  ;; visible would overflow the box and kill the TUI.
                  (doseq [w [120 80 60 40 20 10]]
                    (doseq [l (str/split (render {:items ["漢字テスト/モデル"
                                                          "🎉 party/🚀-v2"
                                                          "plain-ascii"]
                                                  :selected-idx 1
                                                  :max-width w})
                                         #"\n")]
                      (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w))))))

            (it "leaves the visible text identical to the unstyled frame"
                (fn []
                  ;; Colour must not change WHAT is shown, only how it looks.
                  (let [opts {:items ["one" "two" "three"] :selected-idx 1 :max-width 40}
                        out  (render opts)]
                    (-> (expect (.includes (strip out) "▶ two")) (.toBe true))
                    (-> (expect (.includes (strip out) "(2/3)")) (.toBe true)))))

            (it "shows a counter and a warning when nothing matched"
                (fn []
                  ;; Previously the counter was suppressed at zero matches, so
                  ;; a filter that matched nothing looked identical to one that
                  ;; matched everything.
                  (let [out (render {:items [] :filter-text "zzz" :no-match-text "No matches"})]
                    (-> (expect (.includes (strip out) "(0/0)")) (.toBe true))
                    (-> (expect (.includes (strip out) "No matches")) (.toBe true))
                    (-> (expect (some (fn [l] (and (.includes (strip l) "No matches")
                                                   (has-ansi? l)))
                                      (str/split out #"\n")))
                        (.toBe true)))))

            (it "dims the key hints so they stop competing with the title"
                (fn []
                  (let [out   (render {:title "Pick a model" :hint "(Enter to select)"})
                        title (first (str/split out #"\n"))]
                    (-> (expect (.includes (strip title) "Pick a model")) (.toBe true))
                    (-> (expect (.includes (strip title) "(Enter to select)")) (.toBe true))
                    ;; Two different styles on one line.
                    (-> (expect (> (count (.split title ESC)) 3)) (.toBe true)))))))
