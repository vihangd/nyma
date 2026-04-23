(ns chat-view-static.test
  "Regression tests for the <Static> render refactor (Step 7 in the plan).

   Tests are grouped by whether they pass TODAY (before Static) or only
   after Static is introduced. The latter are clearly marked with
   '[RED until Step 7]' in their description.

   The core invariant we are encoding:
     - Finalized messages must be committed to terminal scrollback exactly
       once and never repainted over by subsequent turns.
     - The live region (lastFrame) must only contain the current turn.
     - No overpaint artifacts (box-drawing chars embedded in later text) must
       appear when a tall message is followed by a short one."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/chat_view.jsx" :refer [ChatView compute_turn_split last_user_index]]
            [clojure.string :as str]))

(def ^:private test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :border    "#3b4261"}})

(afterEach (fn [] (cleanup)))

;;; ─── Basic frame-history assertions (pass today) ────────────

(describe "ChatView: basic frame history (should pass before and after Static)"
          (fn []

            (it "first turn content appears in lastFrame"
                (fn []
                  (let [msgs [{:role "user"      :content "USER_QUESTION_1"}
                              {:role "assistant" :content "ASSISTANT_REPLY_1"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages msgs :theme test-theme}])]
                    (-> (expect (lastFrame)) (.toContain "ASSISTANT_REPLY_1")))))

            (it "content from all rendered turns accumulates in frames history"
                ;; ink-testing-library.frames captures every rendered output.
                ;; After both turns are rendered, the first turn must have
                ;; appeared in at least one frame.
                (fn []
                  (let [turn-1 [{:role "user"      :content "FIRST_QUESTION"}
                                {:role "assistant" :content "FIRST_REPLY"}]
                        turn-2 (into turn-1
                                     [{:role "user"      :content "SECOND_QUESTION"}
                                      {:role "assistant" :content "SECOND_REPLY"}])
                        {:keys [frames rerender]}
                        (render #jsx [ChatView {:messages turn-1 :theme test-theme}])]
                    (rerender #jsx [ChatView {:messages turn-2 :theme test-theme}])
                    ;; At least one frame contains the first-turn content
                    (let [found (boolean (.some frames (fn [f] (.includes f "FIRST_REPLY"))))]
                      (-> (expect found) (.toBe true))))))

            (it "empty message list renders without crash"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [ChatView {:messages [] :theme test-theme}])]
                    (-> (expect (lastFrame)) (.toBeDefined)))))

            (it "tool-start renders tool name"
                (fn []
                  (let [msgs [{:role "user"       :content "do something"}
                              {:role "tool-start" :tool-name "read"
                               :args {:path "f.cljs"} :exec-id "e1" :verbosity "collapsed"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages msgs :theme test-theme :streaming true}])]
                    (-> (expect (lastFrame)) (.toContain "read")))))

            (it "tool-end replaces tool-start — both render without crash"
                ;; apply-tool-end in the reducers replaces the start in-place.
                ;; Verify the ChatView renders both states cleanly.
                (fn []
                  (let [start-msgs [{:role "user"       :content "q"}
                                    {:role "tool-start" :tool-name "read"
                                     :args {:path "x.cljs"} :exec-id "e1" :verbosity "collapsed"}]
                        end-msgs   [{:role "user"     :content "q"}
                                    {:role "tool-end" :tool-name "read"
                                     :args {:path "x.cljs"} :exec-id "e1"
                                     :duration 10 :result "contents" :verbosity "collapsed"}]
                        {:keys [lastFrame rerender]}
                        (render #jsx [ChatView {:messages start-msgs :theme test-theme :streaming true}])]
                    (-> (expect (lastFrame)) (.toContain "read"))
                    (rerender #jsx [ChatView {:messages end-msgs :theme test-theme :streaming false}])
                    (-> (expect (lastFrame)) (.toContain "read")))))

            (it "group of 3 consecutive read-ends renders a tool-group"
                (fn []
                  (let [msgs [{:role "user"     :content "q"}
                              {:role "tool-end" :tool-name "read" :args {:path "a.cljs"}
                               :duration 5 :result "a" :verbosity "collapsed"}
                              {:role "tool-end" :tool-name "read" :args {:path "b.cljs"}
                               :duration 5 :result "b" :verbosity "collapsed"}
                              {:role "tool-end" :tool-name "read" :args {:path "c.cljs"}
                               :duration 5 :result "c" :verbosity "collapsed"}
                              {:role "assistant" :content "done"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages msgs :theme test-theme}])]
                    ;; Tool-group renders checkmarks + tool name
                    (-> (expect (lastFrame)) (.toContain "read")))))))

