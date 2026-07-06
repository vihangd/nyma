(ns small-model-borrows.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.small-model.knowledge-inject :as ki]
            [agent.extensions.small-model.finalize-warn :as fw]
            [agent.extensions.small-model.profiles :as profiles]))

;; ── knowledge-inject: scorer + selection ─────────────────────────

(describe "knowledge-inject score/select" (fn []

                                            (it "scores shared tokens (1.0) and bigrams (2.0)"
                                                (fn []
      ;; query shares tokens "binary" "search" + bigram "binary search" (+2)
                                                  (let [c (ki/prep-card {:match-text "binary search trees and sorting"})
                                                        s (ki/score "implement binary search" (:c-toks c) (:c-bi c))]
        ;; tokens: binary(1)+search(1) = 2 ; bigram binary·search = 2 → 4
                                                    (-> (expect (>= s 4)) (.toBe true)))))

                                            (it "unrelated query scores below threshold"
                                                (fn []
                                                  (let [c (ki/prep-card {:match-text "binary search algorithm"})]
                                                    (-> (expect (< (ki/score "format css colors" (:c-toks c) (:c-bi c)) 2.0)) (.toBe true)))))

                                            (it "select keeps only ≥ threshold and caps at top-k"
                                                (fn []
                                                  (let [cards (map ki/prep-card
                                                                   [{:name "bsearch" :match-text "binary search algorithm"}
                                                                    {:name "dijkstra" :match-text "dijkstra shortest path graph"}
                                                                    {:name "css" :match-text "css flexbox layout"}])
                                                        chosen (ki/select cards "binary search on a sorted array" 2.0 1)]
                                                    (-> (expect (count chosen)) (.toBe 1))
                                                    (-> (expect (:name (first chosen))) (.toBe "bsearch")))))

                                            (it "select returns empty when nothing clears the threshold"
                                                (fn []
                                                  (let [cards (map ki/prep-card [{:name "css" :match-text "css flexbox layout"}])]
                                                    (-> (expect (count (ki/select cards "binary search" 2.0 2))) (.toBe 0)))))))

;; ── knowledge-inject: card loading from disk ─────────────────────

(def ^:private tmp (atom nil))

