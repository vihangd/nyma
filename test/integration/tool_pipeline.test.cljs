(ns integration.tool-pipeline.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [tool]]
            ["zod" :as z]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [agent.core :refer [create-agent]]
            [agent.middleware :refer [wrap-tools-with-middleware create-pipeline
                                      tool-persistence-interceptor]]
            [agent.events :refer [create-event-bus]]
            [agent.tool-registry :refer [create-registry]]
            [agent.tools :refer [builtin-tools]]))

;; Integration: interceptor chain → middleware pipeline → tool execution

(defn mock-tool [execute-fn]
  #js {:execute execute-fn :description "mock"})

(defn ^:async test-full-pipeline-with-agent []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        log      (atom [])
        _        ((:add pipeline)
                   {:name :logger
                    :enter (fn [ctx] (swap! log conj (str "call:" (:tool-name ctx))) ctx)
                    :leave (fn [ctx] (swap! log conj (str "done:" (:tool-name ctx))) ctx)})
        tool     (mock-tool (fn [args] (str "result:" (:x args))))
        tools    (wrap-tools-with-middleware {"test" tool} pipeline (:events agent))
        result   (js-await ((.-execute (get tools "test")) {:x "hello"}))]
    (-> (expect result) (.toBe "result:hello"))
    (-> (expect (clj->js @log)) (.toContain "call:test"))
    (-> (expect (clj->js @log)) (.toContain "done:test"))))

(defn ^:async test-pipeline-arg-transform []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        _        ((:add pipeline)
                   {:name :transform
                    :enter (fn [ctx]
                             (update-in ctx [:args :value] (fn [v] (* v 2))))})
        tool     (mock-tool (fn [args] (str "got:" (:value args))))
        tools    (wrap-tools-with-middleware {"calc" tool} pipeline (:events agent))
        result   (js-await ((.-execute (get tools "calc")) {:value 5}))]
    (-> (expect result) (.toBe "got:10"))))

(defn ^:async test-pipeline-cancellation-via-middleware []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        _        ((:add pipeline)
                   {:name :blocker
                    :enter (fn [ctx]
                             (if (= (:tool-name ctx) "dangerous")
                               (assoc ctx :cancelled true)
                               ctx))})
        safe     (mock-tool (fn [_] "safe-result"))
        danger   (mock-tool (fn [_] "danger-result"))
        tools    (wrap-tools-with-middleware {"safe" safe "dangerous" danger}
                                             pipeline (:events agent))
        r1       (js-await ((.-execute (get tools "safe")) {}))
        r2       (js-await ((.-execute (get tools "dangerous")) {}))]
    (-> (expect r1) (.toBe "safe-result"))
    (-> (expect r2) (.toContain "cancelled"))))

(defn ^:async test-pipeline-with-event-bridge []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        events   (:events agent)
        captured (atom nil)
        _        ((:on events) "before_tool_call"
                   (fn [ctx] (reset! captured (.-name ctx))))
        pipeline (:middleware agent)
        tool     (mock-tool (fn [_] "ok"))
        tools    (wrap-tools-with-middleware {"my-tool" tool} pipeline events)
        _        (js-await ((.-execute (get tools "my-tool")) {}))]
    (-> (expect @captured) (.toBe "my-tool"))))

(defn ^:async test-pipeline-leave-transforms-result []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        _        ((:add pipeline)
                   {:name :postprocess
                    :leave (fn [ctx] (update ctx :result str " [processed]"))})
        tool     (mock-tool (fn [_] "raw"))
        tools    (wrap-tools-with-middleware {"t" tool} pipeline (:events agent))
        result   (js-await ((.-execute (get tools "t")) {}))]
    (-> (expect result) (.toBe "raw [processed]"))))

