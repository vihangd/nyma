(ns scrollback.test
  "Unit + integration tests for src/agent/ui/scrollback.cljs.

   These tests codify the user's complaint about multi-turn rendering:
   `ink-testing-library` runs Ink in debug mode (`ink.js:255`) which writes
   `fullStaticOutput + output` atomically every frame, so lastFrame-based
   tests can never detect bugs where Static emits a large batch and scrolls
   the terminal. This file uses a fresh mock stdout and ink's headless
   `renderToString` to test the real ANSI output directly."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["./agent/ui/scrollback.mjs" :refer [render_message_to_string
                                                 commit_to_scrollback_BANG_
                                                 last_user_index
                                                 committable_past_turn
                                                 committable_completed_turn
                                                 committable_now
                                                 print_header_banner_BANG_]]
            ["./agent/ui/tool_status.jsx" :refer [format_one_line_result_for_tool]]))

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

            (it "assistant with <think> blocks: strips raw tags, shows compact pill"
                ;; Regression for the user-reported inconsistency: committed
                ;; scrollback turns were showing raw "<think>...</think>"
                ;; text because render-assistant passed content through
                ;; incremental-render without split-think-blocks. Live view
                ;; (AssistantMessage) stripped them but committed view
                ;; didn't — same message looked different in-flight vs
                ;; committed.
                (fn []
                  (let [content "<think>I should look at the main.go file.</think>\nHere is the answer."
                        out (render_message_to_string
                             {:role "assistant" :content content :id "a1"}
                             test-theme nil 80)]
                    ;; The raw <think> tag must NOT appear in scrollback
                    (-> (expect (.includes out "<think>")) (.toBe false))
                    (-> (expect (.includes out "</think>")) (.toBe false))
                    ;; The reasoning pill should appear (compact form)
                    (-> (expect (.includes out "✻ Thought")) (.toBe true))
                    ;; The actual answer text must still appear
                    (-> (expect (.includes out "Here is the answer")) (.toBe true))
                    ;; The reasoning text itself should NOT be dumped inline
                    (-> (expect (.includes out "I should look at the main.go file."))
                        (.toBe false)))))

            (it "assistant with ONLY reasoning (no text) → committed as nil/empty"
                ;; Reasoning-only messages happen right before a tool call
                ;; on reasoning models. The live view already showed the
                ;; pill; committing an empty `●` bubble + orphan pill to
                ;; scrollback is noise. Skip the commit entirely.
                (fn []
                  (let [content "<think>Let me check the file first.</think>"
                        out (render_message_to_string
                             {:role "assistant" :content content :id "a1"}
                             test-theme nil 80)]
                    ;; Returns nil or empty — commit-to-scrollback! treats
                    ;; both as a no-op.
                    (-> (expect (or (nil? out) (= "" out))) (.toBe true)))))

            (it "assistant with UNTERMINATED <think> tag → skip commit"
                ;; Mid-stream state: <think>partial reasoning with no
                ;; closing tag yet. All content is treated as reasoning,
                ;; no final text. Don't commit.
                (fn []
                  (let [content "<think>starting to think about this..."
                        out (render_message_to_string
                             {:role "assistant" :content content :id "a1"}
                             test-theme nil 80)]
                    (-> (expect (or (nil? out) (= "" out))) (.toBe true)))))

            (it "commit-to-scrollback! is a no-op when renderer returns nil"
                ;; Reasoning-only messages: renderer returns nil, commit
                ;; should write nothing.
                (fn []
                  (let [calls (atom [])
                        mock-write (fn [data] (swap! calls conj data))]
                    (commit_to_scrollback_BANG_
                     #js {:write mock-write
                          :message {:role "assistant"
                                    :content "<think>only reasoning</think>"
                                    :id "a1"}
                          :theme test-theme
                          :block-renderers nil
                          :columns 80})
                    (-> (expect (count @calls)) (.toBe 0)))))

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

;;; ─── committable-completed-turn ────────────────────────────────
;;;
;;; Utility that returns ALL non-committed, non-tool-start messages.
;;; Kept as a tested utility (may be used in future optimisations).
;;; NOTE: the no-jump fix is in ChatView (in-flight = live, not
;;; filterv-committed) — see compute-turn-split no-jump tests below.

