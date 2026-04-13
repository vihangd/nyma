(ns progress-bar.test
  "Unit tests for agent.ui.progress-bar. All the interesting logic
   is in the pure `render-string` fn, so most tests assert on the
   exact glyph sequence — the last-cell sub-block math is the easy
   place for an off-by-one to creep in."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect]]
            ["ink-testing-library" :refer [render]]
            ;; progress_bar.cljs has {:squint/extension "jsx"} so it
            ;; compiles to progress_bar.jsx, not the default .mjs.
            ;; Namespace-style requires would map to .mjs and fail at
            ;; runtime — match the pattern other jsx test files use
            ;; (see ui_smoke.test.cljs) and import via the relative
            ;; compiled path directly.
            ["./agent/ui/progress_bar.jsx" :refer [render-string ProgressBar]]))

;;; ─── render-string: clamping ─────────────────────────

(describe "render-string: out-of-range ratios"
          (fn []
            (it "ratio 0 renders an all-empty bar"
                (fn []
                  (-> (expect (render-string 0 10)) (.toBe "          "))))

            (it "ratio 1 renders an all-full bar"
                (fn []
                  (-> (expect (render-string 1 10)) (.toBe "\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588\u2588"))))

            (it "negative ratio clamps to 0"
                (fn []
                  (-> (expect (render-string -0.5 5)) (.toBe "     "))))

            (it "ratio >1 clamps to full"
                (fn []
                  (-> (expect (render-string 2.0 5)) (.toBe "\u2588\u2588\u2588\u2588\u2588"))))

            (it "nil ratio is treated as 0"
                (fn []
                  (-> (expect (render-string nil 5)) (.toBe "     "))))

            (it "NaN ratio is treated as 0"
                (fn []
                  (-> (expect (render-string js/NaN 5)) (.toBe "     "))))))

;;; ─── render-string: width edge cases ─────────────────

(describe "render-string: width edge cases"
          (fn []
            (it "width 0 returns the empty string"
                (fn []
                  (-> (expect (render-string 0.5 0)) (.toBe ""))))

            (it "negative width returns the empty string"
                (fn []
                  (-> (expect (render-string 0.5 -3)) (.toBe ""))))

            (it "nil width returns the empty string"
                (fn []
                  (-> (expect (render-string 0.5 nil)) (.toBe ""))))

            (it "width 1 at ratio 0 is a single empty cell"
                (fn []
                  (-> (expect (render-string 0 1)) (.toBe " "))))

            (it "width 1 at ratio 1 is a single full block"
                (fn []
                  (-> (expect (render-string 1 1)) (.toBe "\u2588"))))

            (it "non-integer width is floored"
                (fn []
                  (-> (expect (render-string 1 5.7)) (.toBe "\u2588\u2588\u2588\u2588\u2588"))))))

;;; ─── render-string: sub-cell remainder arithmetic ────

(describe "render-string: sub-cell fills"
          (fn []
            (it "ratio 0.5 on width 10 gives exactly 5 full blocks"
                (fn []
        ;; 5.0 whole blocks, remainder 0.0 → first sub-block is ' '
                  (let [out (render-string 0.5 10)
                        prefix "\u2588\u2588\u2588\u2588\u2588"]
                    (-> (expect (.startsWith out prefix)) (.toBe true))
                    (-> (expect (count out)) (.toBe 10)))))

            (it "ratio 0.25 on width 4 gives 1 full block + empties"
                (fn []
                  (let [out (render-string 0.25 4)]
                    (-> (expect (.startsWith out "\u2588")) (.toBe true))
                    (-> (expect (count out)) (.toBe 4)))))

            (it "ratio 0.999 on width 10 gives 9 full blocks and a near-full sub-block"
                (fn []
        ;; whole = floor(9.99) = 9, remainder ≈ 0.99, middle index ≈ 8 → full block
                  (let [out (render-string 0.999 10)]
                    (-> (expect (count out)) (.toBe 10))
          ;; All cells should be full or near-full — no empty space.
                    (-> (expect (.includes out " ")) (.toBe false)))))

            (it "length of the output always equals the requested width"
                (fn []
                  (doseq [ratio [0 0.1 0.25 0.37 0.5 0.73 0.9 1.0]]
                    (-> (expect (count (render-string ratio 20))) (.toBe 20)))))))

;;; ─── JSX wrapper smoke test ──────────────────────────

(describe "ProgressBar component"
          (fn []
            (it "renders through ink-testing-library without crashing"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [ProgressBar {:ratio 0.5 :width 10}])]
                    (-> (expect (lastFrame)) (.toBeDefined))
                    (-> (expect (count (lastFrame))) (.toBeGreaterThan 0)))))

            (it "empty bar with ratio 0 still renders some cells"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [ProgressBar {:ratio 0 :width 5}])]
          ;; Should render 5 space glyphs (possibly wrapped in ANSI escapes).
                    (-> (expect (some? (lastFrame))) (.toBe true)))))

            (it "full bar with ratio 1 contains at least one full block char"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [ProgressBar {:ratio 1.0 :width 5}])]
                    (-> (expect (.includes (lastFrame) "\u2588")) (.toBe true)))))))
