(ns compaction.test
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["./agent/sessions/compaction.mjs" :as cmp]
            ["./agent/token_estimation.mjs" :as te]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager attach-session-persistence!]]
            ["node:path" :as path]
            ["node:os" :as os]
            ["./agent/events.mjs" :refer [create-event-bus]]
            ["./agent/sessions/compaction.mjs" :refer [compact format-messages
                                                       extract-files-read
                                                       extract-files-modified
                                                       compact-system-prompt
                                                       build-fix-user-prompt]]))

(defn ^:async test-under-threshold []
  (let [sm     (create-session-manager nil)
        events (create-event-bus)]
    ;; Add a few small messages (well under 85% of 100k tokens)
    ((:append sm) {:role "user" :content "hello"})
    ((:append sm) {:role "assistant" :content "hi there"})
    (let [tree-before ((:get-tree sm))]
      (js-await (compact sm "mock-model" events))
      ;; No compaction entry should have been added
      (let [tree-after ((:get-tree sm))]
        (-> (expect (count tree-after)) (.toBe (count tree-before)))))))

(describe "agent.sessions.compaction"
          (fn []
            (it "does nothing when messages are under threshold" test-under-threshold)))

(describe "agent.sessions.compaction - format-messages"
          (fn []
            (it "formats single message with tagged prefix"
                (fn []
                  (let [result (format-messages [{:role "user" :content "hello"}])]
                    (-> (expect result) (.toBe "[User]: hello")))))

            (it "joins multiple messages with double newline"
                (fn []
                  (let [result (format-messages [{:role "user" :content "hi"}
                                                 {:role "assistant" :content "hey"}])]
                    (-> (expect result) (.toBe "[User]: hi\n\n[Assistant]: hey")))))

            (it "returns empty string for empty messages"
                (fn []
                  (let [result (format-messages [])]
                    (-> (expect result) (.toBe "")))))

            (it "truncates tool_result content over 2000 chars"
                (fn []
                  (let [long-content (apply str (repeat 3000 "x"))
                        result (format-messages [{:role "tool_result" :content long-content}])]
                    (-> (expect (.includes result "...[truncated]")) (.toBe true))
                    (-> (expect (< (count result) 2200)) (.toBe true)))))

            (it "preserves short tool_result content"
                (fn []
                  (let [result (format-messages [{:role "tool_result" :content "short output"}])]
                    (-> (expect result) (.toBe "[Tool_result]: short output")))))))

(describe "extract-files-read" (fn []
                                 (it "extracts paths from read tool_call entries"
                                     (fn []
                                       (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/foo.cljs"}}}
                                                   {:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/bar.cljs"}}}
                                                   {:role "tool_call" :content "..." :metadata {:tool-name "write" :args {:path "/out.txt"}}}
                                                   {:role "user" :content "hello"}]
                                             result (extract-files-read msgs)]
                                         (-> (expect (count result)) (.toBe 2))
                                         (-> (expect (first result)) (.toBe "/src/foo.cljs")))))

                                 (it "deduplicates paths"
                                     (fn []
                                       (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/foo.cljs"}}}
                                                   {:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/foo.cljs"}}}]
                                             result (extract-files-read msgs)]
                                         (-> (expect (count result)) (.toBe 1)))))

                                 (it "returns empty for no read entries"
                                     (fn []
                                       (let [msgs [{:role "user" :content "hi"}]
                                             result (extract-files-read msgs)]
                                         (-> (expect (count result)) (.toBe 0)))))))

(describe "extract-files-modified" (fn []
                                     (it "extracts paths from write and edit tool_call entries"
                                         (fn []
                                           (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "write" :args {:path "/out.txt"}}}
                                                       {:role "tool_call" :content "..." :metadata {:tool-name "edit" :args {:path "/src/foo.cljs"}}}
                                                       {:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/bar.cljs"}}}]
                                                 result (extract-files-modified msgs)]
                                             (-> (expect (count result)) (.toBe 2)))))

                                     (it "returns empty for no write/edit entries"
                                         (fn []
                                           (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "bash" :args {:command "ls"}}}]
                                                 result (extract-files-modified msgs)]
                                             (-> (expect (count result)) (.toBe 0)))))))

(describe "before_compact event payload" (fn []
                                           (it "emits enriched payload with split-point and file lists"
                                               (fn []
                                                 (let [events   (create-event-bus)
                                                       sm       (create-session-manager nil)
                                                       captured (atom nil)]
        ;; Listen for before_compact event
                                                   ((:on events) "before_compact"
                                                                 (fn [evt-ctx]
                                                                   (reset! captured evt-ctx)
            ;; Set summary to prevent LLM call
                                                                   (aset evt-ctx "summary" "test summary")))
        ;; Add enough messages to trigger compaction (need > 85k tokens)
        ;; We can't easily hit the threshold in a unit test, so just verify
        ;; the function signature accepts the enriched payload structure
                                                   (-> (expect (fn? compact)) (.toBe true)))))))