(describe "committable-completed-turn"
          (fn []

            (it "returns all messages for a single completed turn"
                ;; This is the key case: turn 1 ends, streaming goes false.
                ;; Both messages must be returned so they can be committed
                ;; before the user types question 2 — preventing the jump.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}]
                        result (committable_completed_turn msgs)]
                    (-> (expect (.-length result)) (.toBe 2))
                    (-> (expect (.-id (aget result 0))) (.toBe "u1"))
                    (-> (expect (.-id (aget result 1))) (.toBe "a1")))))

            (it "skips already-committed messages"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "a1" :id "a1"}]
                        result (committable_completed_turn msgs)]
                    (-> (expect (.-length result)) (.toBe 1))
                    (-> (expect (.-id (aget result 0))) (.toBe "a1")))))

            (it "skips tool-start (in-flight)"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "tool-start" :tool-name "Read" :id "t1"}
                                  #js {:role "assistant" :content "done" :id "a1"}]
                        result (committable_completed_turn msgs)]
                    ;; tool-start filtered, user and assistant included
                    (-> (expect (.-length result)) (.toBe 2))
                    (-> (expect (.-id (aget result 0))) (.toBe "u1"))
                    (-> (expect (.-id (aget result 1))) (.toBe "a1")))))

            (it "returns [] for empty messages"
                (fn []
                  (let [result (committable_completed_turn #js [])]
                    (-> (expect (.-length result)) (.toBe 0)))))

            (it "returns [] when all messages already committed"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "a1" :id "a1" :committed true}]
                        result (committable_completed_turn msgs)]
                    (-> (expect (.-length result)) (.toBe 0)))))

            (it "includes multi-turn messages (all uncommitted, streaming just ended)"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}
                                  #js {:role "assistant" :content "a2" :id "a2"}]
                        result (committable_completed_turn msgs)]
                    (-> (expect (.-length result)) (.toBe 4)))))

            (it "commits all uncommitted messages and writes to stdout"
                ;; Functional smoke test: given [u1, a1], all are returned
                ;; and write() is called once per message.
                (fn []
                  (let [writes (atom [])
                        write  (fn [d] (swap! writes conj d))
                        msgs   #js [#js {:role "user" :content "q1" :id "u1"}
                                    #js {:role "assistant" :content "a1" :id "a1"}]
                        to-commit (committable_completed_turn msgs)]
                    (doseq [m to-commit]
                      (commit_to_scrollback_BANG_
                       #js {:write write :message m
                            :theme (clj->js {:colors {:primary "#7aa2f7"
                                                      :secondary "#9ece6a"
                                                      :muted "#565f89"}})
                            :block-renderers nil :columns 80}))
                    ;; Both messages written to scrollback
                    (-> (expect (count @writes)) (.toBe 2)))))))

;;; ─── committable-now (eager per-sub-message commit) ────────────
;;;
;;; These tests pin the REGRESSION that caused "❯ prompt" to appear
;;; 19 times during streaming. Root cause: Ink's log-update can't
;;; clear lines that have scrolled off-screen, so when the dynamic
;;; region overflowed the terminal (user prompt + assistant text +
;;; tool output + more assistant text all in flight), the top of
;;; the region leaked permanently into scrollback — once per frame.
;;;
;;; Fix: commit each sub-message as soon as it stops being the
;;; currently-streaming tail. Keeps the dynamic region bounded to
;;; at most one sub-message, so overflow (and leak) can't happen.

