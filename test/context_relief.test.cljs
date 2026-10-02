(ns context-relief-test
  "`small_model/context_relief.cljs` had no test at all — and it hooks
   `provider_error`, the same event whose per-site payload asymmetry killed the
   fallback chain.

   Why nothing covered it, which is the more interesting part: the
   `small_model` extension ships with `\"enabled\": false`, and its `index.cljs`
   guards every submodule behind `(when (:enabled config) …)`. So even
   `all_tools_schema_validation_full_load`, which activates every extension
   through the real loader, returns before a single submodule runs. Twelve
   submodules have pure-function tests; not one of their `activate` fns was
   ever called by any test.

   That had already cost something visible: `activate` returned `(fn [] nil)`
   where every sibling unregisters what it added, so both handlers survived
   `/reload` and accumulated a copy per reload. Fixed alongside these tests —
   a leak nothing could observe."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.context-relief :as cr]))

(describe "context-length-error?"
          (fn []
            (it "recognises the shapes providers actually send"
                (fn []
                  (doseq [m ["Prompt too long"
                             "input length and max_tokens exceed context window"
                             "This model's maximum context length is 8192 tokens"
                             "context_length_exceeded"
                             "number of tokens exceeds the limit"
                             "token limit reached"
                             "requested 9000 tokens exceeds max of 8192"]]
                    (-> (expect #js [m (cr/context-length-error? m)]) (.toEqual #js [m true])))))

            (it "is case-insensitive"
                (fn []
                  (-> (expect (cr/context-length-error? "PROMPT TOO LONG")) (.toBe true))))

            (it "does not fire on unrelated failures"
                (fn []
        ;; A false positive here prunes the conversation for no reason, which
        ;; is worse than not relieving.
                  (doseq [m ["401 Unauthorized"
                             "429 rate limit exceeded"
                             "connect ECONNREFUSED 127.0.0.1:8000"
                             "Cannot connect to API: Unable to connect."
                             "the model refused to answer"
                             ""]]
                    (-> (expect #js [m (cr/context-length-error? m)]) (.toEqual #js [m false])))))

            (it "tolerates nil"
                (fn []
                  (-> (expect (cr/context-length-error? nil)) (.toBe false))))))

(defn- msgs [n]
  (vec (for [i (range n)] {:role "user" :content (str "m" i)})))

(describe "prune-messages"
          (fn []
            (it "leaves a conversation that is already short enough alone"
                (fn []
                  (let [m (msgs 10)]
                    (-> (expect (count (cr/prune-messages m 2 20))) (.toBe 10))
          ;; identical, not merely the same length
                    (-> (expect (clj->js (mapv :content (cr/prune-messages m 2 20))))
                        (.toEqual (clj->js (mapv :content m)))))))

            (it "keeps the head as the task anchor and the tail as recent context"
                (fn []
                  (let [out (cr/prune-messages (msgs 50) 2 20)
                        contents (mapv :content out)]
          ;; head
                    (-> (expect (clj->js (subvec contents 0 2))) (.toEqual (clj->js ["m0" "m1"])))
          ;; tail — the last 20 originals, unchanged and in order
                    (-> (expect (clj->js (subvec contents (- (count contents) 20))))
                        (.toEqual (clj->js (mapv #(str "m" %) (range 30 50))))))))

            (it "inserts exactly one notice, at the cut, saying how many went"
                (fn []
                  (let [out (cr/prune-messages (msgs 50) 2 20)
                        notices (filter #(.includes (str (:content %)) "context-relief") out)]
                    (-> (expect (count notices)) (.toBe 1))
          ;; 50 - 2 - 20 = 28
                    (-> (expect (:content (first notices))) (.toContain "28 messages dropped"))
          ;; and it sits between head and tail
                    (-> (expect (:content (nth out 2))) (.toContain "context-relief")))))

            (it "actually shrinks the list — the caller only dispatches if it does"
                (fn []
        ;; `activate` guards on `(< (count pruned) (count msgs))`, so a prune
        ;; that grew the list would silently disable relief.
                  (let [before (msgs 50)
                        after  (cr/prune-messages before 2 20)]
                    (-> (expect (< (count after) (count before))) (.toBe true))
          ;; 2 head + 1 notice + 20 tail
                    (-> (expect (count after)) (.toBe 23)))))

            (it "is stable at the boundary, where head+tail equals the total"
                (fn []
        ;; 22 messages with keep 2 + 20: nothing to drop, so no notice either.
                  (let [out (cr/prune-messages (msgs 22) 2 20)]
                    (-> (expect (count out)) (.toBe 22))
                    (-> (expect (some #(.includes (str (:content %)) "context-relief") out))
                        (.toBeFalsy)))))

            (it "handles an empty conversation"
                (fn []
                  (-> (expect (count (cr/prune-messages [] 2 20))) (.toBe 0))))))

(describe "activate"
          (fn []
            (it "unregisters both handlers on deactivate"
                (fn []
        ;; The leak: this returned `(fn [] nil)`, so `/reload` left the old
        ;; handlers attached and added new ones.
                  (let [on  (atom [])
                        off (atom [])
                        api #js {:on  (fn [ev h] (swap! on conj [ev h]))
                                 :off (fn [ev h] (swap! off conj [ev h]))}
                        stop (cr/activate api {})]
                    (-> (expect (count @on)) (.toBe 2))
                    (stop)
                    (-> (expect (count @off)) (.toBe 2))
          ;; the SAME handler identities, or `.off` removes nothing
                    (-> (expect (clj->js (mapv first @off)))
                        (.toEqual (clj->js (mapv first @on))))
                    (-> (expect (= (set (map second @on)) (set (map second @off)))) (.toBe true)))))))
