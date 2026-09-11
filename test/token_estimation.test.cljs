(ns token-estimation.test
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.token-estimation :refer [estimate-tokens estimate-messages-tokens
                                            count-non-alpha]]))

(describe "estimate-tokens" (fn []

  (it "estimates prose tokens within 25% of 3.8 chars/token"
    (fn []
      (let [text "The quick brown fox jumps over the lazy dog and runs away"
            est (estimate-tokens text)
            expected (js/Math.ceil (/ (count text) 3.8))]
        ;; Within 25%
        (-> (expect (js/Math.abs (- est expected))) (.toBeLessThan (* expected 0.25))))))

  (it "estimates code tokens within 25% of 3.2 chars/token"
    (fn []
      (let [text "(defn foo [{:keys [bar baz]}]\n  (+ bar (* baz 2)))"
            est (estimate-tokens text)
            expected (js/Math.ceil (/ (count text) 3.2))]
        (-> (expect (js/Math.abs (- est expected))) (.toBeLessThan (* expected 0.25))))))

  (it "returns 0 for empty string"
    (fn []
      (-> (expect (estimate-tokens "")) (.toBe 0))))

  (it "returns 0 for nil input"
    (fn []
      (-> (expect (estimate-tokens nil)) (.toBe 0))))

  (it "returns integer (not float)"
    (fn []
      (let [est (estimate-tokens "hello world")]
        (-> (expect (js/Number.isInteger est)) (.toBe true)))))))

(describe "estimate-messages-tokens" (fn []

  (it "adds per-message overhead"
    (fn []
      (let [msgs [{:role "user" :content "hi"}
                   {:role "assistant" :content "hello"}]
            est (estimate-messages-tokens msgs)
            ;; Should be more than just content estimates
            content-only (+ (estimate-tokens "hi") (estimate-tokens "hello"))]
        (-> (expect est) (.toBeGreaterThan content-only)))))

  (it "handles mix of CLJ maps and JS objects"
    (fn []
      (let [msgs [#js {:content "from js object"}
                   {:role "user" :content "from clj map"}]
            est (estimate-messages-tokens msgs)]
        (-> (expect est) (.toBeGreaterThan 0)))))))

;;; ─── The rewrite ────────────────────────────────────────────
;;; `(count (re-seq #"[^a-zA-Z\s]" s))` allocated one string per non-alpha
;;; character — ~100k of them to measure a 230 KB message, 13.8ms a call, from
;;; sixteen call sites that mostly walk every message every turn. The charCode
;;; loop below is ~1.5ms and allocates nothing. These tests pin the two things
;;; that could go wrong in the swap: a different answer, or a slow return.

(defn- re-seq-count
  "The implementation that shipped, kept here as the differential oracle."
  [s]
  (count (re-seq #"[^a-zA-Z\s]" (str s))))

(describe "count-non-alpha matches the regex it replaced" (fn []

  (it "agrees on whitespace the narrow four-code version would miss"
    (fn []
      ;; nbsp, VT, FF, line/paragraph separators, ideographic space, BOM — all
      ;; whitespace to JS `\s`, and all ordinary in web_fetch output. Counting
      ;; them as non-alpha inflates the ratio, flips the code branch, and
      ;; compacts early.
      (doseq [c [160 11 12 8232 8233 8239 8287 12288 65279 5760 8194]]
        (let [s (str "word" (js/String.fromCharCode c) "word")]
          (-> (expect (count-non-alpha s (count s))) (.toBe (re-seq-count s)))))))

  (it "agrees on prose, code, unicode and the empty string"
    (fn []
      (doseq [s ["" "hello world"
                 "(defn foo [{:keys [bar]}] (+ bar 2))"
                 "emoji 🎉 and accents éàü, CJK 日本語"
                 "tabs\tand\nnewlines\r\n"]]
        (-> (expect (count-non-alpha s (count s))) (.toBe (re-seq-count s))))))

  ;; The corpus is the real test: whatever the crafted cases miss, 8.7MB of
  ;; actual transcripts will have. Skipped rather than failed when absent, so a
  ;; fresh machine is not red for lacking history.
  (it "agrees on every message in the local session corpus"
    (fn []
      (let [dir (str (.. js/process -env -HOME) "/.nyma/sessions")]
        (if-not (fs/existsSync dir)
          (-> (expect true) (.toBe true))
          (let [files (filterv #(.endsWith % ".jsonl") (vec (fs/readdirSync dir)))
                mismatches
                (reduce
                 (fn [acc f]
                   (reduce
                    (fn [acc line]
                      (if (empty? (.trim line))
                        acc
                        (let [e (try (js/JSON.parse line) (catch :default _ nil))
                              c (when e (let [v (.-content e)]
                                          (if (string? v) v (js/JSON.stringify (or v "")))))]
                          (if (and c (not= (count-non-alpha c (count c)) (re-seq-count c)))
                            (inc acc)
                            acc))))
                    acc
                    (vec (.split (fs/readFileSync (path/join dir f) "utf8") "\n"))))
                 0 files)]
            (-> (expect mismatches) (.toBe 0)))))))

  (it "measures a 230KB string in well under the old 13.8ms"
    (fn []
      ;; Loose on purpose: this fails on a return to allocation, not on a slow
      ;; machine.
      (let [s     (str (.repeat "x" 50000) (.repeat "() => {};" 20000))
            start (js/Date.now)]
        (dotimes [_ 5] (estimate-tokens s))
        (-> (expect (/ (- (js/Date.now) start) 5)) (.toBeLessThan 6)))))))
