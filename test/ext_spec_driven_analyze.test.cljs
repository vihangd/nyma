(ns ext-spec-driven-analyze.test
  "Tests for the analyze pure-helpers — prompt composition, output
   parsing, content hashing, and the soft-block-on-start predicate.
   The actual model call is a generateText invocation; that's covered
   by manual end-to-end testing."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs"   :as fs]
            ["node:os"   :as os]
            ["node:path" :as path]
            ["./agent/extensions/spec_driven/analyze.mjs" :as analyze]))

(defn- mktmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-analyze-")))

;; ── compose-analyze-prompt ─────────────────────────────────────

(describe "analyze/compose-analyze-prompt"
          (fn []
            (it "interpolates all four artifacts"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content         "SPEC-MARKER"
                            :plan-content         "PLAN-MARKER"
                            :tasks-content        "TASKS-MARKER"
                            :constitution-content "CONST-MARKER"})]
                    (-> (expect (.includes p "SPEC-MARKER"))   (.toBe true))
                    (-> (expect (.includes p "PLAN-MARKER"))   (.toBe true))
                    (-> (expect (.includes p "TASKS-MARKER"))  (.toBe true))
                    (-> (expect (.includes p "CONST-MARKER"))  (.toBe true)))))

            (it "missing artifacts get sensible placeholders"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content "x"
                            :plan-content nil
                            :tasks-content nil
                            :constitution-content nil})]
                    (-> (expect (.includes p "no constitution.md present"))
                        (.toBe true)))))

            (it "states the 6 detection passes"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content "x" :plan-content "y"
                            :tasks-content "z" :constitution-content nil})]
                    (-> (expect (.includes p "Duplication"))         (.toBe true))
                    (-> (expect (.includes p "Ambiguity"))           (.toBe true))
                    (-> (expect (.includes p "Underspecification"))  (.toBe true))
                    (-> (expect (.includes p "Constitution"))        (.toBe true))
                    (-> (expect (.includes p "Coverage Gaps"))       (.toBe true))
                    (-> (expect (.includes p "Inconsistency"))       (.toBe true)))))

            (it "states the 4 severity levels"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content "x" :plan-content "y"
                            :tasks-content "z" :constitution-content nil})]
                    (-> (expect (.includes p "CRITICAL")) (.toBe true))
                    (-> (expect (.includes p "HIGH"))     (.toBe true))
                    (-> (expect (.includes p "MEDIUM"))   (.toBe true))
                    (-> (expect (.includes p "LOW"))      (.toBe true)))))

            (it "explicitly forbids file modification"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content "x" :plan-content "y"
                            :tasks-content "z" :constitution-content nil})]
                    (-> (expect (.includes p "MUST NOT modify any files"))
                        (.toBe true)))))

            (it "states the 50-finding cap"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content "x" :plan-content "y"
                            :tasks-content "z" :constitution-content nil})]
                    (-> (expect (.includes p "50 findings")) (.toBe true)))))

            (it "specifies stable IDs for diffability"
                (fn []
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content "x" :plan-content "y"
                            :tasks-content "z" :constitution-content nil})]
                    (-> (expect (.includes p "Same content → same"))
                        (.toBe true)))))

            (it "literal %s in spec content does not consume plan/tasks placeholders"
                (fn []
                  ;; Regression: chained `.replace "%s"` would substitute
                  ;; later artifacts into earlier ones' user-authored %s.
                  (let [p (analyze/compose-analyze-prompt
                           {:spec-content         "SPEC has \"user %s\" example."
                            :plan-content         "PLAN-CONTENT-MARKER"
                            :tasks-content        "TASKS-CONTENT-MARKER"
                            :constitution-content "CONST-CONTENT-MARKER"})
                        plan-section (.slice p
                                             (.indexOf p "## plan.md")
                                             (.indexOf p "## tasks.md"))
                        tasks-section (.slice p
                                              (.indexOf p "## tasks.md")
                                              (.indexOf p "## constitution.md"))
                        const-section (.slice p
                                              (.indexOf p "## constitution.md"))]
                    ;; All four sections land their intended content.
                    (-> (expect (.includes plan-section "PLAN-CONTENT-MARKER")) (.toBe true))
                    (-> (expect (.includes tasks-section "TASKS-CONTENT-MARKER")) (.toBe true))
                    (-> (expect (.includes const-section "CONST-CONTENT-MARKER")) (.toBe true))
                    ;; And the user's literal %s is preserved.
                    (-> (expect (.includes p "user %s")) (.toBe true)))))))

