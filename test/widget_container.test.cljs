(ns widget-container.test
  "Unit tests for src/agent/ui/widget_container.cljs.

   Widgets are pinned status UI that never scroll into terminal
   scrollback. That makes them a silent overflow vector: an extension
   can push 200 lines of thinking/status/debug into the dynamic region
   and push the editor off-screen, replicating the exact Ink log-update
   leak we fixed in ChatView. These tests pin the truncation contract
   so any regression in the cap logic fails loudly."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/widget_container.jsx" :refer [WidgetContainer truncate_widget_lines]]))

(afterEach (fn [] (cleanup)))

;;; ─── truncate-widget-lines (pure) ──────────────────────

(describe "truncate-widget-lines"
          (fn []

            (it "returns all lines when count ≤ cap"
                (fn []
                  (let [{:keys [shown extra]} (truncate_widget_lines #js ["a" "b" "c"] 5)]
                    (-> (expect (count shown)) (.toBe 3))
                    (-> (expect extra) (.toBe 0)))))

            (it "truncates to cap and reports extra when count > cap"
                (fn []
                  (let [lines (vec (map (fn [i] (str "line-" i)) (range 25)))
                        {:keys [shown extra]} (truncate_widget_lines lines 20)]
                    (-> (expect (count shown)) (.toBe 20))
                    (-> (expect extra) (.toBe 5))
                    (-> (expect (nth shown 0)) (.toBe "line-0"))
                    (-> (expect (nth shown 19)) (.toBe "line-19")))))

            (it "cap of 0 shows nothing, reports all as extra"
                ;; Edge case: zero cap means `:shown` is empty but the
                ;; container should still render the overflow indicator.
                (fn []
                  (let [{:keys [shown extra]} (truncate_widget_lines #js ["a" "b"] 0)]
                    (-> (expect (count shown)) (.toBe 0))
                    (-> (expect extra) (.toBe 2)))))

            (it "cap equal to count returns all, extra=0"
                (fn []
                  (let [{:keys [shown extra]} (truncate_widget_lines #js ["a" "b" "c"] 3)]
                    (-> (expect (count shown)) (.toBe 3))
                    (-> (expect extra) (.toBe 0)))))

            (it "empty input returns empty, extra=0"
                (fn []
                  (let [{:keys [shown extra]} (truncate_widget_lines #js [] 10)]
                    (-> (expect (count shown)) (.toBe 0))
                    (-> (expect extra) (.toBe 0)))))

            (it "negative cap is clamped to 0 (defensive)"
                (fn []
                  (let [{:keys [shown extra]} (truncate_widget_lines #js ["a" "b"] -5)]
                    (-> (expect (count shown)) (.toBe 0))
                    (-> (expect extra) (.toBe 2)))))))

;;; ─── WidgetContainer rendering ─────────────────────────

(describe "WidgetContainer"
          (fn []

            (it "renders all lines when widget is short"
                (fn []
                  (let [widgets {"w1" {:lines ["short-widget-line"]
                                       :position "below"
                                       :priority 0}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                    (-> (expect (lastFrame)) (.toContain "short-widget-line")))))

            (it "truncates at default cap (20) and shows overflow indicator"
                ;; REGRESSION: without the cap, a widget with 30 lines would
                ;; render all 30, pushing the editor off-screen and causing
                ;; Ink log-update to leak top lines into real scrollback.
                (fn []
                  (let [lines   (vec (map (fn [i] (str "widget-line-" i)) (range 30)))
                        widgets {"w1" {:lines lines :position "below" :priority 0}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                    (-> (expect (lastFrame)) (.toContain "widget-line-0"))
                    (-> (expect (lastFrame)) (.toContain "widget-line-19"))
                    (-> (expect (lastFrame)) (.not.toContain "widget-line-20"))
                    (-> (expect (lastFrame)) (.toContain "+10 more lines")))))

            (it "respects per-widget :max-lines override"
                (fn []
                  (let [lines   (vec (map (fn [i] (str "L-" i)) (range 10)))
                        widgets {"w1" {:lines lines :position "below"
                                       :priority 0 :max-lines 5}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                    (-> (expect (lastFrame)) (.toContain "L-0"))
                    (-> (expect (lastFrame)) (.toContain "L-4"))
                    (-> (expect (lastFrame)) (.not.toContain "L-5"))
                    (-> (expect (lastFrame)) (.toContain "+5 more lines")))))

            (it "filters by position (above vs below)"
                (fn []
                  (let [widgets {"a" {:lines ["ABOVE-LINE"] :position "above" :priority 0}
                                 "b" {:lines ["BELOW-LINE"] :position "below" :priority 0}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "above"}])]
                    (-> (expect (lastFrame)) (.toContain "ABOVE-LINE"))
                    (-> (expect (lastFrame)) (.not.toContain "BELOW-LINE")))))

            (it "renders nothing when no widgets match the position"
                (fn []
                  (let [widgets {"a" {:lines ["only-above"] :position "above" :priority 0}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                    ;; Returns nil; lastFrame should not crash and should
                    ;; not contain the widget text.
                    (-> (expect (or (= "" (lastFrame))
                                    (not (.includes (lastFrame) "only-above"))))
                        (.toBe true)))))

            (it "multiple widgets sort by priority (higher first)"
                (fn []
                  (let [widgets {"low"  {:lines ["LOW-PRI"]  :position "below" :priority 0}
                                 "high" {:lines ["HIGH-PRI"] :position "below" :priority 10}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "below"}])
                        frame (lastFrame)
                        ;; Both should render; high-priority one should appear
                        ;; earlier in the output.
                        high-idx (.indexOf frame "HIGH-PRI")
                        low-idx  (.indexOf frame "LOW-PRI")]
                    (-> (expect (>= high-idx 0)) (.toBe true))
                    (-> (expect (>= low-idx 0)) (.toBe true))
                    (-> (expect (< high-idx low-idx)) (.toBe true)))))

            (it ":max-lines 0 renders only the overflow indicator"
                ;; Useful for producers that want the widget to appear
                ;; (for its border/label) but not contribute any lines.
                (fn []
                  (let [widgets {"w" {:lines ["a" "b" "c"] :position "below"
                                      :priority 0 :max-lines 0}}
                        {:keys [lastFrame]}
                        (render #jsx [WidgetContainer {:widgets widgets :position "below"}])]
                    (-> (expect (lastFrame)) (.not.toContain "\na\n"))
                    (-> (expect (lastFrame)) (.toContain "+3 more lines")))))))
