(ns bench-scoring.test
  "The benchmark harness makes two claims that have to be true or every number
   it produces is noise: the same seed picks the same tasks, and a task that
   could not run is never scored as a task the agent got wrong.

   Compiled tests live in dist/, so bench/ is one directory up."
  (:require ["bun:test" :refer [describe it expect]]
            ["../bench/scoring.mjs" :as b]))

(def ^:private tasks
  [#js {:id "python/alpha"  :lang "python" :name "alpha"  :runnable true}
   #js {:id "python/beta"   :lang "python" :name "beta"   :runnable true}
   #js {:id "python/gamma"  :lang "python" :name "gamma"  :runnable true}
   #js {:id "python/delta"  :lang "python" :name "delta"  :runnable true}
   #js {:id "js/eps"        :lang "javascript" :name "eps" :runnable false}])

(defn- ids [sel] (vec (map (fn [t] (.-id t)) sel)))

(describe "bench/selection"
          (fn []
            (it "same seed picks the same tasks, every time"
                (fn []
                  (let [a (b/selectTasks (clj->js tasks) #js {:seed 7 :count 2})
                        c (b/selectTasks (clj->js tasks) #js {:seed 7 :count 2})]
                    (-> (expect (ids a)) (.toEqual (ids c)))
                    (-> (expect (count a)) (.toBe 2)))))

            (it "a different seed can pick a different set"
        ;; not guaranteed for every pair, but these two differ — if this ever
        ;; fails the shuffle has stopped depending on the seed at all
                (fn []
                  (let [a (ids (b/selectTasks (clj->js tasks) #js {:seed 1 :count 2}))
                        c (ids (b/selectTasks (clj->js tasks) #js {:seed 99 :count 2}))]
                    (-> (expect (= a c)) (.toBe false)))))

            (it "never selects an unrunnable task"
                (fn []
                  (-> (expect (ids (b/selectTasks (clj->js tasks) #js {:count 99})))
                      (.not.toContain "js/eps"))))

            (it "--only wins over sampling and accepts a lang wildcard"
                (fn []
                  (-> (expect (ids (b/selectTasks (clj->js tasks) #js {:only #js ["python/beta"]})))
                      (.toEqual #js ["python/beta"]))
                  (-> (expect (count (b/selectTasks (clj->js tasks) #js {:only #js ["python/*"]})))
                      (.toBe 4))))))

(describe "bench/test-output"
          (fn []
            (it "exit 0 passes, non-zero fails"
                (fn []
                  (-> (expect (b/classifyTestRun #js {:exitCode 0 :stdout "Ran 3 tests\nOK"}))
                      (.toBe "pass"))
                  (-> (expect (b/classifyTestRun #js {:exitCode 1 :stdout "FAILED (failures=2)"}))
                      (.toBe "fail"))))

            (it "a suite that collected nothing is not a pass"
        ;; deleting the tests and exiting 0 is the cheapest way to fake a score
                (fn []
                  (-> (expect (b/classifyTestRun #js {:exitCode 0 :stdout "Ran 0 tests in 0.000s\nOK"}))
                      (.toBe "error"))))

            (it "a timeout is a timeout, not a failure"
                (fn []
                  (-> (expect (b/classifyTestRun #js {:exitCode 137 :timedOut true}))
                      (.toBe "timeout"))))

            (it "a missing module is not scored as the agent being wrong"
                (fn []
                  (-> (expect (b/classifyTestRun #js {:exitCode 0 :stderr "ModuleNotFoundError: no mod"}))
                      (.toBe "error"))))))

(describe "bench/aggregate"
          (fn []
            (it "skips leave the denominator, they do not deflate the score"
        ;; 1 pass, 1 fail, 2 skipped => 50%, not 25%
                (fn []
                  (let [agg (b/aggregate #js [#js {:status "pass"} #js {:status "fail"}
                                              #js {:status "skip"} #js {:status "skip"}])]
                    (-> (expect (.-pct agg)) (.toBe 50))
                    (-> (expect (.-attempted agg)) (.toBe 2))
                    (-> (expect (.-skipped agg)) (.toBe 2)))))

            (it "reports no percentage at all when nothing could be attempted"
                (fn []
                  (-> (expect (.-pct (b/aggregate #js [#js {:status "skip"}]))) (.toBeNull))))

            (it "one trial reports no spread rather than zero spread"
                (fn []
                  (-> (expect (.-spread (b/summarizeTrials #js [40]))) (.toBeNull))
                  (let [s (b/summarizeTrials #js [40 50])]
                    (-> (expect (.-mean s)) (.toBe 45))
                    (-> (expect (.-spread s)) (.toBe 5)))))))

(describe "bench/diff"
          (fn []
            (it "separates improvements from regressions"
                (fn []
                  (let [before #js {:results #js [#js {:id "a" :status "pass"}
                                                  #js {:id "b" :status "fail"}
                                                  #js {:id "c" :status "fail"}]}
                        after  #js {:results #js [#js {:id "a" :status "fail"}
                                                  #js {:id "b" :status "pass"}
                                                  #js {:id "c" :status "timeout"}]}
                        d (b/diffRuns before after)]
                    (-> (expect (.-improved d)) (.toEqual #js ["b"]))
                    (-> (expect (.-regressed d)) (.toEqual #js ["a"]))
                    (-> (expect (count (.-changed d))) (.toBe 1)))))))

(describe "bench/agent-build"
          (fn []
            (it "refuses the bundled binary, which loads a different agent"
        ;; ./nyma loads 2 extensions; dist/agent/cli.mjs loads ~40. Both run.
                (fn []
                  (-> (expect (b/isDistEntry #js ["./nyma"])) (.toBe false))
                  (-> (expect (b/isDistEntry #js ["bun" "/x/dist/agent/cli.mjs"])) (.toBe true))
                  (-> (expect (.-ok (b/checkAgentBuild 2))) (.toBe false))
                  (-> (expect (.-ok (b/checkAgentBuild 40))) (.toBe true))))

            (it "refuses when the count could not be determined"
                (fn []
                  (-> (expect (.-ok (b/checkAgentBuild js/NaN))) (.toBe false))))

            (it "keeps the reference solution out of the agent's copy"
                (fn []
                  (-> (expect b/EXCLUDED_FROM_COPY) (.toContain ".meta"))))))


;; ── the four metrics ─────────────────────────────────────────────
;; One pooled number hides which half moved. Enabling prose tool-call rescue on
;; omlx fixed two Rust tasks and broke two others, netting zero — and the score
;; alone could not show that. The literature says the same thing with numbers:
;; abstention accuracy -29.5 against tool selection +17..+62, pooling to +7.7
;; (arXiv 2608.13959); and 91.5% -> 48.0% executable accuracy at an unchanged
;; 100% schema validity (arXiv 2605.26128). Both papers' practical instruction
;; is to report validity and accuracy separately.
(describe "bench/aggregate — validity vs accuracy"
  (fn []
    (it "splits a run into validity, accuracy, wrong-valid and no-tool-call"
        (fn []
          (let [m (.-metrics (b/aggregate
                              #js [#js {:status "pass"}
                                   #js {:status "fail"}
                                   #js {:status "timeout"}
                                   #js {:status "error"
                                        :reason "agent never modified the stub (no tool call?)"}]))]
            (-> (expect (.-executableAccuracy m)) (.toBe 25))
            (-> (expect (.-wrongValid m))         (.toBe 25))
            (-> (expect (.-noToolCall m))         (.toBe 25))
            (-> (expect (.-schemaValidity m))     (.toBe 75)))))

    (it "does not count a request that never landed as valid output"
        ;; a rate limit is neither valid nor invalid output — 92 tasks died this
        ;; way in the corpus, and folding them into validity would flatter it
        (fn []
          (let [m (.-metrics (b/aggregate
                              #js [#js {:status "pass"}
                                   #js {:status "error"
                                        :reason "AI_APICallError: Rate limit exceeded"}]))]
            (-> (expect (.-schemaValidity m)) (.toBe 100)))))

    (it "distinguishes a silent agent from a wrong one"
        ;; both score zero; only one is a wire-format problem
        (fn []
          (let [silent (.-metrics (b/aggregate
                                   #js [#js {:status "error"
                                             :reason "agent never modified the stub (no tool call?)"}]))
                wrong  (.-metrics (b/aggregate #js [#js {:status "fail"}]))]
            (-> (expect (.-schemaValidity silent)) (.toBe 0))
            (-> (expect (.-schemaValidity wrong))  (.toBe 100))
            (-> (expect (.-executableAccuracy silent)) (.toBe 0))
            (-> (expect (.-executableAccuracy wrong))  (.toBe 0)))))))
