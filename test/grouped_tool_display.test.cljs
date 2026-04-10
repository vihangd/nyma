(ns grouped-tool-display.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/tool_status.jsx" :refer [group_messages tool_group_label]]))

;;; ─── group_messages ─────────────────────────────────────

(describe "grouped-tool-display:group_messages" (fn []
  (it "groups 3 consecutive reads into 1 tool-group"
    (fn []
      (let [msgs [{:role "tool-end" :tool-name "read" :args {:path "a.cljs"} :duration 10 :result "..."}
                  {:role "tool-end" :tool-name "read" :args {:path "b.cljs"} :duration 15 :result "..."}
                  {:role "tool-end" :tool-name "read" :args {:path "c.cljs"} :duration 12 :result "..."}]
            grouped (group_messages msgs)]
        (-> (expect (count grouped)) (.toBe 1))
        (-> (expect (:role (first grouped))) (.toBe "tool-group"))
        (-> (expect (:tool-name (first grouped))) (.toBe "read"))
        (-> (expect (count (:items (first grouped)))) (.toBe 3)))))

  (it "does not group a single tool call"
    (fn []
      (let [msgs [{:role "tool-end" :tool-name "read" :args {:path "a.cljs"} :duration 10 :result "..."}]
            grouped (group_messages msgs)]
        (-> (expect (count grouped)) (.toBe 1))
        (-> (expect (:role (first grouped))) (.toBe "tool-end")))))

  (it "does not group non-groupable tools (bash, write, edit)"
    (fn []
      (let [msgs [{:role "tool-end" :tool-name "bash" :args {:command "ls"} :duration 10 :result "..."}
                  {:role "tool-end" :tool-name "bash" :args {:command "pwd"} :duration 5 :result "..."}]
            grouped (group_messages msgs)]
        (-> (expect (count grouped)) (.toBe 2))
        (-> (expect (:role (first grouped))) (.toBe "tool-end")))))

  (it "breaks grouping across different tool types"
    (fn []
      (let [msgs [{:role "tool-end" :tool-name "read" :args {:path "a.cljs"} :duration 10 :result ""}
                  {:role "tool-end" :tool-name "read" :args {:path "b.cljs"} :duration 10 :result ""}
                  {:role "tool-end" :tool-name "grep" :args {:pattern "x"} :duration 10 :result ""}
                  {:role "tool-end" :tool-name "grep" :args {:pattern "y"} :duration 10 :result ""}]
            grouped (group_messages msgs)]
        (-> (expect (count grouped)) (.toBe 2))
        (-> (expect (:tool-name (first grouped))) (.toBe "read"))
        (-> (expect (:tool-name (second grouped))) (.toBe "grep")))))

  (it "interleaved assistant message breaks grouping"
    (fn []
      (let [msgs [{:role "tool-end" :tool-name "read" :args {:path "a.cljs"} :duration 10 :result ""}
                  {:role "assistant" :content "thinking..."}
                  {:role "tool-end" :tool-name "read" :args {:path "b.cljs"} :duration 10 :result ""}]
            grouped (group_messages msgs)]
        ;; Should be 3: single read, assistant, single read
        (-> (expect (count grouped)) (.toBe 3)))))

  (it "preserves non-tool messages unchanged"
    (fn []
      (let [msgs [{:role "user" :content "hello"}
                  {:role "assistant" :content "hi"}]
            grouped (group_messages msgs)]
        (-> (expect (count grouped)) (.toBe 2))
        (-> (expect (:role (first grouped))) (.toBe "user")))))

  (it "groups glob calls"
    (fn []
      (let [msgs [{:role "tool-end" :tool-name "glob" :args {:pattern "*.cljs"} :duration 5 :result ""}
                  {:role "tool-end" :tool-name "glob" :args {:pattern "*.ts"} :duration 5 :result ""}]
            grouped (group_messages msgs)]
        (-> (expect (count grouped)) (.toBe 1))
        (-> (expect (:role (first grouped))) (.toBe "tool-group")))))

  (it "handles empty message list"
    (fn []
      (-> (expect (count (group_messages []))) (.toBe 0))))))

;;; ─── tool_group_label ───────────────────────────────────

(describe "grouped-tool-display:tool_group_label" (fn []
  (it "extracts path for read tool"
    (fn []
      (-> (expect (tool_group_label "read" {:path "src/foo.cljs"}))
          (.toBe "src/foo.cljs"))))

  (it "extracts pattern for glob tool"
    (fn []
      (-> (expect (tool_group_label "glob" {:pattern "**/*.ts"}))
          (.toBe "**/*.ts"))))

  (it "wraps pattern in quotes for grep tool"
    (fn []
      (-> (expect (tool_group_label "grep" {:pattern "hello"}))
          (.toBe "\"hello\""))))

  (it "returns . for ls with no path"
    (fn []
      (-> (expect (tool_group_label "ls" {}))
          (.toBe "."))))

  (it "appends corrected-path annotation when present"
    (fn []
      (-> (expect (tool_group_label "read" {:path "src/foo.cljs"
                                            :corrected-path "src/fo.cljs"}))
          (.toBe "src/foo.cljs (was: src/fo.cljs)"))))

  (it "applies corrected-path annotation to grep too"
    (fn []
      (-> (expect (tool_group_label "grep" {:pattern "hello"
                                            :corrected-path "src/old.cljs"}))
          (.toBe "\"hello\" (was: src/old.cljs)"))))

  (it "no annotation when corrected-path is absent"
    (fn []
      (-> (expect (tool_group_label "read" {:path "a.cljs"}))
          (.toBe "a.cljs"))))))
