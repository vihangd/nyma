(ns pi-rpc-messages.test
  "The fields a pi RPC consumer builds its transcript from, pinned as ThinkRail
   reads them (apps/web appStore reduceSessionEvent, server agentSessionManager):

   - every message_update carries the message so far as `partial`;
   - one assistant message per step, its toolCall block ids equal to the
     tool_execution_* toolCallIds, stopReason \"toolUse\" when tools ran;
   - toolResult messages after it; user messages as message_start/end;
   - agent_end.messages holds the run; queue_update follows the queues."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.pi-rpc :refer [subscribe-events! new-st]]
            [agent.utils.pi-shape :as shape]))

(defn- capture [f]
  (let [real (.-log js/console)
        out  (atom [])]
    (set! (.-log js/console) (fn [line] (swap! out conj (js/JSON.parse (str line)))))
    (try (f) (finally (set! (.-log js/console) real)))
    @out))

(defn- fake-agent []
  (let [handlers (atom {})
        sub      (atom nil)]
    {:events {:on  (fn [e h] (swap! handlers update e (fnil conj []) h) nil)
              :off (fn [e h] (swap! handlers update e (fn [hs] (vec (remove #(= % h) hs)))) nil)}
     :store {:subscribe (fn [l] (reset! sub l) (fn [] (reset! sub nil)))}
     :steer-queue (atom [])
     :follow-queue (atom [])
     :__handlers handlers
     :__sub sub}))

(defn- fire! [agent e data]
  (doseq [h (get @(:__handlers agent) e)] (h data)))

(defn- add-user! [agent text]
  (@(:__sub agent) :message-added {} {:message {:role "user" :content text}}))

(defn- step [finish text]
  #js {:text text :finishReason finish :toolResults #js []
       :usage #js {:inputTokens 10 :outputTokens 2}})

(defn- a-run [agent]
  (capture
   (fn []
     (let [unsub (subscribe-events! agent (new-st))]
       (add-user! agent "fix it")
       (fire! agent "agent_start" #js {})
       (fire! agent "turn_start" #js {})
       (fire! agent "message_start" #js {:type "text-start"})
       (fire! agent "message_update" #js {:type "text-delta" :text "Look"})
       (fire! agent "message_update" #js {:type "text-delta" :text "ing"})
       (fire! agent "message_end" #js {:type "text-end"})
       (fire! agent "tool_call" #js {:type "tool-call" :toolCallId "c1" :toolName "read" :input #js {:path "a"}})
       (fire! agent "tool_execution_start" {:execId "c1" :toolName "read" :args {:path "a"}})
       ;; c2 starts before the loop reads its tool-call part
       (fire! agent "tool_execution_start" {:execId "c2" :toolName "bash" :args {:command "ls"}})
       (fire! agent "tool_call" #js {:type "tool-call" :toolCallId "c2" :toolName "bash" :input #js {:command "ls"}})
       ;; a call from inside a script is no toolCall of the model's
       (fire! agent "tool_execution_start" {:execId "n1" :toolName "read" :args {} :nested true})
       (fire! agent "tool_execution_end" {:execId "n1" :toolName "read" :result "x"})
       (fire! agent "tool_execution_end" {:execId "c1" :toolName "read" :result "A"})
       (fire! agent "tool_execution_end" {:execId "c2" :toolName "bash" :result "B" :isError true})
       (fire! agent "turn_end" (step "tool-calls" "Looking"))
       (fire! agent "message_start" #js {:type "text-start"})
       (fire! agent "message_update" #js {:type "text-delta" :text "Done"})
       (fire! agent "message_end" #js {:type "text-end"})
       (fire! agent "turn_end" (step "stop" "Done"))
       (fire! agent "agent_end" #js {})
       (fire! agent "turn_finalize" #js {})
       (unsub)))))

(defn- of-type [evs t] (filterv #(= t (.-type %)) evs))

(describe "pi-rpc messages"
          (fn []
            (it "every message_update carries the message so far"
                (fn []
                  (let [ups (of-type (a-run (fake-agent)) "message_update")]
                    (-> (expect (count ups)) (.toBeGreaterThan 0))
                    (doseq [u ups]
                      (-> (expect (.. u -assistantMessageEvent -partial -role)) (.toBe "assistant")))
                    (let [d (second (filter #(= "text_delta" (.. % -assistantMessageEvent -type)) ups))]
                      (-> (expect (.. d -assistantMessageEvent -partial -content (at 0) -text)) (.toBe "Looking"))))))

            (it "one assistant message per step, joined to its tool executions"
                (fn []
                  (let [evs   (a-run (fake-agent))
                        ends  (filterv #(= "assistant" (.. % -message -role)) (of-type evs "message_end"))
                        first-msg (.-message (first ends))
                        calls (filterv #(= "toolCall" (.-type %)) (.-content first-msg))
                        exec-ids (mapv #(.-toolCallId %) (of-type evs "tool_execution_start"))]
                    (-> (expect (count ends)) (.toBe 2))
                    (-> (expect (.-stopReason first-msg)) (.toBe "toolUse"))
                    (-> (expect (mapv #(.-id %) calls)) (.toEqual #js ["c1" "c2"]))
                    (-> (expect (filterv #(not= "n1" %) exec-ids)) (.toEqual (mapv #(.-id %) calls)))
                    (-> (expect (.-stopReason (.-message (second ends)))) (.toBe "stop"))
                    (-> (expect (.. first-msg -usage -input)) (.toBe 10)))))

            (it "sends toolResult messages after the assistant message, not for script calls"
                (fn []
                  (let [evs (a-run (fake-agent))
                        trs (filterv #(= "toolResult" (.. % -message -role)) (of-type evs "message_end"))]
                    (-> (expect (mapv #(.. % -message -toolCallId) trs)) (.toEqual #js ["c1" "c2"]))
                    (-> (expect (.. (second trs) -message -isError)) (.toBe true)))))

            (it "agent_end.messages holds the run, user message first"
                (fn []
                  (let [end  (first (of-type (a-run (fake-agent)) "agent_end"))
                        roles (mapv #(.-role %) (.-messages end))]
                    (-> (expect roles) (.toEqual #js ["user" "assistant" "toolResult" "toolResult" "assistant"])))))

            (it "each step's assistant message sits inside its turn pair"
                (fn []
                  (let [ts (->> (a-run (fake-agent))
                                (keep (fn [e] (case (.-type e)
                                                ("turn_start" "turn_end") (.-type e)
                                                "message_start" (when (= "assistant" (.. e -message -role)) "msg")
                                                nil)))
                                vec)]
                    (-> (expect ts) (.toEqual #js ["turn_start" "msg" "turn_end" "turn_start" "msg" "turn_end"])))))

            (it "a user message is sent as message_start and message_end"
                (fn []
                  (let [evs (a-run (fake-agent))]
                    (-> (expect (.. (first evs) -message -role)) (.toBe "user"))
                    (-> (expect (.-type (second evs))) (.toBe "message_end")))))

            (it "queue_update follows the queues"
                (fn []
                  (let [agent (fake-agent)
                        evs   (capture
                               (fn []
                                 (let [unsub (subscribe-events! agent (new-st))]
                                   (swap! (:follow-queue agent) conj {:role "user" :content "then this"})
                                   (unsub)
                                   (swap! (:follow-queue agent) conj {:role "user" :content "unwatched"}))))]
                    (-> (expect (mapv #(.-type %) evs)) (.toEqual #js ["queue_update"]))
                    (-> (expect (.-followUp (first evs))) (.toEqual #js ["then this"])))))))

(describe "entries->messages"
          (fn []
            (it "rebuilds tool calls and results from a session branch"
                (fn []
                  (let [ms (shape/entries->messages
                            [{:role "user" :content "fix it"}
                             {:role "tool_call" :content "A" :metadata {:tool-name "read" :tool-call-id "c1" :args {:path "a"}}}
                             {:role "tool_call" :content "B" :metadata {:tool-name "bash" :tool-call-id "c2" :args {}}}
                             {:role "session-name" :content "x"}
                             {:role "assistant" :content "Done"}])]
                    (-> (expect (mapv :role ms)) (.toEqual #js ["user" "assistant" "toolResult" "toolResult" "assistant"]))
                    (-> (expect (mapv :id (:content (second ms)))) (.toEqual #js ["c1" "c2"]))
                    (-> (expect (:stopReason (second ms))) (.toBe "toolUse"))
                    (-> (expect (:text (first (:content (nth ms 2))))) (.toBe "A")))))))
