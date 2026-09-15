(ns think-tag-parser.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/think_tag_parser.mjs" :refer [split-think-blocks strip-think-tags]]))

(describe "think_tag_parser/split-think-blocks"
          (fn []
            (it "basic: extracts closed <think> block"
                (fn []
                  (let [result (split-think-blocks "a<think>b</think>c")]
                    (-> (expect (.-reasoning result)) (.toBe "b"))
                    (-> (expect (.-text result)) (.toBe "ac")))))

            (it "<thinking> variant works the same"
                (fn []
                  (let [result (split-think-blocks "x<thinking>inner</thinking>y")]
                    (-> (expect (.-reasoning result)) (.toBe "inner"))
                    (-> (expect (.-text result)) (.toBe "xy")))))

            (it "case-insensitive: <THINK> is tolerated"
                (fn []
                  (let [result (split-think-blocks "<THINK>upper</THINK>answer")]
                    (-> (expect (.-reasoning result)) (.toBe "upper"))
                    (-> (expect (.-text result)) (.toBe "answer")))))

            (it "multi-block: joins reasoning with \\n\\n"
                (fn []
                  (let [result (split-think-blocks "<think>a</think>mid<think>b</think>end")]
                    (-> (expect (.-reasoning result)) (.toBe "a\n\nb"))
                    (-> (expect (.-text result)) (.toBe "midend")))))

            (it "unterminated trailing block (streaming): splits at opener"
                (fn []
                  (let [result (split-think-blocks "a<think>partial")]
                    (-> (expect (.-reasoning result)) (.toBe "partial"))
                    (-> (expect (.-text result)) (.toBe "a")))))

            (it "unterminated after a closed block"
                (fn []
                  (let [result (split-think-blocks "<think>x</think>mid<think>still")]
                    (-> (expect (.-reasoning result)) (.toBe "x\n\nstill"))
                    (-> (expect (.-text result)) (.toBe "mid")))))

            (it "no tags: returns text unchanged with empty reasoning"
                (fn []
                  (let [result (split-think-blocks "just plain text")]
                    (-> (expect (.-reasoning result)) (.toBe ""))
                    (-> (expect (.-text result)) (.toBe "just plain text")))))

            (it "empty input: returns empty strings"
                (fn []
                  (let [result (split-think-blocks "")]
                    (-> (expect (.-reasoning result)) (.toBe ""))
                    (-> (expect (.-text result)) (.toBe "")))))

            (it "multiline content inside think block"
                (fn []
                  (let [result (split-think-blocks "before<think>line1\nline2\nline3</think>after")]
                    (-> (expect (.-reasoning result)) (.toBe "line1\nline2\nline3"))
                    (-> (expect (.-text result)) (.toBe "beforeafter")))))

            (it "whitespace between closing tag and text is consumed"
                (fn []
                  (let [result (split-think-blocks "<think>r</think>   answer")]
                    (-> (expect (.-text result)) (.toBe "answer")))))

            ;; poolside/Laguna: template pre-fills the opening <think>, so a bare
            ;; </think> arrives with no opener. The two shapes seen live:
            (it "orphan closer after thinking text (no opener)"
                (fn []
                  (let [result (split-think-blocks "The user is asking about routes...</think>")]
                    (-> (expect (.-reasoning result)) (.toBe "The user is asking about routes..."))
                    (-> (expect (.-text result)) (.toBe ""))
                    (-> (expect (.-text result)) (.not.toContain "</think>")))))

            (it "orphan closer at start (no thinking this step)"
                (fn []
                  (let [result (split-think-blocks "</think>This project has 7 routes:")]
                    (-> (expect (.-reasoning result)) (.toBe ""))
                    (-> (expect (.-text result)) (.toBe "This project has 7 routes:"))
                    (-> (expect (.-text result)) (.not.toContain "</think>")))))

            (it "orphan </thinking> variant"
                (fn []
                  (let [result (split-think-blocks "reasoning here</thinking>the answer")]
                    (-> (expect (.-reasoning result)) (.toBe "reasoning here"))
                    (-> (expect (.-text result)) (.toBe "the answer")))))

            ;; A model that pairs its tags never emits an orphan, so a stray
            ;; closer beside a closed pair is literal text, not a prefill artifact.
            (it "closed pair present ⇒ later stray closer is left in text"
                (fn []
                  (let [result (split-think-blocks "<think>A</think>B</think>C")]
                    (-> (expect (.-reasoning result)) (.toBe "A"))
                    (-> (expect (.-text result)) (.toBe "B</think>C")))))))

(describe "think_tag_parser/strip-think-tags"
          (fn []
            (it "returns clean text without tags"
                (fn []
                  (-> (expect (strip-think-tags "<think>hidden</think>visible"))
                      (.toBe "visible"))))

            ;; Output is PERSISTED (session + smart compaction). An orphan-closer
            ;; false positive here would permanently drop the preceding text, so
            ;; the conservative path must leave a literal </think> alone.
            (it "does NOT apply orphan-closer handling (persisted callers)"
                (fn []
                  (let [src "close the block with </think> when done"]
                    (-> (expect (strip-think-tags src)) (.toBe src)))))

            (it "empty string passthrough"
                (fn []
                  (-> (expect (strip-think-tags "")) (.toBe ""))))

            (it "no tags passthrough"
                (fn []
                  (-> (expect (strip-think-tags "plain")) (.toBe "plain"))))))


;; The renderer re-parses the ACCUMULATED text on every chunk, so a tag split
;; across chunks must classify the same way at each prefix as it does whole.
(def ^:private stream-table
  ;; [accumulated-text expected-reasoning expected-text]
  [["<thi"                              ""          "<thi"]
   ["<think"                            ""          "<think"]
   ["<think>"                           ""          ""]
   ["<think>plan"                       "plan"      ""]
   ["<think>plan</thi"                  "plan</thi" ""]
   ["<think>plan</think>"               "plan"      ""]
   ["<think>plan</think>ans"            "plan"      "ans"]
   ["<think>plan</think>ans<think>more" "plan\n\nmore" "ans"]
   ;; nested / literal text inside the block is reasoning, not a second tag
   ["<think>a <b> c</think>x"           "a <b> c"   "x"]
   ["<think>outer <think>inner</think>" "outer <think>inner" ""]])

(describe "think_tag_parser/split-think-blocks on streamed prefixes"
          (fn []
            (doseq [[text reasoning clean] stream-table]
              (it (str (pr-str text) " → reasoning " (pr-str reasoning) ", text " (pr-str clean))
                  (fn []
                    (let [r (split-think-blocks text)]
                      (-> (expect (.-reasoning r)) (.toBe reasoning))
                      (-> (expect (.-text r)) (.toBe clean))))))))
