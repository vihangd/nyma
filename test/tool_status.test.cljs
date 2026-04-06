(ns tool-status.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/tool_status.jsx" :refer [truncate_lines format_args format_result format_duration
                                                   format_one_line_args format_one_line_result]]))

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
        (-> (expect (format_result text "full" nil)) (.toBe "short")))))

  (it "one-line returns empty string (handled by component)"
    (fn []
      (-> (expect (format_result "lots of output" "one-line" 500)) (.toBe ""))))))

(describe "format-one-line-args" (fn []
  (it "bash shows command"
    (fn []
      (-> (expect (format_one_line_args "bash" {:command "ls -la /tmp"}))
          (.toBe "ls -la /tmp"))))

  (it "bash shows first line of multi-line command"
    (fn []
      (let [result (format_one_line_args "bash" {:command "echo hello\necho world"})]
        (-> (expect result) (.toBe "echo hello")))))

  (it "read shows path"
    (fn []
      (-> (expect (format_one_line_args "read" {:path "src/main.ts"}))
          (.toBe "src/main.ts"))))

  (it "read shows path with range"
    (fn []
      (-> (expect (format_one_line_args "read" {:path "src/main.ts" :range [10 50]}))
          (.toBe "src/main.ts:10-50"))))

  (it "write shows path"
    (fn []
      (-> (expect (format_one_line_args "write" {:path "out.txt" :content "data"}))
          (.toBe "out.txt"))))

  (it "edit shows path"
    (fn []
      (-> (expect (format_one_line_args "edit" {:path "src/app.ts" :old_string "foo" :new_string "bar"}))
          (.toBe "src/app.ts"))))

  (it "ls shows path or dot"
    (fn []
      (-> (expect (format_one_line_args "ls" {:path "/tmp"})) (.toBe "/tmp"))
      (-> (expect (format_one_line_args "ls" {})) (.toBe "."))))

  (it "glob shows pattern"
    (fn []
      (-> (expect (format_one_line_args "glob" {:pattern "**/*.tsx"}))
          (.toBe "**/*.tsx"))))

  (it "glob shows pattern with path"
    (fn []
      (-> (expect (format_one_line_args "glob" {:pattern "*.ts" :path "src/"}))
          (.toBe "*.ts in src/"))))

  (it "grep shows quoted pattern"
    (fn []
      (-> (expect (format_one_line_args "grep" {:pattern "TODO"}))
          (.toBe "\"TODO\""))))

  (it "grep shows pattern with path"
    (fn []
      (-> (expect (format_one_line_args "grep" {:pattern "TODO" :path "src/"}))
          (.toBe "\"TODO\" in src/"))))

  (it "web_fetch shows url"
    (fn []
      (-> (expect (format_one_line_args "web_fetch" {:url "https://example.com"}))
          (.toBe "https://example.com"))))

  (it "web_search shows quoted query"
    (fn []
      (-> (expect (format_one_line_args "web_search" {:query "bun runtime"}))
          (.toBe "\"bun runtime\""))))

  (it "think shows first line of thought"
    (fn []
      (-> (expect (format_one_line_args "think" {:thought "Consider the options\nThen decide"}))
          (.toBe "Consider the options"))))

  (it "unknown tool falls back to key=value pairs"
    (fn []
      (let [result (format_one_line_args "custom_tool" {:foo "bar" :baz 42})]
        (-> (expect (.includes result "foo=bar")) (.toBe true))
        (-> (expect (.includes result "baz=42")) (.toBe true)))))

  (it "truncates long values"
    (fn []
      (let [long-cmd (apply str (repeat 200 "x"))
            result   (format_one_line_args "bash" {:command long-cmd})]
        (-> (expect (.endsWith result "…")) (.toBe true))
        (-> (expect (< (count result) 200)) (.toBe true)))))

  (it "handles nil args gracefully"
    (fn []
      (let [result (format_one_line_args "bash" {:command nil})]
        (-> (expect result) (.toBe "")))))

  (it "handles empty args gracefully"
    (fn []
      (let [result (format_one_line_args "bash" {})]
        (-> (expect result) (.toBe "")))))))

(describe "format-one-line-result" (fn []
  (it "multi-line result shows line count"
    (fn []
      (-> (expect (format_one_line_result "line1\nline2\nline3"))
          (.toBe "3 lines"))))

  (it "single short line shown directly"
    (fn []
      (-> (expect (format_one_line_result "hello world"))
          (.toBe "hello world"))))

  (it "nil returns empty string"
    (fn []
      (-> (expect (format_one_line_result nil)) (.toBe ""))))

  (it "empty string returns empty string"
    (fn []
      (-> (expect (format_one_line_result "")) (.toBe ""))))

  (it "long single line is truncated"
    (fn []
      (let [long-text (apply str (repeat 200 "x"))
            result    (format_one_line_result long-text)]
        (-> (expect (.endsWith result "…")) (.toBe true))
        (-> (expect (< (count result) 200)) (.toBe true)))))

  (it "two lines shows 2 lines"
    (fn []
      (-> (expect (format_one_line_result "a\nb")) (.toBe "2 lines")))))

(describe "format-args one-line delegation" (fn []
  (it "one-line returns empty string"
    (fn []
      (-> (expect (format_args {:path "/tmp"} "one-line")) (.toBe "")))))))
