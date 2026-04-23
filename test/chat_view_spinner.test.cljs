(ns chat-view-spinner.test
  "Regression tests for the is-live gating on the ReasoningBlock spinner.
   These cover behaviour that was shipped in the is-live threading refactor
   but had no test suite before.

   Key invariant: the ✻ Thinking animated header (with Spinner) must ONLY
   appear when the assistant message is the currently-streaming one (is-live=true).
   Historic / completed messages must always render as the static ✻ Thought pill."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/chat_view.jsx" :refer [AssistantMessage ChatView]]))

(def ^:private test-theme
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :muted     "#565f89"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :border    "#3b4261"}})

(afterEach (fn [] (cleanup)))

;;; ─── AssistantMessage: is-live prop ─────────────────────────

(describe "AssistantMessage: is-live gating for ReasoningBlock"
          (fn []

            (it "is-live=true + reasoning-only content → shows ✻ Thinking header"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content  "<think>some reasoning</think>"
                                                        :theme    test-theme
                                                        :is-live  true}])]
                    (-> (expect (lastFrame)) (.toContain "Thinking")))))

            (it "is-live=false + reasoning-only content → collapses to ✻ Thought pill"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content  "<think>some reasoning</think>"
                                                        :theme    test-theme
                                                        :is-live  false}])]
                    (-> (expect (lastFrame)) (.toContain "Thought"))
                    ;; "Thinking" must NOT appear — once completed it is a static pill
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false)))))

            (it "omitted is-live (nil/undefined) also collapses to pill — nil-safe default"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>r</think>"
                                                        :theme   test-theme}])]
                    ;; is-live not passed → nil → expanded? = false → pill
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false))
                    (-> (expect (lastFrame)) (.toContain "Thought")))))

            (it "is-live=true + answer text already arrived → collapses (answer wins)"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>r</think>the answer"
                                                        :theme   test-theme
                                                        :is-live true}])]
                    ;; expanded? = (and is-live has-reasoning (not has-text))
                    ;;           = (and true true (not "the answer")) = false
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false))
                    (-> (expect (lastFrame)) (.toContain "the answer")))))

            (it "no think tags at all → no reasoning block in output"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "just plain text"
                                                        :theme   test-theme
                                                        :is-live true}])]
                    (-> (expect (lastFrame)) (.toContain "just plain text"))
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false))
                    (-> (expect (.includes (lastFrame) "Thought"))  (.toBe false)))))

            (it "token count label appears in expanded header"
                (fn []
                  ;; Estimate-tokens on a non-empty string should produce > 0
                  ;; tokens and the label should appear in the header.
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>lots of words here to ensure a positive token count</think>"
                                                        :theme   test-theme
                                                        :is-live true}])]
                    (-> (expect (lastFrame)) (.toContain "Thinking"))
                    ;; Token label is formatted as "~N tokens" or "~N.Nk tokens"
                    (-> (expect (lastFrame)) (.toContain "tokens")))))

            (it "token count label appears in collapsed pill"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>lots of words here to ensure a positive token count</think>"
                                                        :theme   test-theme
                                                        :is-live false}])]
                    (-> (expect (lastFrame)) (.toContain "Thought"))
                    (-> (expect (lastFrame)) (.toContain "tokens")))))

            (it "reasoning-only content: suppresses the empty `●` bubble row"
                ;; User-reported UX bug: a reasoning-only assistant message
                ;; (e.g. before a tool call) rendered as pill + empty `●`
                ;; bubble. The `●` looks broken because the content column
                ;; is empty. Suppress the `●` row when there's only
                ;; reasoning — the pill/block is the only signal.
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>r</think>"
                                                        :theme   test-theme
                                                        :is-live false}])]
                    ;; The pill DOES appear
                    (-> (expect (lastFrame)) (.toContain "Thought"))
                    ;; The `●` assistant bullet must NOT appear (empty bubble)
                    (-> (expect (.includes (lastFrame) "●")) (.toBe false)))))

            (it "pre-first-chunk (no reasoning, no text): shows `● …` placeholder"
                ;; Regression guard: when nothing has streamed yet (empty
                ;; content), the user still needs SOMETHING to indicate
                ;; the assistant is preparing to respond. Show `● …`.
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content ""
                                                        :theme   test-theme
                                                        :is-live true}])]
                    (-> (expect (lastFrame)) (.toContain "●"))
                    (-> (expect (lastFrame)) (.toContain "…")))))

            (it "text + reasoning: both pill AND `●` text row render"
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>r</think>the answer"
                                                        :theme   test-theme
                                                        :is-live false}])]
                    (-> (expect (lastFrame)) (.toContain "Thought"))
                    (-> (expect (lastFrame)) (.toContain "●"))
                    (-> (expect (lastFrame)) (.toContain "the answer")))))))

