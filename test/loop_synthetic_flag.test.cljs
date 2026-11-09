(ns loop-synthetic-flag.test
  "before_agent_start says whether its run is nyma's own message (an
   extension's nudge) rather than one the user wrote, queued ones included,
   so per-prompt work can skip only the former."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.loop :refer [run follow-up]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn ^:async t-synthetic []
  (let [agent (make-test-agent)
        seen  (atom [])]
    ((:on (:events agent)) "before_agent_start" (fn [d] (swap! seen conj (.-synthetic d)) nil))
    ((:on (:events agent)) "before_provider_request"
                           (fn [_]
                             (case (count @seen)
                               ;; the user queued a message (alt+enter) while it ran
                               1 (follow-up agent {:role "user" :content "next task"})
                               ;; an extension's nudge
                               2 (follow-up agent {:role "user" :content "nudge" :turn {:synthetic true}})
                               nil)
                             #js {:block true :reason "captured"}))
    (js-await (run agent "go"))
    (-> (expect (clj->js @seen)) (.toEqual #js [false false true]))))

(describe "loop: synthetic runs" (fn []
  (it "flags a run started by nyma's own message, not a queued one of the user's" t-synthetic)))
