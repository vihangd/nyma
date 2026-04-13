(ns settings-duplicate-keys.test
  "Unit tests for agent.settings.manager/detect-duplicate-keys. The
   scanner has to handle nested objects, string escapes, and the
   easy-to-miss case where a duplicate key is inside a nested object
   (not the top level). JSON.parse silently keeps the last value, so
   catching duplicates at load time turns silent data loss into a
   stderr warning."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.settings.manager :refer [detect-duplicate-keys]]))

(describe "detect-duplicate-keys: clean input"
          (fn []
            (it "returns empty for simple valid JSON"
                (fn []
                  (-> (expect (count (detect-duplicate-keys "{\"a\": 1, \"b\": 2}")))
                      (.toBe 0))))

            (it "returns empty for nested objects with unique keys per scope"
                (fn []
                  (let [src "{\"outer\": {\"a\": 1}, \"inner\": {\"a\": 2}}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 0)))))

            (it "returns empty for empty input"
                (fn []
                  (-> (expect (count (detect-duplicate-keys ""))) (.toBe 0))))

            (it "returns empty for nil input"
                (fn []
                  (-> (expect (count (detect-duplicate-keys nil))) (.toBe 0))))

            (it "returns empty for non-string input"
                (fn []
                  (-> (expect (count (detect-duplicate-keys 42))) (.toBe 0))))))

(describe "detect-duplicate-keys: top-level duplicates"
          (fn []
            (it "flags a single top-level duplicate"
                (fn []
                  (let [src "{\"theme\": \"dark\", \"theme\": \"light\"}"
                        issues (detect-duplicate-keys src)]
                    (-> (expect (count issues)) (.toBe 1))
                    (-> (expect (:type (first issues))) (.toBe "settings/duplicate-key"))
                    (-> (expect (.includes (:message (first issues)) "theme")) (.toBe true))
          ;; Suggestion points the user at the fix.
                    (-> (expect (some? (:suggestion (first issues)))) (.toBe true)))))

            (it "flags every duplicate when more than one key repeats"
                (fn []
                  (let [src "{\"a\": 1, \"a\": 2, \"b\": 3, \"b\": 4}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 2)))))

            (it "flags a key repeated three times as two issues (not one)"
                (fn []
        ;; Each repeat after the first generates one issue — makes
        ;; the output precise so users can see exactly how many
        ;; dupes exist.
                  (let [src "{\"k\": 1, \"k\": 2, \"k\": 3}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 2)))))))

(describe "detect-duplicate-keys: nested scope"
          (fn []
            (it "flags duplicates inside a nested object"
                (fn []
                  (let [src "{\"outer\": {\"a\": 1, \"a\": 2}}"
                        issues (detect-duplicate-keys src)]
                    (-> (expect (count issues)) (.toBe 1))
                    (-> (expect (.includes (:message (first issues)) "a")) (.toBe true)))))

            (it "does NOT flag the same key in different object scopes"
                (fn []
        ;; 'name' appears in two sibling objects — that's fine.
                  (let [src "{\"user\": {\"name\": \"alice\"}, \"admin\": {\"name\": \"bob\"}}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 0)))))

            (it "correctly restores parent scope after exiting a nested object"
                (fn []
        ;; First 'x' is the child of 'inner'; the second 'x' is on
        ;; the outer object. Neither is a duplicate.
                  (let [src "{\"inner\": {\"x\": 1}, \"x\": 2}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 0)))))))

(describe "detect-duplicate-keys: string handling"
          (fn []
            (it "ignores braces and quotes inside string values"
                (fn []
        ;; A value that contains '{}' and '\"key\"' must not confuse
        ;; the scanner into thinking there's a duplicate.
                  (let [src "{\"a\": \"{\\\"theme\\\": 1}\", \"b\": 2}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 0)))))

            (it "handles escaped quotes inside string values"
                (fn []
                  (let [src "{\"a\": \"quoted \\\"value\\\"\"}"]
                    (-> (expect (count (detect-duplicate-keys src))) (.toBe 0)))))))
