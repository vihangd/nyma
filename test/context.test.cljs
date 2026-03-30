(ns context.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.context :refer [build-context get-active-tools]]))

(describe "agent.context"
  (fn []
    (it "filters to valid LLM roles"
      (fn []
        (let [agent (create-agent {:model "m"})]
          (swap! (:state agent) assoc :messages
            [{:role "user" :content "hi"}
             {:role "assistant" :content "hello"}
             {:role "tool_call" :content "call"}
             {:role "tool_result" :content "result"}])
          (let [ctx (build-context agent)]
            (-> (expect (count ctx)) (.toBe 4))))))

    (it "excludes compaction and system roles"
      (fn []
        (let [agent (create-agent {:model "m"})]
          (swap! (:state agent) assoc :messages
            [{:role "user" :content "hi"}
             {:role "compaction" :content "summary"}
             {:role "system" :content "sys"}
             {:role "assistant" :content "hello"}])
          (let [ctx (build-context agent)]
            (-> (expect (count ctx)) (.toBe 2))))))

    (it "empty messages yields empty array"
      (fn []
        (let [agent (create-agent {:model "m"})
              ctx   (build-context agent)]
          (-> (expect (count ctx)) (.toBe 0)))))

    (it "preserves insertion order"
      (fn []
        (let [agent (create-agent {:model "m"})]
          (swap! (:state agent) assoc :messages
            [{:role "user" :content "A"}
             {:role "assistant" :content "B"}
             {:role "user" :content "C"}])
          (let [ctx (build-context agent)]
            (-> (expect (:content (first ctx))) (.toBe "A"))
            (-> (expect (:content (last ctx))) (.toBe "C"))))))

    (it "get-active-tools returns tools from registry"
      (fn []
        (let [agent (create-agent {:model "m"})
              tools (get-active-tools agent)]
          (-> (expect (get tools "read")) (.toBeTruthy))
          (-> (expect (get tools "bash")) (.toBeTruthy)))))))
