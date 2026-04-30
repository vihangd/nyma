(ns ext-mcp-client-status.test
  "Tests for the status-line segments. Each render is pure over the
   manager's state, so we drive states via the client atoms and
   inspect the segment output directly."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]
            [agent.extensions.mcp-client.status-segments :as segs]))

(defn- mock-api []
  (let [reg (atom {})]
    #js {:registerStatusSegment   (fn [id def] (swap! reg assoc id def) nil)
         :unregisterStatusSegment (fn [id]      (swap! reg dissoc id) nil)
         :__registry              reg}))

(defn- get-render [api seg-id]
  (when-let [def (get @(.-__registry api) seg-id)]
    (.-render def)))

(defn- with-states [m state-pairs]
  "state-pairs is [[name state] ...]. Builds a fresh client per
   pair, sets state, installs in manager."
  (reset! (:clients m)
          (into {} (for [[n st] state-pairs]
                     (let [c (client/create {:name n :command "echo"})]
                       (reset! (:state c) st)
                       [n c])))))

;;; ─── summary segment ─────────────────────────────────────────────

(describe "status-segments/mcp.summary"
          (fn []
            (it "registers under id 'mcp.summary' on the left"
                (fn []
                  (let [api    (mock-api)
                        m-ref  (atom (mgr/create))
                        sd-ref (atom false)]
                    (segs/register-all! api m-ref sd-ref)
                    (let [def (get @(.-__registry api) "mcp.summary")]
                      (-> (expect (some? def)) (.toBe true))
                      (-> (expect (.-position def)) (.toBe "left"))
                      (-> (expect (.-autoAppend def)) (.toBe true))))))

            (it "is hidden when no servers are configured"
                (fn []
                  (let [api    (mock-api)
                        m      (mgr/create)
                        m-ref  (atom m)
                        sd-ref (atom false)]
                    (segs/register-all! api m-ref sd-ref)
                    (let [render (get-render api "mcp.summary")
                          out    (render #js {:theme #js {}})]
                      (-> (expect (:visible? out)) (.toBe false))))))

            (it "shows 'MCP 2/3' counts and green when all healthy"
                (fn []
                  (let [api    (mock-api)
                        m      (mgr/create)
                        m-ref  (atom m)
                        sd-ref (atom false)]
                    (with-states m [["a" :running] ["b" :running]])
                    (segs/register-all! api m-ref sd-ref)
                    (let [out (.call (get-render api "mcp.summary") nil #js {:theme #js {}})]
                      (-> (expect (:visible? out)) (.toBe true))
                      (-> (expect (.includes (:content out) "MCP 2/2"))
                          (.toBe true))
                      (-> (expect (:color out)) (.toBe "#9ece6a"))))))

            (it "shows yellow when any server is starting/restarting"
                (fn []
                  (let [api    (mock-api)
                        m      (mgr/create)
                        m-ref  (atom m)
                        sd-ref (atom false)]
                    (with-states m [["a" :running] ["b" :starting]])
                    (segs/register-all! api m-ref sd-ref)
                    (let [out (.call (get-render api "mcp.summary") nil #js {:theme #js {}})]
                      (-> (expect (:color out)) (.toBe "#e0af68"))))))

            (it "shows red when any server is errored"
                (fn []
                  (let [api    (mock-api)
                        m      (mgr/create)
                        m-ref  (atom m)
                        sd-ref (atom false)]
                    (with-states m [["a" :running] ["b" :stopped-error]])
                    (segs/register-all! api m-ref sd-ref)
                    (let [out (.call (get-render api "mcp.summary") nil #js {:theme #js {}})]
                      (-> (expect (:color out)) (.toBe "#f7768e"))
                      (-> (expect (.includes (:content out) "MCP 1/2"))
                          (.toBe true))))))))

;;; ─── detail segment ─────────────────────────────────────────────

(describe "status-segments/mcp.detail"
          (fn []
            (it "is hidden when show-detail? is false"
                (fn []
                  (let [api    (mock-api)
                        m      (mgr/create)
                        m-ref  (atom m)
                        sd-ref (atom false)]
                    (with-states m [["a" :running]])
                    (segs/register-all! api m-ref sd-ref)
                    (let [out (.call (get-render api "mcp.detail") nil #js {:theme #js {}})]
                      (-> (expect (:visible? out)) (.toBe false))))))

            (it "shows per-server icons when show-detail? is true"
                (fn []
                  (let [api    (mock-api)
                        m      (mgr/create)
                        m-ref  (atom m)
                        sd-ref (atom true)]
                    (with-states m [["alpha" :running]
                                    ["beta"  :starting]
                                    ["gamma" :stopped-error]])
                    (segs/register-all! api m-ref sd-ref)
                    (let [out (.call (get-render api "mcp.detail") nil #js {:theme #js {}})
                          c   (:content out)]
                      (-> (expect (:visible? out)) (.toBe true))
                      (-> (expect (.includes c "alpha ✓")) (.toBe true))
                      (-> (expect (.includes c "beta ⚠")) (.toBe true))
                      (-> (expect (.includes c "gamma ✗")) (.toBe true))))))

            (it "is hidden when no servers are configured"
                (fn []
                  (let [api    (mock-api)
                        m-ref  (atom (mgr/create))
                        sd-ref (atom true)]
                    (segs/register-all! api m-ref sd-ref)
                    (let [out (.call (get-render api "mcp.detail") nil #js {:theme #js {}})]
                      (-> (expect (:visible? out)) (.toBe false))))))))
