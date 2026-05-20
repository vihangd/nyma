(ns ext-spec-driven-clarify.test
  "Tests for the clarify seed-prompt builder. The Q&A flow itself is
   carried out by the model on subsequent turns under nyma's normal
   conversation loop — that's covered by the load-smoke + manual
   end-to-end testing. This file pins the seed builder."
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/extensions/spec_driven/clarify.mjs" :as clarify]))

(describe "clarify/today-iso"
          (fn []
            (it "returns YYYY-MM-DD string"
                (fn []
                  (let [d (clarify/today-iso)]
                    (-> (expect (.-length d)) (.toBe 10))
                    ;; Matches ISO date pattern
                    (-> (expect (boolean (.match d (js/RegExp. "^\\d{4}-\\d{2}-\\d{2}$"))))
                        (.toBe true)))))))

(describe "clarify/ranked-marker-summary"
          (fn []
            (it "no markers → no-markers fallback"
                (fn []
                  (let [s (clarify/ranked-marker-summary [])]
                    (-> (expect (.includes s "no [NEEDS CLARIFICATION] markers"))
                        (.toBe true)))))

            (it "lists markers with line indices"
                (fn []
                  (let [markers [{:line-idx 5 :text "auth method"}
                                 {:line-idx 12 :text "idle timeout"}]
                        s (clarify/ranked-marker-summary markers)]
                    (-> (expect (.includes s "Priority-1 inline markers")) (.toBe true))
                    (-> (expect (.includes s "line 5: auth method")) (.toBe true))
                    (-> (expect (.includes s "line 12: idle timeout")) (.toBe true)))))))

(describe "clarify/compose-clarify-seed"
          (fn []
            (it "includes the spec content verbatim"
                (fn []
                  (let [spec "# auth-flow\n\nUSERS-NEED-AUTH"
                        seed (clarify/compose-clarify-seed
                              {:spec-name "auth-flow"
                               :spec-content spec
                               :spec-path ".specify/specs/auth-flow/spec.md"})]
                    (-> (expect (.includes seed "USERS-NEED-AUTH")) (.toBe true)))))

            (it "includes the spec name and file path"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "auth-flow"
                               :spec-content "x"
                               :spec-path ".specify/specs/auth-flow/spec.md"})]
                    (-> (expect (.includes seed "auth-flow")) (.toBe true))
                    (-> (expect (.includes seed ".specify/specs/auth-flow/spec.md"))
                        (.toBe true)))))

            (it "includes today's ISO date"
                (fn []
                  (let [today (clarify/today-iso)
                        seed (clarify/compose-clarify-seed
                              {:spec-name "x"
                               :spec-content "y"
                               :spec-path "p"})]
                    (-> (expect (.includes seed today)) (.toBe true)))))

            (it "states the 5-question hard cap"
                (fn []
                  ;; Template source wraps "5" and "questions" across a
                  ;; line break, so check for both pieces independently.
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "Hard cap: 5")) (.toBe true))
                    (-> (expect (.includes seed "up to 5 highly targeted questions")) (.toBe true)))))

            (it "states the early-termination words"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "done")) (.toBe true))
                    (-> (expect (.includes seed "no more")) (.toBe true)))))

            (it "names the recommended-shortcut tokens"
                (fn []
                  ;; User can type yes/recommended/suggested to accept.
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "yes")) (.toBe true))
                    (-> (expect (.includes seed "recommended")) (.toBe true))
                    (-> (expect (.includes seed "suggested")) (.toBe true)))))

            (it "lists the 11 clarification categories"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "Functional Scope")) (.toBe true))
                    (-> (expect (.includes seed "Domain & Data Model")) (.toBe true))
                    (-> (expect (.includes seed "Interaction & UX")) (.toBe true))
                    (-> (expect (.includes seed "Non-Functional")) (.toBe true))
                    (-> (expect (.includes seed "Integration & External")) (.toBe true))
                    (-> (expect (.includes seed "Edge Cases")) (.toBe true))
                    (-> (expect (.includes seed "Constraints & Tradeoffs")) (.toBe true))
                    (-> (expect (.includes seed "Terminology & Consistency")) (.toBe true))
                    (-> (expect (.includes seed "Completion Signals")) (.toBe true))
                    (-> (expect (.includes seed "Misc / Placeholders")) (.toBe true))
                    (-> (expect (.includes seed "[NEEDS CLARIFICATION:")) (.toBe true)))))

            (it "highlights inline markers from the spec"
                (fn []
                  (let [spec (str "# auth-flow\n\n"
                                  "FR-001: [NEEDS CLARIFICATION: auth method?]\n")
                        seed (clarify/compose-clarify-seed
                              {:spec-name "auth-flow"
                               :spec-content spec
                               :spec-path ".specify/specs/auth-flow/spec.md"})]
                    ;; Marker text appears in the priority summary
                    (-> (expect (.includes seed "auth method?")) (.toBe true))
                    (-> (expect (.includes seed "Priority-1 inline markers")) (.toBe true)))))

            (it "instructs the model to use edit/write tools"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "edit")) (.toBe true)))))

            (it "specifies the per-Q+A bullet format"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "- Q: <question> → A: <final answer>"))
                        (.toBe true)))))

            (it "tells the model to replace marker text rather than duplicate"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "REPLACE the marker text")) (.toBe true)))))

            (it "ends with a single-line summary line"
                (fn []
                  (let [seed (clarify/compose-clarify-seed
                              {:spec-name "x" :spec-content "y" :spec-path "p"})]
                    (-> (expect (.includes seed "Clarify session complete"))
                        (.toBe true)))))

            (it "preserves literal %s in user-supplied spec content"
                (fn []
                  ;; Regression: chained `.replace "%s"` had a leakage bug
                  ;; that would corrupt later substitutions if user content
                  ;; contained "%s". interpolate-template makes this safe.
                  (let [spec (str "# auth-flow\n\n"
                                  "Error format: \"user %s logged in\"\n")
                        seed (clarify/compose-clarify-seed
                              {:spec-name "auth-flow"
                               :spec-content spec
                               :spec-path ".specify/specs/auth-flow/spec.md"})]
                    ;; The user's literal %s should survive
                    (-> (expect (.includes seed "user %s logged in")) (.toBe true))
                    ;; And the spec name (a downstream value in the old API)
                    ;; should still be in the right place
                    (-> (expect (.includes seed "Active spec name: auth-flow"))
                        (.toBe true)))))))