(describe "committable-now (eager sub-message commit)"
          (fn []

            (it "empty messages → []"
                (fn []
                  (-> (expect (.-length (committable_now #js []))) (.toBe 0))))

            (it "single user message → [] (user is the tail)"
                ;; Right after submit, before any chunk arrives. User is
                ;; the only (and therefore last) message — keep it in the
                ;; dynamic region so the editor stays anchored below it.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}]]
                    (-> (expect (.-length (committable_now msgs))) (.toBe 0)))))

            (it "user + partial assistant → [user] (assistant is the tail)"
                ;; First chunk arrived, assistant message created.
                ;; User can commit now — it's no longer the tail.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "hi" :id "a1"}]
                        result (committable_now msgs)]
                    (-> (expect (.-length result)) (.toBe 1))
                    (-> (expect (.-id (aget result 0))) (.toBe "u1")))))

            (it "user-committed + partial assistant → [] (user already committed, assistant is tail)"
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "hi" :id "a1"}]
                        result (committable_now msgs)]
                    (-> (expect (.-length result)) (.toBe 0)))))

            (it "user + assistant + tool-start → [assistant] (tool-start is tail but filtered anyway)"
                ;; Tool fired during assistant's turn. tool-start is both
                ;; tail AND excluded by the tool-start rule.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "thinking" :id "a1"}
                                  #js {:role "tool-start" :tool-name "Read" :id "t1"}]
                        result (committable_now msgs)]
                    (-> (expect (.-length result)) (.toBe 1))
                    (-> (expect (.-id (aget result 0))) (.toBe "a1")))))

            (it "user + assistant + tool-end + partial A2 → [assistant, tool-end]"
                ;; Tool finished (replaced tool-start in place), A2 started.
                ;; The completed assistant bubble AND the finished tool-end
                ;; can both commit now — A2 is the new tail.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "thinking" :id "a1"}
                                  #js {:role "tool-end" :tool-name "Read" :result "ok" :id "t1"}
                                  #js {:role "assistant" :content "second" :id "a2"}]
                        result (committable_now msgs)]
                    (-> (expect (.-length result)) (.toBe 2))
                    (-> (expect (.-id (aget result 0))) (.toBe "a1"))
                    (-> (expect (.-id (aget result 1))) (.toBe "t1")))))

            (it "mid-stream (streaming=true): tail is NOT committed"
                ;; While streaming is in progress, the tail is partial
                ;; content — committing would write a half-written
                ;; message to permanent scrollback. Keep it dynamic.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "partial" :id "a1"}]
                        result (committable_now msgs true)]
                    (-> (expect (.-length result)) (.toBe 0)))))

            (it "stream-end (streaming=false): tail IS committed"
                ;; When streaming transitions true→false, the final
                ;; assistant message is complete. Commit it — otherwise
                ;; every keypress in the editor re-renders the full
                ;; bubble and each re-render leaks the top row into
                ;; permanent scrollback (stacked `✻ Thought` pill bug).
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "done" :id "a1"}]
                        result (committable_now msgs false)]
                    (-> (expect (.-length result)) (.toBe 1))
                    (-> (expect (.-id (aget result 0))) (.toBe "a1")))))

            (it "1-arg form defaults to streaming=true (conservative)"
                ;; Back-compat: callers that don't pass streaming are
                ;; treated as if mid-stream — safe default (never
                ;; commit a possibly-partial tail).
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "done" :id "a1"}]
                        result (committable_now msgs)]
                    (-> (expect (.-length result)) (.toBe 0)))))

            (it "new user submit makes previous assistant committable"
                ;; This is the transition: user types Q2. The previous
                ;; A1 is no longer the tail — Q2 is. A1 commits now,
                ;; which scrolls it to real terminal scrollback.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "done" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        result (committable_now msgs)]
                    (-> (expect (.-length result)) (.toBe 1))
                    (-> (expect (.-id (aget result 0))) (.toBe "a1")))))

            (it "all already committed → []"
                (fn []
                  (let [msgs #js [#js {:role "user" :id "u1" :committed true}
                                  #js {:role "assistant" :id "a1" :committed true}]]
                    (-> (expect (.-length (committable_now msgs))) (.toBe 0)))))

            (it "REGRESSION: user prompt commits before assistant text grows past terminal"
                ;; The original bug: [u1, a1-small] → [u1, a1-big] → [u1, a1-huge].
                ;; Each growth step had u1 AT THE TOP of the dynamic region.
                ;; When a1 overflowed the terminal, u1 scrolled off-screen
                ;; and Ink's log-update couldn't clear it — each subsequent
                ;; frame re-wrote u1 into permanent scrollback above the
                ;; cleared region. 19+ copies accumulated.
                ;;
                ;; After this fix: on the very first chunk that creates a1,
                ;; committable-now returns [u1]. The sweep commits u1 once
                ;; to scrollback. On every subsequent chunk, u1 is already
                ;; :committed and absent from in-flight. Only a1 lives in
                ;; the dynamic region, so there's no "top-that-scrolls-off"
                ;; for log-update to leak.
                (fn []
                  (let [u1   #js {:role "user" :content "what framework" :id "u1"}
                        a1-partial #js {:role "assistant" :content "a" :id "a1"}
                        first-chunk (committable_now #js [u1 a1-partial])]
                    ;; First chunk: u1 is immediately eligible for commit.
                    (-> (expect (.-length first-chunk)) (.toBe 1))
                    (-> (expect (.-id (aget first-chunk 0))) (.toBe "u1")))
                  ;; After the sweep runs, u1 is :committed. The second
                  ;; chunk (a1 grows) must find nothing to commit — and,
                  ;; critically, ChatView's in-flight filter must drop u1
                  ;; so it's not re-rendered as "top of dynamic region".
                  (let [u1c  #js {:role "user" :content "what framework" :id "u1" :committed true}
                        a1-bigger #js {:role "assistant" :content "aaaa" :id "a1"}
                        second-chunk (committable_now #js [u1c a1-bigger])]
                    (-> (expect (.-length second-chunk)) (.toBe 0)))))))

;;; ─── Shrink-bound invariant (blank-lines-below-editor) ─────────
;;;
;;; When the commit sweep fires, React re-renders the ChatView with
;;; the just-committed messages filtered out of in-flight. Ink's
;;; log-update clears the PREVIOUS lastOutput height and writes the
;;; new (smaller) output. The N_old - N_new lines are left blank
;;; below the editor — the "blank space after a response" visual.
;;;
;;; We can't control Ink's clear/rewrite behavior, but we CAN bound
;;; how big the shrink is by controlling what `committable-now`
;;; returns at each frame. The invariant we pin here: at any point
;;; in a realistic streaming session, each commit sweep reduces
;;; in-flight by a bounded, predictable number of messages — never
;;; by "all of them at once" (which would be the worst-case blank
;;; gap). Combined with "tail is always kept", this caps the blank
;;; region to at most a handful of lines in practice.
;;;
;;; These are simulation tests: we walk through messages growing
;;; frame-by-frame (as React would see them during streaming) and
;;; assert the in-flight size AND the commit-sweep delta at each
;;; step. If a future change ever makes committable-now greedier
;;; or more conservative, these tests catch the regression in the
;;; visual invariant before it reaches the user.

(defn- in-flight-after-sweep
  "Simulate one useEffect cycle: compute committable-now, mark those
   messages :committed in the state, and return the resulting
   in-flight slice (== filter-uncommitted) that ChatView would render.

   Second arg `streaming` is forwarded to committable-now (defaults to
   true — mid-stream — to match the 1-arg back-compat default)."
  ([messages] (in-flight-after-sweep messages true))
  ([messages streaming]
   (let [to-commit (committable_now messages streaming)
         to-commit-ids (into #{} (map #(.-id %) to-commit))
         after (.map messages
                     (fn [m]
                       (if (contains? to-commit-ids (.-id m))
                         (let [clone (js/Object.assign #js {} m)]
                           (set! (.-committed clone) true)
                           clone)
                         m)))]
     {:to-commit to-commit
      :after     after
      :in-flight (.filter after (fn [m] (not (.-committed m))))})))

(describe "shrink-bound invariant (blank-lines-below-editor)"
          (fn []

            (it "single-turn streaming: in-flight never exceeds 2"
                ;; Walk through Q1 submit → A1 first chunk → A1 grows.
                ;; At every step, after the sweep fires, the dynamic
                ;; region should hold at most 2 messages — current tail
                ;; and anything not-yet-committable. In practice, always
                ;; exactly 1 after the first sweep.
                (fn []
                  (let [q1 #js {:role "user" :content "q1" :id "u1"}
                        ;; State A: user just submitted, no chunks yet.
                        state-a (in-flight-after-sweep #js [q1])]
                    ;; u1 is the tail → not committable → stays visible.
                    (-> (expect (.-length (:in-flight state-a))) (.toBe 1)))
                  ;; State B: first A1 chunk arrives. u1 can now commit.
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "h" :id "a1"}]
                        state-b (in-flight-after-sweep msgs)]
                    ;; Only a1 left in-flight (u1 committed to scrollback).
                    (-> (expect (.-length (:to-commit state-b))) (.toBe 1))
                    (-> (expect (.-length (:in-flight state-b))) (.toBe 1))
                    (-> (expect (.-id (aget (:in-flight state-b) 0))) (.toBe "a1")))
                  ;; State C: a1 grows through many chunks.
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "hello world this is longer" :id "a1"}]
                        state-c (in-flight-after-sweep msgs)]
                    ;; Nothing more to commit; a1 stays as-tail.
                    (-> (expect (.-length (:to-commit state-c))) (.toBe 0))
                    (-> (expect (.-length (:in-flight state-c))) (.toBe 1)))))

            (it "stream-end (streaming=false): tail commits, in-flight drops to 0"
                ;; When streaming transitions true→false, the final
                ;; assistant bubble is finalized. The sweep commits it
                ;; to scrollback; the dynamic region then holds nothing
                ;; until the next user submit. This is what prevents
                ;; the per-keypress `✻ Thought` pill leak — once a1 is
                ;; in real scrollback, editor keypresses can't re-render
                ;; it (and therefore can't overflow it).
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "done" :id "a1"}]
                        state (in-flight-after-sweep msgs false)]
                    (-> (expect (.-length (:to-commit state))) (.toBe 1))
                    (-> (expect (.-id (aget (:to-commit state) 0))) (.toBe "a1"))
                    (-> (expect (.-length (:in-flight state))) (.toBe 0)))))

            (it "mid-stream (streaming=true): tail stays in dynamic region"
                ;; While streaming, the tail is partial. Leave it in
                ;; the dynamic region so the user sees updates live.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "partial" :id "a1"}]
                        state (in-flight-after-sweep msgs true)]
                    (-> (expect (.-length (:to-commit state))) (.toBe 0))
                    (-> (expect (.-length (:in-flight state))) (.toBe 1))
                    (-> (expect (.-id (aget (:in-flight state) 0))) (.toBe "a1")))))

            (it "next-turn submit: shrink bound is ≤ 1 message"
                ;; User types Q2. Previous tail (A1) is now past-tail and
                ;; commits. Dynamic region shrinks from {A1, Q2} to {Q2}.
                ;; That's a SHRINK OF ONE MESSAGE — the bound we pin. If
                ;; a future change committed multiple messages at this
                ;; transition (e.g. grouping), the blank region would be
                ;; proportionally larger.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                  #js {:role "assistant" :content "a1-done" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        state (in-flight-after-sweep msgs)]
                    (-> (expect (.-length (:to-commit state))) (.toBe 1))
                    (-> (expect (.-id (aget (:to-commit state) 0))) (.toBe "a1"))
                    (-> (expect (.-length (:in-flight state))) (.toBe 1))
                    (-> (expect (.-id (aget (:in-flight state) 0))) (.toBe "u2")))))

            (it "tool call mid-turn: in-flight shrinks incrementally, never all at once"
                ;; Realistic multi-phase turn: user → assistant text →
                ;; tool-start → tool-end → more assistant text.
                ;; The commit sweep fires after each messages change. We
                ;; simulate each frame and assert that at no step does
                ;; the dynamic region shrink by more than the sub-message
                ;; that just became past-tail.
                (fn []
                  ;; Frame 1: first chunk of a1 (thinking).
                  (let [f1 #js [#js {:role "user" :content "q1" :id "u1"}
                                #js {:role "assistant" :content "think" :id "a1"}]
                        s1 (in-flight-after-sweep f1)]
                    ;; u1 commits, a1 stays.
                    (-> (expect (.-length (:to-commit s1))) (.toBe 1))
                    (-> (expect (.-length (:in-flight s1))) (.toBe 1)))
                  ;; Frame 2: tool-start appended.
                  (let [f2 #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                #js {:role "assistant" :content "think done" :id "a1"}
                                #js {:role "tool-start" :tool-name "Read" :id "t1"}]
                        s2 (in-flight-after-sweep f2)]
                    ;; a1 is past-tail now; tool-start is skipped anyway.
                    (-> (expect (.-length (:to-commit s2))) (.toBe 1))
                    (-> (expect (.-id (aget (:to-commit s2) 0))) (.toBe "a1"))
                    ;; In-flight: a1* (just committed, filtered) and
                    ;; tool-start — but filter-uncommitted drops a1, so 1.
                    (-> (expect (.-length (:in-flight s2))) (.toBe 1)))
                  ;; Frame 3: tool-end replaces tool-start in place.
                  (let [f3 #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                #js {:role "assistant" :content "think done" :id "a1" :committed true}
                                #js {:role "tool-end" :tool-name "Read" :result "ok" :id "t1"}]
                        s3 (in-flight-after-sweep f3)]
                    ;; tool-end is the new tail, not committable.
                    (-> (expect (.-length (:to-commit s3))) (.toBe 0))
                    (-> (expect (.-length (:in-flight s3))) (.toBe 1)))
                  ;; Frame 4: a2 chunk after tool-end.
                  (let [f4 #js [#js {:role "user" :content "q1" :id "u1" :committed true}
                                #js {:role "assistant" :content "think done" :id "a1" :committed true}
                                #js {:role "tool-end" :tool-name "Read" :result "ok" :id "t1"}
                                #js {:role "assistant" :content "final" :id "a2"}]
                        s4 (in-flight-after-sweep f4)]
                    ;; tool-end commits (past-tail), a2 stays.
                    (-> (expect (.-length (:to-commit s4))) (.toBe 1))
                    (-> (expect (.-id (aget (:to-commit s4) 0))) (.toBe "t1"))
                    (-> (expect (.-length (:in-flight s4))) (.toBe 1)))))

            (it "BOUND: in-flight is always ≤ 1 after a sweep (except when tail is user with no follow-up)"
                ;; Strong invariant: after the sweep completes, ChatView
                ;; renders AT MOST ONE uncommitted message. This is what
                ;; makes the blank-lines-below issue cosmetic rather than
                ;; catastrophic — the dynamic region's max height is
                ;; bounded by a single sub-message's height, not the
                ;; entire turn's.
                (fn []
                  ;; Try every interesting shape.
                  (let [shapes [#js []
                                #js [#js {:role "user" :id "u1"}]
                                #js [#js {:role "user" :id "u1" :committed true}
                                     #js {:role "assistant" :id "a1"}]
                                #js [#js {:role "user" :id "u1" :committed true}
                                     #js {:role "assistant" :id "a1" :committed true}
                                     #js {:role "tool-end" :id "t1" :result "x"}]
                                #js [#js {:role "user" :id "u1" :committed true}
                                     #js {:role "assistant" :id "a1" :committed true}
                                     #js {:role "tool-end" :id "t1" :result "x" :committed true}
                                     #js {:role "assistant" :id "a2"}]]]
                    (doseq [shape shapes]
                      (let [state (in-flight-after-sweep shape)]
                        (-> (expect (<= (.-length (:in-flight state)) 1))
                            (.toBe true)))))))))

