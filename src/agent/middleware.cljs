(ns agent.middleware
  (:require [agent.interceptors :as ic]
            [agent.extension-context :refer [create-extension-context]]
            [agent.utils.ansi :refer [truncate-text]]))

(defn normalize-tool-result
  "Normalize tool results to string. Supports both plain string returns
   and pi-compatible {content: [{type: 'text', text: '...'}], details: {...}} format."
  [result]
  (cond
    (string? result) result
    (and (some? result) (not (string? result)) (.-content result))
    (let [parts (.-content result)]
      (.join
        (.map parts
          (fn [item]
            (if (= (.-type item) "text") (.-text item) (str item))))
        "\n"))
    :else (str result)))

(defn ^:async execute-tool-fn
  "Terminal interceptor enter — actually calls tool.execute.
   Passes extension context as second arg so extension tools can access UI.
   Attaches toolCallId, abortSignal, and onUpdate to the extension context."
  [ctx]
  (if (:cancelled ctx)
    (assoc ctx :result (or (:cancel-reason ctx)
                           (str "Tool call '" (:tool-name ctx) "' was cancelled")))
    (let [ext-ctx    (:extension-context ctx)
          events     (:events ctx)
          ;; Enrich extension context with tool execution info
          _          (when ext-ctx
                       (aset ext-ctx "toolCallId" (:exec-id ctx))
                       (aset ext-ctx "abortSignal"
                         (when-let [ctrl (:abort-controller ctx)]
                           (.-signal ctrl)))
                       (aset ext-ctx "onUpdate"
                         (fn [data]
                           (when events
                             ((:emit events) "tool_execution_update"
                               {:tool-name (:tool-name ctx)
                                :exec-id   (:exec-id ctx)
                                :data      data})))))
          raw-result (js-await ((.-execute (:tool ctx)) (:args ctx) ext-ctx))
          result     (normalize-tool-result raw-result)
          ;; Preserve structured metadata for downstream consumers
          details    (when (and (some? raw-result) (not (string? raw-result))
                               (.-details raw-result))
                       (js->clj (.-details raw-result)))
          is-error   (boolean (and (some? raw-result) (not (string? raw-result))
                                   (.-isError raw-result)))
          content-parts (when (and (some? raw-result) (not (string? raw-result))
                                   (.-content raw-result))
                          (js->clj (.-content raw-result)))]
      (cond-> (assoc ctx :result result)
        details       (assoc :result-details details)
        is-error      (assoc :result-is-error true)
        content-parts (assoc :result-content-parts content-parts)))))

(def tool-execution-interceptor
  "Terminal interceptor that executes the tool. Always last in chain."
  {:name  :execute-tool
   :enter execute-tool-fn})

(defn ^:async before-hook-compat-enter
  "Enter stage for before-hook-compat interceptor. Uses emit-async to
   ensure async handlers complete before checking cancelled/blocked flag.
   Supports both legacy before_tool_call and pi-compatible tool_call events.
   Handlers can block execution or mutate event.input to modify args."
  [events ctx]
  (let [original-input (clj->js (:args ctx))
        evt-ctx #js {:name      (:tool-name ctx)
                     :toolName  (:tool-name ctx)
                     :args      (:args ctx)
                     :input     original-input
                     :cancelled false
                     :blocked   false
                     :reason    nil}]
    (js-await ((:emit-async events) "before_tool_call" evt-ctx))
    (js-await ((:emit-async events) "tool_call" evt-ctx))
    (cond
      (.-blocked evt-ctx)
      (assoc ctx :cancelled true
                 :cancel-reason (or (.-reason evt-ctx) "Blocked by extension"))
      (.-cancelled evt-ctx)
      (assoc ctx :cancelled true)
      ;; If input was mutated by handler, use modified args
      (not= (js/JSON.stringify original-input) (js/JSON.stringify (.-input evt-ctx)))
      (assoc ctx :args (js->clj (.-input evt-ctx) :keywordize-keys true))
      ;; No mutations, pass through original args
      :else ctx)))

