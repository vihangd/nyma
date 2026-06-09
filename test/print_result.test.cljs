(ns print-result.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.print :refer [result-object]]))

(defn- fake-agent [state-map session]
  {:state (atom state-map) :session (atom session)})

(describe "print:result-object (claude-style)"
          (fn []
            (it "emits a claude-style result with text + usage + cost"
                (fn []
                  (let [a (fake-agent
                           {:messages [{:role "user" :content "q"}
                                       {:role "assistant" :content "the answer"}]
                            :total-input-tokens 100 :total-output-tokens 50 :total-cost 0.0123}
                           nil)
                        r (result-object a 42 nil)]
                    (-> (expect (.-type r)) (.toBe "result"))
                    (-> (expect (.-is_error r)) (.toBe false))
                    (-> (expect (.-result r)) (.toBe "the answer"))
                    (-> (expect (.-total_cost_usd r)) (.toBe 0.0123))
                    (-> (expect (.. r -usage -input_tokens)) (.toBe 100))
                    (-> (expect (.. r -usage -output_tokens)) (.toBe 50))
                    (-> (expect (.. r -usage -total_tokens)) (.toBe 150))
                    (-> (expect (.-duration_ms r)) (.toBe 42))
                    (-> (expect (string? (.-session_id r))) (.toBe true))
                    (-> (expect (pos? (count (.-session_id r)))) (.toBe true)))))

            (it "extracts text from array content (last assistant)"
                (fn []
                  (let [a (fake-agent
                           {:messages [{:role "assistant"
                                        :content [#js {:type "text" :text "part A"}
                                                  #js {:type "text" :text "part B"}]}
                                       {:role "tool" :content "ignored tool output"}]}
                           nil)
                        r (result-object a 1 nil)]
                    (-> (expect (.-result r)) (.toContain "part A"))
                    (-> (expect (.-result r)) (.toContain "part B"))
                    (-> (expect (.-result r)) (.not.toContain "ignored")))))

            (it "derives session_id from the session file path"
                (fn []
                  (let [a (fake-agent
                           {:messages [{:role "assistant" :content "x"}]}
                           {:get-file-path (fn [] "/home/u/.nyma/sessions/2026/abc123.jsonl")})
                        r (result-object a 1 nil)]
                    (-> (expect (.-session_id r)) (.toBe "abc123")))))

            (it "defaults usage/cost to 0 when absent"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "y"}]} nil)
                        r (result-object a 0 nil)]
                    (-> (expect (.-total_cost_usd r)) (.toBe 0))
                    (-> (expect (.. r -usage -input_tokens)) (.toBe 0))
                    (-> (expect (.. r -usage -total_tokens)) (.toBe 0)))))))

;; is_error so orchestrators (cw) can fall back on flaky/empty small-model replies
(describe "print:result-object is_error"
          (fn []
            (it "flags is_error when run threw"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "partial"}]} nil)
                        r (result-object a 5 "No model configured")]
                    (-> (expect (.-is_error r)) (.toBe true))
                    (-> (expect (.-result r)) (.toContain "No model configured")))))

            (it "flags is_error when the assistant produced no text (empty reply)"
                (fn []
                  (let [a (fake-agent {:messages [{:role "assistant" :content "   "}]} nil)
                        r (result-object a 5 nil)]
                    (-> (expect (.-is_error r)) (.toBe true))
                    (-> (expect (.-result r)) (.toContain "empty")))))

            (it "flags is_error when there is no assistant message at all"
                (fn []
                  (let [a (fake-agent {:messages [{:role "user" :content "q"}]} nil)
                        r (result-object a 5 nil)]
                    (-> (expect (.-is_error r)) (.toBe true)))))))
