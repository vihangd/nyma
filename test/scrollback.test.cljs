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