;;; ─── Sweep integration (end-to-end commit flow) ────────────────
;;;
;;; The useEffect body in app.cljs is trivially wired:
;;;   1. compute (committable-past-turn messages)
;;;   2. doseq commit-to-scrollback! each
;;;   3. set-messages (mark :committed true by id)
;;;
;;; These tests simulate that loop directly (no React needed) so we
;;; can assert on the full side-effect chain — write order, ID-based
;;; marking, idempotent re-entry, multi-turn transitions.

(defn- simulate-sweep!
  "Simulate one pass of the app.cljs useEffect sweep body.
   Returns the new messages vector after marking committed ids."
  [messages write theme]
  (let [to-commit (committable_past_turn messages)]
    (doseq [msg to-commit]
      (commit_to_scrollback_BANG_
       #js {:write write :message msg
            :theme theme :block-renderers nil :columns 80}))
    (let [ids (into #{} (map #(.-id %) to-commit))]
      (.map messages
            (fn [m]
              (if (contains? ids (.-id m))
                (js/Object.assign #js {} m #js {:committed true})
                m))))))

(describe "sweep integration: commit-sweep end-to-end"
          (fn []

            (it "single turn: no past turn → no writes, no state change"
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}]
                        new-msgs (simulate-sweep! msgs write test-theme)]
                    (-> (expect (count @writes)) (.toBe 0))
                    ;; messages unchanged (no :committed flag added)
                    (-> (expect (.-committed (aget new-msgs 0))) (.toBeUndefined))
                    (-> (expect (.-committed (aget new-msgs 1))) (.toBeUndefined)))))

            (it "two-turn transition: past turn commits, current turn stays"
                ;; The UX invariant: when a new user message arrives,
                ;; the previous turn writes to scrollback, current turn
                ;; stays uncommitted in React state.
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        new-msgs (simulate-sweep! msgs write test-theme)]
                    ;; u1 and a1 committed (2 writes)
                    (-> (expect (count @writes)) (.toBe 2))
                    ;; u1 and a1 marked :committed
                    (-> (expect (.-committed (aget new-msgs 0))) (.toBe true))
                    (-> (expect (.-committed (aget new-msgs 1))) (.toBe true))
                    ;; u2 NOT committed (current turn)
                    (-> (expect (.-committed (aget new-msgs 2))) (.toBeUndefined)))))

            (it "commit order: writes emit in message order (oldest first)"
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        msgs #js [#js {:role "user" :content "FIRST" :id "u1"}
                                  #js {:role "assistant" :content "REPLY1" :id "a1"}
                                  #js {:role "user" :content "SECOND" :id "u2"}
                                  #js {:role "assistant" :content "REPLY2" :id "a2"}
                                  #js {:role "user" :content "THIRD" :id "u3"}]]
                    (simulate-sweep! msgs write test-theme)
                    ;; 4 writes, in order: u1, a1, u2, a2
                    (-> (expect (count @writes)) (.toBe 4))
                    (-> (expect (.includes (nth @writes 0) "FIRST")) (.toBe true))
                    (-> (expect (.includes (nth @writes 1) "REPLY1")) (.toBe true))
                    (-> (expect (.includes (nth @writes 2) "SECOND")) (.toBe true))
                    (-> (expect (.includes (nth @writes 3) "REPLY2")) (.toBe true)))))

            (it "idempotent: running sweep again on committed messages is a no-op"
                ;; The sweep effect re-fires whenever `messages` changes
                ;; (including the mark-committed state change it itself
                ;; causes). Second run must NOT re-commit.
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        after-1 (simulate-sweep! msgs write test-theme)
                        first-count (count @writes)
                        after-2 (simulate-sweep! after-1 write test-theme)
                        second-count (count @writes)
                        after-3 (simulate-sweep! after-2 write test-theme)
                        third-count (count @writes)]
                    ;; First run writes 2 (u1, a1). Subsequent runs don't.
                    (-> (expect first-count) (.toBe 2))
                    (-> (expect second-count) (.toBe 2))
                    (-> (expect third-count) (.toBe 2)))))

            (it "tool-start in past turn stays uncommitted, its tool-end commits"
                ;; Tool lifecycle: tool-start represents in-flight work;
                ;; tool-end replaces it when the call resolves. Sweep
                ;; must never commit a bare tool-start (it would show
                ;; stale/incomplete state in scrollback).
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        ;; Turn 1: user, tool-start (not yet replaced), new user
                        msgs1 #js [#js {:role "user" :content "q1" :id "u1"}
                                   #js {:role "tool-start" :tool-name "Read" :id "t1"}
                                   #js {:role "user" :content "q2" :id "u2"}]
                        after-1 (simulate-sweep! msgs1 write test-theme)]
                    ;; u1 commits, t1 does NOT (tool-start filtered)
                    (-> (expect (count @writes)) (.toBe 1))
                    (-> (expect (.-committed (aget after-1 0))) (.toBe true))
                    (-> (expect (.-committed (aget after-1 1))) (.toBeUndefined))
                    ;; Now simulate tool-end replacing tool-start in place
                    ;; (this is what apply-tool-end does). Sweep runs again.
                    (let [msgs2 #js [(aget after-1 0)  ; u1 (committed)
                                     #js {:role "tool-end" :tool-name "Read"
                                          :result "done" :duration 10 :id "t1"}
                                     (aget after-1 2)]  ; u2 (uncommitted)
                          after-2 (simulate-sweep! msgs2 write test-theme)]
                      ;; Now the tool-end commits. u1 was already committed.
                      (-> (expect (count @writes)) (.toBe 2))
                      (-> (expect (.-committed (aget after-2 1))) (.toBe true))))))

            (it "bash/eval messages commit through the sweep"
                ;; Regression guard: bash (:kind :bash) and eval
                ;; (:kind :eval) messages must route to their renderers.
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :kind "bash"
                                       :command "ls" :stdout "a.txt"
                                       :stderr "" :exit-code 0 :id "b1"}
                                  #js {:role "assistant" :kind "eval"
                                       :expr "(+ 1 2)" :stdout "3"
                                       :stderr "" :exit-code 0 :id "e1"}
                                  #js {:role "user" :content "q2" :id "u2"}]]
                    (simulate-sweep! msgs write test-theme)
                    ;; 3 past-turn messages committed
                    (-> (expect (count @writes)) (.toBe 3))
                    ;; bash content recognizable
                    (-> (expect (.some @writes
                                       (fn [w] (.includes w "ls"))))
                        (.toBe true))
                    ;; eval content recognizable
                    (-> (expect (.some @writes
                                       (fn [w] (.includes w "(+ 1 2)"))))
                        (.toBe true)))))

            (it "reasoning-only assistant in past turn: no write, but marked committed"
                ;; Reasoning-only messages produce nil from the renderer
                ;; (commit-to-scrollback! skips the write). BUT the sweep
                ;; still marks them :committed so it doesn't retry them
                ;; on every rerender forever.
                (fn []
                  (let [writes (atom [])
                        write (fn [d] (swap! writes conj d))
                        msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant"
                                       :content "<think>planning</think>"
                                       :id "a1"}
                                  #js {:role "assistant" :content "the answer" :id "a2"}
                                  #js {:role "user" :content "q2" :id "u2"}]
                        after-1 (simulate-sweep! msgs write test-theme)]
                    ;; 2 writes: u1 and a2 (a1 is reasoning-only, skipped)
                    (-> (expect (count @writes)) (.toBe 2))
                    ;; Second run: a1 still marked committed → no retry
                    (let [after-2 (simulate-sweep! after-1 write test-theme)]
                      (-> (expect (count @writes)) (.toBe 2))
                      (-> (expect (.-committed (aget after-2 1))) (.toBe true))))))))

