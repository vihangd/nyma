(ns error-class-overflow.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.error-class :as ec]
            [agent.sessions.compaction :as c]
            [agent.providers.fallback :as fb]))

(describe "one overflow list" (fn []
  (it "recognises the phrasings that were missed"
    (fn []
      (doseq [m ["request (9000 tokens) exceeds the available context size, try increasing it"
                 "Prompt exceeds max length"
                 "Range of input length should be [1, 98304]"
                 "This model's maximum prompt length is 131072"
                 "input length and max_tokens exceed context limit"]]
        (-> (expect #js [m (c/context-overflow-error? (js/Error. m))]) (.toEqual #js [m true]))
        (-> (expect #js [m (ec/error-type m)]) (.toEqual #js [m "context_length"]))
        (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m false])))))
  (it "a timeout is not an overflow"
    (fn [] (-> (expect (ec/context-overflow? "context deadline exceeded")) (.toBe false))))
  (it "openlux's Chinese capacity 429 is overloaded and falls back"
    (fn []
      (-> (expect (ec/error-type "当前分组上游负载已饱和")) (.toBe "overloaded"))
      (-> (expect (fb/should-fall-back? "当前分组上游负载已饱和")) (.toBe true))))
  (it "a status code inside a request id does not classify"
    (fn [] (-> (expect (ec/error-type "something odd (request id req_14290)")) (.toBe "unknown"))))))
