(ns loop-queued-flag.test
  "before_agent_start says whether its run is a queued follow-up rather than
   a new message from the user, so per-prompt work can skip it."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.loop :refer [run follow-up]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn ^:async t-queued []
  (let [agent (make-test-agent)
        seen  (atom [])]
    ((:on (:events agent)) "before_agent_start" (fn [d] (swap! seen conj (.-queued d)) nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_]
                             (when (= 1 (count @seen)) (follow-up agent {:role "user" :content "nudge"}))
                             #js {:block true :reason "captured"}))
    (js-await (run agent "go"))
    (-> (expect (clj->js @seen)) (.toEqual #js [false true]))))

(describe "loop: queued runs" (fn []
  (it "flags a drained follow-up run, not the user's" t-queued)))
