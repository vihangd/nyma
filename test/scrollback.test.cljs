(ns scrollback.test
  "Unit + integration tests for src/agent/ui/scrollback.cljs.

   These tests codify the user's complaint about multi-turn rendering:
   `ink-testing-library` runs Ink in debug mode (`ink.js:255`) which writes
   `fullStaticOutput + output` atomically every frame, so lastFrame-based
   tests can never detect bugs where Static emits a large batch and scrolls
   the terminal. This file uses a fresh mock stdout and ink's headless
   `renderToString` to test the real ANSI output directly."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/scrollback.mjs" :refer [render_message_to_string
                                                 commit_to_scrollback_BANG_
                                                 last_user_index
                                                 committable_past_turn]]))

(def ^:private test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :border    "#3b4261"}})

;;; ─── render-message-to-string ──────────────────────────────────────

(describe "render-message-to-string: each message kind renders"
          (fn []

            (it "user message contains its content text"
                (fn []
                  (let [out (render_message_to_string
                             {:role "user" :content "HELLO_USER_MSG" :id "u1"}
                             test-theme nil 80)]
                    (-> (expect (.includes out "HELLO_USER_MSG")) (.toBe true)))))

            (it "assistant message contains its content text"
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :content "THIS_IS_THE_REPLY" :id "a1"}
                             test-theme nil 80)]
                    (-> (expect (.includes out "THIS_IS_THE_REPLY")) (.toBe true)))))

            (it "bash message renders command + stdout"
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :kind :bash :id "b1"
                              :command "ls -la" :stdout "total 42\nfoo.txt"
                              :stderr "" :exit-code 0}
                             test-theme nil 80)]
                    (-> (expect (.includes out "ls -la")) (.toBe true))
                    (-> (expect (.includes out "total 42")) (.toBe true)))))

            (it "eval message renders expression + stdout"
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :kind :eval :id "e1"
                              :expr "(+ 1 2)" :stdout "3"
                              :stderr "" :exit-code 0}
                             test-theme nil 80)]
                    (-> (expect (.includes out "(+ 1 2)")) (.toBe true))
                    (-> (expect (.includes out "3")) (.toBe true)))))

            (it "returns a non-empty string for simple user message"
                (fn []
                  (let [out (render_message_to_string
                             {:role "user" :content "x" :id "u1"}
                             test-theme nil 80)]
                    (-> (expect (> (count out) 0)) (.toBe true)))))

            (it "accepts the columns option without throwing"
                ;; We don't assert specific wrap behavior — useStreamingMarkdown
                ;; handles wrapping its own way, and the point of the columns
                ;; option is that it's forwarded to ink's renderer successfully.
                (fn []
                  (let [out (render_message_to_string
                             {:role "assistant" :content "some text" :id "a1"}
                             test-theme nil 40)]
                    (-> (expect (> (count out) 0)) (.toBe true)))))

            (it "handles complex markdown (code blocks, lists, headers) without crashing"
                ;; Regression guard: the initial implementation used ink's
                ;; renderToString which triggered a yoga-wasm call_indirect
                ;; crash via flushPassiveEffects firing AFTER teardown freed
                ;; the yoga node. The pure renderer must handle anything the
                ;; markdown renderer supports.
                (fn []
                  (let [md (str "# Heading\n\n"
                                "Some text with **bold** and *italic*.\n\n"
                                "```python\n"
                                "def hello():\n"
                                "    print('world')\n"
                                "```\n\n"
                                "- item 1\n"
                                "- item 2\n"
                                "- item 3\n\n"
                                "> a quoted line\n")
                        out (render_message_to_string
                             {:role "assistant" :content md :id "a1"}
                             test-theme nil 80)]
                    (-> (expect (> (count out) 0)) (.toBe true))
                    ;; Content should carry through — at least the raw text
                    (-> (expect (.includes out "Heading")) (.toBe true))
                    (-> (expect (.includes out "hello")) (.toBe true))
                    (-> (expect (.includes out "item 1")) (.toBe true)))))

            (it "tool-end message renders tool name and compact result"
                (fn []
                  (let [out (render_message_to_string
                             {:role "tool-end"
                              :tool-name "Read"
                              :args {"file_path" "/tmp/foo.txt"}
                              :result "file contents here"
                              :duration 123
                              :id "t1"}
                             test-theme nil 80)]
                    (-> (expect (.includes out "Read")) (.toBe true))
                    (-> (expect (.includes out "file contents")) (.toBe true)))))

            (it "renders each kind+role combination without throwing"
                ;; Exhaustive smoke test — every branch in the cond.
                (fn []
                  (doseq [msg [{:role "user" :content "x" :id "u"}
                               {:role "assistant" :content "y" :id "a"}
                               {:role "assistant" :kind :bash :command "ls"
                                :stdout "out" :stderr "" :exit-code 0 :id "b"}
                               {:role "assistant" :kind :eval :expr "(+ 1 2)"
                                :stdout "3" :stderr "" :exit-code 0 :id "e"}
                               {:role "tool-end" :tool-name "Grep"
                                :args {"pattern" "x"} :result "match" :id "t"}
                               {:role "thinking" :content "hmm" :id "th"}
                               {:role "plan" :content "steps" :id "p"}
                               {:role "error" :content "oops" :id "er"}
                               {:role "unknown" :content "??" :id "un"}]]
                    (let [out (render_message_to_string msg test-theme nil 80)]
                      (-> (expect (string? out)) (.toBe true))))))))

