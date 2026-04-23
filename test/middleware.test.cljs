(ns middleware.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.middleware :refer [create-pipeline tool-execution-interceptor
                                      before-hook-compat wrap-tools-with-middleware
                                      normalize-tool-result tool-persistence-interceptor]]
            [agent.events :refer [create-event-bus]]
            [agent.tool-registry :refer [create-registry]]
            [agent.state :refer [create-agent-store]]
            [agent.core :refer [create-agent]]))

;; Helper: create a mock tool with an execute function
(defn mock-tool [execute-fn]
  #js {:execute execute-fn :description "mock"})

(defn ^:async test-empty-pipeline-executes-tool []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        tool     (mock-tool (fn [args] (str "result:" (:input args))))
        ctx      (js-await ((:execute pipeline) "test-tool" tool {:input "hello"}))]
    (-> (expect (:result ctx)) (.toBe "result:hello"))))

(defn ^:async test-middleware-modifies-args []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:add pipeline)
                  {:name :uppercase-args
                   :enter (fn [ctx]
                            (update-in ctx [:args :input] (fn [s] (.toUpperCase s))))})
        tool     (mock-tool (fn [args] (str "got:" (:input args))))
        ctx      (js-await ((:execute pipeline) "test-tool" tool {:input "hello"}))]
    (-> (expect (:result ctx)) (.toBe "got:HELLO"))))

(defn ^:async test-middleware-cancellation []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:add pipeline)
                  {:name :blocker
                   :enter (fn [ctx] (assoc ctx :cancelled true))})
        called   (atom false)
        tool     (mock-tool (fn [_] (reset! called true) "should not run"))
        ctx      (js-await ((:execute pipeline) "blocked-tool" tool {}))]
    (-> (expect @called) (.toBe false))
    (-> (expect (:result ctx)) (.toContain "cancelled"))))

(defn ^:async test-middleware-chain-ordering []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        log      (atom [])
        _        ((:add pipeline)
                  {:name :first
                   :enter (fn [ctx] (swap! log conj "first-enter") ctx)
                   :leave (fn [ctx] (swap! log conj "first-leave") ctx)})
        _        ((:add pipeline)
                  {:name :second
                   :enter (fn [ctx] (swap! log conj "second-enter") ctx)
                   :leave (fn [ctx] (swap! log conj "second-leave") ctx)})
        tool     (mock-tool (fn [_] "ok"))
        _ctx     (js-await ((:execute pipeline) "t" tool {}))]
    ;; Enter: first, second, before-hook-compat, execute-tool
    ;; Leave: execute-tool, before-hook-compat, second-leave, first-leave
    (-> (expect (clj->js @log))
        (.toEqual #js ["first-enter" "second-enter"
                       "second-leave" "first-leave"]))))

(defn ^:async test-middleware-result-transform-in-leave []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:add pipeline)
                  {:name :transform
                   :leave (fn [ctx] (update ctx :result str " [transformed]"))})
        tool     (mock-tool (fn [_] "original"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (:result ctx)) (.toBe "original [transformed]"))))

(defn ^:async test-before-hook-compat-bridges-events []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        captured (atom nil)
        _        ((:on events) "before_tool_call"
                               (fn [ctx] (reset! captured (.-name ctx))))
        tool     (mock-tool (fn [_] "ok"))
        _ctx     (js-await ((:execute pipeline) "my-tool" tool {}))]
    (-> (expect @captured) (.toBe "my-tool"))))

(defn ^:async test-before-hook-compat-cancellation []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:on events) "before_tool_call"
                               (fn [_data] #js {:block true}))
        tool     (mock-tool (fn [_] "should not run"))
        ctx      (js-await ((:execute pipeline) "blocked" tool {}))]
    (-> (expect (:result ctx)) (.toContain "Blocked"))))

(defn ^:async test-add-middleware-first-position []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        log      (atom [])
        _        ((:add pipeline)
                  {:name :second :enter (fn [ctx] (swap! log conj "second") ctx)})
        _        ((:add pipeline)
                  {:name :first :enter (fn [ctx] (swap! log conj "first") ctx)}
                  {:position :first})
        tool     (mock-tool (fn [_] "ok"))
        _ctx     (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (first @log)) (.toBe "first"))
    (-> (expect (second @log)) (.toBe "second"))))

