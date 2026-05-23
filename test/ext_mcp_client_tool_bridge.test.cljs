(ns ext-mcp-client-tool-bridge.test
  "Tool-bridge unit tests with fake clients + a minimal mock api."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]
            [agent.extensions.mcp-client.tool-bridge :as bridge]))

(defn- mock-api []
  (let [reg (atom {})]
    #js {:registerTool   (fn [name def] (swap! reg assoc name def) nil)
         :unregisterTool (fn [name]      (swap! reg dissoc name) nil)
         :overrideTool   (fn [name def] (swap! reg assoc name def) nil)
         :unoverrideTool (fn [name]     (swap! reg dissoc name) nil)
         :__registry     reg}))

(defn- fake-client-with-tools
  "Build a client locked at :running with a hardcoded tools list."
  [server-name tools]
  (let [c (client/create {:name server-name :command "echo"})]
    (reset! (:state c) :running)
    (reset! (:tools c) tools)
    ;; Install a fake SDK client that handles call-tool calls.
    (let [fake #js {}]
      (aset fake "callTool"
            (fn [args _opts]
              (let [n (.-name args)]
                (js/Promise.resolve
                 #js {:content #js [#js {:type "text"
                                         :text (str "result-of-" n)}]
                      :isError false}))))
      (aset fake "close" (fn [] (js/Promise.resolve nil)))
      (reset! (:client c) fake))
    c))

;;; ─── Naming ──────────────────────────────────────────────────────

(describe "tool-bridge/nyma-tool-name"
          (fn []
            (it "uses the CC mcp__<server>__<tool> shape"
                (fn []
                  (-> (expect (bridge/nyma-tool-name "lean-ctx" "ctx_read"))
                      (.toBe "mcp__lean-ctx__ctx_read"))
                  (-> (expect (bridge/nyma-tool-name "everything" "echo"))
                      (.toBe "mcp__everything__echo"))))))

;;; ─── register-all! / unregister-all! ─────────────────────────────

(describe "tool-bridge/register-all!"
          (fn []
            (it "registers every tool from a running client"
                (fn []
                  (let [api (mock-api)
                        m   (mgr/create)
                        c   (fake-client-with-tools
                             "lean-ctx"
                             [{:name "ctx_read" :description "read"
                               :input-schema #js {:type "object"}}
                              {:name "ctx_tree" :description "tree"
                               :input-schema #js {:type "object"}}])]
                    (reset! (:clients m) {"lean-ctx" c})
                    (let [names (bridge/register-all! api m)]
                      (-> (expect (count names)) (.toBe 2))
                      (-> (expect (contains? (set names) "mcp__lean-ctx__ctx_read"))
                          (.toBe true))
                      (-> (expect (contains? (set names) "mcp__lean-ctx__ctx_tree"))
                          (.toBe true)))
                    (-> (expect (count @(.-__registry api))) (.toBe 2)))))

            (it "skips clients that aren't :running"
                (fn []
                  (let [api (mock-api)
                        m   (mgr/create)
                        c-good (fake-client-with-tools
                                "good"
                                [{:name "tool-a" :description "a"
                                  :input-schema #js {:type "object"}}])
                        c-bad  (client/create {:name "bad" :command "echo"})]
                    (reset! (:state c-bad) :stopped-error)
                    (reset! (:clients m) {"good" c-good "bad" c-bad})
                    (let [names (bridge/register-all! api m)]
                      (-> (expect (count names)) (.toBe 1))
                      (-> (expect (first names)) (.toBe "mcp__good__tool-a"))))))

            (it "registers nothing when no servers are :running"
                (fn []
                  (let [api (mock-api)
                        m   (mgr/create)
                        c   (client/create {:name "x" :command "echo"})]
                    ;; default :state is :stopped
                    (reset! (:clients m) {"x" c})
                    (-> (expect (count (bridge/register-all! api m))) (.toBe 0)))))))

(describe "tool-bridge/unregister-all!"
          (fn []
            (it "removes each registered name"
                (fn []
                  (let [api (mock-api)
                        m   (mgr/create)
                        c   (fake-client-with-tools
                             "x"
                             [{:name "t1" :description "t1"
                               :input-schema #js {:type "object"}}
                              {:name "t2" :description "t2"
                               :input-schema #js {:type "object"}}])]
                    (reset! (:clients m) {"x" c})
                    (let [names (bridge/register-all! api m)]
                      (-> (expect (count @(.-__registry api))) (.toBe 2))
                      (bridge/unregister-all! api names)
                      (-> (expect (count @(.-__registry api))) (.toBe 0))))))

            (it "tolerates names that aren't registered"
                (fn []
                  (let [api (mock-api)]
                    (bridge/unregister-all! api ["never-registered"])
                    ;; Should not throw, registry stays empty
                    (-> (expect (count @(.-__registry api))) (.toBe 0)))))))

;;; ─── execute fn delegates to client.call-tool! ───────────────────

(defn ^:async test-execute-fn-delegates-to-client []
  (let [api (mock-api)
        m   (mgr/create)
        c   (fake-client-with-tools
             "leanc"
             [{:name "ctx_read" :description "read"
               :input-schema #js {:type "object"
                                  :properties #js {:path #js {:type "string"}}}}])]
    (reset! (:clients m) {"leanc" c})
    (bridge/register-all! api m)
    (let [tool-def (get @(.-__registry api) "mcp__leanc__ctx_read")
          execute  (.-execute tool-def)
          result   (js-await (execute #js {:path "/tmp/x"}))]
      (-> (expect result) (.toBe "result-of-ctx_read")))))

(describe "tool-bridge/execute" (fn []
                                  (it "delegates calls to client.call-tool! and flattens text content"
                                      test-execute-fn-delegates-to-client)))