;;; ─── print-header-banner! ──────────────────────────────────────
;;;
;;; Writes to process.stdout directly before Ink mounts. We can't
;;; fully unit-test the stdout interaction without stubbing, but we
;;; CAN check that calling it with a stub doesn't throw and writes
;;; something sensible.

(describe "print-header-banner!"
          (fn []

            (it "writes a single line containing 'nyma' and the model id"
                (fn []
                  (let [original-write (.-write js/process.stdout)
                        captured (atom [])]
                    (try
                      (set! (.-write js/process.stdout)
                            (fn [d] (swap! captured conj d) true))
                      (print_header_banner_BANG_
                       #js {:model-id "test-model-123"
                            :theme (clj->js test-theme)})
                      (finally
                        (set! (.-write js/process.stdout) original-write)))
                    (let [all (apply str @captured)]
                      (-> (expect (.includes all "nyma")) (.toBe true))
                      (-> (expect (.includes all "test-model-123")) (.toBe true))
                      ;; Trailing blank line so Ink's dynamic region starts
                      ;; on a fresh line.
                      (-> (expect (.includes all "\n\n")) (.toBe true))))))

            (it "handles nil model-id gracefully (falls back to 'unknown')"
                (fn []
                  (let [original-write (.-write js/process.stdout)
                        captured (atom [])]
                    (try
                      (set! (.-write js/process.stdout)
                            (fn [d] (swap! captured conj d) true))
                      (print_header_banner_BANG_
                       #js {:model-id nil
                            :theme (clj->js test-theme)})
                      (finally
                        (set! (.-write js/process.stdout) original-write)))
                    (let [all (apply str @captured)]
                      (-> (expect (.includes all "unknown")) (.toBe true)))))))

