(ns chat-renderer.test
  "Tests for render-message — every role path must produce non-empty
   string[] output and must not throw (catches scope/variable bugs like
   the 'muted is not defined' regression in the info role)."
  (:require [test-util.text :refer [strip-ansi]]
             ["bun:test" :refer [describe it expect]]
            [agent.ui.chat-renderer :refer [render-message]]))

;;; ─── helpers ──────────────────────────────────────────────────────────────

(def ^:private theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"}})

(defn- render [msg]
  (render-message {:msg msg :width 80 :theme theme :md-cache nil}))

(defn- visible-text [lines]
  (strip-ansi (.join (to-array lines) "\n")))

;;; ─── user ─────────────────────────────────────────────────────────────────

(describe "chat-renderer/user" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "user" :content "hello" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes the content"
              (fn []
                (let [text (visible-text (render {:role "user" :content "hello world" :id "1"}))]
                  (-> (expect (.includes text "hello world")) (.toBe true)))))

          (it "includes the ❯ prompt marker"
              (fn []
                (let [text (visible-text (render {:role "user" :content "hi" :id "1"}))]
                  (-> (expect (.includes text "❯")) (.toBe true))))))

;;; ─── assistant ────────────────────────────────────────────────────────────

(describe "chat-renderer/assistant" (fn [])
          (it "returns at least the ● header line"
              (fn []
                (let [lines (render {:role "assistant" :content "" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes the ● bullet"
              (fn []
                (let [text (visible-text (render {:role "assistant" :content "hi" :id "1"}))]
                  (-> (expect (.includes text "●")) (.toBe true)))))

          (it "renders content"
              (fn []
                (let [text (visible-text (render {:role "assistant" :content "some answer" :id "1"}))]
                  (-> (expect (.includes text "some answer")) (.toBe true)))))

          (it "strips think tags from visible text"
              (fn []
                (let [text (visible-text (render {:role "assistant"
                                                  :content "<think>hidden</think>visible"
                                                  :id "1"}))]
                  (-> (expect (.includes text "visible")) (.toBe true)))))

          (it "indents continuation lines by 2 columns under the bullet"
              (fn []
                ;; Force a multi-line response by feeding an explicit \n.
                ;; First line has the "● " prefix; subsequent lines should
                ;; start with two spaces so the body aligns under the
                ;; first-line content (Claude Code convention).
                (let [lines (vec (render {:role "assistant"
                                          :content "first line\nsecond line\nthird line"
                                          :id "1"}))]
                  (-> (expect (pos? (count lines))) (.toBe true))
                  ;; First line has the ● bullet — visible text starts after
                  ;; the bullet+space. Continuation lines start with "  ".
                  (let [strip (fn [s] (.replace s (js/RegExp. "\u001b\\[[0-9;]*m" "g") ""))
                        cont-lines (vec (.slice (clj->js lines) 1))]
                    ;; At least one continuation line must exist for this
                    ;; test to be meaningful.
                    (-> (expect (pos? (.-length cont-lines))) (.toBe true))
                    (doseq [i (range (.-length cont-lines))]
                      (let [bare (strip (aget cont-lines i))]
                        ;; Skip blank lines (they don't need indentation).
                        (when (seq (.trim bare))
                          (-> (expect (.startsWith bare "  "))
                              (.toBe true))))))))))

;;; ─── tool-start ───────────────────────────────────────────────────────────

(describe "chat-renderer/tool-start" (fn [])
          (it "returns exactly one line"
              (fn []
                (let [lines (render {:role "tool-start" :tool-name "read"
                                     :args {:path "/foo.txt"} :id "1"})]
                  (-> (expect (count lines)) (.toBe 1)))))

          (it "includes tool name"
              (fn []
                (let [text (visible-text (render {:role "tool-start" :tool-name "read"
                                                  :args {:path "/foo.txt"} :id "1"}))]
                  (-> (expect (.includes text "read")) (.toBe true)))))

          (it "includes ⚙ icon"
              (fn []
                (let [text (visible-text (render {:role "tool-start" :tool-name "bash"
                                                  :args {:command "ls"} :id "1"}))]
                  (-> (expect (.includes text "⚙")) (.toBe true))))))

;;; ─── tool-end ─────────────────────────────────────────────────────────────

(describe "chat-renderer/tool-end" (fn [])
          (it "returns exactly one line"
              (fn []
                (let [lines (render {:role "tool-end" :tool-name "read"
                                     :args {:path "/foo.txt"} :result "line1\nline2" :id "1"})]
                  (-> (expect (count lines)) (.toBe 1)))))

          (it "includes ✓ icon"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "edit"
                                                  :args {:path "/f"} :result "" :id "1"}))]
                  (-> (expect (.includes text "✓")) (.toBe true)))))

          (it "shows duration when present"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "bash"
                                                  :args {} :result "" :duration 1500 :id "1"}))]
                  (-> (expect (.includes text "1.5s")) (.toBe true)))))

          ;;; ── a tool's own :display metadata ──────────────────────────
          ;;
          ;; middleware computes formatArgs/formatResult/icon and app_reducers
          ;; copies them onto the message. Nothing read them here, so every
          ;; extension tool fell through to the generic `k=v` branch — a
          ;; questionnaire call rendered as
          ;;   ✓ questionnaire__questionnaire questions=[object Object] · [object Object]
          ;; with the declared ❓ icon nowhere in sight. Registry written,
          ;; nobody reading it (roadmap 3a).

          (it "prefers the tool's own one-line args over the generic preview"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-end" :tool-name "questionnaire__questionnaire"
                                     :args {:questions [{:id "q1" :prompt "Q?"}]}
                                     :custom-one-line-args "1 question"
                                     :custom-one-line-result "1 answer"
                                     :result "User answered 1 question:\n  q1: blue"
                                     :id "1"}))]
                  (-> (expect (.includes text "1 question")) (.toBe true))
                  (-> (expect (.includes text "1 answer")) (.toBe true))
                  ;; The failure this test exists for.
                  (-> (expect (.includes text "[object Object]")) (.toBe false)))))

          (it "prefers the tool's own icon"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-end" :tool-name "questionnaire"
                                     :args {} :result "" :custom-icon "❓" :id "1"}))]
                  (-> (expect (.includes text "❓")) (.toBe true))
                  (-> (expect (.includes text "✓")) (.toBe false)))))

          (it "falls back to the generic preview when a tool declares nothing"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "read"
                                                  :args {:path "src/foo.cljs"}
                                                  :result "a\nb" :id "1"}))]
                  (-> (expect (.includes text "src/foo.cljs")) (.toBe true)))))

          (it "shows the read path on tool-end (args + result combined)"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "read"
                                                  :args {:path "src/foo.cljs"}
                                                  :result "a\nb\nc" :id "1"}))]
                  (-> (expect (.includes text "src/foo.cljs")) (.toBe true))
                  (-> (expect (.includes text "3 lines")) (.toBe true)))))

          (it "tool-start with very long MCP arg list never exceeds terminal width"
              (fn []
                ;; Regression: pi-tui crashes when render produces a line
                ;; wider than the terminal. mcp__lean-ctx__ctx_multi_read
                ;; with several full file paths used to overflow the
                ;; per-arg budget computed inside format-one-line-args.
                ;; truncate-line-to-width is the safety net.
                (let [long-paths (str "/Users/me/projects/very/deep/repo/packages/foo/src/main/java/io/example/foo/Bar.java,"
                                      "/Users/me/projects/very/deep/repo/packages/foo/src/main/java/io/example/foo/Baz.java,"
                                      "/Users/me/projects/very/deep/repo/packages/foo/src/main/java/io/example/foo/Qux.java")
                      msg {:role "tool-end"
                           :tool-name "mcp__lean-ctx__ctx_multi_read"
                           :args {:mode "signatures" :paths long-paths}
                           :result "39 lines of output\nbla\nbla"
                           :duration 100
                           :id "1"}
                      lines (render-message
                             {:msg msg :width 80 :theme theme :md-cache nil})
                      visible (strip-ansi (first lines))]
                  ;; must produce a single line
                  (-> (expect (count lines)) (.toBe 1))
                  ;; visible width must fit in 80 cols
                  (-> (expect (<= (count visible) 80)) (.toBe true))
                  ;; must still convey it was the right tool
                  (-> (expect (.includes visible "mcp__lean-ctx__ctx_multi_read"))
                      (.toBe true)))))

          (it "shows grep pattern + path + match count"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "grep"
                                                  :args {:pattern "ipsum" :path "src/"}
                                                  :result "m1\nm2" :id "1"}))]
                  (-> (expect (.includes text "ipsum")) (.toBe true))
                  (-> (expect (.includes text "src/")) (.toBe true))
                  (-> (expect (.includes text "2 matches")) (.toBe true)))))

          (it "shows glob pattern + path + file count"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "glob"
                                                  :args {:pattern "*.cljs" :path "src/"}
                                                  :result "f1\nf2\nf3" :id "1"}))]
                  (-> (expect (.includes text "*.cljs")) (.toBe true))
                  (-> (expect (.includes text "src/")) (.toBe true))
                  (-> (expect (.includes text "3 files")) (.toBe true)))))

          (it "uses · separator between args and result summary"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "read"
                                                  :args {:path "/x"}
                                                  :result "line" :id "1"}))]
                  (-> (expect (.includes text "·")) (.toBe true))))))

