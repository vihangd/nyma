(ns tool-call-id.test
  "A tool execution carries the model's toolCallId, so a consumer can join
   tool_execution_* to the toolCall block in the assistant message. ThinkRail
   keys its tool results and ask-user waiters on exactly that join."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.middleware :refer [wrap-tools-with-middleware tool-tracking-interceptor]]))

(describe "tool call id"
          (fn []
            (it "wrap-tools-with-middleware passes the AI SDK's toolCallId to the pipeline"
                (fn []
                  (let [seen    (atom nil)
                        pipe    {:execute (fn [_n _t _a opts]
                                            (reset! seen opts)
                                            (js/Promise.resolve {:result "ok"}))}
                        wrapped (wrap-tools-with-middleware {"foo" #js {:execute (fn [_] "ok")}}
                                                            pipe {:emit (fn [_ _])})]
                    ((.-execute (get wrapped "foo")) #js {} #js {:toolCallId "call_1"})
                    (-> (expect (:tool-call-id @seen)) (.toBe "call_1")))))

            (it "the execution id is the toolCallId, and generated without one"
                (fn []
                  (let [emitted (atom [])
                        enter   (:enter (tool-tracking-interceptor
                                         {:emit (fn [e d] (swap! emitted conj [e d]))} nil))
                        a       (enter {:tool-name "read" :args {} :tool-call-id "call_1"})
                        b       (enter {:tool-name "read" :args {}})]
                    (-> (expect (:exec-id a)) (.toBe "call_1"))
                    (-> (expect (:execId (second (first @emitted)))) (.toBe "call_1"))
                    (-> (expect (seq (:exec-id b))) (.toBeTruthy)))))))
