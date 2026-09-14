(ns pi-rpc-turn-events.test
  "The turn pair, agent_settled, and clear_queue.

   pi's documented RPC flow is

     response/prompt -> agent_start -> turn_start -> message_* -> turn_end -> agent_end

   and nyma forwarded everything in it except the turn pair — which is exactly
   what a consumer keys its per-turn accounting on. `@agentproto/adapter-pi`
   derives its own `usage_update` from `turn_end.message.usage` rather than
   from a separate event, so a missing or misnamed field there costs the
   consumer its token accounting with nothing to show that anything went wrong.

   Hence field-by-field assertions rather than \"the event arrived\"."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.pi-rpc :refer [turn-end-event drain-queues!]]))

(defn- step
  "An AI SDK StepResult of the shape loop.cljs emits on turn_end."
  [& {:keys [text finish in out tools]}]
  #js {:text         (or text "")
       :finishReason (or finish "stop")
       :usage        (when (or in out) #js {:inputTokens (or in 0) :outputTokens (or out 0)})
       :toolResults  (clj->js (or tools []))})

(describe "turn-end-event"
          (fn []
            (it "has the field names pi's spec defines"
                (fn []
                  ;; Pinned against pi's docs/rpc.md:
                  ;;   {"type": "turn_end", "message": {...}, "toolResults": [...]}
                  (let [e (turn-end-event (step :text "done" :in 10 :out 2))]
                    (-> (expect (:type e)) (.toBe "turn_end"))
                    (-> (expect (:role (:message e))) (.toBe "assistant"))
                    (-> (expect (:text (first (:content (:message e))))) (.toBe "done"))
                    (-> (expect (some? (:toolResults e))) (.toBe true)))))

            (it "puts usage and stopReason on the message, not at the top level"
                (fn []
                  ;; Where the adapter looks for them. At the top level they
                  ;; would arrive and be ignored.
                  (let [e (turn-end-event (step :text "x" :finish "tool-calls" :in 7 :out 3))]
                    (-> (expect (:stopReason (:message e))) (.toBe "tool-calls"))
                    (-> (expect (:inputTokens (:usage (:message e)))) (.toBe 7))
                    (-> (expect (:outputTokens (:usage (:message e)))) (.toBe 3)))))

            (it "omits usage entirely when the step reported none"
                (fn []
                  (-> (expect (contains? (:message (turn-end-event (step))) :usage)) (.toBe false))))

            (it "survives a step with nothing on it"
                (fn []
                  ;; A provider that returns an empty step must not take the
                  ;; RPC channel down with it.
                  (let [e (turn-end-event nil)]
                    (-> (expect (:type e)) (.toBe "turn_end"))
                    (-> (expect (:stopReason (:message e))) (.toBe "stop")))))))

(describe "drain-queues!"
          (fn []
            (it "returns both queues' text and empties them"
                (fn []
                  ;; pi's response shape: {"steering": [...], "followUp": [...]}
                  (let [agent {:steer-queue  (atom [{:role "user" :content "change direction"}])
                               :follow-queue (atom [{:role "user" :content "summarize after"}])}
                        out   (drain-queues! agent)]
                    (-> (expect (vec (:steering out))) (.toEqual #js ["change direction"]))
                    (-> (expect (vec (:followUp out))) (.toEqual #js ["summarize after"]))
                    ;; Emptied — a second clear must not resurrect them.
                    (-> (expect (count @(:steer-queue agent))) (.toBe 0))
                    (-> (expect (count @(:follow-queue agent))) (.toBe 0)))))

            (it "returns empty lists when nothing is queued"
                (fn []
                  (let [out (drain-queues! {:steer-queue (atom []) :follow-queue (atom [])})]
                    (-> (expect (count (:steering out))) (.toBe 0))
                    (-> (expect (count (:followUp out))) (.toBe 0)))))))
