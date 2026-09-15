(ns compact-receipt.test
  "/compact reports what actually happened.

   Observed on the binary: with two turns, /compact printed `Nothing to
   compact` (the summary estimated longer than the turns it replaced) while
   the session file got a summary and the next resume showed it. The receipt
   now comes from `compact`'s return value, not from a before/after estimate."
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager]]
            ["./agent/sessions/compaction.mjs" :refer [compact compaction-receipt]]))

(def ^:private six-section
  (str "## 1. Previous Conversation\nx\n\n## 2. Current Work\nsrc/a.cljs:1\n\n"
       "## 3. Key Technical Concepts\nx\n\n## 4. Relevant Files and Code\nsrc/a.cljs:1\n\n"
       "## 5. Problem Solving\nx\n\n## 6. Pending Tasks and Next Steps\n- x\n  Quote: \"y\""))

(defn- events-offering [summary]
  {:on         (fn [_e _h] nil)
   :emit-async (fn [event ctx]
                 (when (and summary (= event "before_compact")) (aset ctx "summary" summary))
                 (js/Promise.resolve nil))
   :emit       (fn [_e _d] nil)})

(defn- gen-stub [text] (fn [_opts] (js/Promise.resolve #js {:text text})))

(defn- two-turn-session []
  (let [sm (create-session-manager nil)]
    ((:append sm) {:role "user" :content "hi"})
    ((:append sm) {:role "assistant" :content "hello"})
    ((:append sm) {:role "user" :content "ok"})
    ((:append sm) {:role "assistant" :content "sure"})
    sm))

(describe "/compact receipt reflects what ran"
          (fn []
            (it "a forced compaction on a tiny session reports the built-in summariser and the rejected extension summary"
                (^:async fn []
                  (let [st     (atom {:messages []})
                        result (js-await (compact (two-turn-session) "mock-model"
                                                  (events-offering "## Previous Context\njunk")
                                                  {:gen-fn (gen-stub six-section)
                                                   :force? true
                                                   :state-atom st}))
                        text   (compaction-receipt result)]
                    (-> (expect (:summariser result)) (.toBe :built-in))
                    (-> (expect (pos? (count (:rejected result)))) (.toBe true))
                    (-> (expect text) (.toContain "Compacted ~"))
                    (-> (expect text) (.toContain "built-in summariser"))
                    (-> (expect text) (.toContain "the extension summary was rejected: missing section header: ## 1. Previous Conversation")))))

            (it "an accepted extension summary is reported without the built-in note"
                (^:async fn []
                  (let [result (js-await (compact (two-turn-session) "mock-model"
                                                  (events-offering six-section)
                                                  {:gen-fn (gen-stub "SHOULD NOT BE CALLED")
                                                   :force? true}))
                        text   (compaction-receipt result)]
                    (-> (expect (:summariser result)) (.toBe :extension))
                    (-> (expect text) (.toContain "Compacted ~"))
                    (-> (expect text) (.not.toContain "built-in")))))

            (it "says Nothing to compact only when no compaction ran"
                (^:async fn []
                  (let [result (js-await (compact (two-turn-session) "mock-model"
                                                  (events-offering nil)
                                                  {:gen-fn (gen-stub six-section)}))]
                    (-> (expect result) (.toBeFalsy))
                    (-> (expect (compaction-receipt result)) (.toBe "Nothing to compact")))))))