(defn ^:async test-multiple-middleware-compose []
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        _        ((:add pipeline)
                   {:name :add-timestamp
                    :enter (fn [ctx] (assoc-in ctx [:args :ts] 12345))})
        _        ((:add pipeline)
                   {:name :add-source
                    :enter (fn [ctx] (assoc-in ctx [:args :source] "test"))})
        tool     (mock-tool (fn [args] (str (:ts args) "-" (:source args))))
        tools    (wrap-tools-with-middleware {"t" tool} pipeline (:events agent))
        result   (js-await ((.-execute (get tools "t")) {}))]
    (-> (expect result) (.toBe "12345-test"))))

(describe "integration: tool pipeline" (fn []
  (it "full pipeline with agent" test-full-pipeline-with-agent)
  (it "middleware transforms args" test-pipeline-arg-transform)
  (it "middleware can cancel specific tools" test-pipeline-cancellation-via-middleware)
  (it "event bridge forwards before_tool_call" test-pipeline-with-event-bridge)
  (it "leave stage transforms result" test-pipeline-leave-transforms-result)
  (it "multiple middleware compose correctly" test-multiple-middleware-compose)))

;; --- Real AI SDK tools through full pipeline + JS conversion ---
;; These tests exercise the EXACT production path that caused two bugs:
;; 1. Object.create(t) crash — tools from registry through wrap-tools-with-middleware
;; 2. input_schema.type error — inputSchema with Zod must produce valid JSON Schema

(defn make-real-tool
  "Create a tool using the actual Vercel AI SDK tool() function.
   Uses inputSchema (Zod) to match production tools.cljs exactly."
  [description execute-fn]
  (tool #js {:description  description
             :inputSchema  (.object z #js {:input (.string z)})
             :execute      execute-fn}))

(defn ^:async test-real-tools-through-agent-pipeline []
  ;; Full production path: create-agent → middleware pipeline → wrap → execute
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        events   (:events agent)
        real-t   (make-real-tool "integration test" (fn [args] (str "got:" (:input args))))
        tools    (wrap-tools-with-middleware {"real" real-t} pipeline events)
        result   (js-await ((.-execute (get tools "real")) {:input "hello"}))]
    (-> (expect result) (.toBe "got:hello"))
    ;; Verify inputSchema survived wrapping
    (-> (expect (.-inputSchema (get tools "real"))) (.toBeTruthy))
    ;; Verify asSchema produces valid JSON Schema (the actual API check)
    (let [schema (.-jsonSchema (asSchema (.-inputSchema (get tools "real"))))]
      (-> (expect (.-type schema)) (.toBe "object")))))