;; ── parse-analyze-output ───────────────────────────────────────

(describe "analyze/parse-analyze-output"
          (fn []
            (it "nil/non-string → empty findings"
                (fn []
                  (let [r (analyze/parse-analyze-output nil)]
                    (-> (expect (count (:findings r))) (.toBe 0))
                    (-> (expect (:critical-count r))   (.toBe 0)))))

            (it "no Findings table → empty findings"
                (fn []
                  (let [r (analyze/parse-analyze-output
                           "## Specification Analysis Report\n\n✓ No issues detected.\n")]
                    (-> (expect (count (:findings r))) (.toBe 0)))))

            (it "extracts findings from a real-shape report"
                (fn []
                  (let [report (str
                                "## Specification Analysis Report\n\n"
                                "| ID | Category | Severity | Location | Summary | Recommendation |\n"
                                "|----|----------|----------|----------|---------|----------------|\n"
                                "| A1 | Ambiguity | HIGH | spec.md:24 | vague 'fast' | quantify p95 |\n"
                                "| D1 | Duplication | MEDIUM | FR-002, FR-007 | overlap | merge |\n"
                                "| C1 | Constitution | CRITICAL | plan.md | bypasses MUST | revise |\n"
                                "\n## Coverage Summary\n")
                        r (analyze/parse-analyze-output report)]
                    (-> (expect (count (:findings r))) (.toBe 3))
                    (-> (expect (:critical-count r)) (.toBe 1))
                    (let [a1 (first (:findings r))]
                      (-> (expect (:id a1))       (.toBe "A1"))
                      (-> (expect (:category a1)) (.toBe "Ambiguity"))
                      (-> (expect (:severity a1)) (.toBe "HIGH"))
                      (-> (expect (:location a1)) (.toBe "spec.md:24"))))))

            (it "tolerates extra leading/trailing pipes and trim variations"
                (fn []
                  (let [report (str "## Specification Analysis Report\n\n"
                                    "| ID | Category | Severity | Location | Summary | Recommendation |\n"
                                    "|----|----------|----------|----------|---------|----------------|\n"
                                    "| A1| Ambiguity |HIGH| spec.md:1 |x|y|\n"
                                    "## Next\n")
                        r (analyze/parse-analyze-output report)]
                    (-> (expect (count (:findings r))) (.toBe 1))
                    (-> (expect (:severity (first (:findings r)))) (.toBe "HIGH")))))))

;; ── compute-content-hash ───────────────────────────────────────

(describe "analyze/compute-content-hash"
          (fn []
            (it "same inputs → same hash"
                (fn []
                  (let [a (analyze/compute-content-hash
                           {:spec-content "s" :plan-content "p"
                            :tasks-content "t" :constitution-content "c"})
                        b (analyze/compute-content-hash
                           {:spec-content "s" :plan-content "p"
                            :tasks-content "t" :constitution-content "c"})]
                    (-> (expect a) (.toBe b)))))

            (it "different spec → different hash"
                (fn []
                  (let [a (analyze/compute-content-hash
                           {:spec-content "s1" :plan-content "p"
                            :tasks-content "t" :constitution-content "c"})
                        b (analyze/compute-content-hash
                           {:spec-content "s2" :plan-content "p"
                            :tasks-content "t" :constitution-content "c"})]
                    (-> (expect (= a b)) (.toBe false)))))

            (it "missing constitution still produces a stable hash"
                (fn []
                  (let [a (analyze/compute-content-hash
                           {:spec-content "s" :plan-content "p"
                            :tasks-content "t" :constitution-content nil})
                        b (analyze/compute-content-hash
                           {:spec-content "s" :plan-content "p"
                            :tasks-content "t" :constitution-content nil})]
                    (-> (expect a) (.toBe b)))))))

;; ── State persistence + soft-block ─────────────────────────────

