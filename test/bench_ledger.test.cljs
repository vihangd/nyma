(ns bench-ledger-test
  "`bench/RESULTS.md` is generated, and was the only generated artifact in the
   repo with no drift test — `docs/event-map.md` is compared by exact equality,
   and so are AGENTS.md's namespace table and the README's event and builtin
   tables.

   It had no test because it could not have one: `gen-bench-ledger.mjs` had no
   exports and called `writeFileSync(\"bench/RESULTS.md\", …)` at module top
   level, so importing it from a test would overwrite the committed ledger.
   That write is now behind `import.meta.main`.

   A correction worth recording: the audit that prompted this reported the
   ledger's baseline as 9% wrong. It is not. The header's mention of 174,845 is
   the figure the old result FILE stores, and the ledger exists to override it
   — the row prints the honest 190,049, and regenerating produces no diff. The
   real gaps were the missing drift test and a duplicated denominator, both
   below."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["../bench/scoring.mjs" :as scoring]
            ["../scripts/gen-bench-ledger.mjs" :as ledger]))

(def ^:private results-dir
  (path/join (js/process.cwd) "bench" "results"))

(defn- result-files []
  (->> (fs/readdirSync results-dir)
       (filter (fn [f] (and (.endsWith f ".json") (not= f "latest.json"))))
       vec))

(describe "the committed ledger is the generated one"
          (fn []
            (it "matches byte for byte"
                (fn []
        ;; Exact equality, not `.includes` — three other generated-doc tests in
        ;; this repo use `.includes`, which is satisfied by an empty generator
        ;; (`String.includes("")` is always true) and by a hand-added extra row.
                  (let [committed (fs/readFileSync (path/join (js/process.cwd) "bench" "RESULTS.md") "utf8")]
                    (-> (expect (.-out ledger)) (.toBe committed)))))

            (it "summarises a believable number of runs"
                (fn []
        ;; Guard the guard: an empty results dir would make the equality above
        ;; pass against an empty table.
                  (-> (expect (count (result-files))) (.toBeGreaterThan 50))
                  (-> (expect (count (js/Array.from (.rowsOf ledger)))) (.toBeGreaterThan 50))))))

(describe "the denominator agrees with scoring.mjs"
          (fn []
            (it "both average over tasks that REPORTED usage, not all attempted"
                (fn []
        ;; The rule both implement, and the reason it exists: a timed-out task
        ;; reports no usage, so dividing by every attempted task understates
        ;; per-task cost by exactly the expensive tail.
                  (let [divergent
                        (->> (result-files)
                             (keep (fn [f]
                                     (let [d  (js/JSON.parse
                                               (fs/readFileSync (path/join results-dir f) "utf8"))
                                           rs (or (.-results d) #js [])]
                                       (when (pos? (.-length rs))
                                         (let [agg (.aggregate scoring rs)
                                               row (.summarize ledger f)
                                               ;; `tokensPerTask` is nested under
                                     ;; `aggregate`'s `cost` (costSummary is
                                     ;; private), not on the top level.
                                     a   (some-> agg .-cost .-tokensPerTask)
                                               l   (and row (.-tokPerTask row))]
                                 ;; Both null is agreement; otherwise they must
                                 ;; match.
                                           (when (and (or (some? a) (some? l)) (not= a l))
                                             #js {:file f :scoring a :ledger l}))))))
                             vec)]
          ;; Printed rather than counted, so a disagreement names the run.
                    (-> (expect (clj->js divergent)) (.toEqual (clj->js []))))))))