;;; ─── error ────────────────────────────────────────────────────────────────

(describe "chat-renderer/error" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "error" :content "boom" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes ✗ prefix"
              (fn []
                (let [text (visible-text (render {:role "error" :content "boom" :id "1"}))]
                  (-> (expect (.includes text "✗")) (.toBe true)))))

          (it "includes the message"
              (fn []
                (let [text (visible-text (render {:role "error" :content "network timeout" :id "1"}))]
                  (-> (expect (.includes text "network timeout")) (.toBe true))))))

;;; ─── thinking ─────────────────────────────────────────────────────────────

(describe "chat-renderer/thinking" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "thinking" :content "reasoning..." :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes │ prefix"
              (fn []
                (let [text (visible-text (render {:role "thinking" :content "step 1" :id "1"}))]
                  (-> (expect (.includes text "│")) (.toBe true))))))

;;; ─── plan ─────────────────────────────────────────────────────────────────

(describe "chat-renderer/plan" (fn [])
          (it "returns non-empty lines"
              (fn []
                (let [lines (render {:role "plan" :content "do X then Y" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes 📋 prefix"
              (fn []
                (let [text (visible-text (render {:role "plan" :content "step 1" :id "1"}))]
                  (-> (expect (.includes text "📋")) (.toBe true))))))

;;; ─── info (regression: muted-not-defined) ────────────────────────────────

(describe "chat-renderer/info" (fn [])
          (it "does not throw (regression: muted is not defined)"
              (fn []
                (let [lines (render {:role "info" :content "Role: plan → provider/model" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes the content"
              (fn []
                (let [text (visible-text (render {:role "info" :content "Role switched" :id "1"}))]
                  (-> (expect (.includes text "Role switched")) (.toBe true)))))

          (it "includes ℹ prefix"
              (fn []
                (let [text (visible-text (render {:role "info" :content "ok" :id "1"}))]
                  (-> (expect (.includes text "ℹ")) (.toBe true))))))

;;; ─── shell (bash/eval output) ────────────────────────────────────────────

(describe "chat-renderer/shell" (fn [])
          (it "renders shell output without a bullet prefix"
              (fn []
                (let [text (visible-text (render {:role "shell" :content "Cargo.lock\nCargo.toml" :id "1"}))]
                  (-> (expect (.includes text "●")) (.toBe false))
                  (-> (expect (.includes text "Cargo.lock")) (.toBe true)))))

          (it "returns empty array for empty content"
              (fn []
                (let [lines (render {:role "shell" :content "" :id "1"})]
                  (-> (expect (count lines)) (.toBe 0)))))

          (it "returns empty array for nil content"
              (fn []
                (let [lines (render {:role "shell" :content nil :id "1"})]
                  (-> (expect (count lines)) (.toBe 0))))))

;;; ─── widget (verbatim lines) ──────────────────────────────────────────────

(describe "chat-renderer/widget" (fn [])
          (it "returns lines split on newline"
              (fn []
                (let [lines (render {:role "widget" :content "line1\nline2\nline3" :id "1"})]
                  (-> (expect (count lines)) (.toBe 3)))))

          (it "preserves ANSI codes verbatim"
              (fn []
                (let [content "💭 Thinking (1.2k tokens)\n│ step 1"
                      lines   (render {:role "widget" :content content :id "1"})]
                  (-> (expect (first lines)) (.toBe "💭 Thinking (1.2k tokens)")))))

          (it "returns empty array for empty content"
              (fn []
                (let [lines (render {:role "widget" :content "" :id "1"})]
                  (-> (expect (count lines)) (.toBe 0))))))

;;; ─── fallback ─────────────────────────────────────────────────────────────

(describe "chat-renderer/fallback" (fn [])
          (it "handles unknown roles without throwing"
              (fn []
                (let [lines (render {:role "unknown-role" :content "data" :id "1"})]
                  (-> (expect (pos? (count lines))) (.toBe true)))))

          (it "includes role name in fallback output"
              (fn []
                (let [text (visible-text (render {:role "custom" :content "val" :id "1"}))]
                  (-> (expect (.includes text "custom")) (.toBe true))))))

;;; ─── nil/missing content guard ────────────────────────────────────────────

(describe "chat-renderer/nil-safety" (fn [])
          (it "handles nil content without throwing"
              (fn []
                (doseq [role ["user" "assistant" "error" "thinking" "plan" "info"]]
                  (let [lines (render {:role role :content nil :id "1"})]
                    (-> (expect (vector? lines)) (.toBe true))))))

          (it "handles missing content key"
              (fn []
                (let [lines (render {:role "user" :id "1"})]
                  (-> (expect (vector? lines)) (.toBe true))))))

;;; ─── a failed tool call ───────────────────────────────────────────────────
;;
;; middleware emits :isError on tool_execution_end and app_reducers now copies
;; it onto the message. Before that the icon was a flat `(if is-end "✓" "⚙")`,
;; so a tool that threw was reported to the user as a success — the worst
;; thing a transcript can get wrong.

(describe "chat-renderer/failed tool call" (fn [])
          (it "renders ✗ instead of ✓ when the tool failed"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-end" :tool-name "bash" :is-error true
                                     :args {:command "false"}
                                     :result "exit 1" :id "1"}))]
                  (-> (expect (.includes text "✗")) (.toBe true))
                  (-> (expect (.includes text "✓")) (.toBe false)))))

          (it "paints the failed line in the theme's error colour"
              (fn []
                (let [raw (.join (to-array (render {:role "tool-end" :tool-name "bash"
                                                    :is-error true :args {}
                                                    :result "boom" :id "1"}))
                                 "\n")]
                  ;; #f7768e → 247;118;142
                  (-> (expect (.includes raw "38;2;247;118;142")) (.toBe true)))))

          (it "shows the first line of the result instead of a line count"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-end" :tool-name "read" :is-error true
                                     :args {:path "/nope"}
                                     :result "ENOENT: no such file\nstack frame\nstack frame"
                                     :id "1"}))]
                  (-> (expect (.includes text "ENOENT: no such file")) (.toBe true))
                  (-> (expect (.includes text "3 lines")) (.toBe false)))))

          (it "the failure icon beats a tool's own custom icon"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-end" :tool-name "questionnaire"
                                     :is-error true :custom-icon "❓" :args {}
                                     :result "denied" :id "1"}))]
                  (-> (expect (.includes text "✗")) (.toBe true))
                  (-> (expect (.includes text "❓")) (.toBe false)))))

          (it "leaves a successful tool call alone"
              (fn []
                (let [text (visible-text (render {:role "tool-end" :tool-name "edit"
                                                  :args {:path "/f"} :result "" :id "1"}))]
                  (-> (expect (.includes text "✓")) (.toBe true))
                  (-> (expect (.includes text "✗")) (.toBe false))))))