(defn ^:async test-real-tools-survive-reduce-kv-for-streamtext []
  ;; This simulates the EXACT path in loop.cljs that sends tools to streamText.
  ;; The tools map goes through: wrap-tools-with-middleware → reduce-kv → streamText
  (let [agent    (create-agent {:model "mock" :system-prompt "test"})
        pipeline (:middleware agent)
        events   (:events agent)
        real-t   (make-real-tool "reduce-kv test" (fn [args] (str "rv:" (:input args))))
        wrapped  (wrap-tools-with-middleware {"test-tool" real-t} pipeline events)
        ;; This is the reduce-kv pattern from loop.cljs
        js-tools (reduce-kv (fn [acc k v] (doto acc (aset k v))) #js {} wrapped)
        t        (aget js-tools "test-tool")]
    ;; Tool must have working execute
    (let [result (js-await ((.-execute t) {:input "world"}))]
      (-> (expect result) (.toBe "rv:world")))
    ;; inputSchema must survive for Anthropic
    (-> (expect (.-inputSchema t)) (.toBeTruthy))
    (-> (expect (.-description t)) (.toBe "reduce-kv test"))
    ;; asSchema must produce valid JSON Schema
    (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
      (-> (expect (.-type schema)) (.toBe "object")))))

(defn ^:async test-builtin-tools-through-registry-and-middleware []
  ;; Full production path: builtin-tools → registry → get-active → wrap → reduce-kv
  (let [events   (create-event-bus)
        pipeline (create-pipeline events)
        registry (create-registry builtin-tools)
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        ;; Convert to JS object using loop.cljs pattern
        js-tools (reduce-kv (fn [acc k v] (doto acc (aset k v))) #js {} wrapped)]
    ;; Verify all 4 builtin tools survived the full pipeline
    (doseq [name #js ["read" "write" "edit" "bash"]]
      (let [t      (aget js-tools name)
            schema (.-jsonSchema (asSchema (.-inputSchema t)))]
        (-> (expect t) (.toBeTruthy))
        (-> (expect (.-execute t)) (.toBeInstanceOf js/Function))
        (-> (expect (.-inputSchema t)) (.toBeTruthy))
        (-> (expect (.-type schema)) (.toBe "object"))
        (-> (expect (.-description t)) (.toBeTruthy))))))

(defn test-asschema-regression []
  ;; Regression guard: asSchema(tool.inputSchema) MUST produce {type: "object", ...}
  ;; This is what the AI SDK calls before sending tools to Anthropic.
  ;; A Zod schema passed as inputSchema goes through the ~standard interface.
  (let [zod-schema (.object z #js {:x (.string z)})
        t          (tool #js {:description "test"
                              :inputSchema zod-schema
                              :execute     (fn [_] "ok")})
        ;; Simulate Object.assign wrapping
        wrapped    (js/Object.assign #js {} t #js {:execute (fn [_] "wrapped")})
        schema     (.-jsonSchema (asSchema (.-inputSchema wrapped)))]
    (-> (expect (.-type schema)) (.toBe "object"))
    (-> (expect (.-properties schema)) (.toBeTruthy))))

;; --- tool-persistence-interceptor ---

(defn ^:async test-persistence-interceptor-appends-result []
  (let [events   (create-event-bus)
        appended (atom [])
        session  {:append (fn [entry] (swap! appended conj entry))}
        pipeline (create-pipeline events)
        _        ((:add pipeline) (tool-persistence-interceptor session))
        t        (mock-tool (fn [_] "file contents"))
        ctx      (js-await ((:execute pipeline) "read" t {:path "/foo.txt"}))]
    ;; Leave stage should have appended the result
    (-> (expect (count @appended)) (.toBe 1))
    (let [entry (first @appended)]
      (-> (expect (:role entry)) (.toBe "tool_call"))
      (-> (expect (:content entry)) (.toBe "file contents"))
      (-> (expect (get-in entry [:metadata :tool-name])) (.toBe "read"))
      (-> (expect (get-in entry [:metadata :args :path])) (.toBe "/foo.txt")))))

(defn ^:async test-persistence-interceptor-skips-cancelled []
  (let [events   (create-event-bus)
        appended (atom [])
        session  {:append (fn [entry] (swap! appended conj entry))}
        pipeline (create-pipeline events)
        _        ((:add pipeline) (tool-persistence-interceptor session))
        _        ((:add pipeline) {:name :blocker
                                   :enter (fn [ctx] (assoc ctx :cancelled true))})
        t        (mock-tool (fn [_] "should not persist"))
        ctx      (js-await ((:execute pipeline) "blocked" t {}))]
    (-> (expect (count @appended)) (.toBe 0))))

(describe "integration: real AI SDK tools through full pipeline" (fn []
  (it "real tools survive agent pipeline wrapping" test-real-tools-through-agent-pipeline)
  (it "real tools survive reduce-kv for streamText" test-real-tools-survive-reduce-kv-for-streamtext)
  (it "builtin tools survive registry → middleware → reduce-kv" test-builtin-tools-through-registry-and-middleware)
  (it "asSchema(inputSchema) produces valid JSON Schema" test-asschema-regression)))

(describe "integration: tool-persistence-interceptor" (fn []
  (it "appends tool call result to session" test-persistence-interceptor-appends-result)
  (it "skips persistence when cancelled" test-persistence-interceptor-skips-cancelled)))
