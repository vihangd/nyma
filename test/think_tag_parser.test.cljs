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
                    (-> (expect (.-text result)) (.toBe "answer")))))))

(describe "think_tag_parser/strip-think-tags"
          (fn []
            (it "returns clean text without tags"
                (fn []
                  (-> (expect (strip-think-tags "<think>hidden</think>visible"))
                      (.toBe "visible"))))

            (it "empty string passthrough"
                (fn []
                  (-> (expect (strip-think-tags "")) (.toBe ""))))

            (it "no tags passthrough"
                (fn []
                  (-> (expect (strip-think-tags "plain")) (.toBe "plain"))))))
