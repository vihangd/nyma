(ns tool-status.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/tool_status.jsx" :refer [truncate_lines format_args format_result format_duration]]))

(describe "format-duration" (fn []
  (it "formats sub-second as milliseconds"
    (fn []
      (-> (expect (format_duration 500)) (.toBe "500ms"))))

  (it "formats seconds with one decimal"
    (fn []
      (-> (expect (format_duration 1500)) (.toBe "1.5s"))))

  (it "formats exactly one second"
    (fn []
      (-> (expect (format_duration 1000)) (.toBe "1.0s"))))

  (it "handles nil"
    (fn []
      (-> (expect (format_duration nil)) (.toBe ""))))))

(describe "truncate-lines" (fn []
  (it "passes through short text"
    (fn []
      (let [text "line1\nline2"]
        (-> (expect (truncate_lines text 5)) (.toBe text)))))

  (it "truncates and shows remaining count"
    (fn []
      (let [text "a\nb\nc\nd\ne\nf\ng\nh\ni\nj"
            result (truncate_lines text 3)]
        (-> (expect (.startsWith result "a\nb\nc\n")) (.toBe true))
        (-> (expect (.includes result "7 more lines")) (.toBe true)))))

  (it "handles nil"
    (fn []
      (-> (expect (truncate_lines nil 10)) (.toBe ""))))

  (it "handles single line within limit"
    (fn []
      (-> (expect (truncate_lines "one" 1)) (.toBe "one"))))

  (it "handles exact boundary"
    (fn []
      (let [text "a\nb\nc"]
        (-> (expect (truncate_lines text 3)) (.toBe text)))))))

(describe "format-args" (fn []
  (it "collapsed returns empty string"
    (fn []
      (-> (expect (format_args {:path "/tmp"} "collapsed")) (.toBe ""))))

  (it "summary returns first 3 lines of JSON"
    (fn []
      (let [args {:path "/tmp" :command "echo hello" :timeout 5000}
            result (format_args args "summary")]
        ;; JSON.stringify with indent 2 produces multiple lines
        ;; summary should show at most 3 + truncation message
        (-> (expect (<= (count (.split result "\n")) 4)) (.toBe true)))))

  (it "full returns complete JSON"
    (fn []
      (let [args {:path "/tmp/test.txt"}
            result (format_args args "full")]
        (-> (expect (.includes result "/tmp/test.txt")) (.toBe true)))))))

(describe "format-result" (fn []
  (it "collapsed returns empty string"
    (fn []
      (-> (expect (format_result "lots of output" "collapsed" 500)) (.toBe ""))))

  (it "summary returns first 5 lines"
    (fn []
      (let [text "1\n2\n3\n4\n5\n6\n7\n8\n9\n10"
            result (format_result text "summary" 500)]
        (-> (expect (.includes result "1")) (.toBe true))
        (-> (expect (.includes result "5")) (.toBe true))
        (-> (expect (.includes result "5 more lines")) (.toBe true)))))

  (it "full caps at max-lines"
    (fn []
      (let [text (.join (js/Array.from #js {:length 100} (fn [_ i] (str "line" i))) "\n")
            result (format_result text "full" 10)]
        (-> (expect (.includes result "90 more lines")) (.toBe true)))))

  (it "respects custom max-lines value"
    (fn []
      (let [text "a\nb\nc\nd\ne\nf"
            result (format_result text "full" 3)]
        (-> (expect (.includes result "3 more lines")) (.toBe true)))))

  (it "full with nil max-lines defaults to 500"
    (fn []
      (let [text "short"]
        (-> (expect (format_result text "full" nil)) (.toBe "short")))))))
