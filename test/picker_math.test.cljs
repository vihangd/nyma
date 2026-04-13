(ns picker-math.test
  "Pure unit tests for the picker boundary math. Every branch in
   picker_math.cljs has at least one test hitting it; the regressions
   catch the off-by-one and empty-list bugs the old scattered copies
   had. Borrowed test style from cc-kit's pure-resolver tests at
   tests/ui-keybindings.test.ts — no React, no DOM, no timing."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.picker-math :refer [clamp step-up step-down safe-index
                                          window-centered window-trailing]]))

;;; ─── clamp ──────────────────────────────────────────────

(describe "clamp" (fn []
                    (it "returns the value unchanged when in range"
                        (fn []
                          (-> (expect (clamp 5 0 10)) (.toBe 5))
                          (-> (expect (clamp 0 0 10)) (.toBe 0))
                          (-> (expect (clamp 10 0 10)) (.toBe 10))))

                    (it "clamps below the lower bound"
                        (fn []
                          (-> (expect (clamp -3 0 10)) (.toBe 0))
                          (-> (expect (clamp -1 5 10)) (.toBe 5))))

                    (it "clamps above the upper bound"
                        (fn []
                          (-> (expect (clamp 15 0 10)) (.toBe 10))
                          (-> (expect (clamp 100 0 0)) (.toBe 0))))

                    (it "treats nil upper bound as unbounded"
                        (fn []
                          (-> (expect (clamp 1000 0 nil)) (.toBe 1000))
                          (-> (expect (clamp 1000 0))     (.toBe 1000))))

                    (it "treats nil lower bound as unbounded below"
                        (fn []
                          (-> (expect (clamp -1000 nil 10)) (.toBe -1000))))))

;;; ─── step-up ────────────────────────────────────────────

(describe "step-up" (fn []
                      (it "decrements by one"
                          (fn []
                            (-> (expect (step-up 5)) (.toBe 4))))

                      (it "clamps at 0 — does not go negative"
                          (fn []
                            (-> (expect (step-up 0)) (.toBe 0))
                            (-> (expect (step-up -1)) (.toBe 0))))

                      (it "treats nil index as 0 (defensive)"
                          (fn []
                            (-> (expect (step-up nil)) (.toBe 0))))))

;;; ─── step-down ──────────────────────────────────────────

(describe "step-down" (fn []
                        (it "increments by one within the range"
                            (fn []
                              (-> (expect (step-down 2 10)) (.toBe 3))))

                        (it "clamps at count - 1 — never overshoots"
                            (fn []
                              (-> (expect (step-down 9 10)) (.toBe 9))
                              (-> (expect (step-down 99 10)) (.toBe 9))))

                        (it "returns 0 for an empty list — NOT -1"
                            (fn []
                              ;; REGRESSION: the old `(min (dec count) (inc %))`
                              ;; returned -1 here, which silently poisoned any
                              ;; downstream `nth` call by index.
                              (-> (expect (step-down 0 0)) (.toBe 0))
                              (-> (expect (step-down 5 0)) (.toBe 0))))

                        (it "stays at 0 for a single-item list"
                            (fn []
                              (-> (expect (step-down 0 1)) (.toBe 0))))

                        (it "treats nil index as 0"
                            (fn []
                              (-> (expect (step-down nil 5)) (.toBe 1))))))

;;; ─── safe-index ─────────────────────────────────────────

(describe "safe-index" (fn []
                         (it "returns the index unchanged when in range"
                             (fn []
                               (-> (expect (safe-index 3 10)) (.toBe 3))
                               (-> (expect (safe-index 0 10)) (.toBe 0))
                               (-> (expect (safe-index 9 10)) (.toBe 9))))

                         (it "clamps an out-of-range index into the current list"
                             (fn []
                               ;; REGRESSION: this is the exact scenario where
                               ;; selected-idx was 7 but the filter shrank the
                               ;; list to 3 items. Must clamp to 2, not return 7.
                               (-> (expect (safe-index 7 3)) (.toBe 2))))

                         (it "returns 0 for an empty list"
                             (fn []
                               (-> (expect (safe-index 5 0)) (.toBe 0))
                               (-> (expect (safe-index 0 0)) (.toBe 0))))

                         (it "returns 0 for a negative count (defensive)"
                             (fn []
                               (-> (expect (safe-index 0 -1)) (.toBe 0))))

                         (it "treats nil idx as 0"
                             (fn []
                               (-> (expect (safe-index nil 10)) (.toBe 0))))))

;;; ─── window-centered ────────────────────────────────────