(defn ^:async test-remove-middleware []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        log      (atom [])
        _        ((:add pipeline)
                  {:name :removable :enter (fn [ctx] (swap! log conj "should-not-run") ctx)})
        _        ((:remove pipeline) :removable)
        tool     (mock-tool (fn [_] "ok"))
        _ctx     (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (count @log)) (.toBe 0))))

(defn ^:async test-async-middleware []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:add pipeline)
                  {:name :async-mw
                   :enter (fn [ctx]
                            (js/Promise.resolve (assoc ctx :async-touched true)))})
        tool     (mock-tool (fn [_] "ok"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (:async-touched ctx)) (.toBe true))))

(defn ^:async test-wrap-tools-with-middleware []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        tools    {"greet" (mock-tool (fn [args] (str "Hello " (:name args))))}
        wrapped  (wrap-tools-with-middleware tools pipeline events)
        w        (get wrapped "greet")
        result   (js-await ((.-execute w) {:name "World"}))]
    (-> (expect result) (.toBe "Hello World"))
    ;; Verify non-execute properties survive wrapping
    (-> (expect (.-description w)) (.toBe "mock"))))

(defn ^:async test-error-in-middleware []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:add pipeline)
                  {:name  :error-mw
                   :enter (fn [_ctx] (throw (js/Error. "mw-error")))
                   :error (fn [ctx] ctx)})
        tool     (mock-tool (fn [_] "ok"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (:error ctx)) (.toBeDefined))))

(describe "create-pipeline" (fn []
                              (it "empty pipeline executes tool directly" test-empty-pipeline-executes-tool)
                              (it "middleware can modify args" test-middleware-modifies-args)
                              (it "middleware can cancel tool execution" test-middleware-cancellation)
                              (it "middleware runs in order (enter/leave)" test-middleware-chain-ordering)
                              (it "leave stage can transform result" test-middleware-result-transform-in-leave)
                              (it "before-hook-compat bridges events" test-before-hook-compat-bridges-events)
                              (it "before-hook-compat supports cancellation" test-before-hook-compat-cancellation)
                              (it "add with :first position prepends" test-add-middleware-first-position)
                              (it "remove middleware by name" test-remove-middleware)
                              (it "async middleware is awaited" test-async-middleware)
                              (it "error in middleware is captured" test-error-in-middleware)))

(defn ^:async test-before-hook-compat-async-cancellation []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        ;; Register a handler that returns block (emit-collect pattern)
        _        ((:on events) "before_tool_call"
                               (fn [_data]
                                 (js/Promise.resolve #js {:block true :reason "async block"})))
        tool     (mock-tool (fn [_] "should not run"))
        ctx      (js-await ((:execute pipeline) "blocked" tool {}))]
    (-> (expect (:result ctx)) (.toContain "async block"))))

(describe "wrap-tools-with-middleware" (fn []
                                         (it "wraps tools to use pipeline" test-wrap-tools-with-middleware)))

(describe "before-hook-compat async" (fn []
                                       (it "awaits async handlers before checking cancelled" test-before-hook-compat-async-cancellation)))

;; --- Real AI SDK tool tests ---
;; These use Vercel AI SDK's tool() function to match production tool shapes exactly.
;; The original Object.create(t) bug was only triggered with real AI SDK tools
;; passed through Squint map iteration — plain #js mocks masked the issue.

(defn make-real-tool
  "Create a tool using the actual Vercel AI SDK tool() function,
   matching what builtin-tools produces in production."
  [execute-fn]
  (tool #js {:description "real test tool"
             :inputSchema  (.object z #js {:input (.string z)})
             :execute     execute-fn}))

(defn ^:async test-wrap-with-real-ai-sdk-tools []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        real-t   (make-real-tool (fn [args] (str "got:" (:input args))))
        tools    {"real" real-t}
        wrapped  (wrap-tools-with-middleware tools pipeline events)
        result   (js-await ((.-execute (get wrapped "real")) {:input "hello"}))]
    (-> (expect result) (.toBe "got:hello"))))