(beforeEach
 (fn []
   (let [d (path/join (os/tmpdir) (str "nyma-ki-" (js/Date.now)))]
     (fs/mkdirSync (path/join d ".nyma" "knowledge") #js {:recursive true})
     (reset! tmp d))))

(afterEach
 (fn []
   (when @tmp (try (fs/rmSync @tmp #js {:recursive true :force true}) (catch :default _)))))

(describe "knowledge-inject load-cards" (fn []

                                          (it "loads *.md cards with frontmatter name/description + body"
                                              (fn []
                                                (let [kdir (path/join @tmp ".nyma" "knowledge")
                                                      _    (fs/writeFileSync (path/join kdir "bsearch.md")
                                                                             "---\nname: bsearch\ndescription: binary search on sorted arrays\n---\nMid = (lo+hi)/2.")
                                                      cwd  (js/process.cwd)]
                                                  (try
                                                    (.chdir js/process @tmp)
                                                    (let [cards (ki/load-cards "knowledge")]
                                                      (-> (expect (count cards)) (.toBe 1))
                                                      (-> (expect (:name (first cards))) (.toBe "bsearch"))
                                                      (-> (expect (.includes (:match-text (first cards)) "binary search")) (.toBe true))
                                                      (-> (expect (.includes (:body (first cards)) "Mid =")) (.toBe true)))
                                                    (finally (.chdir js/process cwd))))))))

;; ── finalize-warn: premature heuristic ───────────────────────────

(describe "finalize-warn premature?" (fn []

                                       (it "flags a 'stop' turn whose text reads unfinished"
                                           (fn []
                                             (-> (expect (fw/premature? "stop" "Next step: I'll wire up the handler.")) (.toBe true))
                                             (-> (expect (fw/premature? "stop" "Should I proceed with the refactor?")) (.toBe true))))

                                       (it "does not flag a turn that ended in a tool call"
                                           (fn []
                                             (-> (expect (fw/premature? "tool-calls" "Next step: edit the file.")) (.toBe false))))

                                       (it "does not flag an aborted turn"
                                           (fn []
                                             (-> (expect (fw/premature? "stream-filter-aborted" "let me continue")) (.toBe false))))

                                       (it "does not flag a genuinely-finished answer with no open-task markers"
                                           (fn []
                                             (-> (expect (fw/premature? "stop" "The bug was a stale cache; fixed in cache.ts.")) (.toBe false))))

                                       (it "does not flag empty/blank text"
                                           (fn []
                                             (-> (expect (fw/premature? "stop" "")) (.toBe false))))

                                       (it "does not fire on soft closers (false-positive guard)"
                                           (fn []
                                             (-> (expect (fw/premature? "stop" "Done. Let me know if you need anything else.")) (.toBe false))
                                             (-> (expect (fw/premature? "stop" "No remaining issues; the suite is green.")) (.toBe false))))

                                       ;; finishReason is normalized to a string upstream (loop.cljs
                                       ;; agent_end emit), so premature? only ever sees a string.
                                       (it "expects a normalized string finishReason"
                                           (fn []
                                             (-> (expect (fw/premature? "stop" "Next step: add the test.")) (.toBe true))
                                             (-> (expect (fw/premature? "tool-calls" "Next step: add the test.")) (.toBe false))))

                                       (it "does not fire on bare 'I'll <word>' in a finished answer"
                                           (fn []
                                             (-> (expect (fw/premature? "stop" "Done — I'll keep the original formatting.")) (.toBe false))))))

(defn- fake-api []
  (let [handlers (atom {}) sent (atom [])]
    {:obj #js {:on              (fn [ev h] (swap! handlers assoc ev h))
               :off             (fn [_ _] nil)
               :sendUserMessage (fn [msg _opts] (swap! sent conj msg))}
     :handlers handlers
     :sent sent}))

(describe "finalize-warn handler" (fn []

                                    (it "nudges on premature, re-arms after a clean turn, and caps"
                                        (fn []
                                          (let [fa (fake-api)
                                                _  (fw/activate (:obj fa) {:finalize-warn {:max-nudges 2}} (atom {}))
                                                h  (get @(:handlers fa) "agent_end")
                                                premature #js {:finishReason "stop" :text "Next step: I'll wire it."}
                                                clean     #js {:finishReason "stop" :text "Fixed; all tests pass."}]
                                            (h premature nil) (h premature nil) (h premature nil)
                                            (-> (expect (count @(:sent fa))) (.toBe 2))
                                            (h clean nil)
                                            (h premature nil)
                                            (-> (expect (count @(:sent fa))) (.toBe 3)))))

                                    (it "does not nudge a clean turn"
                                        (fn []
                                          (let [fa (fake-api)
                                                _  (fw/activate (:obj fa) {:finalize-warn {:max-nudges 2}} (atom {}))
                                                h  (get @(:handlers fa) "agent_end")]
                                            (h #js {:finishReason "stop" :text "All done; the bug is fixed."} nil)
                                            (-> (expect (count @(:sent fa))) (.toBe 0)))))))

;; ── profiles: resultCap truncation ───────────────────────────────

(describe "profiles cap-result" (fn []

                                  (it "truncates a result longer than the cap and adds a notice"
                                      (fn []
                                        ;; head+tail preserved: "aaa…bbb" → keep a-head and b-tail.
                                        (let [out (profiles/cap-result (str (.repeat "a" 60) (.repeat "b" 40)) 20)]
                                          (-> (expect (.startsWith out "a")) (.toBe true))
                                          (-> (expect (.endsWith out "b")) (.toBe true))
                                          (-> (expect (.includes out "truncated")) (.toBe true))
                                          (-> (expect (< (count out) 100)) (.toBe true)))))

                                  (it "leaves a short result untouched"
                                      (fn []
                                        (-> (expect (profiles/cap-result "short" 20)) (.toBe "short"))))

                                  (it "is a no-op when cap is unset/non-positive"
                                      (fn []
                                        (-> (expect (profiles/cap-result "abc" nil)) (.toBe "abc"))
                                        (-> (expect (profiles/cap-result "abc" 0)) (.toBe "abc"))))))
