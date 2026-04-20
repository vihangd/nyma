(ns integration.tool-grouping-pipeline.test
  "Integration test: middleware pipeline -> App reducers -> group_messages.

   Drives real tool executions through wrap-tools-with-middleware, feeds the
   resulting events through apply-tool-start / apply-tool-end (the same
   reducers App uses), then passes the accumulated messages through
   group_messages + tool-group-label.

   This is the exact failure path for Bug-2: the middleware end-event never
   carries :args, so without the fix in apply-tool-end the grouped labels
   would be '?' for every tool call.

   Without this test the bug was invisible: tool_visibility.test.cljs only
   checked that start/end events were emitted at all -- it never verified
   that the resulting message list would render correctly in the UI."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [tool]]
            ["zod" :as z]
            ["../agent/ui/tool_status.jsx" :refer [group_messages]]
            [agent.events :refer [create-event-bus]]
            [agent.state :refer [create-agent-store]]
            [agent.middleware :refer [create-pipeline wrap-tools-with-middleware]]
            [agent.tool-registry :refer [create-registry]]
            [agent.ui.app-reducers :as reducers]))

(defn- make-tool [result]
  (tool #js {:description "test"
             :inputSchema (.object z #js {})
             :execute     (fn [_] result)}))

(defn- make-env []
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{}
                                      :model nil :tool-calls {}
                                      :active-executions #{}})
        pipeline (create-pipeline events store)]
    {:events events :pipeline pipeline}))

(defn- wire-reducers! [events messages]
  ((:on events) "tool_execution_start"
                (fn [data] (swap! messages #(reducers/apply-tool-start % data "collapsed" 500))))
  ((:on events) "tool_execution_end"
                (fn [data] (swap! messages #(reducers/apply-tool-end % data "collapsed" 500)))))

;;; ─── Test functions (defn ^:async so js-await compiles correctly) ───

(defn ^:async test-glob-grouped-labels-show-patterns []
  ;; Bug-2 regression: the exact scenario the user reported.
  ;; One "glob" tool executed three times -> grouped -> each item's label
  ;; must be the pattern, not the literal "?" that appeared before the fix.
  ;; Grouping requires consecutive calls with the same :tool-name, which is
  ;; the map key used to register the tool ("glob" here).
  (let [{:keys [events pipeline]} (make-env)
        patterns ["**/*.cljs" "src/**/*.ts" "test/**/*.cljs"]
        registry (create-registry {"glob" (make-tool "file.cljs")})
        wrapped  (wrap-tools-with-middleware ((:get-active registry)) pipeline events)
        messages (atom [])]
    (wire-reducers! events messages)
    (doseq [pattern patterns]
      (js-await ((.-execute (get wrapped "glob")) #js {:pattern pattern})))
    (let [grouped (group_messages @messages)
          group   (first grouped)]
      (-> (expect (count grouped)) (.toBe 1))
      (-> (expect (:role group)) (.toBe "tool-group"))
      (-> (expect (count (:items group))) (.toBe 3))
      ;; Every item must have :args -- none should fall back to "?"
      (doseq [item (:items group)]
        (-> (expect (nil? (:args item))) (.toBe false))))))

(defn ^:async test-read-grouped-labels-carry-path []
  (let [{:keys [events pipeline]} (make-env)
        registry (create-registry {"read" (make-tool "content")})
        wrapped  (wrap-tools-with-middleware ((:get-active registry)) pipeline events)
        messages (atom [])]
    (wire-reducers! events messages)
    (js-await ((.-execute (get wrapped "read")) #js {:path "src/a.cljs"}))
    (js-await ((.-execute (get wrapped "read")) #js {:path "src/b.cljs"}))
    (let [items (:items (first (group_messages @messages)))]
      (-> (expect (count items)) (.toBe 2))
      ;; :args must be present on both -- middleware end-event never carries
      ;; them so apply-tool-end must have copied from the start message
      (doseq [item items]
        (-> (expect (some? (:args item))) (.toBe true))))))

(defn ^:async test-end-event-has-no-args []
  ;; Documents the environmental assumption that makes apply-tool-end's
  ;; start-copy necessary. If middleware ever starts emitting :args on
  ;; the end event this test will fail -- the right signal to revisit
  ;; whether the copy in apply-tool-end is still needed.
  (let [{:keys [events pipeline]} (make-env)
        registry (create-registry {"probe" (make-tool "ok")})
        wrapped  (wrap-tools-with-middleware ((:get-active registry)) pipeline events)
        end-data (atom nil)]
    ((:on events) "tool_execution_end" (fn [data] (reset! end-data data)))
    (js-await ((.-execute (get wrapped "probe")) #js {:input "x"}))
    (-> (expect (nil? (get @end-data :args))) (.toBe true))))

(defn ^:async test-single-call-not-grouped []
  (let [{:keys [events pipeline]} (make-env)
        registry (create-registry {"solo" (make-tool "result")})
        wrapped  (wrap-tools-with-middleware ((:get-active registry)) pipeline events)
        messages (atom [])]
    (wire-reducers! events messages)
    (js-await ((.-execute (get wrapped "solo")) #js {}))
    ;; group_messages requires >=2 calls to collapse -- single stays as-is
    (let [grouped (group_messages @messages)]
      (-> (expect (count grouped)) (.toBe 1))
      (-> (expect (:role (first grouped))) (.toBe "tool-end")))))

(describe "integration: tool-grouping pipeline"
          (fn []
            (it "glob x3 grouped labels show patterns not '?' (Bug-2 regression)"
                test-glob-grouped-labels-show-patterns)
            (it "read x2 grouped labels carry :path from start event"
                test-read-grouped-labels-carry-path)
            (it "end-event payload has no :args -- confirms middleware never adds them"
                test-end-event-has-no-args)
            (it "single tool call is not grouped -- rendered as plain tool-end"
                test-single-call-not-grouped)))