(describe "window-centered" (fn []
                              (it "returns an empty window for a zero-total list"
                                  (fn []
                                    (let [w (window-centered 0 10 0)]
                                      (-> (expect (:start w)) (.toBe 0))
                                      (-> (expect (:end w)) (.toBe 0))
                                      (-> (expect (:visible-count w)) (.toBe 0)))))

                              (it "returns an empty window for zero visible"
                                  (fn []
                                    (let [w (window-centered 5 0 100)]
                                      (-> (expect (:visible-count w)) (.toBe 0)))))

                              (it "shows all items when total ≤ visible"
                                  (fn []
                                    (let [w (window-centered 2 10 5)]
                                      (-> (expect (:start w)) (.toBe 0))
                                      (-> (expect (:end w)) (.toBe 5))
                                      (-> (expect (:visible-count w)) (.toBe 5)))))

                              (it "centers on the focused index"
                                  (fn []
                                    ;; 20 items, 10 visible, focus on 10.
                                    ;; Half-window is 5, so start = 10 - 5 = 5,
                                    ;; end = 5 + 10 = 15.
                                    (let [w (window-centered 10 10 20)]
                                      (-> (expect (:start w)) (.toBe 5))
                                      (-> (expect (:end w)) (.toBe 15))
                                      (-> (expect (:visible-count w)) (.toBe 10)))))

                              (it "left-aligns when the focus is near the top"
                                  (fn []
                                    (let [w (window-centered 1 10 20)]
                                      (-> (expect (:start w)) (.toBe 0))
                                      (-> (expect (:end w)) (.toBe 10)))))

                              (it "right-aligns when the focus is near the bottom"
                                  (fn []
                                    ;; Focus 18 in a 20-item list, 10 visible.
                                    ;; Must end exactly at 20, not run off the end.
                                    (let [w (window-centered 18 10 20)]
                                      (-> (expect (:end w)) (.toBe 20))
                                      (-> (expect (:start w)) (.toBe 10))
                                      (-> (expect (:visible-count w)) (.toBe 10)))))

                              (it "clamps a too-high focused index into range"
                                  (fn []
                                    ;; safe-index handles this — the stored idx
                                    ;; is 50 but the list only has 20 items.
                                    ;; Must not throw or return negative bounds.
                                    (let [w (window-centered 50 10 20)]
                                      (-> (expect (:end w)) (.toBe 20))
                                      (-> (expect (:start w)) (.toBe 10)))))

                              (it "handles a single-item list"
                                  (fn []
                                    (let [w (window-centered 0 10 1)]
                                      (-> (expect (:start w)) (.toBe 0))
                                      (-> (expect (:end w)) (.toBe 1))
                                      (-> (expect (:visible-count w)) (.toBe 1)))))

                              (it "returns an empty window for nil inputs"
                                  (fn []
                                    (let [w (window-centered nil nil nil)]
                                      (-> (expect (:start w)) (.toBe 0))
                                      (-> (expect (:end w)) (.toBe 0)))))))

;;; ─── window-trailing ────────────────────────────────────
;;; Borrowed from cc-kit's FuzzyPicker strategy
;;; (packages/ui/src/design-system/FuzzyPicker.tsx:154) — focus at
;;; the BOTTOM of the visible window so every arrow-down past the
;;; last visible row immediately scrolls. Fixes the 'first 7 arrows
;;; don't seem to do anything' feel of the centred strategy on long
;;; lists.

(describe "window-trailing"
          (fn []
            (it "empty window for zero total"
                (fn []
                  (let [w (window-trailing 0 10 0)]
                    (-> (expect (:start w)) (.toBe 0))
                    (-> (expect (:end w)) (.toBe 0)))))

            (it "empty window for zero visible"
                (fn []
                  (let [w (window-trailing 5 0 100)]
                    (-> (expect (:visible-count w)) (.toBe 0)))))

            (it "total fits in window → start=0 regardless of focus"
                (fn []
                  (let [w (window-trailing 2 10 5)]
                    (-> (expect (:start w)) (.toBe 0))
                    (-> (expect (:end w)) (.toBe 5)))))

            (it "focus inside initial window → start stays at 0"
                (fn []
        ;; 24 items, 15 visible, focus 0..14 should all show [0,15).
                  (doseq [idx [0 3 7 10 14]]
                    (let [w (window-trailing idx 15 24)]
                      (-> (expect (:start w)) (.toBe 0))
                      (-> (expect (:end w)) (.toBe 15))))))

            (it "focus at idx 15 shifts window by 1 (trailing scroll)"
                (fn []
                  (let [w (window-trailing 15 15 24)]
                    (-> (expect (:start w)) (.toBe 1))
                    (-> (expect (:end w)) (.toBe 16)))))

            (it "focus at last item pins window to [total-visible, total)"
                (fn []
                  (let [w (window-trailing 23 15 24)]
                    (-> (expect (:start w)) (.toBe 9))
                    (-> (expect (:end w)) (.toBe 24)))))

            (it "every arrow-down past the initial window scrolls exactly 1"
                (fn []
        ;; The core UX property: from the moment the user starts
        ;; scrolling, each arrow-down produces a window shift.
                  (let [w14 (window-trailing 14 15 24)
                        w15 (window-trailing 15 15 24)
                        w16 (window-trailing 16 15 24)]
                    (-> (expect (:start w14)) (.toBe 0))
                    (-> (expect (:start w15)) (.toBe 1))
                    (-> (expect (:start w16)) (.toBe 2)))))

            (it "clamps an out-of-range focus to the last item"
                (fn []
                  (let [w (window-trailing 999 15 24)]
                    (-> (expect (:start w)) (.toBe 9))
                    (-> (expect (:end w)) (.toBe 24)))))

            (it "handles nil inputs"
                (fn []
                  (let [w (window-trailing nil nil nil)]
                    (-> (expect (:start w)) (.toBe 0))
                    (-> (expect (:end w)) (.toBe 0)))))))
