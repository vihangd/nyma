(ns small-model-stream-rules.test
  "Correcting a turn while it is still being written.

   `loop.cljs:449` already runs the stream inside a retry loop bounded at 2
   attempts, and a `stream_filter` handler returning `{abort, reason, inject}`
   makes it re-run with those messages appended. Nothing decided *when* to do
   that, which is what these rules add — as a settings table, so shipping no
   rules means nothing fires."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.stream-rules :as sr
             :refer [compile-rules first-match abort-result]]))

(def ^:private rules
  (compile-rules [{"pattern" "as an ai" "reminder" "Answer directly."}
                  {"pattern" "TODO\\(later\\)" "flags" "" "reminder" "No placeholders."}]))

(describe "compile-rules"
          (fn []
            (it "compiles the rules it is given"
                (fn []
                  (-> (expect (count rules)) (.toBe 2))
                  (-> (expect (:reminder (first rules))) (.toBe "Answer directly."))))

            (it "drops a rule whose pattern does not compile, keeping the rest"
                (fn []
                  ;; A bad regex in settings must not take the turn down.
                  (let [r (compile-rules [{"pattern" "(unclosed" "reminder" "x"}
                                          {"pattern" "fine" "reminder" "y"}])]
                    (-> (expect (count r)) (.toBe 1))
                    (-> (expect (:reminder (first r))) (.toBe "y")))))

            (it "drops a rule missing a pattern or a reminder"
                (fn []
                  (-> (expect (count (compile-rules [{"pattern" "x"}
                                                     {"reminder" "y"}])))
                      (.toBe 0))))

            (it "is empty when there are no rules"
                (fn []
                  ;; The default. Nothing configured, nothing fires.
                  (-> (expect (count (compile-rules nil))) (.toBe 0))
                  (-> (expect (count (compile-rules []))) (.toBe 0))))))

(describe "first-match"
          (fn []
            (it "matches case-insensitively by default"
                (fn []
                  (-> (expect (:id (first-match rules #{} "Well, As An AI model I"))) (.toBe "rule-0"))))

            (it "honours explicit flags"
                (fn []
                  ;; rule-1 was compiled with flags "", so case matters.
                  (-> (expect (:id (first-match rules #{} "left a TODO(later) here"))) (.toBe "rule-1"))
                  (-> (expect (first-match rules #{} "left a todo(later) here")) (.toBeNil))))

            (it "skips a rule that already fired"
                (fn []
                  ;; Otherwise a rule whose own reminder matches its pattern
                  ;; spends both retries re-triggering itself.
                  (-> (expect (first-match rules #{"rule-0"} "as an ai")) (.toBeNil))))

            (it "is nil for text nothing matches, and for no text"
                (fn []
                  (-> (expect (first-match rules #{} "a perfectly ordinary sentence")) (.toBeNil))
                  (-> (expect (first-match rules #{} "")) (.toBeNil))
                  (-> (expect (first-match rules #{} nil)) (.toBeNil))))))

(describe "abort-result"
          (fn []
            (it "is the shape loop.cljs retries on"
                (fn []
                  ;; Pinned against loop.cljs: it reads "abort", then "inject"
                  ;; and appends each entry as a message. A drifting key here
                  ;; would silently stop aborting.
                  (let [r (abort-result (first rules))]
                    (-> (expect (.-abort r)) (.toBe true))
                    (-> (expect (.-reason r)) (.toContain "rule-0"))
                    (-> (expect (.-length (.-inject r))) (.toBe 1))
                    (-> (expect (.-role (aget (.-inject r) 0))) (.toBe "user"))
                    (-> (expect (.-content (aget (.-inject r) 0))) (.toBe "Answer directly.")))))))

;;; ─── through the event bus ──────────────────────────────────

(defn- fake-api []
  (let [handlers (atom {})]
    {:api #js {:on  (fn [e h] (swap! handlers update e (fnil conj []) h) nil)
               :off (fn [e h] (swap! handlers update e (fn [hs] (vec (remove #(= % h) hs)))) nil)}
     ;; mapv, not map: a lazy seq whose result is discarded never runs the
     ;; handler, which silently skipped the turn_start reset.
     :fire (fn [e data] (mapv (fn [h] (h data)) (get @handlers e)))
     :handlers handlers}))

(defn- cfg [rs] {:stream-rules {:rules rs}})

(describe "stream-rules on the bus"
          (fn []
            (it "aborts when the accumulated text matches"
                (fn []
                  (let [{:keys [api fire]} (fake-api)
                        stop (sr/activate api (cfg [{"pattern" "as an ai" "reminder" "Answer directly."}]))
                        out  (first (fire "stream_filter" #js {:delta "Well, as an AI"}))]
                    (-> (expect (.-abort out)) (.toBe true))
                    (stop))))

            (it "returns nothing for text that does not match"
                (fn []
                  (let [{:keys [api fire]} (fake-api)
                        stop (sr/activate api (cfg [{"pattern" "as an ai" "reminder" "x"}]))]
                    (-> (expect (first (fire "stream_filter" #js {:delta "hello there"}))) (.toBeFalsy))
                    (stop))))

            (it "fires a rule once per turn, and again after turn_start"
                (fn []
                  ;; The retry re-runs the stream, so the same delta arrives
                  ;; again; firing twice would burn the retry budget on one rule.
                  (let [{:keys [api fire]} (fake-api)
                        stop (sr/activate api (cfg [{"pattern" "oops" "reminder" "x"}]))
                        d    #js {:delta "oops"}]
                    (-> (expect (.-abort (first (fire "stream_filter" d)))) (.toBe true))
                    (-> (expect (first (fire "stream_filter" d))) (.toBeFalsy))
                    (fire "turn_start" #js {})
                    (-> (expect (.-abort (first (fire "stream_filter" d)))) (.toBe true))
                    (stop))))

            (it "subscribes nothing when no rules are configured"
                (fn []
                  ;; The default path: no rules, no handlers, no cost.
                  (let [{:keys [api handlers]} (fake-api)
                        stop (sr/activate api {:stream-rules {}})]
                    (-> (expect (count (get @handlers "stream_filter" []))) (.toBe 0))
                    (stop))))

            (it "unsubscribes on cleanup"
                (fn []
                  (let [{:keys [api handlers]} (fake-api)
                        stop (sr/activate api (cfg [{"pattern" "x" "reminder" "y"}]))]
                    (-> (expect (count (get @handlers "stream_filter" []))) (.toBe 1))
                    (stop)
                    (-> (expect (count (get @handlers "stream_filter" []))) (.toBe 0)))))))
