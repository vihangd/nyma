(ns bracketed-paste.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.bracketed-paste :refer [create-paste-handler
                                               format-marker
                                               expand-paste-markers]]))

(def START "\u001b[200~")
(def END   "\u001b[201~")

;;; ─── format-marker ──────────────────────────────────────

(describe "format-marker" (fn []
  (it "counts 1 line for single-line content"
    (fn []
      (-> (expect (format-marker 1 "hello")) (.toBe "[paste #1 +1 lines]"))))

  (it "counts N lines for multi-line content"
    (fn []
      (-> (expect (format-marker 2 "a\nb\nc")) (.toBe "[paste #2 +3 lines]"))))

  (it "counts 0 lines for empty content"
    (fn []
      (-> (expect (format-marker 3 "")) (.toBe "[paste #3 +0 lines]"))))))

;;; ─── create-paste-handler :process ──────────────────────

(describe "paste handler: process" (fn []
  (it "passes through plain text unchanged"
    (fn []
      (let [{:keys [process]} (create-paste-handler)
            result            (process "hello world")]
        (-> (expect (:handled result)) (.toBe false))
        (-> (expect (:passthrough result)) (.toBe "hello world")))))

  (it "captures a complete single-chunk paste"
    (fn []
      (let [{:keys [process pastes]} (create-paste-handler)
            data    (str START "line1\nline2\nline3" END)
            result  (process data)]
        (-> (expect (:handled result)) (.toBe true))
        (-> (expect (:marker result)) (.toBe "[paste #1 +3 lines]"))
        (-> (expect (:content result)) (.toBe "line1\nline2\nline3"))
        (-> (expect (:id result)) (.toBe 1))
        (-> (expect (:passthrough result)) (.toBe ""))
        (-> (expect (get @pastes 1)) (.toBe "line1\nline2\nline3")))))

  (it "preserves pre and post text around a paste in the same chunk"
    (fn []
      (let [{:keys [process]} (create-paste-handler)
            data    (str "pre" START "body" END "post")
            result  (process data)]
        (-> (expect (:handled result)) (.toBe true))
        ;; passthrough carries the non-paste characters
        (-> (expect (:passthrough result)) (.toBe "prepost")))))

  (it "buffers a paste split across two chunks"
    (fn []
      (let [{:keys [process pastes]} (create-paste-handler)
            r1 (process (str START "chunk-one"))
            r2 (process (str "chunk-two" END))]
        ;; First chunk is inside a paste but has no end marker yet
        (-> (expect (:handled r1)) (.toBe true))
        (-> (expect (:marker r1)) (.toBe js/undefined))
        ;; Second chunk completes the paste
        (-> (expect (:handled r2)) (.toBe true))
        (-> (expect (:content r2)) (.toBe "chunk-onechunk-two"))
        (-> (expect (:marker r2)) (.toBe "[paste #1 +1 lines]"))
        (-> (expect (get @pastes 1)) (.toBe "chunk-onechunk-two")))))

  (it "increments id for successive pastes"
    (fn []
      (let [{:keys [process pastes]} (create-paste-handler)
            _ (process (str START "first" END))
            r (process (str START "second" END))]
        (-> (expect (:id r)) (.toBe 2))
        (-> (expect (count @pastes)) (.toBe 2))
        (-> (expect (get @pastes 1)) (.toBe "first"))
        (-> (expect (get @pastes 2)) (.toBe "second")))))

  (it "handles an empty paste body"
    (fn []
      (let [{:keys [process pastes]} (create-paste-handler)
            r (process (str START END))]
        (-> (expect (:content r)) (.toBe ""))
        (-> (expect (:marker r)) (.toBe "[paste #1 +0 lines]"))
        (-> (expect (get @pastes 1)) (.toBe "")))))))

;;; ─── expand-paste-markers ──────────────────────────────

(describe "expand-paste-markers" (fn []
  (it "leaves text without markers unchanged"
    (fn []
      (-> (expect (expand-paste-markers "hello" {1 "ignored"}))
          (.toBe "hello"))))

  (it "replaces a single marker with its content"
    (fn []
      (-> (expect (expand-paste-markers "before [paste #1 +2 lines] after"
                                         {1 "line-a\nline-b"}))
          (.toBe "before line-a\nline-b after"))))

  (it "replaces multiple markers"
    (fn []
      (let [text   "a [paste #1 +1 lines] b [paste #2 +1 lines] c"
            pastes {1 "ONE" 2 "TWO"}]
        (-> (expect (expand-paste-markers text pastes))
            (.toBe "a ONE b TWO c")))))

  (it "leaves unknown marker ids intact"
    (fn []
      (-> (expect (expand-paste-markers "x [paste #7 +1 lines] y" {}))
          (.toBe "x [paste #7 +1 lines] y"))))

  (it "handles empty string"
    (fn []
      (-> (expect (expand-paste-markers "" {1 "x"})) (.toBe ""))))

  (it "handles nil"
    (fn []
      (-> (expect (expand-paste-markers nil {})) (.toBe ""))))))