;;; ─── ChatView: streaming prop → is-live routing ─────────────

(describe "ChatView: streaming flag drives is-live per message"
          (fn []

            (it "streaming=false → no message ever renders ✻ Thinking"
                (fn []
                  (let [messages [{:role "user"      :content "question"}
                                  {:role "assistant" :content "<think>old reasoning</think>answer 1"}
                                  {:role "user"      :content "follow up"}
                                  {:role "assistant" :content "<think>more reasoning</think>"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages messages :theme test-theme :streaming false}])]
                    ;; With streaming=false every message is finalized → all pills
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false)))))

            (it "streaming=true → only the last message can be live; earlier ones are pills"
                (fn []
                  (let [messages [{:role "user"      :content "q1"}
                                  {:role "assistant" :content "<think>old reasoning</think>"}
                                  {:role "user"      :content "q2"}
                                  {:role "assistant" :content "<think>new reasoning</think>"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages messages :theme test-theme :streaming true}])]
                    ;; The LAST assistant message is live → "Thinking"
                    (-> (expect (lastFrame)) (.toContain "Thinking"))
                    ;; The EARLIER assistant message is not live → "Thought" (pill)
                    (-> (expect (lastFrame)) (.toContain "Thought"))
                    ;; "Thinking" appears exactly once (only the live message)
                    (let [thinking-count (dec (.-length (.split (lastFrame) "Thinking")))]
                      (-> (expect thinking-count) (.toBe 1))))))

            (it "streaming=true but last message is user → no spinner"
                (fn []
                  (let [messages [{:role "assistant" :content "<think>reasoning</think>"}
                                  {:role "user"      :content "my follow-up"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages messages :theme test-theme :streaming true}])]
                    ;; Last (live) message is user, not assistant — no Thinking header
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false)))))

            (it "streaming=true but last message is tool-start → no spinner on reasoning"
                (fn []
                  (let [messages [{:role "assistant"  :content "<think>plan reasoning</think>"}
                                  {:role "tool-start" :tool-name "read"
                                   :args {:path "f.cljs"} :exec-id "e1" :verbosity "collapsed"}]
                        {:keys [lastFrame]}
                        (render #jsx [ChatView {:messages messages :theme test-theme :streaming true}])]
                    ;; Tool-start is live but it's not AssistantMessage → no Thinking
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false)))))

            (it "rerender from streaming=true to streaming=false collapses the live message"
                (fn []
                  (let [msgs [{:role "user"      :content "q"}
                              {:role "assistant" :content "<think>reasoning</think>"}]
                        {:keys [lastFrame rerender]}
                        (render #jsx [ChatView {:messages msgs :theme test-theme :streaming true}])]
                    ;; While streaming: shows "Thinking"
                    (-> (expect (lastFrame)) (.toContain "Thinking"))
                    ;; Stream ends
                    (rerender #jsx [ChatView {:messages msgs :theme test-theme :streaming false}])
                    ;; After stream ends: collapses to pill
                    (-> (expect (.includes (lastFrame) "Thinking")) (.toBe false))
                    (-> (expect (lastFrame)) (.toContain "Thought")))))

            (it "REGRESSION: ReasoningBlock does NOT include a ticking spinner glyph"
                ;; The user reported 12 stacked "⠋ ✻ Thinking" lines leaking
                ;; into permanent scrollback. Root cause: ink-spinner ticked
                ;; every 80 ms inside a dynamic region that could reach the
                ;; terminal bottom; each tick's trailing newline scrolled the
                ;; top line into unreachable scrollback. Fix: remove the
                ;; Spinner from ReasoningBlock entirely and move visual
                ;; liveness to the status-line activity segment (which lives
                ;; in a fixed 1-row region that can't overflow).
                ;;
                ;; This test pins that the ReasoningBlock renders with a
                ;; STATIC ✻ glyph (no spinner-frame chars). If someone
                ;; reintroduces a Spinner here thinking it's harmless, this
                ;; test fails and points them at the bug we're preventing.
                (fn []
                  (let [{:keys [lastFrame]}
                        (render #jsx [AssistantMessage {:content "<think>some reasoning</think>"
                                                        :theme   test-theme
                                                        :is-live true}])
                        frame (lastFrame)
                        spinner-chars ["⠋" "⠙" "⠹" "⠸" "⠼"
                                       "⠴" "⠦" "⠧" "⠇" "⠏"]]
                    (-> (expect frame) (.toContain "Thinking"))
                    (doseq [c spinner-chars]
                      (-> (expect (.includes frame c)) (.toBe false))))))))