(describe "analyze/write-analyze-result! and read-spec-state"
          (fn []
            (it "round-trips through .nyma/spec-state.json"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (analyze/write-analyze-result!
                       tmp "auth-flow"
                       {:content-hash "abc123" :critical-count 2 :finding-count 7})
                      (let [state (analyze/read-spec-state tmp)
                            entry (aget state "auth-flow")]
                        (-> (expect (some? entry)) (.toBe true))
                        (-> (expect (aget entry "content-hash")) (.toBe "abc123"))
                        (-> (expect (aget entry "critical-count")) (.toBe 2)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "missing file → empty object"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [s (analyze/read-spec-state tmp)]
                        (-> (expect (zero? (count (js/Object.keys s)))) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "analyze/should-warn-on-start?"
          (fn []
            (it "no analyze run → :no-analyze"
                (fn []
                  (let [w (analyze/should-warn-on-start? #js {} "auth-flow" "hash")]
                    (-> (expect (some? (:reason w))) (.toBe true)))))

            (it "fresh-clean run → no warning"
                (fn []
                  ;; Build the state with the actual kebab-case keys
                  ;; that write-analyze-result! produces on disk.
                  (let [entry #js {}
                        _     (aset entry "content-hash"   "h1")
                        _     (aset entry "critical-count" 0)
                        _     (aset entry "run-at"         "2026-05-05T00:00:00Z")
                        state #js {}
                        _     (aset state "auth-flow" entry)
                        w (analyze/should-warn-on-start? state "auth-flow" "h1")]
                    (-> (expect w) (.toBeNil)))))

            (it "critical findings → warning (even if hash matches)"
                (fn []
                  (let [entry #js {}
                        _     (aset entry "content-hash"   "h1")
                        _     (aset entry "critical-count" 2)
                        _     (aset entry "run-at"         "2026-05-05T00:00:00Z")
                        state #js {}
                        _     (aset state "auth-flow" entry)
                        w (analyze/should-warn-on-start? state "auth-flow" "h1")]
                    (-> (expect (some? w)) (.toBe true))
                    (-> (expect (.includes (:detail w) "CRITICAL")) (.toBe true)))))

            (it "stale (hash mismatch) → warning"
                (fn []
                  (let [entry #js {}
                        _     (aset entry "content-hash"   "old")
                        _     (aset entry "critical-count" 0)
                        _     (aset entry "run-at"         "2026-05-05T00:00:00Z")
                        state #js {}
                        _     (aset state "auth-flow" entry)
                        w (analyze/should-warn-on-start? state "auth-flow" "new")]
                    (-> (expect (some? w)) (.toBe true))
                    (-> (expect (.includes (:detail w) "changed")) (.toBe true)))))

            (it "no-analyze suppressed when spec is still the unedited template"
                (fn []
                  ;; Brand-new spec from /spec new — placeholder content,
                  ;; no FR/SC IDs, generic tasks. Soft-block should NOT
                  ;; fire. Otherwise every `/spec start` after `/spec new`
                  ;; nags the user to run analyze on boilerplate.
                  (let [tpl-spec  "# auth-flow — Spec\n\n## What & Why\n\n<!-- description -->\n"
                        tpl-tasks "- [ ] First task\n- [ ] Second task\n"
                        w (analyze/should-warn-on-start?
                           #js {} "auth-flow" "h1" tpl-spec tpl-tasks)]
                    (-> (expect w) (.toBeNil)))))

            (it "no-analyze fires once spec has real FR-### IDs"
                (fn []
                  (let [real-spec  "# auth-flow\n\n## FR\nFR-001 do thing\n"
                        tpl-tasks  "- [ ] First task\n- [ ] Second task\n"
                        w (analyze/should-warn-on-start?
                           #js {} "auth-flow" "h1" real-spec tpl-tasks)]
                    (-> (expect (some? w)) (.toBe true))
                    (-> (expect (:reason w)) (.toBe :no-analyze)))))))

(describe "analyze/template-content?"
          (fn []
            (it "true for empty template (no IDs + template tasks)"
                (fn []
                  (-> (expect (analyze/template-content?
                               "scaffolded body"
                               "- [ ] First task\n- [ ] Second task\n"))
                      (.toBe true))))

            (it "false once any FR-### ID appears"
                (fn []
                  (-> (expect (analyze/template-content?
                               "FR-001 something\n"
                               "- [ ] First task\n- [ ] Second task\n"))
                      (.toBe false))))

            (it "false once tasks have been edited away from template"
                (fn []
                  (-> (expect (analyze/template-content?
                               "no ids"
                               "- [ ] Wire OAuth handler\n"))
                      (.toBe false))))))
