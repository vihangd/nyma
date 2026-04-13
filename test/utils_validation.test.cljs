(ns utils-validation.test
  "Unit tests for agent.utils.validation — issue constructors,
   filtering helpers, and formatting."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.validation :as v]))

;;; ─── Constructors ─────────────────────────────────────

(describe "issue / error / warning" (fn []
                                      (it "issue defaults to :warning severity"
                                          (fn []
                                            (let [r (v/issue :x "hi")]
                                              (-> (expect (:severity r)) (.toBe "warning"))
                                              (-> (expect (:type r)) (.toBe "x"))
                                              (-> (expect (:message r)) (.toBe "hi")))))

                                      (it "error sets severity :error"
                                          (fn []
                                            (-> (expect (:severity (v/error :x "boom"))) (.toBe "error"))))

                                      (it "warning sets severity :warning"
                                          (fn []
                                            (-> (expect (:severity (v/warning :x "hi"))) (.toBe "warning"))))

                                      (it "optional path and suggestion are preserved"
                                          (fn []
                                            (let [r (v/warning :x "hi" {:path [:a :b] :suggestion "Try z."})]
                                              (-> (expect (= [:a :b] (:path r))) (.toBe true))
                                              (-> (expect (:suggestion r)) (.toBe "Try z.")))))))

;;; ─── Filtering helpers ────────────────────────────────

(describe "errors-only / warnings-only / has-errors?" (fn []
                                                        (let [mixed [(v/error :a "1")
                                                                     (v/warning :b "2")
                                                                     (v/error :c "3")
                                                                     (v/warning :d "4")]]
                                                          (it "errors-only keeps only :error-severity issues"
                                                              (fn []
                                                                (-> (expect (count (v/errors-only mixed))) (.toBe 2))))

                                                          (it "warnings-only keeps only :warning-severity issues"
                                                              (fn []
                                                                (-> (expect (count (v/warnings-only mixed))) (.toBe 2))))

                                                          (it "has-errors? is true when any error exists"
                                                              (fn []
                                                                (-> (expect (v/has-errors? mixed)) (.toBe true))))

                                                          (it "has-errors? is false for only-warnings input"
                                                              (fn []
                                                                (-> (expect (v/has-errors? [(v/warning :a "x")])) (.toBe false))))

                                                          (it "has-errors? is false for empty input"
                                                              (fn []
                                                                (-> (expect (v/has-errors? [])) (.toBeFalsy)))))))

;;; ─── Formatting ───────────────────────────────────────

(describe "format-issue / format-issues" (fn []
                                           (it "format-issue includes severity, type, and message"
                                               (fn []
                                                 (let [line (v/format-issue (v/warning :settings/dup "key repeats"))]
                                                   (-> (expect (.includes line "[warning]")) (.toBe true))
                                                   (-> (expect (.includes line "settings/dup")) (.toBe true))
                                                   (-> (expect (.includes line "key repeats")) (.toBe true)))))

                                           (it "format-issue includes the breadcrumb path when present"
                                               (fn []
                                                 (let [line (v/format-issue
                                                             (v/warning :x "hi" {:path [:a :b :c]}))]
                                                   (-> (expect (.includes line "a.b.c")) (.toBe true)))))

                                           (it "format-issue appends the suggestion"
                                               (fn []
                                                 (let [line (v/format-issue
                                                             (v/warning :x "oops"
                                                                        {:suggestion "Try renaming the key."}))]
                                                   (-> (expect (.includes line "Try renaming")) (.toBe true)))))

                                           (it "format-issues returns empty string for no issues"
                                               (fn []
                                                 (-> (expect (v/format-issues [])) (.toBe ""))))

                                           (it "format-issues includes a count header"
                                               (fn []
                                                 (let [s (v/format-issues [(v/error :a "x")
                                                                           (v/warning :b "y")])]
                                                   (-> (expect (.includes s "1 error")) (.toBe true))
                                                   (-> (expect (.includes s "1 warning")) (.toBe true)))))

                                           (it "pluralises correctly"
                                               (fn []
                                                 (let [s (v/format-issues [(v/error :a "x")
                                                                           (v/error :b "y")
                                                                           (v/warning :c "z")])]
                                                   (-> (expect (.includes s "2 errors")) (.toBe true))
                                                   (-> (expect (.includes s "1 warning")) (.toBe true)))))))
