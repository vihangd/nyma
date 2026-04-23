(ns overlay.test
  "Unit tests for src/agent/ui/overlay.cljs pure helpers.

   Scope is deliberately narrow — Ink-rendering tests for the Overlay
   component live in test/ui_render.test.cljs. This file covers the
   `clamp-interval` helper that guards `component.interval` from spamming
   React renders when an extension passes 0, 5, nil, or NaN."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/overlay.jsx" :refer [clamp_interval]]))

(describe "clamp-interval"
          (fn []

            (it "returns the floor (100 ms) for 0"
                ;; `setInterval(fn, 0)` would spam React renders. Must clamp.
                (fn []
                  (-> (expect (clamp_interval 0)) (.toBe 100))))

            (it "returns the floor for values below 100"
                (fn []
                  (-> (expect (clamp_interval 5)) (.toBe 100))
                  (-> (expect (clamp_interval 99)) (.toBe 100))))

            (it "returns the floor at exactly 100"
                (fn []
                  (-> (expect (clamp_interval 100)) (.toBe 100))))

            (it "passes through values >= 100 unchanged"
                (fn []
                  (-> (expect (clamp_interval 200)) (.toBe 200))
                  (-> (expect (clamp_interval 5000)) (.toBe 5000))))

            (it "returns the floor for nil"
                ;; Defensive: a misconfigured extension might pass undefined
                ;; while still setting the `.interval` property.
                (fn []
                  (-> (expect (clamp_interval nil)) (.toBe 100))))

            (it "returns the floor for non-numeric input"
                (fn []
                  (-> (expect (clamp_interval "500")) (.toBe 100))
                  (-> (expect (clamp_interval #js {})) (.toBe 100))))

            (it "returns the floor for NaN"
                (fn []
                  (-> (expect (clamp_interval js/NaN)) (.toBe 100))))

            (it "returns the floor for negative values"
                ;; setInterval accepts these but treats them as 0, which
                ;; is the very thing we're preventing.
                (fn []
                  (-> (expect (clamp_interval -100)) (.toBe 100))))))