(defn before-hook-compat
  "Backwards-compatible interceptor that bridges old before_tool_call events.
   Extensions using (on \"before_tool_call\" handler) still work."
  [events]
  {:name  :before-hook-compat
   :enter (fn [ctx] (before-hook-compat-enter events ctx))})

(defn ^:async tool-result-leave
  "Leave stage: emit tool_result via emit-collect, allow extensions to modify result."
  [events ctx]
  (if-not events
    ctx
    (let [result-event {:toolName  (:tool-name ctx)
                        :toolCallId (:exec-id ctx)
                        :result    (:result ctx)
                        :details   (:result-details ctx)
                        :isError   (:result-is-error ctx)}
          collected (js-await ((:emit-collect events) "tool_result" (clj->js result-event)))]
      (if-let [modified-result (get collected "result")]
        (assoc ctx :result (if (string? modified-result)
                             modified-result
                             (str modified-result)))
        ctx))))

(defn ^:async tool-tracking-leave
  "Leave phase for tool-tracking: emit tool_result for modification, then tool_execution_end."
  [events store ctx]
  (let [ctx        (js-await (tool-result-leave events ctx))
        duration   (- (js/Date.now) (or (:start-time ctx) 0))
        result-str (truncate-text (str (:result ctx)) 500)]
    (when events
      ((:emit events) "tool_execution_end"
        {:tool-name       (:tool-name ctx)
         :exec-id         (:exec-id ctx)
         :duration        duration
         :result          result-str
         :details         (:result-details ctx)
         :is-error        (:result-is-error ctx)
         :content-parts   (:result-content-parts ctx)}))
    (when store
      ((:dispatch! store) :tool-execution-ended
        {:exec-id (:exec-id ctx) :duration duration})
      ((:dispatch! store) :tool-call-ended
        {:exec-id (:exec-id ctx) :duration duration :result result-str}))
    ctx))

(defn tool-tracking-interceptor
  "Interceptor that emits tool_execution_start/end events with timing data.
   Tracks active tool executions in the state store.
   Also emits tool_result in leave phase for extensions to modify results."
  [events store]
  {:name  :tool-tracking
   :enter (fn [ctx]
            (let [exec-id (str (js/Date.now) "-" (.toString (js/Math.random) 36))
                  start-time (js/Date.now)]
              (when events
                ((:emit events) "tool_execution_start"
                  {:tool-name (:tool-name ctx) :exec-id exec-id :args (:args ctx)
                   :label (when-let [t (:tool ctx)] (.-label t))}))
              (when store
                ((:dispatch! store) :tool-execution-started
                  {:tool-name (:tool-name ctx) :exec-id exec-id})
                ((:dispatch! store) :tool-call-started
                  {:tool-name (:tool-name ctx) :exec-id exec-id
                   :args (:args ctx) :start-time start-time}))
              (assoc ctx :exec-id exec-id :start-time start-time)))
   :leave (fn [ctx]
            (tool-tracking-leave events store ctx))})

(def prepare-arguments-interceptor
  "Interceptor that calls tool.prepareArguments if defined, transforming args before execution."
  {:name  :prepare-arguments
   :enter (fn [ctx]
            (if-let [prep (and (:tool ctx) (.-prepareArguments (:tool ctx)))]
              (let [prepared (prep (clj->js (:args ctx)))]
                (assoc ctx :args (js->clj prepared :keywordize-keys true)))
              ctx))})

(defn create-pipeline
  "Create a middleware pipeline for tool execution.
   Interceptors run in order: user middleware → before-hook-compat → execute-tool.
   Returns a map with :add, :remove, :execute, and :chain.
   Optionally accepts a store for tool execution tracking and an agent-ref
   atom for injecting extension context into tool execution."
  [events & [store agent-ref]]
  (let [chain (atom [(tool-tracking-interceptor events store)])]
    {:add
     (fn [interceptor & [opts]]
       (let [position (if opts (:position opts) nil)]
         (case position
           :first (swap! chain #(into [interceptor] %))
           (swap! chain conj interceptor))))

     :remove
     (fn [interceptor-name]
       (swap! chain (fn [c] (vec (remove #(= (:name %) interceptor-name) c)))))

     :execute
     (fn [tool-name tool args]
       (let [agent    (when agent-ref @agent-ref)
             ext-ctx  (when agent (create-extension-context agent))
             ctx      {:tool-name        tool-name
                       :tool             tool
                       :args             args
                       :cancelled        false
                       :result           nil
                       :extension-context ext-ctx
                       :events           events
                       :abort-controller (when agent (:abort-controller agent))}
             full     (vec (concat @chain
                                  [prepare-arguments-interceptor
                                   (before-hook-compat events)
                                   tool-execution-interceptor]))]
         (ic/execute full ctx)))

     :chain (fn [] @chain)}))

(defn wrap-tools-with-middleware
  "Wrap each tool's execute fn to run through the middleware pipeline.
   Returns a new tools map with wrapped execute functions."
  [tools pipeline events]
  (let [entries (js/Object.entries (clj->js tools))]
    (into {}
      (map (fn [entry]
             (let [tool-name (aget entry 0)
                   t         (aget entry 1)]
               [tool-name
                (js/Object.assign #js {} t
                  #js {:execute
                       (fn [args]
                         (let [result-promise ((:execute pipeline) tool-name t args)]
                           (.then result-promise (fn [ctx] (:result ctx)))))})])))
      entries)))

(defn tool-persistence-interceptor
  "Interceptor that persists tool call results to the session JSONL.
   This enables branch summarization to know which files were read/modified."
  [session]
  {:name  :tool-persistence
   :leave (fn [ctx]
            (when (and session (not (:cancelled ctx)))
              ((:append session)
                {:role     "tool_call"
                 :content  (str (:result ctx))
                 :metadata {:tool-name (:tool-name ctx)
                            :args      (:args ctx)}}))
            ctx)})