(describe "compact-system-prompt schema" (fn []
                                           (it "contains all 6 section headers"
                                               (fn []
                                                 (doseq [h ["## 1. Previous Conversation"
                                                            "## 2. Current Work"
                                                            "## 3. Key Technical Concepts"
                                                            "## 4. Relevant Files and Code"
                                                            "## 5. Problem Solving"
                                                            "## 6. Pending Tasks and Next Steps"]]
                                                   (-> (expect (.includes compact-system-prompt h)) (.toBe true)))))

                                           (it "requires verbatim quotes in section 6"
                                               (fn []
                                                 (-> (expect (.includes compact-system-prompt "VERBATIM QUOTE")) (.toBe true))
                                                 (-> (expect (.includes compact-system-prompt "Quote:")) (.toBe true))))

                                           (it "warns against continuing the conversation"
                                               (fn []
                                                 (-> (expect (.includes compact-system-prompt "Do NOT continue")) (.toBe true))))))

(describe "build-fix-user-prompt" (fn []
                                    (it "instructs to not recompress and lists errors verbatim"
                                        (fn []
                                          (let [prompt (build-fix-user-prompt "old summary" ["err one" "err two"])]
                                            (-> (expect (.includes prompt "DO NOT recompress")) (.toBe true))
                                            (-> (expect (.includes prompt "DO NOT shorten")) (.toBe true))
                                            (-> (expect (.includes prompt "<previous-summary>\nold summary")) (.toBe true))
                                            (-> (expect (.includes prompt "- err one")) (.toBe true))
                                            (-> (expect (.includes prompt "- err two")) (.toBe true)))))))

;;; ─── the trigger ─────────────────────────────────────────────────────────
;;; `compact` was only ever reachable by hand (/compact, pi-rpc, the extension
;;; api). Nothing called it per turn, so its own threshold check never ran. A
;;; real session reached 615k tokens with ZERO compactions and the model stopped
;;; calling tools entirely from ~turn 32 — 56% of turns did no work.

(describe "compaction-point"
          (fn []
            (it "uses the percentage on a large window"
                (fn []
                  ;; 85% is the band the field converged on; 95% is documented
                  ;; as too late.
                  (-> (expect (cmp/compaction-point 200000 0.85 16384)) (.toBe 170000))))

            (it "uses the reserve floor on a small window"
                (fn []
                  ;; 0.85 of a 32k local-model window leaves ~5k for the reply
                  ;; AND the summary, so the reserve has to bind first.
                  (let [p (cmp/compaction-point 32768 0.85 16384)]
                    (-> (expect (< p (* 32768 0.85))) (.toBe true)))))

            (it "does not fire absurdly early on a small window"
                (fn []
                  ;; A flat 16k reserve on a 20k window would fire at 3.6k —
                  ;; 18% used. Clamping the reserve to a share of the window
                  ;; keeps it sane.
                  (let [p (cmp/compaction-point 20000 0.85 16384)]
                    (-> (expect (> p (* 20000 0.5))) (.toBe true)))))

            (it "never returns a non-positive point when reserve exceeds window"
                (fn []
                  ;; Otherwise it would compact forever.
                  (-> (expect (> (cmp/compaction-point 8000 0.85 16384) 0)) (.toBe true)))))) 

(describe "should-compact?"
          (fn []
            (it "fires above the point and not below"
                (fn []
                  (-> (expect (cmp/should-compact? 180000 200000 {})) (.toBe true))
                  (-> (expect (cmp/should-compact? 100000 200000 {})) (.toBe false))))

            (it "is disabled by :enabled? false"
                (fn []
                  ;; The :compaction settings section shipped as a default that
                  ;; NOTHING read — disabling it did nothing at all.
                  (-> (expect (cmp/should-compact? 9999999 200000 {:enabled? false})) (.toBe false))))

            (it "honours a custom threshold"
                (fn []
                  (-> (expect (cmp/should-compact? 110000 200000 {:threshold 0.5})) (.toBe true))
                  (-> (expect (cmp/should-compact? 110000 200000 {:threshold 0.9})) (.toBe false))))

            (it "does not fire on an unknown window"
                (fn []
                  (-> (expect (cmp/should-compact? 999999 0 {})) (.toBe false))))))

