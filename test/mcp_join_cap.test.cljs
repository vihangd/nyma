(ns mcp-join-cap.test
  "The first turn waits for the MCP bring-up only up to join-timeout-ms.
   Nine servers with two uvx cold starts held the first reply 15–18 s."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.mcp-client.index :as mcp]))

(defn ^:async test-cap []
  (let [never (js/Promise. (fn [_ _] nil))
        t0    (js/Date.now)]
    (-> (expect (js-await (mcp/join-with-cap never 50))) (.toBe :late))
    (-> (expect (< (- (js/Date.now) t0) 1000)) (.toBe true))
    (-> (expect (js-await (mcp/join-with-cap (js/Promise.resolve 1) 50))) (.toBe :done))
    ;; a failed bring-up is settled, not late: the turn must not wait on it
    (-> (expect (js-await (mcp/join-with-cap (js/Promise.reject (js/Error. "x")) 50))) (.toBe :done))))

(describe "mcp-client/join cap" (fn []
                                  (it "gives up on a slow bring-up at the cap, never on a settled one" test-cap)
                                  (it "names the servers still starting, and nothing when all are up"
                                      (fn []
                                        (-> (expect (mcp/starting-note ["serena" "rea"])) (.toMatch #"still starting: rea, serena"))
                                        (-> (expect (mcp/starting-note [])) (.toBeNil))))
                                  (it "reads join-timeout-ms with a default"
                                      (fn []
                                        (-> (expect (:join-timeout-ms (mcp/load-settings))) (.toBe 2000))))))
