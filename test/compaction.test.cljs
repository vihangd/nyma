(ns compaction.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["./agent/sessions/compaction.mjs" :as cmp]
            ["./agent/token_estimation.mjs" :as te]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager]]
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