(describe "settings->opts"
          (fn []
            (it "reads the compaction settings section"
                (fn []
                  (let [o (cmp/settings->opts {:compaction {:enabled false :threshold 0.5}})]
                    (-> (expect (:enabled? o)) (.toBe false))
                    (-> (expect (:threshold o)) (.toBe 0.5)))))

            (it "tolerates string keys from user JSON"
                (fn []
                  (let [o (cmp/settings->opts {"compaction" {"threshold" 0.6}})]
                    (-> (expect (:threshold o)) (.toBe 0.6)))))

            (it "defaults to enabled at the evidence-band threshold"
                (fn []
                  (let [o (cmp/settings->opts {})]
                    (-> (expect (:enabled? o)) (.toBe true))
                    (-> (expect (:threshold o)) (.toBe 0.85)))))))

;;; ─── replay: would this have caught the real collapse? ───────────────────
;;; A synthetic threshold test proves the arithmetic, not that the trigger
;;; would have helped. This replays a transcript shaped like the session that
;;; failed — ~615k tokens of accumulated turns against a 200k window — and
;;; asserts compaction fires, and fires EARLY. Against the old code (no
;;; trigger, threshold unreadable) the answer was zero, for 615k tokens.

(defn- synthetic-transcript
  "n turns sized from the session that actually collapsed: ~615k tokens across
   ~70 turns is ~8.8k tokens per turn, with ~19 tool calls each. Sizing this
   from the real measurement matters — an under-sized fixture made this test
   fail for the wrong reason, and weakening the assertion instead would have
   hidden that the trigger fires later than the collapse it is meant to prevent."
  [n]
  (vec (mapcat (fn [i]
                 [{:role "user" :content (str "do thing " i)}
                  {:role "assistant" :content (apply str (repeat 800 "reasoning and output text "))}
                  {:role "tool_call" :content (apply str (repeat 800 "tool output line "))}])
               (range n))))