(defn ^:async test-wrap-with-mixed-tool-types []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        plain-t  (mock-tool (fn [args] (str "plain:" (:input args))))
        real-t   (make-real-tool (fn [args] (str "real:" (:input args))))
        tools    {"plain" plain-t "real" real-t}
        wrapped  (wrap-tools-with-middleware tools pipeline events)
        r1       (js-await ((.-execute (get wrapped "plain")) {:input "a"}))
        r2       (js-await ((.-execute (get wrapped "real")) {:input "b"}))]
    (-> (expect r1) (.toBe "plain:a"))
    (-> (expect r2) (.toBe "real:b"))))

(defn ^:async test-wrap-preserves-tool-properties []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        real-t   (make-real-tool (fn [_] "ok"))
        tools    {"t" real-t}
        wrapped  (wrap-tools-with-middleware tools pipeline events)
        w        (get wrapped "t")]
    ;; Verify description and inputSchema survived wrapping
    (-> (expect (.-description w)) (.toBe "real test tool"))
    (-> (expect (.-inputSchema w)) (.toBeDefined))
    ;; Execute still works
    (let [result (js-await ((.-execute w) {:input "x"}))]
      (-> (expect result) (.toBe "ok")))))

(defn ^:async test-wrap-tools-from-registry []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        real-t   (make-real-tool (fn [args] (str "registered:" (:input args))))
        registry (create-registry {"my-tool" real-t})
        ;; This is the exact production path: registry → get-active → wrap
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        result   (js-await ((.-execute (get wrapped "my-tool")) {:input "test"}))]
    (-> (expect result) (.toBe "registered:test"))))

(defn ^:async test-wrap-tools-from-registry-after-extension-register []
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        registry (create-registry {})
        ;; Simulate extension registering a tool
        ext-tool (make-real-tool (fn [args] (str "ext:" (:input args))))
        _        ((:register registry) "ext__search" ext-tool)
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        result   (js-await ((.-execute (get wrapped "ext__search")) {:input "query"}))]
    (-> (expect result) (.toBe "ext:query"))))

(describe "wrap-tools-with-middleware - real AI SDK tools" (fn []
                                                             (it "wraps real AI SDK tool() objects" test-wrap-with-real-ai-sdk-tools)
                                                             (it "handles mixed plain and AI SDK tools" test-wrap-with-mixed-tool-types)
                                                             (it "preserves description and parameters after wrapping" test-wrap-preserves-tool-properties)))

(describe "wrap-tools-with-middleware - registry integration" (fn []
                                                                (it "wraps tools from registry get-active pipeline" test-wrap-tools-from-registry)
                                                                (it "wraps extension-registered tools from registry" test-wrap-tools-from-registry-after-extension-register)))

;; --- Enriched tool-tracking events ---

(defn ^:async test-tracking-emits-args []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)]
    ((:on events) "tool_execution_start"
                  (fn [data] (reset! captured data)))
    (let [tool (mock-tool (fn [_] "ok"))]
      (js-await ((:execute pipeline) "read" tool {:path "/tmp/test"})))
    (-> (expect (get @captured :args)) (.toBeTruthy))
    (-> (expect (:path (get @captured :args))) (.toBe "/tmp/test"))))

(defn ^:async test-tracking-emits-result []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)]
    ((:on events) "tool_execution_end"
                  (fn [data] (reset! captured data)))
    (let [tool (mock-tool (fn [_] "file-contents"))]
      (js-await ((:execute pipeline) "read" tool {:path "/tmp/x"})))
    (-> (expect (get @captured :result)) (.toBe "file-contents"))
    (-> (expect (get @captured :duration)) (.toBeGreaterThanOrEqual 0))))

