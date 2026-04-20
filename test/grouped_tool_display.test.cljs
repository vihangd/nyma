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
                                                        (-> (expect (count (group_messages []))) (.toBe 0))))

  ;; Bug-2 regression: before the fix, tool-end messages had :args nil because
  ;; on-end in app.cljs never copied them from the matching start message.
  ;; group-messages builds each grouped item from (:args msg) on the end
  ;; message, so nil args meant tool-group-label always hit its "?" fallback.
                                                  (it "grouped items with nil :args produce '?' labels (documents the fallback)"
                                                      (fn []
                                                        (let [msgs [{:role "tool-end" :tool-name "glob" :args nil :duration 5 :result "a.cljs"}
                                                                    {:role "tool-end" :tool-name "glob" :args nil :duration 5 :result "b.cljs"}]
                                                              group (first (group_messages msgs))]
        ;; Confirm grouping still fires
                                                          (-> (expect (:role group)) (.toBe "tool-group"))
        ;; Each label hits the "?" fallback because args is nil — this is
        ;; exactly what the user saw before the fix
                                                          (doseq [item (:items group)]
                                                            (-> (expect (tool_group_label "glob" (:args item))) (.toBe "?"))))))))

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

;;; ─── group-messages prefix stability ───────────────────────
;;; When the finalized slice grows by appending new messages, the
;;; grouping of the earlier prefix must not change. This is the
;;; property that makes <Static> safe: committed grouped output
;;; stays correct when new messages arrive.

(describe "grouped-tool-display:group-messages prefix stability"
          (fn []

            (it "prefix grouping is stable when a new non-groupable message appends"
                ;; [user, read×3, assistant] → [user, tool-group{3}, assistant]
                ;; Appending [user-2] must not change the first 3 output items.
                (fn []
                  (let [base  [{:role "user"     :content "q"}
                              {:role "tool-end" :tool-name "read" :args {:path "a"} :duration 1 :result "a"}
                                {:role "tool-end" :tool-name "read" :args {:path "b"} :duration 1 :result "b"}
                                {:role "tool-end" :tool-name "read" :args {:path "c"} :duration 1 :result "c"}
                                {:role "assistant" :content "done"}]
                        ext   (conj base {:role "user" :content "more"})
                        g1    (group_messages base)
                        g2    (group_messages ext)]
                    ;; Both outputs have the same prefix shape
                    (-> (expect (count g1)) (.toBe 3))              ; user, tool-group, assistant
                    (-> (expect (count g2)) (.toBe 4))              ; + user-2
                    ;; The first 3 items are structurally identical
                    (-> (expect (:role (first g1)))   (.toBe (:role (first g2))))
                    (-> (expect (:role (second g1)))  (.toBe (:role (second g2))))
                    (-> (expect (:role (nth g1 2)))   (.toBe (:role (nth g2 2))))
                    ;; The tool-group item count is unchanged
                    (-> (expect (count (:items (second g1)))) (.toBe (count (:items (second g2))))))))

            (it "prefix grouping is stable when a new groupable message appends to a different tool"
                ;; A glob-end appended after read×3+assistant must not merge into the read group.
                (fn []
                  (let [base  [{:role "tool-end" :tool-name "read" :args {:path "a"} :duration 1 :result "a"}
                              {:role "tool-end" :tool-name "read" :args {:path "b"} :duration 1 :result "b"}
                                {:role "tool-end" :tool-name "read" :args {:path "c"} :duration 1 :result "c"}
                                {:role "assistant" :content "done"}]
                        ext   (conj base
                                    {:role "tool-end" :tool-name "glob" :args {:pattern "*.ts"} :duration 1 :result ""})
                        g1    (group_messages base)
                        g2    (group_messages ext)]
                    ;; Base: [read-group, assistant]
                    (-> (expect (:tool-name (first g1))) (.toBe "read"))
                    ;; Extended: [read-group, assistant, glob-end] — read-group unchanged
                    (-> (expect (:tool-name (first g2))) (.toBe "read"))
                    (-> (expect (count (:items (first g2)))) (.toBe 3)))))

            (it "a lone tool-end before the minimum is preserved when followed by a different tool"
                ;; A single read-end (below the min-2 threshold) followed by a glob-end
                ;; must not be retroactively merged if another read-end later appears.
                (fn []
                  (let [msgs  [{:role "tool-end" :tool-name "read" :args {:path "x"} :duration 1 :result "x"}
                              {:role "tool-end" :tool-name "glob" :args {:pattern "*"} :duration 1 :result ""}
                                {:role "tool-end" :tool-name "glob" :args {:pattern "*.ts"} :duration 1 :result ""}]
                        g     (group_messages msgs)]
                    ;; The lone read does not group; the 2 globs do
                    (-> (expect (count g)) (.toBe 2))
                    (-> (expect (:role (first g)))     (.toBe "tool-end"))   ; lone read
                    (-> (expect (:role (second g)))    (.toBe "tool-group")) ; glob pair
                    (-> (expect (:tool-name (second g))) (.toBe "glob")))))))