;;; ─── in-flight tool progress ──────────────────────────────────────────────
;;
;; tool_execution_update writes :custom-status-text and nothing read it, so a
;; long-running tool reporting progress showed a motionless "⚙ name args".

(describe "chat-renderer/running tool progress" (fn [])
          (it "appends the live status text while the tool is running"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-start" :tool-name "web_fetch"
                                     :args {:url "https://x.dev"}
                                     :custom-status-text "downloading 40%" :id "1"}))]
                  (-> (expect (.includes text "⚙")) (.toBe true))
                  (-> (expect (.includes text "https://x.dev")) (.toBe true))
                  (-> (expect (.includes text "— downloading 40%")) (.toBe true)))))

          (it "omits the separator when there is no status yet"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-start" :tool-name "web_fetch"
                                     :args {:url "https://x.dev"} :id "1"}))]
                  (-> (expect (.includes text "—")) (.toBe false)))))

          (it "does not carry the status onto the finished line"
              (fn []
                (let [text (visible-text
                            (render {:role "tool-end" :tool-name "web_fetch"
                                     :args {:url "https://x.dev"}
                                     :custom-status-text "downloading 40%"
                                     :result "ok" :id "1"}))]
                  (-> (expect (.includes text "downloading 40%")) (.toBe false))))))

