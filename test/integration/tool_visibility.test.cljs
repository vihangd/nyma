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
                                      :active-executions #{}})
        pipeline (create-pipeline events store)
        t        (make-tool (fn [_] "ok"))
        registry (create-registry {"test" t})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        captured (atom nil)]
    ((:on events) "tool_execution_start" (fn [data] (reset! captured data)))
    (js-await ((.-execute (get wrapped "test")) {:input "hello"}))
    (-> (expect (get @captured :toolName)) (.toBe "test"))
    (-> (expect (get @captured :args)) (.toBeTruthy))
    (-> (expect (get @captured :execId)) (.toBeTruthy))))

(defn ^:async test-end-event-has-result-and-duration []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil
                                      :active-executions #{}})
        pipeline (create-pipeline events store)
        t        (make-tool (fn [_] "the-result"))
        registry (create-registry {"test" t})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)
        captured (atom nil)]
    ((:on events) "tool_execution_end" (fn [data] (reset! captured data)))
    (js-await ((.-execute (get wrapped "test")) {:input "x"}))
    (-> (expect (get @captured :toolName)) (.toBe "test"))
    (-> (expect (get @captured :result)) (.toBe "the-result"))
    (-> (expect (get @captured :duration)) (.toBeGreaterThanOrEqual 0))))

(defn ^:async test-state-tracks-lifecycle []
  ;; There was a :tool-calls map here holding every call's args and result.
  ;; Nothing read it, so it was removed; :active-executions is the live view
  ;; the extension context and waitForIdle actually poll.
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil
                                      :active-executions #{}})
        pipeline (create-pipeline events store)
        seen     (atom nil)
        t        (make-tool (fn [_]
                              ;; Sampled mid-flight: the entry has to be there
                              ;; WHILE the tool runs, not just after.
                              (reset! seen (count (:active-executions ((:get-state store)))))
                              "result"))
        registry (create-registry {"test" t})
        active   ((:get-active registry))
        wrapped  (wrap-tools-with-middleware active pipeline events)]
    (-> (expect (count (:active-executions ((:get-state store))))) (.toBe 0))
    (js-await ((.-execute (get wrapped "test")) {:input "hi"}))
    (-> (expect @seen) (.toBe 1))
    (-> (expect (count (:active-executions ((:get-state store))))) (.toBe 0))))

(describe "integration: tool visibility events" (fn []
  (it "tool execution emits start event with args data" test-start-event-has-args)
  (it "tool execution emits end event with result and duration" test-end-event-has-result-and-duration)
  (it "active-executions fills during a call and empties after" test-state-tracks-lifecycle)))