;;; ─── Grouping prefix stability (passes before and after Static) ─

(describe "ChatView: group-messages prefix stability"
          (fn []

            (it "adding a new message tail does not change earlier grouped output"
                ;; group-messages is pure and deterministic on its input prefix.
                ;; Three consecutive read-ends followed by an assistant flush the
                ;; group. Appending new messages cannot retroactively change that.
                (fn []
                  (let [base-msgs [{:role "user"     :content "work"}
                                   {:role "tool-end" :tool-name "read"
                                    :args {:path "a.cljs"} :duration 5 :result "a" :verbosity "collapsed"}
                                   {:role "tool-end" :tool-name "read"
                                    :args {:path "b.cljs"} :duration 5 :result "b" :verbosity "collapsed"}
                                   {:role "tool-end" :tool-name "read"
                                    :args {:path "c.cljs"} :duration 5 :result "c" :verbosity "collapsed"}
                                   {:role "assistant" :content "done reading"}]
                        extended  (into base-msgs
                                        [{:role "user"      :content "more work"}
                                         {:role "assistant" :content "ok"}])
                        {:keys [frames rerender]}
                        (render #jsx [ChatView {:messages base-msgs :theme test-theme}])]
                    ;; After base render: tool group appeared in some frame
                    (-> (expect (boolean (.some frames (fn [f] (.includes f "read"))))) (.toBe true))
                    (rerender #jsx [ChatView {:messages extended :theme test-theme}])
                    ;; After extending: tool group still appeared somewhere
                    (-> (expect (boolean (.some frames (fn [f] (.includes f "read"))))) (.toBe true)))))))

;;; ─── Live-region and scrollback assertions (all pass after Step 7) ─
;;;
;;; NOTE: ink-testing-library writes static + dynamic content together in each
;;; stdout.write() call. Static items appear in the frame they are FIRST
;;; committed; subsequent frames no longer include them. The overpaint fix
;;; (box-drawing chars not bleeding into later frames) is therefore only
;;; verifiable with 3+ renders, or in a real terminal.
;;;
;;; What we verify here (best-effort through ink-testing-library):
;;;   • The "live" last message is always in lastFrame.
;;;   • Prior-turn content appears somewhere in the frames history (not lost).
;;;   • No crashes under multi-turn or streaming rerender.
;;;
;;; Where the AUTHORITATIVE region-assignment invariant lives:
;;;   • test/ui_layout.test.cljs `compute-turn-split: no-jump invariant`
;;;     — pure-function tests, including `"frame-by-frame simulation"`,
;;;     assert that past-turn content never appears in the `live` slice
;;;     as messages accumulate. That is the regression test for "past
;;;     turn stays in Static / past turn stays committed to scrollback".
;;;   • test/scrollback.test.cljs `committable-now (eager sub-message
;;;     commit)` + `shrink-bound invariant` — pin that each sub-message
;;;     of a streaming turn is eligible to be committed exactly when it
;;;     stops being the tail, so the dynamic region can never balloon
;;;     large enough to leak via Ink's log-update overflow.
;;;
;;; The tests below cover ink-rendering smoke (render + rerender doesn't
;;; crash; content appears somewhere). They CANNOT distinguish Static vs
;;; dynamic region placement because ink-testing-library writes both
;;; regions atomically every frame. Don't strengthen these without
;;; replacing ink-testing-library with a split-stream harness.

(describe "ChatView: live-region and scrollback"
          (fn []

            (it "second turn content is in lastFrame; first turn appears in frames history"
                (fn []
                  (let [turn-1 [{:role "user"      :content "UNIQUE_TURN_ONE_MARKER"}
                                {:role "assistant" :content "FIRST_REPLY_MARKER"}]
                        turn-2 (into turn-1
                                     [{:role "user"      :content "second question"}
                                      {:role "assistant" :content "SECOND_REPLY_CONTENT"}])
                        {:keys [lastFrame rerender frames]}
                        (render #jsx [ChatView {:messages turn-1 :theme test-theme}])]
                    (rerender #jsx [ChatView {:messages turn-2 :theme test-theme}])
                    ;; The live (last) message is always visible in lastFrame
                    (-> (expect (lastFrame)) (.toContain "SECOND_REPLY_CONTENT"))
                    ;; The first turn appeared in at least one frame (not silently dropped)
                    (-> (expect (boolean (.some frames #(.includes % "FIRST_REPLY_MARKER"))))
                        (.toBe true)))))

            (it "live streaming message is in lastFrame; finalized answer in frames history"
                (fn []
                  (let [base-msgs [{:role "user"      :content "first question"}
                                   {:role "assistant" :content "FINALIZED_ANSWER_MARKER"}]
                        streaming (into base-msgs
                                        [{:role "user"      :content "second question"}
                                         {:role "assistant" :content "partial answer..."}])
                        {:keys [lastFrame rerender frames]}
                        (render #jsx [ChatView {:messages base-msgs
                                                :theme    test-theme
                                                :streaming false}])]
                    (rerender #jsx [ChatView {:messages streaming
                                              :theme    test-theme
                                              :streaming true}])
                    ;; Live (streaming) message is in lastFrame
                    (-> (expect (lastFrame)) (.toContain "partial answer..."))
                    ;; Prior finalized answer appeared somewhere in the frame log
                    (-> (expect (boolean (.some frames #(.includes % "FINALIZED_ANSWER_MARKER"))))
                        (.toBe true))))

                (it "short reply after tall message: live message is in lastFrame; table in frames history"
                ;; The real overpaint fix (table rows not bleeding into a shorter subsequent
                ;; frame) is only verifiable in a real terminal. Here we confirm the live
                ;; message IS rendered and the table appeared somewhere in history.
                    (fn []
                      (let [table-row  (fn [n] (str "│ row-" n " │ content-" n " │"))
                            tall-reply (str/join "\n" (concat
                                                       ["┌───────┬──────────┐"
                                                        "│ key   │ value    │"
                                                        "├───────┼──────────┤"]
                                                       (map table-row (range 15))
                                                       ["└───────┴──────────┘"]))
                            msgs-tall  [{:role "user"      :content "show me a table"}
                                        {:role "assistant" :content tall-reply}]
                            msgs-both  (into msgs-tall
                                             [{:role "user"      :content "short follow-up"}
                                              {:role "assistant" :content "SHORT_REPLY"}])
                            {:keys [lastFrame rerender frames]}
                            (render #jsx [ChatView {:messages msgs-tall :theme test-theme}])]
                        (rerender #jsx [ChatView {:messages msgs-both :theme test-theme}])
                    ;; The live short reply is in lastFrame
                        (-> (expect (lastFrame)) (.toContain "SHORT_REPLY"))
                    ;; The tall table appeared somewhere in the frame history
                        (-> (expect (boolean (.some frames #(.includes % "│ row-"))))
                            (.toBe true))))))))

;;; ─── Streaming token updates (regression for memo-stall bug) ────────────────

(describe "ChatView: streaming token updates are visible"
          (fn []

            (it "rerendering with grown tail content updates lastFrame"
                ;; Simulates add-assistant-chunk! in-place tail update:
                ;; same id + count, only :content grows. The memo on
                ;; group-messages must NOT stall on tail-id alone.
                (fn []
                  (let [msgs-1 [{:role "user" :content "question" :id "u1"}
                                {:role "assistant" :content "H" :id "a1"}]
                        msgs-2 [{:role "user" :content "question" :id "u1"}
                                {:role "assistant" :content "Hello world" :id "a1"}]
                        {:keys [lastFrame rerender]}
                        (render #jsx [ChatView {:messages msgs-1 :theme test-theme :streaming true}])]
                    (-> (expect (lastFrame)) (.toContain "H"))
                    (rerender #jsx [ChatView {:messages msgs-2 :theme test-theme :streaming true}])
                    (-> (expect (lastFrame)) (.toContain "Hello world")))))

            (it "multiple successive streaming updates all appear"
                (fn []
                  (let [step     (fn [content]
                                   [{:role "user" :content "q" :id "u1"}
                                    {:role "assistant" :content content :id "a1"}])
                        {:keys [lastFrame rerender]}
                        (render #jsx [ChatView {:messages (step "A") :theme test-theme :streaming true}])]
                    (rerender #jsx [ChatView {:messages (step "A B") :theme test-theme :streaming true}])
                    (-> (expect (lastFrame)) (.toContain "A B"))
                    (rerender #jsx [ChatView {:messages (step "A B C") :theme test-theme :streaming true}])
                    (-> (expect (lastFrame)) (.toContain "A B C")))))

            (it "stream end: streaming=false, live message still visible"
                (fn []
                  (let [msgs [{:role "user" :content "q" :id "u1"}
                              {:role "assistant" :content "final answer" :id "a1"}]
                        {:keys [lastFrame rerender]}
                        (render #jsx [ChatView {:messages msgs :theme test-theme :streaming true}])]
                    (rerender #jsx [ChatView {:messages msgs :theme test-theme :streaming false}])
                    (-> (expect (lastFrame)) (.toContain "final answer")))))))

;;; ─── compute-turn-split: pure-function tests for region assignment ──────────
;;;
;;; ink-testing-library runs Ink in `debug: true` mode, which writes
;;; `fullStaticOutput + output` on every frame. That means `lastFrame()`
;;; and `frames[]` CANNOT distinguish whether a message is in the live
;;; (dynamic) region or the Static (scrollback) region — both always
;;; appear in the captured output.
;;;
;;; In a real terminal, Static output scrolls above the viewport as the
;;; dynamic region grows; the user cannot see it while their current turn
;;; is being rendered. If a message is wrongly placed in Static, it visually
;;; disappears from the user's perspective.
;;;
;;; Therefore: assert the split structurally via the pure function, not
;;; via rendered output. These tests would have caught the
;;; "user question disappears when assistant streams" bug.

(describe "compute-turn-split: pure-function region assignment"
          (fn []

            (it "empty messages → empty live, no finalized"
                (fn []
                  (let [split (compute_turn_split [])]
                    (-> (expect (:turn-idx split)) (.toBe -1))
                    (-> (expect (some? (:finalized split))) (.toBe false))
                    (-> (expect (count (:live split))) (.toBe 0)))))

            (it "single user message stays in live"
                (fn []
                  (let [msgs  [{:role "user" :content "q" :id "u1"}]
                        split (compute_turn_split msgs)]
                    (-> (expect (:turn-idx split)) (.toBe 0))
                    (-> (expect (some? (:finalized split))) (.toBe false))
                    (-> (expect (count (:live split))) (.toBe 1))
                    (-> (expect (:id (first (:live split)))) (.toBe "u1")))))

            (it "user + streaming assistant: BOTH in live, nothing in finalized"
                ;; CRITICAL: this is the regression for the disappearing-user bug.
                ;; The user's question must stay in the live region while the
                ;; assistant is streaming — otherwise the real terminal scrolls
                ;; it off the top.
                (fn []
                  (let [msgs  [{:role "user"      :content "MY_QUESTION" :id "u1"}
                               {:role "assistant" :content "partial"     :id "a1"}]
                        split (compute_turn_split msgs)]
                    (-> (expect (:turn-idx split)) (.toBe 0))
                    (-> (expect (some? (:finalized split))) (.toBe false))
                    (-> (expect (count (:live split))) (.toBe 2))
                    (-> (expect (:id (first (:live split)))) (.toBe "u1"))
                    (-> (expect (:id (second (:live split)))) (.toBe "a1")))))

            (it "completed turn + new user message: previous turn finalizes"
                (fn []
                  (let [msgs  [{:role "user"      :content "q1" :id "u1"}
                               {:role "assistant" :content "a1" :id "a1"}
                               {:role "user"      :content "q2" :id "u2"}]
                        split (compute_turn_split msgs)]
                    (-> (expect (:turn-idx split)) (.toBe 2))
                    (-> (expect (count (:finalized split))) (.toBe 2))
                    (-> (expect (:id (first (:finalized split)))) (.toBe "u1"))
                    (-> (expect (:id (second (:finalized split)))) (.toBe "a1"))
                    (-> (expect (count (:live split))) (.toBe 1))
                    (-> (expect (:id (first (:live split)))) (.toBe "u2")))))

            (it "turn with tool calls: all sub-messages stay in live until next user"
                (fn []
                  (let [msgs  [{:role "user"      :content "q"       :id "u1"}
                               {:role "assistant" :content "let me check" :id "a1"}
                               {:role "tool-end"  :tool-name "read"  :id "t1"}
                               {:role "assistant" :content "answer"  :id "a2"}]
                        split (compute_turn_split msgs)]
                    (-> (expect (:turn-idx split)) (.toBe 0))
                    (-> (expect (some? (:finalized split))) (.toBe false))
                    (-> (expect (count (:live split))) (.toBe 4)))))

            (it "multi-turn conversation with tool calls finalizes completed turns"
                (fn []
                  (let [msgs  [{:role "user"      :content "q1"       :id "u1"}
                               {:role "assistant" :content "thinking" :id "a1"}
                               {:role "tool-end"  :tool-name "read"   :id "t1"}
                               {:role "assistant" :content "ans1"     :id "a2"}
                               {:role "user"      :content "q2"       :id "u2"}
                               {:role "assistant" :content "ans2-part" :id "a3"}]
                        split (compute_turn_split msgs)]
                    (-> (expect (:turn-idx split)) (.toBe 4))
                    (-> (expect (count (:finalized split))) (.toBe 4))
                    (-> (expect (count (:live split))) (.toBe 2))
                    (-> (expect (:id (first (:live split)))) (.toBe "u2"))
                    (-> (expect (:id (second (:live split)))) (.toBe "a3")))))

            (it "streaming token updates do not shift the turn boundary"
                ;; Simulates in-place content growth. turn-idx must be stable
                ;; across deltas so the group-messages memo does not re-fire
                ;; on every token — but the live slice must still reflect the
                ;; updated content (fresh read, no memo).
                (fn []
                  (let [msgs-1  [{:role "user"      :content "q"  :id "u1"}
                                 {:role "assistant" :content "H"  :id "a1"}]
                        msgs-2  [{:role "user"      :content "q"  :id "u1"}
                                 {:role "assistant" :content "Hello world" :id "a1"}]
                        split-1 (compute_turn_split msgs-1)
                        split-2 (compute_turn_split msgs-2)]
                    (-> (expect (:turn-idx split-1)) (.toBe (:turn-idx split-2)))
                    ;; Live is read fresh — content updates propagate.
                    (-> (expect (:content (second (:live split-1)))) (.toBe "H"))
                    (-> (expect (:content (second (:live split-2)))) (.toBe "Hello world")))))

            (it "last-user-index returns -1 for no user messages"
                (fn []
                  (-> (expect (last_user_index [])) (.toBe -1))
                  (-> (expect (last_user_index [{:role "assistant" :id "a1"}])) (.toBe -1))))

            (it "last-user-index finds the latest user message"
                (fn []
                  (let [msgs [{:role "user"      :id "u1"}
                              {:role "assistant" :id "a1"}
                              {:role "user"      :id "u2"}
                              {:role "assistant" :id "a2"}]]
                    (-> (expect (last_user_index msgs)) (.toBe 2)))))))

;;; ─── Session switch: regression for useMemo closes-over-stale-value ─────
;;;
;;; The fin-grouped useMemo callback closes over `finalized`. Its dep key
;;; must include enough identity to distinguish a different session's
;;; messages from the current one, even if both happen to have the same
;;; `turn-idx`. Before the first-id dep was added, a session switch where
;;; the new session's last-user-idx matched the old session's would return
;;; the PRIOR session's finalized messages from the memo cache.

(describe "ChatView: session switch invalidates finalized memo"
          (fn []

            (it "switching to a different session with same turn-idx updates finalized content"
                (fn []
                  ;; Both sessions have last-user at index 2 (turn-idx = 2).
                  ;; Without first-id in the dep array, the memo returns the
                  ;; stale session-A cached value on the rerender.
                  (let [session-a [{:role "user"      :content "session-A-q1"    :id "a-u1"}
                                   {:role "assistant" :content "SESSION_A_REPLY" :id "a-a1"}
                                   {:role "user"      :content "session-A-q2"    :id "a-u2"}]
                        session-b [{:role "user"      :content "session-B-q1"    :id "b-u1"}
                                   {:role "assistant" :content "SESSION_B_REPLY" :id "b-a1"}
                                   {:role "user"      :content "session-B-q2"    :id "b-u2"}]
                        {:keys [lastFrame frames rerender]}
                        (render #jsx [ChatView {:messages session-a :theme test-theme :streaming false}])]
                    (-> (expect (lastFrame)) (.toContain "session-A-q2"))
                    (rerender #jsx [ChatView {:messages session-b :theme test-theme :streaming false}])
                    (-> (expect (lastFrame)) (.toContain "session-B-q2"))
                    ;; Session-B's finalized content (SESSION_B_REPLY) must appear
                    ;; in the frame history after the switch. Session-A's must also
                    ;; be present (it was committed to Static earlier). The
                    ;; stale-memo bug would leave SESSION_B_REPLY missing entirely.
                    (-> (expect (boolean (.some frames #(.includes % "SESSION_B_REPLY"))))
                        (.toBe true)))))))