(defn ^:async test-tracking-dispatches-tool-call-started []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)]
    (let [tool (mock-tool (fn [_] "ok"))]
      (js-await ((:execute pipeline) "bash" tool {:command "echo hi"})))
    (let [tc (:tool-calls ((:get-state store)))]
      ;; There should be exactly one entry
      (-> (expect (count tc)) (.toBe 1))
      (let [entry (second (first tc))]
        (-> (expect (:tool-name entry)) (.toBe "bash"))
        (-> (expect (:status entry)) (.toBe "done"))
        (-> (expect (:duration entry)) (.toBeGreaterThanOrEqual 0))))))

(defn ^:async test-tracking-dispatches-tool-call-ended []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)]
    (let [tool (mock-tool (fn [_] "the-result"))]
      (js-await ((:execute pipeline) "read" tool {:path "/x"})))
    (let [tc (:tool-calls ((:get-state store)))
          entry (second (first tc))]
      (-> (expect (:result entry)) (.toBe "the-result"))
      (-> (expect (:duration entry)) (.toBeGreaterThanOrEqual 0)))))

(describe "tool-tracking-interceptor enriched events" (fn []
                                                        (it "emits args in tool_execution_start event" test-tracking-emits-args)
                                                        (it "emits result in tool_execution_end event" test-tracking-emits-result)
                                                        (it "dispatches tool-call-started to store" test-tracking-dispatches-tool-call-started)
                                                        (it "dispatches tool-call-ended to store with result" test-tracking-dispatches-tool-call-ended)))

;; ── normalize-tool-result ──────────────────────────────────

(describe "normalize-tool-result" (fn []
                                    (it "passes through plain string"
                                        (fn [] (-> (expect (normalize-tool-result "hello")) (.toBe "hello"))))

                                    (it "converts pi-compat content array to string"
                                        (fn []
                                          (let [result #js {:content #js [#js {:type "text" :text "line1"}
                                                                          #js {:type "text" :text "line2"}]}]
                                            (-> (expect (normalize-tool-result result)) (.toBe "line1\nline2")))))

                                    (it "stringifies non-text content items"
                                        (fn []
                                          (let [result #js {:content #js [#js {:type "image" :url "x.png"}]}]
        ;; Non-text items are coerced via str, producing [object Object]
                                            (-> (expect (normalize-tool-result result)) (.toBe "[object Object]")))))

                                    (it "coerces number to string"
                                        (fn [] (-> (expect (normalize-tool-result 42)) (.toBe "42"))))

                                    (it "coerces nil to string"
                                        (fn [] (-> (expect (normalize-tool-result nil)) (.toBe ""))))))

;; ── tool-persistence-interceptor ───────────────────────────

(describe "tool-persistence-interceptor" (fn []
                                           (it "leave stage appends to session"
                                               (fn []
                                                 (let [appended (atom nil)
                                                       session  {:append (fn [entry] (reset! appended entry))}
                                                       ic       (tool-persistence-interceptor session)
                                                       ctx      {:tool-name "read" :args {:path "/x"} :result "content" :cancelled false}
                                                       result   ((:leave ic) ctx)]
                                                   (-> (expect (some? @appended)) (.toBe true))
                                                   (-> (expect (:role @appended)) (.toBe "tool_call"))
                                                   (-> (expect (get-in @appended [:metadata :tool-name])) (.toBe "read")))))

                                           (it "does not persist cancelled context"
                                               (fn []
                                                 (let [appended (atom nil)
                                                       session  {:append (fn [entry] (reset! appended entry))}
                                                       ic       (tool-persistence-interceptor session)
                                                       ctx      {:tool-name "read" :args {} :result "x" :cancelled true}]
                                                   ((:leave ic) ctx)
                                                   (-> (expect @appended) (.toBeNull)))))

                                           (it "handles nil session gracefully"
                                               (fn []
                                                 (let [ic  (tool-persistence-interceptor nil)
                                                       ctx {:tool-name "read" :args {} :result "x" :cancelled false}]
        ;; Should not throw
                                                   ((:leave ic) ctx))))))

;; ── display metadata propagation ──────────────────────────

(defn- mock-tool-with-display [execute-fn display]
  #js {:execute execute-fn :description "mock" :display (clj->js display)})

(defn ^:async test-display-formatArgs-propagated []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)]
    ((:on events) "tool_execution_start"
                  (fn [data] (reset! captured data)))
    (let [t (mock-tool-with-display
             (fn [_] "ok")
             {:formatArgs (fn [args] (str "custom:" (.-url args)))})]
      (js-await ((:execute pipeline) "scraper" t {:url "https://example.com"})))
    (-> (expect (get @captured :custom-one-line-args)) (.toBe "custom:https://example.com"))))