;;; ─── commit-to-scrollback! ─────────────────────────────────────────

(describe "commit-to-scrollback!: writes to the stream exactly once"
          (fn []

            (it "calls write with the rendered message content"
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "user" :content "FIND_ME" :id "u1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (count @calls)) (.toBe 1))
                    (-> (expect (.includes (first @calls) "FIND_ME")) (.toBe true)))))

            (it "is a single batched write, not progressive"
                ;; This is the key regression check: committing must be ONE
                ;; write, not many small fragmented writes. Otherwise log-update
                ;; gets confused about dynamic-region height.
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))
                        long-text (apply str (interpose "\n" (repeat 20 "line")))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "assistant" :content long-text :id "a1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (count @calls)) (.toBe 1)))))

            (it "appends a trailing newline so writes stack correctly"
                ;; Each committed message must end with \n so subsequent
                ;; commits start on a fresh line rather than being glued
                ;; to the previous one.
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "user" :content "abc" :id "u1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (.endsWith (first @calls) "\n")) (.toBe true)))))

            (it "is a no-op when write is nil"
                ;; During unit tests or early render passes, write may be
                ;; unavailable. Don't crash.
                (fn []
                  (commit_to_scrollback_BANG_
                   #js {:write nil
                        :message {:role "user" :content "x" :id "u1"}
                        :theme test-theme
                        :block-renderers nil
                        :columns 80})
                  ;; Reaching here without throwing is the assertion.
                  (-> (expect true) (.toBe true))))))

;;; ─── Turn-boundary semantics (pure) ────────────────────────────
;;;
;;; This is the test gap the user flagged when they saw content scroll
;;; above the header after every turn. The fix: commit ONLY past turns
;;; (everything before the last user message), not the current turn.
;;; The current turn stays in the chat region — that's what the user
;;; sees as "the conversation".
;;;
;;; These tests codify the exact rules so they can't silently regress.

(describe "last-user-index"
          (fn []

            (it "returns -1 for empty messages"
                (fn []
                  (-> (expect (last_user_index #js [])) (.toBe -1))))

            (it "returns -1 when no user message exists"
                (fn []
                  (let [msgs #js [#js {:role "assistant" :content "x" :id "a1"}]]
                    (-> (expect (last_user_index msgs)) (.toBe -1)))))

            (it "returns index of the only user message"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q" :id "u1"}]]
                    (-> (expect (last_user_index msgs)) (.toBe 0)))))

            (it "returns the index of the LAST user in a multi-turn list"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}
                                  #js {:role "assistant" :content "a2" :id "a2"}
                                  #js {:role "user" :content "q3" :id "u3"}]]
                    (-> (expect (last_user_index msgs)) (.toBe 4)))))))

(describe "committable-past-turn"
          (fn []

            (it "returns [] when there are no past turns (single turn only)"
                ;; During turn 1, last-user is at index 0, so there's no
                ;; prior slice to commit. The chat region shows the
                ;; whole conversation.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}]]
                    (-> (expect (.-length (committable_past_turn msgs))) (.toBe 0)))))

            (it "returns [] when there's no user message yet"
                (fn []
                  (-> (expect (.-length (committable_past_turn #js []))) (.toBe 0))))

            (it "returns prior turn messages when a new turn starts"
                ;; Turn 2 user just arrived — now turn 1 becomes a past
                ;; turn and should commit.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        result (committable_past_turn msgs)]
                    (-> (expect (.-length result)) (.toBe 2))
                    (-> (expect (.-id (aget result 0))) (.toBe "u1"))
                    (-> (expect (.-id (aget result 1))) (.toBe "a1")))))

            (it "skips messages already marked :committed"
                ;; Idempotent: second-run sees the flag and returns empty.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "a1" :id "a1" :committed true}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        result (committable_past_turn msgs)]
                    (-> (expect (.-length result)) (.toBe 0)))))

            (it "skips tool-start (in-flight — NOT eligible to commit)"
                ;; A tool-start without tool-end is still in-flight. We
                ;; wait for tool-end to replace it before committing.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "tool-start" :tool-name "Read" :id "t1"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        result (committable_past_turn msgs)]
                    ;; Only u1 is committable; t1 is tool-start
                    (-> (expect (.-length result)) (.toBe 1))
                    (-> (expect (.-id (aget result 0))) (.toBe "u1")))))

            (it "handles multi-turn: commits all past turns, keeps current visible"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}
                                  #js {:role "assistant" :content "a2" :id "a2"}
                                  #js {:role "user" :content "q3" :id "u3"}
                                  #js {:role "assistant" :content "a3-streaming" :id "a3"}]
                        result (committable_past_turn msgs)]
                    ;; q3 and a3 are the CURRENT turn (from u3 onwards) — not committed.
                    ;; Everything before u3 (u1, a1, u2, a2) is past turn — committed.
                    (-> (expect (.-length result)) (.toBe 4))
                    (-> (expect (.-id (aget result 3))) (.toBe "a2")))))

            (it "during streaming: current streaming assistant is NOT committed"
                ;; Critical regression guard. If the streaming tail is
                ;; included in commit, the user sees an empty chat
                ;; region after stream ends (content scrolled above
                ;; the header — the bug this test codifies).
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "streaming" :id "a1"}]
                        result (committable_past_turn msgs)]
                    ;; No past turn — only one turn in flight.
                    (-> (expect (.-length result)) (.toBe 0)))))))