;;; ─── notify levels ────────────────────────────────────────────────────────
;;
;; `ui.notify(msg, type)` used to throw its type away, so a warning, a failure
;; and a success all rendered as the same cyan ℹ line.

(describe "chat-renderer/notify levels" (fn [])
          (it "renders ℹ for info"
              (fn []
                (let [text (visible-text (render {:role "info" :content "hello" :id "1"}))]
                  (-> (expect (.includes text "ℹ")) (.toBe true))
                  (-> (expect (.includes text "hello")) (.toBe true)))))

          (it "renders ⚠ in the warning colour for warn"
              (fn []
                (let [lines (render {:role "warn" :content "careful" :id "1"})
                      raw   (.join (to-array lines) "\n")
                      text  (visible-text lines)]
                  (-> (expect (.includes text "⚠")) (.toBe true))
                  (-> (expect (.includes text "careful")) (.toBe true))
                  ;; #e0af68 → 224;175;104
                  (-> (expect (.includes raw "38;2;224;175;104")) (.toBe true)))))

          (it "renders ✗ in the error colour for error"
              (fn []
                (let [lines (render {:role "error" :content "it broke" :id "1"})
                      raw   (.join (to-array lines) "\n")
                      text  (visible-text lines)]
                  (-> (expect (.includes text "✗")) (.toBe true))
                  (-> (expect (.includes text "it broke")) (.toBe true))
                  (-> (expect (.includes raw "38;2;247;118;142")) (.toBe true)))))

          (it "renders ✓ in the success colour for success"
              (fn []
                (let [lines (render {:role "success" :content "all done" :id "1"})
                      raw   (.join (to-array lines) "\n")
                      text  (visible-text lines)]
                  (-> (expect (.includes text "✓")) (.toBe true))
                  (-> (expect (.includes text "all done")) (.toBe true))
                  ;; #9ece6a → 158;206;106
                  (-> (expect (.includes raw "38;2;158;206;106")) (.toBe true))))))