(defn ^:async test-display-formatResult-propagated []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)]
    ((:on events) "tool_execution_end"
                  (fn [data] (reset! captured data)))
    (let [t (mock-tool-with-display
             (fn [_] "line1\nline2\nline3")
             {:formatResult (fn [r] (str (count (.split r "\n")) " pages"))})]
      (js-await ((:execute pipeline) "scraper" t {:url "x"})))
    (-> (expect (get @captured :custom-one-line-result)) (.toBe "3 pages"))))

(defn ^:async test-display-icon-and-verbosity-propagated []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        start-captured (atom nil)
        end-captured   (atom nil)]
    ((:on events) "tool_execution_start" (fn [data] (reset! start-captured data)))
    ((:on events) "tool_execution_end" (fn [data] (reset! end-captured data)))
    (let [t (mock-tool-with-display
             (fn [_] "ok")
             {:icon "🌐" :verbosity "one-line"})]
      (js-await ((:execute pipeline) "scraper" t {:url "x"})))
    (-> (expect (get @start-captured :custom-icon)) (.toBe "🌐"))
    (-> (expect (get @start-captured :custom-verbosity)) (.toBe "one-line"))
    (-> (expect (get @end-captured :custom-icon)) (.toBe "🌐"))
    (-> (expect (get @end-captured :custom-verbosity)) (.toBe "one-line"))))

(defn ^:async test-display-formatter-error-does-not-crash []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)]
    ((:on events) "tool_execution_start"
                  (fn [data] (reset! captured data)))
    (let [t (mock-tool-with-display
             (fn [_] "ok")
             {:formatArgs (fn [_] (throw (js/Error. "boom")))})]
      (js-await ((:execute pipeline) "scraper" t {:url "x"})))
    ;; Should not have custom args (formatter threw), but should not crash
    (-> (expect (get @captured :custom-one-line-args)) (.toBeUndefined))))

(defn ^:async test-no-display-field-works-normally []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)]
    ((:on events) "tool_execution_start"
                  (fn [data] (reset! captured data)))
    (let [t (mock-tool (fn [_] "ok"))]
      (js-await ((:execute pipeline) "read" t {:path "/tmp"})))
    ;; No custom fields when tool has no .display
    (-> (expect (get @captured :custom-one-line-args)) (.toBeUndefined))
    (-> (expect (get @captured :custom-icon)) (.toBeUndefined))))

(describe "display metadata propagation" (fn []
                                           (it "propagates formatArgs to tool_execution_start" test-display-formatArgs-propagated)
                                           (it "propagates formatResult to tool_execution_end" test-display-formatResult-propagated)
                                           (it "propagates icon and verbosity to both events" test-display-icon-and-verbosity-propagated)
                                           (it "handles formatter errors without crashing" test-display-formatter-error-does-not-crash)
                                           (it "works normally when tool has no .display" test-no-display-field-works-normally)))

;; ── __skip short-circuit return ────────────────────────────
;; A before_tool_call handler returning {skip: true, result: "..."} must
;; bypass tool execution while keeping normal leave-stage semantics:
;; tool_result, tool_complete, and tool_execution_end all still fire.

(defn ^:async test-skip-bypasses-execute []
  ;; Core contract: execute fn is never called when skip is set.
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true :result "canned"}))
        called   (atom false)
        tool     (mock-tool (fn [_] (reset! called true) "should not run"))
        ctx      (js-await ((:execute pipeline) "skipped-tool" tool {}))]
    (-> (expect @called) (.toBe false))
    (-> (expect (:result ctx)) (.toBe "canned"))))

(defn ^:async test-skip-with-no-result-defaults-empty-string []
  ;; When skip:true but no result provided, result is "".
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true}))
        tool     (mock-tool (fn [_] "unreachable"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (:result ctx)) (.toBe ""))))

