(ns agent-shell-capability-gates.test
  "Gates that decide what nyma sends an ACP agent.

   The registry's `:features` sets are NOT one of them. They went unread for
   five releases and are wrong where nobody looked, so they drive display only
   (/agents); every gate here reads a live signal the agent itself sent."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.features.agent-switcher :as switcher]
            [agent.extensions.agent-shell.features.effort-switcher :as effort]))

;;; ─── /agents display ───────────────────────────────────────

(describe "registry features are visible"
          (fn []
            (it "list-agents carries the feature set"
                (fn []
                  (let [claude (first (filter #(= (:key %) :claude) (registry/list-agents)))]
                    (-> (expect (contains? (:features claude) :mcp)) (.toBe true)))))

            (it "every builtin declares mcp — the flag was set on one of six"
                (fn []
                  (-> (expect (every? (fn [a] (contains? (:features a) :mcp))
                                      (registry/list-agents)))
                      (.toBe true))))

            (it "formats capabilities as one sorted line, and says so when empty"
                (fn []
                  (-> (expect (switcher/format-features #{:model-switch :cost}))
                      (.toBe "cost, model-switch"))
                  (-> (expect (switcher/format-features #{})) (.toContain "none"))))))

;;; ─── /effort pre-flight ────────────────────────────────────

(describe "effort gate reads the pushed config-option list"
          (fn []
            (it "an empty list proves nothing, so the send goes ahead"
                (fn []
                  ;; Pre-handshake, or an agent that never pushes options. The
                  ;; agent's own error is a better answer than a wrong refusal.
                  (-> (expect (effort/effort-unsupported? [])) (.toBe false))
                  (-> (expect (effort/effort-unsupported? nil)) (.toBe false))))

            (it "a populated list without `effort` refuses"
                (fn []
                  (-> (expect (effort/effort-unsupported? [{:configId "model"}]))
                      (.toBe true))))

            (it "a list containing `effort` sends"
                (fn []
                  (-> (expect (effort/effort-unsupported? [{:configId "model"}
                                                           {:configId "effort"}]))
                      (.toBe false))))

            (it "reads the JS-object shape js->clj* actually produces"
                (fn []
                  ;; :config-options is stored via js->clj*, which keeps plain
                  ;; JS objects with string keys.
                  (-> (expect (effort/effort-unsupported? [#js {:configId "effort"}]))
                      (.toBe false))
                  (-> (expect (effort/effort-unsupported? [#js {:configId "model"}]))
                      (.toBe true))))))

;;; ─── MCP transport filter ──────────────────────────────────

(def ^:private servers
  [{:name "local" :command "node" :args []}
   {:name "remote-http" :url "https://x/mcp" :type "http"}
   {:name "remote-sse"  :url "https://y/sse" :type "sse"}])

(describe "MCP servers are filtered by advertised transport"
          (fn []
            (it "drops a transport the agent's mcpCapabilities omits"
                (fn []
                  (let [{:keys [kept dropped]}
                        (pool/servers-for-agent servers #js {:mcpCapabilities #js {:http true}})]
                    (-> (expect (mapv :name kept)) (.toEqual #js ["local" "remote-http"]))
                    (-> (expect (mapv :name dropped)) (.toEqual #js ["remote-sse"])))))

            (it "forwards everything when the agent sent no mcpCapabilities"
                (fn []
                  ;; Five of six builtin handshakes are unverified; absent must
                  ;; not mean unsupported or working setups break silently.
                  (let [{:keys [kept dropped]}
                        (pool/servers-for-agent servers #js {:promptCapabilities #js {:image true}})]
                    (-> (expect (count kept)) (.toBe 3))
                    (-> (expect (count dropped)) (.toBe 0)))))

            (it "forwards everything before the handshake lands"
                (fn []
                  (-> (expect (count (:kept (pool/servers-for-agent servers nil))))
                      (.toBe 3))))

            (it "a stdio server survives every case"
                (fn []
                  (doseq [caps [nil
                                #js {}
                                #js {:mcpCapabilities #js {}}
                                #js {:mcpCapabilities #js {:http false :sse false}}]]
                    (-> (expect (mapv :name (:kept (pool/servers-for-agent servers caps))))
                        (.toContain "local")))))

            (it "an explicit false is a refusal, not a missing key"
                (fn []
                  (let [{:keys [kept]}
                        (pool/servers-for-agent servers #js {:mcpCapabilities #js {:http false :sse true}})]
                    (-> (expect (mapv :name kept)) (.toEqual #js ["local" "remote-sse"])))))))
