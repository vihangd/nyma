(ns memory.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.memory.shared :as m]))

(describe "memory parse/render round-trip" (fn []
                                             (it "parses ## key blocks (ignoring a title)"
                                                 (fn []
                                                   (let [pairs (m/parse-memory "# Memory\n\n## build\nuse bun\n\n## db\npostgres 16")]
                                                     (-> (expect (count pairs)) (.toBe 2))
                                                     (-> (expect (first (first pairs))) (.toBe "build"))
                                                     (-> (expect (second (first pairs))) (.toBe "use bun"))
                                                     (-> (expect (second (nth pairs 1))) (.toBe "postgres 16")))))

                                             (it "round-trips through render/parse"
                                                 (fn []
                                                   (let [pairs [["a" "one"] ["b" "two\nlines"]]
                                                         back  (m/parse-memory (m/render-memory pairs))]
                                                     (-> (expect back) (.toEqual (clj->js pairs))))))

                                             (it "upsert replaces an existing key, appends a new one"
                                                 (fn []
                                                   (let [p0 [["a" "1"]]
                                                         p1 (m/upsert p0 "a" "2")
                                                         p2 (m/upsert p1 "b" "3")]
                                                     (-> (expect (second (first p1))) (.toBe "2"))
                                                     (-> (expect (count p2)) (.toBe 2))
                                                     (-> (expect (first (nth p2 1))) (.toBe "b")))))

                                             (it "remove-key drops the block"
                                                 (fn []
                                                   (-> (expect (count (m/remove-key [["a" "1"] ["b" "2"]] "a"))) (.toBe 1))))

                                             (it "sanitizes a value containing a ## header so re-parse keeps one block"
                                                 (fn []
                                                   (let [pairs (m/upsert [] "notes" "line1\n## fake-header\nline3")
                                                         back  (m/parse-memory (m/render-memory pairs))]
                                                     (-> (expect (count back)) (.toBe 1))
                                                     (-> (expect (first (first back))) (.toBe "notes")))))

                                             (it "empty content yields no pairs"
                                                 (fn []
                                                   (-> (expect (count (m/parse-memory ""))) (.toBe 0))
                                                   (-> (expect (count (m/parse-memory nil))) (.toBe 0))))))

(describe "memory cap-lines (rot guard)" (fn []
                                           (it "keeps content under the cap unchanged"
                                               (fn []
                                                 (-> (expect (m/cap-lines "a\nb\nc" 5)) (.toBe "a\nb\nc"))))

                                           (it "truncates and notes when over the cap"
                                               (fn []
                                                 (let [out (m/cap-lines "L1\nL2\nL3\nL4\nL5" 2)]
                                                   (-> (expect (.startsWith out "L1\nL2")) (.toBe true))
                                                   (-> (expect (.includes out "truncated")) (.toBe true))
                                                   (-> (expect (.includes out "L4")) (.toBe false)))))))

(describe "memory config" (fn []
                            (it "defaults + settings override"
                                (fn []
                                  (-> (expect (:max-lines (m/config nil))) (.toBe 200))
                                  (let [c (m/config #js {:memory #js {:dir "notes" :max-lines 50}})]
                                    (-> (expect (:dir c)) (.toBe "notes"))
                                    (-> (expect (:max-lines c)) (.toBe 50)))))))
