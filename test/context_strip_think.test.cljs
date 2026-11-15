(ns context-strip-think.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.context :as ctx]))

(describe "build-context and reasoning" (fn []
  (it "an earlier answer goes out without its <think> block, as the saved one does"
    (fn []
      (let [agent {:state (atom {:messages [{:role "user" :content "q"}
                                            {:role "assistant" :content "<think>plan it</think>The answer."}
                                            {:role "assistant" :content "<think>only thinking</think>"}]})}
            out   (ctx/build-context agent)]
        (-> (expect (:content (second out))) (.toBe "The answer."))
        ;; a turn that was nothing but reasoning is blank now, and dropped
        (-> (expect (count out)) (.toBe 2)))))))
