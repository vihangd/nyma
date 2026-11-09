(ns acp-terminal-sandbox-test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.agent-shell.acp.handlers :as h]))

(defn ^:async test-terminal []
  (let [sent (atom [])
        conn {:stdin #js {:write #(swap! sent conj %)} :state (atom {}) :project-root (js/process.cwd)}]
    (js-await (h/handle-terminal-create conn #js {:id 7 :params #js {:command "echo" :args #js ["a b"]}}))
    (let [tid  (first (keys (:terminals @(:state conn))))
          proc (:proc (get-in @(:state conn) [:terminals tid]))]
      (js-await (.-exited proc))
      (js-await (js/Promise. (fn [r] (js/setTimeout r 50))))
      (-> (expect (str @(:output (get-in @(:state conn) [:terminals tid])))) (.toBe "a b\n"))
      (-> (expect (.includes (first @sent) "terminalId")) (.toBe true)))))

(describe "acp terminal/create" (fn []
  (it "runs the hosted agent's argv, quoted, and answers with the id" test-terminal)))