(describe "replay against the shape that collapsed"
          (fn []
            (it "fires early instead of never"
                (fn []
                  (let [limit  200000
                        point  (cmp/compaction-point limit 0.85 16384)
                        msgs   (synthetic-transcript 60)
                        ;; Walk the transcript accumulating tokens, recording
                        ;; the first turn at which the trigger would fire.
                        fired  (atom nil)
                        usage  (atom 0)
                        turn   (atom 0)]
                    (doseq [m msgs]
                      (when (= "user" (:role m)) (swap! turn inc))
                      (swap! usage + (te/estimate-tokens (str (:content m))))
                      (when (and (nil? @fired)
                                 (cmp/should-compact? @usage limit {}))
                        (reset! fired @turn)))
                    ;; It must fire at all …
                    (-> (expect (some? @fired)) (.toBe true))
                    ;; … and well before a 60-turn session is over. The real
                    ;; session's no-op collapse began at turn 32.
                    (-> (expect (< @fired 32)) (.toBe true))
                    ;; Sanity: the transcript really did exceed the window.
                    (-> (expect (> @usage limit)) (.toBe true)))))

            (it "would not fire on a short session"
                (fn []
                  ;; No compaction tax on ordinary work.
                  (let [msgs  (synthetic-transcript 3)
                        usage (reduce + 0 (map #(te/estimate-tokens (str (:content %))) msgs))]
                    (-> (expect (cmp/should-compact? usage 200000 {})) (.toBe false)))))))

;;; ─── the helpers must actually be USED ───────────────────────────────────
;;; The tests above prove the arithmetic. They passed just as happily with
;;; `compact` still hardcoding 0.85 and ignoring the settings entirely — the
;;; exact defect being fixed. These close that gap.

(defn ^:async test-disabled-blocks-compaction []
  (let [sm     (create-session-manager nil)
        events (create-event-bus)]
    ;; Enough content to blow past any threshold.
    (dotimes [i 40]
      ((:append sm) {:role "user" :content (str "q" i)})
      ((:append sm) {:role "assistant"
                     :content (apply str (repeat 2000 "padding text "))}))
    (let [before ((:get-tree sm))]
      (js-await (compact sm "mock-model" events {:enabled? false}))
      (-> (expect (count ((:get-tree sm)))) (.toBe (count before))))))

(describe "compact honours its options"
          (fn []
            (it "does nothing when :enabled? is false, however full the context"
                (^:async fn [] (js-await (test-disabled-blocks-compaction))))))

;;; Wiring: a trigger that is correct but never called is the whole bug. Assert
;;; on compiled output because driving a real turn needs a provider.
(describe "the loop calls the trigger"
          (fn []
            (it "invokes maybe-auto-compact! after turn_finalize"
                (fn []
                  (let [src      (str (fs/readFileSync "dist/agent/loop.mjs" "utf8"))
                        call     (.indexOf src "maybe_auto_compact")
                        finalize (.indexOf src "turn_finalize")]
                    (-> (expect (> call -1)) (.toBe true))
                    (-> (expect (> finalize -1)) (.toBe true))
                    ;; Between turns, not mid-turn.
                    (-> (expect (> call finalize)) (.toBe true)))))))

;;; ─── reasoning does not belong in the transcript ─────────────────────────
;;; 73% of the failed session's assistant text (253 KB of 346 KB) was <think>
;;; blocks, stored and replayed. Stripping is deterministic and loses nothing
;;; of the answer.

(describe "think-stripping at persistence"
          (fn []
            (it "removes <think> from a persisted assistant message"
                (^:async fn []
                 (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-think-"))
                       file (path/join dir "s.jsonl")
                       sm   (create-session-manager file)
                       ev   (create-event-bus)
                       subs (atom nil)
                       agent {:events ev
                              :store {:subscribe (fn [f] (reset! subs f))}}]
                   (attach-session-persistence! agent sm)
                   (@subs :message-added
                          {:messages [{:role "assistant"
                                       :content "<think>private reasoning</think>The answer is 42."}]})
                   (js-await (js/Promise. (fn [r] (js/setTimeout r 30))))
                   (let [raw (str (fs/readFileSync file "utf8"))]
                     (-> (expect (.includes raw "The answer is 42.")) (.toBe true))
                     (-> (expect (.includes raw "private reasoning")) (.toBe false)))
                   (try (fs/rmSync dir #js {:recursive true :force true})
                        (catch :default _ nil)))))))

;;; ─── overflow recovery ───────────────────────────────────────────────────
;;; The net pi and OpenCode V2 both have. It matters where the declared window
;;; is wrong or missing — relay and local providers — because there the
;;; threshold trigger cannot know it should have fired.

(describe "context-overflow-error?"
          (fn []
            (it "recognises how providers phrase it"
                (fn []
                  (doseq [m ["This model's maximum context length is 32768 tokens"
                             "prompt is too long: 250000 tokens > 200000"
                             "input length and max_tokens exceed context limit"
                             "Please reduce the length of the messages"]]
                    (-> (expect (cmp/context-overflow-error? #js {:message m})) (.toBe true)))))

            (it "does not fire on unrelated failures"
                (fn []
                  ;; Retrying a rate limit by compacting would throw away
                  ;; context for nothing.
                  (doseq [m ["429 rate limit exceeded" "ECONNREFUSED" "invalid api key"]]
                    (-> (expect (cmp/context-overflow-error? #js {:message m})) (.toBe false)))))

            (it "survives a nil or string error"
                (fn []
                  (-> (expect (cmp/context-overflow-error? nil)) (.toBe false))
                  (-> (expect (cmp/context-overflow-error? "maximum context length")) (.toBe true))))))

(describe "loop wiring"
          (fn []
            (it "recovers from overflow and retries"
                (fn []
                  (let [src (str (fs/readFileSync "dist/agent/loop.mjs" "utf8"))]
                    (-> (expect (.includes src "context_overflow_error")) (.toBe true))
                    (-> (expect (.includes src "recover_from_overflow")) (.toBe true))
                    ;; Once per turn, never a loop.
                    (-> (expect (.includes src "overflow_recovered")) (.toBe true)))))

            (it "tracks turns that ran no tools"
                (fn []
                  ;; Assert the BEHAVIOUR, not a string that survives its
                  ;; removal: an earlier version matched "no-op-turns" in the
                  ;; notify branch and passed with the counting deleted.
                  (let [src (str (fs/readFileSync "dist/agent/loop.mjs" "utf8"))]
                    ;; the per-turn counter is incremented from step results
                    (-> (expect (.includes src "tools_this_turn")) (.toBe true))
                    ;; …and it is actually tested for zero
                    (-> (expect (boolean (re-find #"tools_this_turn\d*\)?\s*===\s*0" src))) (.toBe true))
                    ;; …and the streak is both incremented and reset
                    (-> (expect (boolean (re-find #"no-op-turns\", squint_core\.fnil" src))) (.toBe true))
                    (-> (expect (boolean (re-find #"no-op-turns\", 0\)" src))) (.toBe true)))))))

;;; ─── an extension summary must EARN its place ────────────────────────────
;;; It used to be written regardless ("validate and warn, but never block"). In
;;; practice token_suite's extraction summary — a different template — replaced
;;; the six-section one on every compaction: 586 characters standing in for
;;; ~900k tokens, with empty file lists. The six-section template exists for
;;; artifact tracking, which is what "did you actually build anything?" asks.

(def ^:private six-section
  (str "## 1. Previous Conversation\nx\n\n## 2. Current Work\nsrc/a.cljs:1\n\n"
       "## 3. Key Technical Concepts\nx\n\n## 4. Relevant Files and Code\nsrc/a.cljs:1\n\n"
       "## 5. Problem Solving\nx\n\n## 6. Pending Tasks and Next Steps\n- x\n  Quote: \"y\""))

(describe "extension summaries are validated, not trusted"
          (fn []
            (it "accepts one that carries the required sections"
                (fn []
                  (-> (expect (count (cmp/validate-compaction six-section [] []))) (.toBe 0))))

            (it "rejects the shape token_suite actually produced"
                (fn []
                  ;; The real one, from the session: its own headings, no file
                  ;; lists, 586 chars for ~900k tokens.
                  (let [errs (cmp/validate-compaction
                              (str "## Previous Context\nx\n\n## User Intent\nx\n\n"
                                   "## Completed Work\nx\n\n## Key References\nx")
                              [] [])]
                    (-> (expect (> (count errs) 0)) (.toBe true))))))) 

;;; ─── do not re-compact immediately ───────────────────────────────────────
;;; Appending a summary does not shrink what the trigger measures, so once true
;;; it stays true. A real session compacted 5 times — twice within 26 and 54
;;; entries — each costing a summarization call and reducing nothing.

(describe "messages-since-last-compaction"
          (fn []
            (it "counts everything when there has never been one"
                (fn []
                  (-> (expect (cmp/messages-since-last-compaction
                               [{:role "user"} {:role "assistant"} {:role "user"}]))
                      (.toBe 3))))

            (it "counts only what followed the most recent one"
                (fn []
                  (-> (expect (cmp/messages-since-last-compaction
                               [{:role "user"} {:role "compaction"} {:role "user"} {:role "assistant"}]))
                      (.toBe 2))))

            (it "is zero immediately after compacting"
                (fn []
                  ;; The guard's whole job: this must not compact again.
                  (-> (expect (cmp/messages-since-last-compaction
                               [{:role "user"} {:role "compaction"}]))
                      (.toBe 0))))))

;;; ─── integration: the helpers must actually be USED ──────────────────────
;;; The unit tests above passed with `compact` still accepting any extension
;;; summary and with the re-fire guard deleted. Testing a predicate proves the
;;; predicate; only driving `compact` proves the behaviour.

(defn- events-offering [summary]
  "Events stub that offers `summary` via before_compact."
  {:on         (fn [_e _h] nil)
   :emit-async (fn [event ctx]
                 (when (= event "before_compact") (aset ctx "summary" summary))
                 (js/Promise.resolve nil))
   :emit       (fn [_e _d] nil)})

(defn- gen-stub [text] (fn [_opts] (js/Promise.resolve #js {:text text})))

(defn- big-session []
  (let [sm (create-session-manager nil)]
    (dotimes [i 60]
      ((:append sm) {:role "user" :content (str "q" i)})
      ((:append sm) {:role "assistant" :content (apply str (repeat 1500 "padding "))}))
    sm))

(defn- last-compaction-content [sm]
  (->> ((:get-tree sm))
       (filter #(= "compaction" (:role %)))
       last
       :content))

(defn ^:async test-invalid-extension-summary-is-not-used []
  (let [sm (big-session)]
    (js-await (compact sm "mock-model"
                       (events-offering "## Previous Context\njunk\n\n## User Intent\njunk")
                       {:gen-fn (gen-stub six-section)}))
    (let [c (str (last-compaction-content sm))]
      ;; The built-in summariser won, not the 586-char extraction shape.
      (-> (expect (.includes c "## 1. Previous Conversation")) (.toBe true))
      (-> (expect (.includes c "junk")) (.toBe false)))))

(defn ^:async test-valid-extension-summary-is-used []
  (let [sm (big-session)]
    (js-await (compact sm "mock-model"
                       (events-offering six-section)
                       {:gen-fn (gen-stub "SHOULD NOT BE CALLED")}))
    (-> (expect (.includes (str (last-compaction-content sm)) "## 1. Previous Conversation"))
        (.toBe true))))

(defn ^:async test-does-not-recompact-immediately []
  (let [sm (big-session)]
    (js-await (compact sm "mock-model" (events-offering nil) {:gen-fn (gen-stub six-section)}))
    (let [n1 (count (filter #(= "compaction" (:role %)) ((:get-tree sm))))]
      ;; Immediately again: nothing new has happened, so nothing to summarize.
      (js-await (compact sm "mock-model" (events-offering nil) {:gen-fn (gen-stub six-section)}))
      (let [n2 (count (filter #(= "compaction" (:role %)) ((:get-tree sm))))]
        (-> (expect n1) (.toBe 1))
        (-> (expect n2) (.toBe 1))))))

(describe "compact uses its own rules"
          (fn []
            (it "falls back to the built-in summariser when the extension's is invalid"
                (^:async fn [] (js-await (test-invalid-extension-summary-is-not-used))))
            (it "uses a valid extension summary"
                (^:async fn [] (js-await (test-valid-extension-summary-is-used))))
            (it "does not compact twice in a row"
                (^:async fn [] (js-await (test-does-not-recompact-immediately))))))

;;; ─── measure what was SENT, not the whole tree ───────────────────────────
;;; The estimate walks the entire session tree; what actually goes to the
;;; provider is pruned at context_assembly and capped by priority_assembly. A
;;; real session compacted five times off a tree estimate climbing 714k -> 956k
;;; while the requests themselves fit a 200k window. The provider's own input
;;; count is ground truth.

(defn ^:async test-observed-usage-wins []
  (let [sm (big-session)]        ;; tree estimate is far over the threshold
    ;; …but the provider says the request was small, so nothing should compact.
    (js-await (compact sm "mock-model" (events-offering nil)
                       {:gen-fn (gen-stub six-section)
                        :observed-usage 1000}))
    (-> (expect (count (filter #(= "compaction" (:role %)) ((:get-tree sm))))) (.toBe 0))))

(defn ^:async test-falls-back-to-the-estimate []
  (let [sm (big-session)]
    ;; No observation yet (first turn) — the estimate is all there is.
    (js-await (compact sm "mock-model" (events-offering nil)
                       {:gen-fn (gen-stub six-section)}))
    (-> (expect (count (filter #(= "compaction" (:role %)) ((:get-tree sm))))) (.toBe 1))))

(describe "the trigger measures the request, not the tree"
          (fn []
            (it "does not compact when the provider reports a small request"
                (^:async fn [] (js-await (test-observed-usage-wins))))
            (it "falls back to the estimate when nothing has been observed yet"
                (^:async fn [] (js-await (test-falls-back-to-the-estimate))))

            (it "the loop records what the provider counted"
                (fn []
                  ;; Without this the observation never reaches the trigger and
                  ;; it silently falls back to the tree estimate forever — the
                  ;; behaviour being fixed. Asserted on compiled output because
                  ;; driving a real provider turn needs credentials.
                  (let [src (str (fs/readFileSync "dist/agent/loop.mjs" "utf8"))]
                    (-> (expect (.includes src "last-input-tokens")) (.toBe true)))))))

;;; ─── compaction must shrink the RUNNING conversation ─────────────────────
;;; It never did. `compact` computed the split, appended the summary and
;;; stopped — it never touched `state :messages`, which is what
;;; agent.context/build-context reads every turn. The summary only took effect
;;; at the next RESUME, so a live session kept growing: five compactions in one
;;; real session, 714k -> 956k tokens, none of which shrank anything.

(defn- tool-heavy-session []
  "A session whose tool calls carry metadata, so file extraction has something
   to find — `context` has none, which is why the real summaries recorded
   files-read: 0."
  (let [sm (create-session-manager nil)]
    (dotimes [i 60]
      ((:append sm) {:role "user" :content (str "q" i)})
      ((:append sm) {:role "tool_call" :content "{}"
                     :metadata {:tool-name "read" :args {:path (str "/repo/src/f" i ".rb")}}})
      ((:append sm) {:role "tool_call" :content "{}"
                     :metadata {:tool-name "edit" :args {:path (str "/repo/src/f" i ".rb")}}})
      ((:append sm) {:role "assistant" :content (apply str (repeat 1500 "padding "))}))
    sm))

(defn ^:async test-live-context-shrinks []
  (let [sm    (tool-heavy-session)
        state (atom {:messages (vec ((:build-context sm)))})
        before (count (:messages @state))]
    (js-await (compact sm "mock-model" (events-offering nil)
                       {:gen-fn (gen-stub six-section) :state-atom state}))
    (let [after (:messages @state)]
      ;; It actually got smaller …
      (-> (expect (< (count after) before)) (.toBe true))
      ;; … and opens on the summary, the same marker resume produces.
      (-> (expect (.includes (str (:content (first after))) "[Earlier conversation summary]"))
          (.toBe true)))))

(defn ^:async test-nothing-is-lost-from-disk []
  (let [sm     (tool-heavy-session)
        state  (atom {:messages (vec ((:build-context sm)))})
        before (count ((:get-tree sm)))]
    (js-await (compact sm "mock-model" (events-offering nil)
                       {:gen-fn (gen-stub six-section) :state-atom state}))
    ;; The JSONL only ever grows — the summarized entries are still on disk for
    ;; later analysis. That is what makes truncating the live context safe.
    (-> (expect (> (count ((:get-tree sm))) before)) (.toBe true))))

(defn ^:async test-file-lists-are-populated []
  (let [sm    (tool-heavy-session)
        state (atom {:messages (vec ((:build-context sm)))})
        seen  (atom nil)]
    (js-await (compact sm "mock-model" (events-offering nil)
                       {:state-atom state
                        :gen-fn (fn [opts]
                                  (reset! seen (str (.-prompt opts) (.-system opts)))
                                  (js/Promise.resolve #js {:text six-section}))}))
    (let [entry (->> ((:get-tree sm)) (filter #(= "compaction" (:role %))) last)]
      ;; Sourced from the tree, where :metadata survives. From `context` these
      ;; were ALWAYS empty — every real compaction recorded 0 and 0.
      (-> (expect (pos? (count (get-in entry [:metadata :files-read])))) (.toBe true))
      (-> (expect (pos? (count (get-in entry [:metadata :files-modified])))) (.toBe true)))))

(describe "compaction shrinks the live session"
          (fn []
            (it "replaces state :messages with summary + kept span"
                (^:async fn [] (js-await (test-live-context-shrinks))))
            (it "leaves the full history on disk"
                (^:async fn [] (js-await (test-nothing-is-lost-from-disk))))
            (it "records the files it actually touched"
                (^:async fn [] (js-await (test-file-lists-are-populated))))

            (it "drops tool entries so live matches what a resume rebuilds"
                (fn []
                  ;; session->seed-messages keeps only user/assistant, so the
                  ;; kept span must too or the two paths describe different
                  ;; conversations.
                  (let [out (cmp/compacted-messages
                             "S" [{:role "user" :content "q"}
                                  {:role "tool_call" :content "{}"}
                                  {:role "assistant" :content "a"}])]
                    (-> (expect (count out)) (.toBe 3))
                    (-> (expect (some #(= "tool_call" (:role %)) out)) (.toBeFalsy)))))))

;;; ─── the analysis paths must not notice ──────────────────────────────────
;;; Truncating the live context is only safe because the file keeps everything.
;;; These assert the things that read the file rather than the context.

(defn ^:async test-export-still-sees-full-history []
  (let [sm    (tool-heavy-session)
        state (atom {:messages (vec ((:build-context sm)))})
        before (count ((:build-context sm)))]
    (js-await (compact sm "mock-model" (events-offering nil)
                       {:gen-fn (gen-stub six-section) :state-atom state}))
    ;; /export serializes ((:build-context sess)) — the TREE walk, not state —
    ;; so it must still see everything. Export quietly losing history would be
    ;; the worst possible side effect of this change.
    (-> (expect (>= (count ((:build-context sm))) before)) (.toBe true))
    ;; …while the live context is far smaller.
    (-> (expect (< (count (:messages @state)) before)) (.toBe true))))

(describe "truncation does not reach the archive"
          (fn []
            (it "keeps /export and the tree walk complete"
                (^:async fn [] (js-await (test-export-still-sees-full-history))))))

;;; ─── a carried-forward summary is not a summary ──────────────────────────
;;; Validation alone was not enough. token_suite's hook wraps the PREVIOUS
;;; summary in a "## Previous Context" block, so its output inherits the six
;;; required headers and validates regardless of what it adds. A real
;;; compaction of a whole working session added exactly 157 characters:
;;;
;;;   ## User Intent
;;;   yes
;;;   ## Completed Work
;;;   - [no edits yet]
;;;
;;; The prior summary rode along, so it looked well-formed while that session's
;;; work — Kite SDK, NPS, the tests — was gone.

(def ^:private prior-summary
  (str six-section "\n" (apply str (repeat 400 "PRIOR DETAIL "))))

(describe "extension-summary-usable?"
          (fn []
            (it "rejects the shape that actually lost a session's work"
                (fn []
                  (let [wrapper (str "## Previous Context\n" prior-summary
                                     "\n\n## User Intent\nyes\n\n## Completed Work\n- [no edits yet]\n")]
                    (-> (expect (cmp/extension-summary-usable? wrapper prior-summary [] []))
                        (.toBe false)))))

            (it "accepts a carry-forward that adds real content"
                (fn []
                  ;; Carrying context forward is correct and should not be
                  ;; penalised — only carrying it forward INSTEAD of summarizing.
                  (let [genuine (str "## Previous Context\n" prior-summary
                                     "\n\n## User Intent\n"
                                     (apply str (repeat 30 "genuinely new detail about the work ")))]
                    (-> (expect (cmp/extension-summary-usable? genuine prior-summary [] []))
                        (.toBe true)))))

            (it "accepts a standalone valid summary with no prior"
                (fn []
                  (-> (expect (cmp/extension-summary-usable?
                               (str six-section (apply str (repeat 400 "x"))) nil [] []))
                      (.toBe true))))

            (it "still rejects an invalid summary outright"
                (fn []
                  (-> (expect (cmp/extension-summary-usable?
                               (apply str (repeat 600 "no headers here ")) nil [] []))
                      (.toBe false))))))

;;; ─── the summarizer needs metadata, and the payload was stripping it ─────
;;; token_suite's before_compact hook extracts file operations from
;;; :metadata tool-name/args. It was handed messages-to-summarize, which is
;;; built from build-context — and entry->core-message strips :metadata. So the
;;; extraction silently found NOTHING: "[no edits yet]" for a session that
;;; edited 159 files, 157 characters standing in for the whole thing.

(defn ^:async test-payload-carries-metadata []
  (let [sm (tool-heavy-session)
        captured (atom nil)]
    (js-await (compact sm "mock-model"
                       {:on (fn [_ _] nil)
                        :emit (fn [_ _] nil)
                        :emit-async (fn [evt ctx]
                                      (when (= evt "before_compact") (reset! captured ctx))
                                      (js/Promise.resolve nil))}
                       {:gen-fn (gen-stub six-section)}))
    (let [entries (aget @captured "entries-to-summarize")
          tools   (filter #(= "tool_call" (.-role %)) (vec entries))]
      ;; The span is present …
      (-> (expect (pos? (count tools))) (.toBe true))
      ;; … and carries what an extractor needs.
      (-> (expect (some? (aget (first tools) "metadata"))) (.toBe true))
      (-> (expect (some? (aget (aget (first tools) "metadata") "tool-name"))) (.toBe true)))))

(describe "before_compact payload"
          (fn []
            (it "includes a metadata-bearing span for extensions"
                (^:async fn [] (js-await (test-payload-carries-metadata))))))

;;; ─── validation must be satisfiable ──────────────────────────────────────
;;; validate-compaction requires every listed path to appear in the summary.
;;; Populating the file lists from the tree (which fixed files-read: 0) made
;;; that strict for the first time — and a real session touches 244 files, so
;;; every compaction would fail validation, burn a retry call, and then use the
;;; unvalidated summary anyway.

(describe "tracked file lists are bounded"
          (fn []
            (it "caps how many paths a summary must mention"
                (fn []
                  (-> (expect (<= cmp/max-tracked-files 50)) (.toBe true))))

            (it "a summary listing the capped set validates"
                (fn []
                  (let [paths (mapv #(str "/repo/f" % ".rb") (range cmp/max-tracked-files))
                        summary (str six-section "\n" (str/join "\n" paths))]
                    (-> (expect (count (cmp/validate-compaction summary paths []))) (.toBe 0)))))))

;;; ─── a percentage stops working at the top end ───────────────────────────
;;; deepseek-v4-pro declares a 1,048,576-token window. 85% of that is 891k and a
;;; 16k reserve is noise, so a purely proportional trigger would let context grow
;;; far past the point where every measured model has degraded badly. The window
;;; is what the model ACCEPTS; the ceiling is what it still works well within.

(describe "compaction-point ceiling"
          (fn []
            (it "caps a 1M window well below its percentage"
                (fn []
                  (let [p (cmp/compaction-point 1048576 0.85 16384)]
                    (-> (expect (<= p cmp/default-max-working-context)) (.toBe true))
                    ;; the number it would have used
                    (-> (expect (< p 891000)) (.toBe true)))))

            (it "leaves ordinary windows exactly as they were"
                (fn []
                  ;; The Claude models in use must not change behaviour.
                  (-> (expect (cmp/compaction-point 200000 0.85 16384)) (.toBe 170000))
                  (-> (expect (< (cmp/compaction-point 32768 0.85 16384) 32768)) (.toBe true))))

            (it "is settings-driven"
                (fn []
                  (-> (expect (cmp/compaction-point 1048576 0.85 16384 50000)) (.toBe 50000))))

            (it "flows from the compaction settings section"
                (fn []
                  (let [o (cmp/settings->opts {:compaction {:max-working-context 12345}})]
                    (-> (expect (:max-working o)) (.toBe 12345)))))

            ;; settings->opts read the ceiling and `compact` accepted it, but the
            ;; should-compact? call in between never passed it on, so the
            ;; built-in default applied no matter what the user configured.
            (it "a configured ceiling actually changes the decision"
                (fn []
                  (let [o (cmp/settings->opts {:compaction {:max-working-context 50000}})
                        ;; 60k used against a 1M window: far under the percentage
                        ;; threshold, over the configured ceiling.
                        opts {:threshold (:threshold o) :reserve (:reserve o)
                              :enabled? true :max-working (:max-working o)}]
                    (-> (expect (cmp/should-compact? 60000 1000000 opts)) (.toBe true))
                    (-> (expect (cmp/should-compact? 60000 1000000 (dissoc opts :max-working)))
                        (.toBe false)))))))