;;; ─── Tool display UX ──────────────────────────────────────────
;;;
;;; The scrollback tool display uses a one-liner format:
;;;   ✓ tool-name args-summary — result-summary duration
;;; These tests pin the format so regressions are caught.

          (describe "format-one-line-result-for-tool: per-tool result summaries"
                    (fn []

                      (it "read: counts lines"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "read" "line1\nline2\nline3" nil))
                                (.toBe "3 lines"))))

                      (it "read: singular '1 line'"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "read" "only one" nil))
                                (.toBe "1 line"))))

                      (it "grep: counts matches"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "grep" "a.cljs:1:foo\nb.cljs:5:bar" nil))
                                (.toBe "2 matches"))))

                      (it "glob: counts files"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "glob" "a.cljs\nb.cljs\nc.cljs" nil))
                                (.toBe "3 files"))))

                      (it "ls: counts items"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "ls" "foo\nbar" nil))
                                (.toBe "2 items"))))

                      (it "edit: returns 'applied'"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "edit" "success" nil))
                                (.toBe "applied"))))

                      (it "write: returns 'written'"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "write" "ok" nil))
                                (.toBe "written"))))

                      (it "bash: counts output lines"
                          (fn []
                            (-> (expect (format_one_line_result_for_tool "bash" "line1\nline2" nil))
                                (.toBe "2 lines"))))

                      (it "unknown tool: falls back to generic format"
                          (fn []
                            (let [out (format_one_line_result_for_tool "custom_tool" "some output" nil)]
                              (-> (expect (string? out)) (.toBe true)))))))

          (describe "render-tool-end: scrollback one-liner format"
                    (fn []

                      (it "read: shows ✓, tool name, path, line count, duration"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "read"
                                        :args {:path "/src/main.cljs"}
                                        :result "line1\nline2\nline3\nline4"
                                        :duration 6 :id "t1"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "✓")) (.toBe true))
                              (-> (expect (.includes out "read")) (.toBe true))
                              (-> (expect (.includes out "/src/main.cljs")) (.toBe true))
                              (-> (expect (.includes out "4 lines")) (.toBe true))
                              (-> (expect (.includes out "6ms")) (.toBe true)))))

                      (it "grep: shows match count and pattern"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "grep"
                                        :args {:pattern "TODO" :path "src/"}
                                        :result "a.cljs:1:TODO\nb.cljs:5:TODO\nc.cljs:9:TODO"
                                        :duration 12 :id "t2"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "grep")) (.toBe true))
                              (-> (expect (.includes out "TODO")) (.toBe true))
                              (-> (expect (.includes out "3 matches")) (.toBe true)))))

                      (it "glob: shows file count"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "glob"
                                        :args {:pattern "**/*.cljs"}
                                        :result "a.cljs\nb.cljs"
                                        :duration 5 :id "t3"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "2 files")) (.toBe true))
                              (-> (expect (.includes out "**/*.cljs")) (.toBe true)))))

                      (it "edit: shows 'applied'"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "edit"
                                        :args {:path "/src/foo.cljs"}
                                        :result "success"
                                        :duration 8 :id "t4"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "applied")) (.toBe true))
                              (-> (expect (.includes out "/src/foo.cljs")) (.toBe true)))))

                      (it "empty result: shows ✗ failure icon"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "read"
                                        :args {:path "/missing.txt"}
                                        :result ""
                                        :duration 2 :id "t5"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "✗")) (.toBe true))
                    ;; ✓ should NOT appear for failures
                              (-> (expect (.includes out "✓")) (.toBe false)))))

                      (it "nil result: shows ✗ failure icon"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "grep"
                                        :args {:pattern "nonexistent"}
                                        :result nil
                                        :duration 3 :id "t6"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "✗")) (.toBe true)))))

                      (it "duration > 1s: formats as seconds"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "bash"
                                        :args {:command "sleep 2"}
                                        :result "done"
                                        :duration 2100 :id "t7"}
                                       test-theme nil 80)]
                              (-> (expect (.includes out "2.1s")) (.toBe true)))))

                      (it "one-liner: no newlines in the output"
                          (fn []
                            (let [out (render_message_to_string
                                       {:role "tool-end" :tool-name "read"
                                        :args {:path "/x.cljs"}
                                        :result "line1\nline2"
                                        :duration 4 :id "t8"}
                                       test-theme nil 80)]
                    ;; Strip ANSI, check no newlines
                              (let [plain (.replace out (js/RegExp. "\\x1b\\[[0-9;]*m" "g") "")]
                                (-> (expect (.includes plain "\n")) (.toBe false)))))))))

