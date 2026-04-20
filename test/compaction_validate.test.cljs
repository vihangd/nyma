(ns compaction-validate.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["./agent/sessions/compaction.mjs" :refer [validate-compaction compact-with-retry
                                                       build-fix-user-prompt]]
            [clojure.string :as str]))

;; ── Helpers ─────────────────────────────────────────────────────

(def ^:private valid-summary
  "## 1. Previous Conversation
User asked to implement a feature.

## 2. Current Work
Working on src/agent/foo.cljs:42

## 3. Key Technical Concepts
ClojureScript, Squint compiler.

## 4. Relevant Files and Code
src/agent/foo.cljs:42 — main entry point

## 5. Problem Solving
No errors encountered.

## 6. Pending Tasks and Next Steps
- finish the implementation
  Quote: \"let's implement the feature\"")

(def ^:private valid-files-read ["src/agent/foo.cljs"])
(def ^:private valid-files-modified [])

;; ── validate-compaction unit tests ──────────────────────────────

(describe "validate-compaction" (fn []
                                  (it "returns empty for a valid summary"
                                      (fn []
                                        (let [errors (validate-compaction valid-summary valid-files-read valid-files-modified)]
                                          (-> (expect (count errors)) (.toBe 0)))))

                                  (it "reports missing section header"
                                      (fn []
                                        (let [no-section3 (str/replace valid-summary "## 3. Key Technical Concepts" "## 3. Concepts")
                                              errors (validate-compaction no-section3 valid-files-read valid-files-modified)]
                                          (-> (expect (some #(str/includes? % "## 3. Key Technical Concepts") errors)) (.toBe true)))))

                                  (it "reports headers out of order"
                                      (fn []
        ;; swap section 1 and section 2 headings
                                        (let [swapped (-> valid-summary
                                                          (str/replace "## 1. Previous Conversation" "## 1. PLACEHOLDER")
                                                          (str/replace "## 2. Current Work" "## 1. Previous Conversation")
                                                          (str/replace "## 1. PLACEHOLDER" "## 2. Current Work"))
                                              errors (validate-compaction swapped valid-files-read valid-files-modified)]
          ;; Out-of-order means section 2 appears before section 1's header search advances
                                          (-> (expect (> (count errors) 0)) (.toBe true)))))

                                  (it "reports file path missing from summary"
                                      (fn []
                                        (let [errors (validate-compaction valid-summary
                                                                          ["src/agent/missing.cljs"]
                                                                          valid-files-modified)]
                                          (-> (expect (some #(str/includes? % "src/agent/missing.cljs") errors)) (.toBe true)))))

                                  (it "passes when no files to check"
                                      (fn []
                                        (let [errors (validate-compaction valid-summary [] [])]
                                          (-> (expect (count errors)) (.toBe 0)))))

                                  (it "reports section 6 missing quote when bullets exist"
                                      (fn []
                                        (let [no-quote (str/replace valid-summary
                                                                    "  Quote: \"let's implement the feature\""
                                                                    "")
                                              errors (validate-compaction no-quote valid-files-read valid-files-modified)]
                                          (-> (expect (some #(str/includes? % "verbatim quotes") errors)) (.toBe true)))))

                                  (it "passes section 6 when no bullets exist"
                                      (fn []
        ;; Section 6 with only prose — no bullets, no quote required
                                        (let [no-bullets (str/replace valid-summary
                                                                      "- finish the implementation\n  Quote: \"let's implement the feature\""
                                                                      "All tasks are complete.")
                                              errors (validate-compaction no-bullets valid-files-read valid-files-modified)]
                                          (-> (expect (count errors)) (.toBe 0)))))

                                  (it "handles completely empty summary"
                                      (fn []
                                        (let [errors (validate-compaction "" [] [])]
                                          (-> (expect (> (count errors) 0)) (.toBe true)))))))

;; ── compact-with-retry retry loop ──────────────────────────────

(def ^:private bad-summary "just some prose with no sections")

(def ^:private call-log (atom []))

(beforeEach (fn [] (reset! call-log [])))

(defn- make-gen
  "Returns a generateText stub that serves responses in order.
   Repeats the last response if called more times than responses provided."
  [& texts]
  (let [responses (vec texts)]
    (fn [_opts]
      (let [idx (count @call-log)
            text (nth responses idx (last responses))]
        (swap! call-log conj text)
        (js/Promise.resolve #js {:text text})))))

(defn ^:async test-retry-first-pass []
  ;; First call returns valid → no retry, call count 1
  (let [result (js-await (compact-with-retry "mock-model" "user prompt" [] [] (make-gen valid-summary)))]
    (-> (expect result) (.toBe valid-summary))
    (-> (expect (count @call-log)) (.toBe 1))))

(defn ^:async test-retry-fixes-bad-first []
  ;; First call invalid, second valid → retry fires, final text is second
  (let [result (js-await (compact-with-retry "mock-model" "user prompt" [] []
                                             (make-gen bad-summary valid-summary)))]
    (-> (expect result) (.toBe valid-summary))
    (-> (expect (count @call-log)) (.toBe 2))))

(defn ^:async test-retry-falls-back-when-both-bad []
  ;; Both calls invalid → still returns the second (unvalidated) text
  (let [result (js-await (compact-with-retry "mock-model" "user prompt" [] []
                                             (make-gen bad-summary bad-summary)))]
    (-> (expect result) (.toBe bad-summary))
    (-> (expect (count @call-log)) (.toBe 2))))

(defn ^:async test-retry-honors-files-list []
  ;; First response missing a required file path, second includes it
  (let [with-file    (str/replace valid-summary
                                  "src/agent/foo.cljs"
                                  "src/agent/specific.cljs")
        without-file (str/replace with-file
                                  "src/agent/specific.cljs"
                                  "src/agent/wrong.cljs")
        result       (js-await (compact-with-retry "mock-model" "user prompt"
                                                   ["src/agent/specific.cljs"] []
                                                   (make-gen without-file with-file)))]
    (-> (expect result) (.toBe with-file))
    (-> (expect (count @call-log)) (.toBe 2))))

(describe "compact-with-retry: retry loop" (fn []
                                             (it "first response valid — no retry" test-retry-first-pass)
                                             (it "first invalid, second valid — retry succeeds" test-retry-fixes-bad-first)
                                             (it "both invalid — falls back to unvalidated second response" test-retry-falls-back-when-both-bad)
                                             (it "honors files-read list in validation across retry" test-retry-honors-files-list)))

;; ── build-fix-user-prompt shape ─────────────────────────────────

(describe "build-fix-user-prompt" (fn []
                                    (it "instructs to not recompress and lists errors verbatim"
                                        (fn []
                                          (let [prompt (build-fix-user-prompt "old summary" ["err one" "err two"])]
                                            (-> (expect (.includes prompt "DO NOT recompress")) (.toBe true))
                                            (-> (expect (.includes prompt "DO NOT shorten")) (.toBe true))
                                            (-> (expect (.includes prompt "<previous-summary>\nold summary")) (.toBe true))
                                            (-> (expect (.includes prompt "- err one")) (.toBe true))
                                            (-> (expect (.includes prompt "- err two")) (.toBe true)))))))
