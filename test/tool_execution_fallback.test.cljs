(ns tool-execution-fallback.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.ui.tool-renderer-registry :refer [get-renderer
                                                      reset-registry!]]
            ["./agent/ui/tool_execution.jsx"
             :refer [expanded-verbosity? has-data?]]))

;;; ─── expanded-verbosity? ────────────────────────────────

(describe "expanded-verbosity?" (fn []
  (it "returns true for full"
    (fn []
      (-> (expect (expanded-verbosity? "full")) (.toBe true))))

  (it "returns true for summary"
    (fn []
      (-> (expect (expanded-verbosity? "summary")) (.toBe true))))

  (it "returns false for collapsed"
    (fn []
      (-> (expect (expanded-verbosity? "collapsed")) (.toBe false))))

  (it "returns false for one-line"
    (fn []
      (-> (expect (expanded-verbosity? "one-line")) (.toBe false))))

  (it "returns false for nil / unknown"
    (fn []
      (-> (expect (expanded-verbosity? nil)) (.toBe false))
      (-> (expect (expanded-verbosity? "unknown")) (.toBe false))))))

;;; ─── has-data? ─────────────────────────────────────────

(describe "has-data?" (fn []
  (it "returns false for nil"
    (fn []
      (-> (expect (has-data? nil)) (.toBe false))))

  (it "returns false for empty string"
    (fn []
      (-> (expect (has-data? "")) (.toBe false))))

  (it "returns true for non-empty string"
    (fn []
      (-> (expect (has-data? "hello")) (.toBe true))))

  (it "returns false for empty object"
    (fn []
      (-> (expect (has-data? #js {})) (.toBe false))))

  (it "returns true for non-empty object"
    (fn []
      (-> (expect (has-data? #js {:a 1})) (.toBe true))))

  (it "returns false for empty array"
    (fn []
      (-> (expect (has-data? #js [])) (.toBe false))))

  (it "returns true for non-empty array"
    (fn []
      (-> (expect (has-data? #js [1 2])) (.toBe true))))

  (it "returns true for numbers"
    (fn []
      (-> (expect (has-data? 42)) (.toBe true))))))

;;; ─── Renderer dispatch path — unregistered tool falls through ─

(describe "unregistered tool dispatch" (fn []
  (beforeEach
    (fn []
      (reset-registry!)
      ;; Built-in renderers self-register on module load, so reset +
      ;; the test runs before re-imports fire again. We don't need
      ;; any renderers for these tests.
      ))

  (it "returns nil for a tool name that has no renderer"
    (fn []
      (-> (expect (get-renderer "never-registered-tool")) (.toBe js/undefined))))))