(defn ^:async test-skip-tool-result-event-still-fires []
  ;; tool_result emit-collect still runs; a subscriber can rewrite the skipped result.
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true :result "original"}))
        _        ((:on events) "tool_result"
                               (fn [_] #js {:result "rewritten"}))
        tool     (mock-tool (fn [_] "unreachable"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect (:result ctx)) (.toBe "rewritten"))))

(defn ^:async test-skip-tool-execution-end-event-fires []
  ;; tool_execution_end must fire even for skipped calls so the UI can dismiss spinners.
  (let [events    (create-event-bus)
        store     (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline  (create-pipeline events store)
        end-fired (atom false)]
    ((:on events) "before_tool_call"
                  (fn [_] #js {:skip true :result "done"}))
    ((:on events) "tool_execution_end"
                  (fn [_] (reset! end-fired true)))
    (let [tool (mock-tool (fn [_] "unreachable"))]
      (js-await ((:execute pipeline) "t" tool {})))
    (-> (expect @end-fired) (.toBe true))))

(defn ^:async test-block-still-returns-error-style-result []
  ;; Regression: existing {block: true} path must not be affected.
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:block true :reason "access denied"}))
        called   (atom false)
        tool     (mock-tool (fn [_] (reset! called true) "unreachable"))
        ctx      (js-await ((:execute pipeline) "blocked" tool {}))]
    (-> (expect @called) (.toBe false))
    (-> (expect (:result ctx)) (.toContain "access denied"))))

(defn ^:async test-cancelled-takes-priority-over-skip []
  ;; :cancelled true (from permission-check or earlier middleware) wins over :skip?.
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        ;; First middleware cancels
        _        ((:add pipeline)
                  {:name  :canceller
                   :enter (fn [ctx] (assoc ctx :cancelled true :cancel-reason "vetoed"))})
        ;; before_tool_call handler also skips (would normally win)
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true :result "skipped"}))
        called   (atom false)
        tool     (mock-tool (fn [_] (reset! called true) "unreachable"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect @called) (.toBe false))
    ;; :cancelled wins — result is the cancel-reason, not the skip result
    (-> (expect (:result ctx)) (.toContain "vetoed"))))

(defn ^:async test-skip-with-args-transform-skip-wins []
  ;; When emit-collect result has both skip:true and args, skip takes precedence.
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true :result "skipped" :args #js {:mutated true}}))
        received-args (atom nil)
        tool     (mock-tool (fn [args] (reset! received-args args) "unreachable"))
        ctx      (js-await ((:execute pipeline) "t" tool {:input "original"}))]
    ;; Tool was never called
    (-> (expect @received-args) (.toBeNull))
    (-> (expect (:result ctx)) (.toBe "skipped"))))

(defn ^:async test-two-skip-handlers-last-result-wins []
  ;; emit-collect merge semantics: skip:true is boolean OR'd (stays true);
  ;; result string is last-writer-wins (lowest-priority subscriber wins).
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        ;; Higher priority handler runs first — its result will be overwritten
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true :result "high-priority"})
                               #js {:priority 10})
        ;; Lower priority handler runs second — its result wins
        _        ((:on events) "before_tool_call"
                               (fn [_] #js {:skip true :result "low-priority"})
                               #js {:priority 5})
        called   (atom false)
        tool     (mock-tool (fn [_] (reset! called true) "unreachable"))
        ctx      (js-await ((:execute pipeline) "t" tool {}))]
    (-> (expect @called) (.toBe false))
    (-> (expect (:result ctx)) (.toBe "low-priority"))))

