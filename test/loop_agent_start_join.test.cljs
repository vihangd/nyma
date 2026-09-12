(ns loop-agent-start-join.test
  "`agent_start` is awaited, and it is awaited early enough to matter.

   mcp_client brings its servers up in the background now — awaiting five of
   them inside `session_ready` cost 3–6 seconds before nyma painted anything
   (measured: 0.13 s with no mcp.json to find, 3–6 s with one). The bring-up
   still has to *join* somewhere, and where it joins is the whole correctness
   question, because `loop.cljs` reads the tool registry on the first line of
   the turn:

     raw-tools     (js-await (get-active-tools-filtered agent))   ← here
     before-result (js-await (emit-collect \"before_agent_start\" …))
     …
     (emit-collect \"before_provider_request\" st-config)

   A join at `before_agent_start` or `before_provider_request` would be too
   late: tools registered by a slow server would be missing on turn one and
   present on turn two — an order-dependent gap that nothing would report.

   So these tests pin two things a refactor could silently take away: that a
   slow `agent_start` handler is waited for at all, and that what it registers
   is in the tool list the provider is handed."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.loop :refer [run]]
            [test-util.agent-harness :refer [make-test-agent]]))

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(defn- late-tool-handler
  "An `agent_start` handler shaped like mcp_client's join: it resolves only
   after a delay, and registers a tool when it does."
  [agent tool-name ms]
  (^:async fn [_]
    (js-await (sleep ms))
    ((:register (:tool-registry agent))
     tool-name
     #js {:description "registered by a slow bring-up"
          :inputSchema #js {:type "object" :properties #js {}}
          :execute     (fn [_] (js/Promise.resolve "ok"))})))

(defn- capture-tools!
  "Block the provider and capture the tool names it would have been given."
  [agent seen]
  ((:on (:events agent)) "before_provider_request"
                         (fn [st-config]
                           (reset! seen (vec (js/Object.keys (.-tools st-config))))
                           #js {:block true :reason "captured"})))

(defn ^:async t-tools-arrive-on-turn-one []
  (let [agent (make-test-agent)
        seen  (atom nil)]
    (capture-tools! agent seen)
    ((:on (:events agent)) "agent_start" (late-tool-handler agent "late_tool" 40))
    (js-await (run agent "go"))
    ;; The assertion that fails if the join moves later: a handler that took
    ;; 40 ms still got its tool into the FIRST request, not the second.
    (-> (expect (vec (sort @seen))) (.toContain "late_tool"))))

(defn ^:async t-agent-start-is-awaited []
  ;; Guard the guard: if `agent_start` were fire-and-forget, the test above
  ;; could still pass by accident on a fast enough machine. This one cannot —
  ;; it asserts the handler had *finished* before the turn moved on.
  (let [agent (make-test-agent)
        done  (atom false)
        at-request (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] (reset! at-request @done) #js {:block true :reason "captured"}))
    ((:on (:events agent)) "agent_start"
                           (^:async fn [_] (js-await (sleep 40)) (reset! done true)))
    (js-await (run agent "go"))
    (-> (expect @at-request) (.toBe true))))

(defn ^:async t-a-failing-handler-does-not-kill-the-turn []
  ;; A dead MCP server must not take the turn with it. mcp_client swallows its
  ;; own failures, but the loop should not depend on that politeness.
  (let [agent    (make-test-agent)
        reached  (atom false)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [_] (reset! reached true) #js {:block true :reason "captured"}))
    ((:on (:events agent)) "agent_start"
                           (^:async fn [_] (js-await (sleep 5)) (throw (js/Error. "server died"))))
    (js-await (run agent "go"))
    (-> (expect @reached) (.toBe true))))

(describe "agent_start is the tool-registration join"
          (fn []
            (it "a slow handler's tools are in the first provider request"
                t-tools-arrive-on-turn-one)
            (it "is awaited, not fire-and-forget"
                t-agent-start-is-awaited)
            (it "a throwing handler still lets the turn reach the provider"
                t-a-failing-handler-does-not-kill-the-turn)))