;;; ─── Commit-sweep strategy contract ────────────────────────────
;;;
;;; These tests pin the commit strategy that app.cljs MUST use.
;;;
;;; REGRESSION HISTORY (2025-04-22):
;;;   app.cljs was accidentally switched from committable-past-turn to
;;;   committable-now. With scrollback-mode defaulting ON, the entire
;;;   conversation was committed to scrollback immediately after each
;;;   response — making messages "appear near the top and disappear
;;;   almost immediately". The tests below ensure the two strategies
;;;   behave differently enough that using the wrong one is detectable.
;;;
;;;   The invariant: committable-past-turn returns NOTHING for a
;;;   single-turn conversation (the current exchange must stay visible).
;;;   committable-now returns the user message immediately. Any caller
;;;   that uses committable-now for the main commit sweep will fail
;;;   this contract.

(describe "commit-sweep strategy: past-turn vs now"
          (fn []

            (it "past-turn: single turn in progress — nothing to commit"
                ;; app.cljs must use committable-past-turn, NOT committable-now.
                ;; With past-turn the CURRENT exchange always stays in the Ink
                ;; region. With committable-now the user message is committed
                ;; immediately after the first assistant chunk, making content
                ;; vanish from view.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "hello" :id "u1"}
                                  #js {:role "assistant" :content "hi" :id "a1"}]]
                    ;; committable-past-turn: no past turn exists → nothing committed
                    (-> (expect (.-length (committable_past_turn msgs)))
                        (.toBe 0)))))

            (it "now: single turn in progress — user message IS committed immediately"
                ;; Confirm that committable-now has different behavior.
                ;; If the app ever uses this for its main sweep, the user
                ;; message disappears after the first assistant chunk.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "hello" :id "u1"}
                                  #js {:role "assistant" :content "hi" :id "a1"}]]
                    ;; committable-now: u1 is no longer tail → returns u1
                    (-> (expect (.-length (committable_now msgs)))
                        (.toBe 1))
                    (-> (expect (.-id (aget (committable_now msgs) 0)))
                        (.toBe "u1")))))

            (it "past-turn: after user submits again, prev turn becomes committable"
                ;; The moment a second user message arrives, turn-1 is past
                ;; and both its messages become eligible. This is the only time
                ;; past-turn commits — the current exchange always stays visible.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "q1" :id "u1"}
                                  #js {:role "assistant" :content "a1" :id "a1"}
                                  #js {:role "user" :content "q2" :id "u2"}]]
                    (let [result (committable_past_turn msgs)]
                      (-> (expect (.-length result)) (.toBe 2))
                      (-> (expect (.-id (aget result 0))) (.toBe "u1"))
                      (-> (expect (.-id (aget result 1))) (.toBe "a1")))
                    ;; u2 (current turn) must NOT be in the result
                    (-> (expect (boolean (some #(= (.-id %) "u2") (committable_past_turn msgs))))
                        (.toBe false)))))

            (it "past-turn: streaming response — ZERO messages committed"
                ;; Critical: while the user is watching the stream, nothing
                ;; should vanish. Any function that returns >0 here is wrong
                ;; for the app commit-sweep role.
                (fn []
                  (let [msgs #js [#js {:role "user" :content "tell me a story" :id "u1"}
                                  #js {:role "assistant" :content "once upon" :id "a1"}]]
                    (-> (expect (.-length (committable_past_turn msgs)))
                        (.toBe 0)))))))