(describe "before_tool_call __skip short-circuit" (fn []
                                                    (it "skip:true bypasses tool execute, returns canned result"
                                                        (fn [] (test-skip-bypasses-execute)))
                                                    (it "skip:true with no result defaults to empty string"
                                                        (fn [] (test-skip-with-no-result-defaults-empty-string)))
                                                    (it "tool_result emit-collect still fires; subscriber can rewrite skipped result"
                                                        (fn [] (test-skip-tool-result-event-still-fires)))
                                                    (it "tool_execution_end still fires for skipped calls"
                                                        (fn [] (test-skip-tool-execution-end-event-fires)))
                                                    (it "block:true regression — still returns error-style cancel result"
                                                        (fn [] (test-block-still-returns-error-style-result)))
                                                    (it ":cancelled true takes priority over skip:true"
                                                        (fn [] (test-cancelled-takes-priority-over-skip)))
                                                    (it "skip with args transform — skip takes precedence"
                                                        (fn [] (test-skip-with-args-transform-skip-wins)))
                                                    (it "two skip handlers — last-writer-wins for result string"
                                                        (fn [] (test-two-skip-handlers-last-result-wins)))))

;;; ─── G18: modelId in extension context ──────────────────────────────

(defn ^:async test-g18-model-id-set-in-ext-ctx []
  (let [agent    (create-agent {:model "claude-test-G18" :system-prompt "test"})
        agent-ref (atom agent)
        events   (:events agent)
        pipeline  (create-pipeline events nil agent-ref nil)
        captured  (atom nil)
        test-tool #js {:description "capture-ctx"
                       :parameters  #js {}
                       :execute     (fn [_ ctx]
                                      (reset! captured ctx)
                                      "ok")}]
    (js-await ((:execute pipeline) "g18-tool" test-tool #js {}))
    (-> (expect (.-modelId @captured)) (.toBe "claude-test-G18"))))

(defn ^:async test-g18-model-id-unknown-when-no-agent []
  ;; No agent-ref → ext-ctx is nil → tool receives nil as second arg
  (let [events   (create-event-bus)
        pipeline  (create-pipeline events nil nil nil)
        captured  (atom :not-called)
        test-tool #js {:description "capture-ctx"
                       :parameters  #js {}
                       :execute     (fn [_ ctx]
                                      (reset! captured ctx)
                                      "ok")}]
    (js-await ((:execute pipeline) "g18-tool" test-tool #js {}))
    ;; ext-ctx is nil when there is no agent-ref
    (-> (expect (nil? @captured)) (.toBe true))))

(describe "G18: modelId in tool extension context" (fn []
                                                     (it "pipeline sets modelId on ext-ctx from agent config"
                                                         test-g18-model-id-set-in-ext-ctx)
                                                     (it "no agent-ref → ext-ctx is nil (tool gets nil ctx)"
                                                         test-g18-model-id-unknown-when-no-agent)))

;; ── tool_complete — blocking hazard ──────────────────────────────────
;; Documents and guards against tool_complete handlers returning slow Promises.
;; A handler that returns a Promise will block emit-collect for every tool call.
;; The fix pattern is to always return nil from tool_complete handlers.

(defn ^:async test-tool-complete-blocks-if-handler-returns-slow-promise []
  (let [events (create-event-bus)
        _      ((:on events) "tool_complete"
                             (fn [_]
                               (js/Promise. (fn [resolve _]
                                              (js/setTimeout resolve 60)))))
        t0      (js/Date.now)
        _       (js-await ((:emit-collect events) "tool_complete" #js {:name "read"}))
        elapsed (- (js/Date.now) t0)]
    (-> (expect elapsed) (.toBeGreaterThan 50))))

(defn ^:async test-tool-complete-resolves-instantly-when-handler-returns-nil []
  (let [events  (create-event-bus)
        _       ((:on events) "tool_complete"
                              (fn [_] nil))
        t0      (js/Date.now)
        _       (js-await ((:emit-collect events) "tool_complete" #js {:name "read"}))
        elapsed (- (js/Date.now) t0)]
    (-> (expect elapsed) (.toBeLessThan 20))))

(describe "tool_complete — blocking hazard" (fn []
                                              (it "emit-collect tool_complete blocks if handler returns a slow Promise"
                                                  test-tool-complete-blocks-if-handler-returns-slow-promise)
                                              (it "emit-collect tool_complete resolves instantly when handler returns nil"
                                                  test-tool-complete-resolves-instantly-when-handler-returns-nil)))
