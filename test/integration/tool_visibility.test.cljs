(ns integration.tool-visibility.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.events :refer [create-event-bus]]
            [agent.state :refer [create-agent-store]]
            [agent.middleware :refer [create-pipeline wrap-tools-with-middleware]]
            [agent.tool-registry :refer [create-registry]]))

;; Helper: create a real AI SDK tool for pipeline testing
(defn- make-tool [execute-fn]
  (tool #js {:description "test tool"
             :inputSchema (.object z #js {:input (-> (.string z) (.optional))})
             :execute execute-fn}))

(defn ^:async test-start-event-has-args []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil
                                      :tool-calls {} :active-executions #{}})
        pipeline (create-pipeline events store)
        t        (make-tool (fn [_] "ok"))
        registry (create-registry {"test" t})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        captured (atom nil)]
    ((:on events) "tool_execution_start" (fn [data] (reset! captured data)))
    (js-await ((.-execute (get wrapped "test")) {:input "hello"}))
    (-> (expect (get @captured :tool-name)) (.toBe "test"))
    (-> (expect (get @captured :args)) (.toBeTruthy))
    (-> (expect (get @captured :exec-id)) (.toBeTruthy))))

(defn ^:async test-end-event-has-result-and-duration []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil
                                      :tool-calls {} :active-executions #{}})
        pipeline (create-pipeline events store)
        t        (make-tool (fn [_] "the-result"))
        registry (create-registry {"test" t})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        captured (atom nil)]
    ((:on events) "tool_execution_end" (fn [data] (reset! captured data)))
    (js-await ((.-execute (get wrapped "test")) {:input "x"}))
    (-> (expect (get @captured :tool-name)) (.toBe "test"))
    (-> (expect (get @captured :result)) (.toBe "the-result"))
    (-> (expect (get @captured :duration)) (.toBeGreaterThanOrEqual 0))))

(defn ^:async test-state-tracks-lifecycle []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil
                                      :tool-calls {} :active-executions #{}})
        pipeline (create-pipeline events store)
        t        (make-tool (fn [_] "result"))
        registry (create-registry {"test" t})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)]
    ;; Before execution
    (-> (expect (count (:tool-calls ((:get-state store))))) (.toBe 0))
    ;; Execute
    (js-await ((.-execute (get wrapped "test")) {:input "hi"}))
    ;; After execution — tool-calls should have one entry with status "done"
    (let [tc (:tool-calls ((:get-state store)))]
      (-> (expect (count tc)) (.toBe 1))
      (let [entry (second (first tc))]
        (-> (expect (:tool-name entry)) (.toBe "test"))
        (-> (expect (:status entry)) (.toBe "done"))
        (-> (expect (:result entry)) (.toBe "result"))
        (-> (expect (:duration entry)) (.toBeGreaterThanOrEqual 0))))
    ;; active-executions should be empty (tool finished)
    (-> (expect (count (:active-executions ((:get-state store))))) (.toBe 0))))

(defn ^:async test-concurrent-tools-tracked-independently []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil
                                      :tool-calls {} :active-executions #{}})
        pipeline (create-pipeline events store)
        t1       (make-tool (fn [_] "result-a"))
        t2       (make-tool (fn [_] "result-b"))
        registry (create-registry {"tool-a" t1 "tool-b" t2})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)]
    ;; Execute both
    (js-await ((.-execute (get wrapped "tool-a")) {:input "a"}))
    (js-await ((.-execute (get wrapped "tool-b")) {:input "b"}))
    ;; Both should be tracked
    (let [tc (:tool-calls ((:get-state store)))]
      (-> (expect (count tc)) (.toBe 2)))))

(describe "integration: tool visibility events" (fn []
  (it "tool execution emits start event with args data" test-start-event-has-args)
  (it "tool execution emits end event with result and duration" test-end-event-has-result-and-duration)
  (it "state store tool-calls map tracks lifecycle" test-state-tracks-lifecycle)
  (it "concurrent tool executions tracked independently" test-concurrent-tools-tracked-independently)))
