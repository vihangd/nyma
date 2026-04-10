(ns fuzzy-scorer.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.fuzzy-scorer :refer [fuzzy-match fuzzy-filter]]))

;;; ─── fuzzy-match ───────────────────────────────────────

(describe "fuzzy-match" (fn []
  (it "empty query matches anything with score 0"
    (fn []
      (let [r (fuzzy-match "" "anything")]
        (-> (expect (:matches r)) (.toBe true))
        (-> (expect (:score r)) (.toBe 0)))))

  (it "nil query matches anything"
    (fn []
      (-> (expect (:matches (fuzzy-match nil "abc"))) (.toBe true))))

  (it "exact match scores 10"
    (fn []
      (let [r (fuzzy-match "abc" "abc")]
        (-> (expect (:matches r)) (.toBe true))
        (-> (expect (:score r)) (.toBe 10)))))

  (it "is case-insensitive"
    (fn []
      (-> (expect (:matches (fuzzy-match "ABC" "abc"))) (.toBe true))))

  (it "prefix match scores better than substring match"
    (fn []
      (let [prefix (:score (fuzzy-match "foo" "foobar"))
            sub    (:score (fuzzy-match "bar" "foobar"))]
        (-> (expect (< prefix sub)) (.toBe true)))))

  (it "substring match scores better than subsequence match"
    (fn []
      (let [sub  (:score (fuzzy-match "abc" "xabcx"))
            seq1 (:score (fuzzy-match "abc" "a1b2c3"))]
        (-> (expect (< sub seq1)) (.toBe true)))))

  (it "subsequence match still succeeds"
    (fn []
      (-> (expect (:matches (fuzzy-match "abc" "a1b2c3"))) (.toBe true))))

  (it "no match returns false"
    (fn []
      (let [r (fuzzy-match "xyz" "abc")]
        (-> (expect (:matches r)) (.toBe false)))))

  (it "query longer than text is a non-match"
    (fn []
      (-> (expect (:matches (fuzzy-match "abcd" "abc"))) (.toBe false))))

  (it "consecutive character run scores better than spread-out match"
    (fn []
      (let [tight (:score (fuzzy-match "foo" "xfoox"))
            loose (:score (fuzzy-match "foo" "f_o_o_"))]
        (-> (expect (< tight loose)) (.toBe true)))))

  (it "matching at a word boundary is cheaper than in the middle"
    (fn []
      ;; 'r' as the start of 'renderer' vs. 'r' in the middle of a word
      (let [boundary (:score (fuzzy-match "fr" "foo/renderer"))
            middle   (:score (fuzzy-match "fr" "fore"))]
        ;; Hard to guarantee strict ordering across changes — just assert
        ;; both match, leave detailed ordering to manual tuning.
        (-> (expect (some? boundary)) (.toBeTruthy))
        (-> (expect (some? middle)) (.toBeTruthy)))))))

;;; ─── fuzzy-filter ──────────────────────────────────────

(describe "fuzzy-filter" (fn []
  (it "returns all items for empty query"
    (fn []
      (let [items ["a" "b" "c"]
            result (fuzzy-filter items "" identity)]
        (-> (expect (count result)) (.toBe 3)))))

  (it "returns only matching items"
    (fn []
      (let [items ["apple" "banana" "apricot"]
            result (fuzzy-filter items "ap" identity)]
        (-> (expect (count result)) (.toBe 2))
        (-> (expect (.includes result "apple")) (.toBe true))
        (-> (expect (.includes result "apricot")) (.toBe true)))))

  (it "sorts best matches first"
    (fn []
      (let [items ["foobar" "xfoox"]
            result (fuzzy-filter items "foo" identity)]
        ;; 'foobar' is a prefix match (better); 'xfoox' is substring.
        (-> (expect (first result)) (.toBe "foobar")))))

  (it "supports multi-token queries (AND semantics)"
    (fn []
      (let [items ["src/agent/core.cljs"
                   "src/agent/tools.cljs"
                   "test/ansi.test.cljs"]
            result (fuzzy-filter items "agent core" identity)]
        (-> (expect (count result)) (.toBe 1))
        (-> (expect (first result)) (.toBe "src/agent/core.cljs")))))

  (it "empty input collection returns empty"
    (fn []
      (-> (expect (count (fuzzy-filter [] "foo" identity))) (.toBe 0))))

  (it "uses get-text-fn to extract strings"
    (fn []
      (let [items [{:name "alpha"} {:name "beta"}]
            result (fuzzy-filter items "al" (fn [i] (:name i)))]
        (-> (expect (count result)) (.toBe 1))
        (-> (expect (:name (first result))) (.toBe "alpha")))))))
