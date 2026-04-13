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
   Attaches toolCallId, abortSignal, and onUpdate to the extension context.

   Execution priority:
     1. :cancelled true  — return cancel-reason without calling the tool (error semantics)
     2. :skip? true      — return pre-populated :result without calling the tool (normal semantics;
                           tool_result / tool_complete / tool_execution_end events still fire)
     3. else             — call the tool normally"
  [ctx]
  (cond
    (:cancelled ctx)
    (assoc ctx :result (or (:cancel-reason ctx)
                           (str "Tool call '" (:tool-name ctx) "' was cancelled")))

    (:skip? ctx)
    ctx

    :else
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
                       (.-details raw-result))
          is-error   (boolean (and (some? raw-result) (not (string? raw-result))
                                   (.-isError raw-result)))
          content-parts (when (and (some? raw-result) (not (string? raw-result))
                                   (.-content raw-result))
                          (.-content raw-result))]
      (cond-> (assoc ctx :result result)
        details       (assoc :result-details details)
        is-error      (assoc :result-is-error true)
        content-parts (assoc :result-content-parts content-parts)))))

(def tool-execution-interceptor
  "Terminal interceptor that executes the tool. Always last in chain."
  {:name  :execute-tool
   :enter execute-tool-fn})

(defn ^:async before-hook-compat-enter
  "Enter stage: emit-collect before_tool_call so handlers can cancel or transform args.
   Handlers return {block: true, reason: '...'} to cancel, {skip: true, result: '...'} to
   short-circuit with a canned result (tool_result/tool_complete events still fire),
   or {args: modified} to transform args. Also fires tool_call for pi-compatibility.

   Merge semantics for multiple handlers (emit-collect):
     block/cancel — boolean OR (any handler can cancel)
     skip         — boolean OR (any handler can skip)
     result       — last-writer-wins (lowest-priority handler's result wins)
     args         — last-writer-wins
   If both block and skip are set, block (cancellation) takes precedence."
  [events ctx]
  (let [data #js {:name     (:tool-name ctx)
                  :toolName (:tool-name ctx)
                  :args     (clj->js (:args ctx))
                  :execId   (:exec-id ctx)}
        result (js-await ((:emit-collect events) "before_tool_call" data))
        ;; Also emit tool_call for pi-compat (fire-and-forget)
        _      ((:emit events) "tool_call" data)]
    (cond
      (or (get result "block") (get result "cancel"))
      (assoc ctx :cancelled true
             :cancel-reason (or (get result "reason") "Blocked by extension"))

      ;; Skip execution — return a canned result string without calling the tool.
      ;; tool_result / tool_complete / tool_execution_end events still fire normally.
      ;; Use this for clean deny-with-explanation, result caching, and mock flows.
      (get result "skip")
      (assoc ctx :skip? true
             :result (or (get result "result") ""))

      ;; Allow arg transformation via returned {args: ...}
      (get result "args")
      (assoc ctx :args (get result "args"))

      :else ctx)))

(defn before-hook-compat
  "Interceptor that emits before_tool_call as emit-collect.
   Handlers can block via {block: true}, short-circuit via {skip: true, result: '...'},
   or transform via {args: modified}."
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

(defn- safe-call
  "Call f with arg, returning nil if f is nil or throws."
  [f arg]
  (when f
    (try (f arg) (catch :default _ nil))))

(defn- extract-display-fields
  "Read .display from tool object, invoke formatters on data, return custom fields map."
  [tool data-for-formatters]
  (when-let [display (and tool (.-display tool))]
    (let [custom-args   (safe-call (.-formatArgs display) (clj->js data-for-formatters))
          status-text   (safe-call (.-statusText display) (clj->js data-for-formatters))
          icon          (.-icon display)
          verbosity     (.-verbosity display)]
      (cond-> {}
        custom-args (assoc :custom-one-line-args custom-args)
        status-text (assoc :custom-status-text status-text)
        icon        (assoc :custom-icon icon)
        verbosity   (assoc :custom-verbosity verbosity)))))

(defn ^:async tool-tracking-leave
  "Leave phase: tool_result (modify), tool_complete (structured), tool_execution_end (UI)."
  [events store ctx]
  (let [ctx        (js-await (tool-result-leave events ctx))
        duration   (- (js/Date.now) (or (:start-time ctx) 0))
        ;; tool_complete — structured emit-collect, extensions can modify result
        complete-result (when events
                          (js-await
                           ((:emit-collect events) "tool_complete"
                                                   #js {:toolName   (:tool-name ctx)
                                                        :toolCallId (:exec-id ctx)
                                                        :args       (clj->js (:args ctx))
                                                        :result     (:result ctx)
                                                        :duration   duration
                                                        :isError    (boolean (:result-is-error ctx))
                                                        :details    (:result-details ctx)})))
        ctx        (if-let [mod-result (get complete-result "result")]
                     (assoc ctx :result (str mod-result))
                     ctx)
        result-str (truncate-text (str (:result ctx)) 500)
        display    (and (:tool ctx) (.-display (:tool ctx)))
        custom-result (when display
                        (safe-call (.-formatResult display) result-str))]
    (when events
      ((:emit events) "tool_execution_end"
                      (cond-> {:tool-name       (:tool-name ctx)
                               :exec-id         (:exec-id ctx)
                               :duration        duration
                               :result          result-str
                               :details         (:result-details ctx)
                               :is-error        (:result-is-error ctx)
                               :content-parts   (:result-content-parts ctx)}
                        custom-result (assoc :custom-one-line-result custom-result)
                        (and display (.-icon display)) (assoc :custom-icon (.-icon display))
                        (and display (.-verbosity display)) (assoc :custom-verbosity (.-verbosity display)))))
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
            (let [exec-id    (str (js/Date.now) "-" (.toString (js/Math.random) 36))
                  start-time (js/Date.now)
                  display-fields (extract-display-fields (:tool ctx) (:args ctx))]
              (when events
                ((:emit events) "tool_execution_start"
                                (merge {:tool-name (:tool-name ctx) :exec-id exec-id :args (:args ctx)
                                        :label (when-let [t (:tool ctx)] (.-label t))}
                                       display-fields)))
              (when store
                ((:dispatch! store) :tool-execution-started
                                    {:tool-name (:tool-name ctx) :exec-id exec-id})
                ((:dispatch! store) :tool-call-started
                                    {:tool-name (:tool-name ctx) :exec-id exec-id
                                     :args (:args ctx) :start-time start-time}))
              (assoc ctx :exec-id exec-id :start-time start-time)))
   :leave (fn [ctx]
            (tool-tracking-leave events store ctx))})

(defn- categorize-tool
  "Categorize a tool for permission checking."
  [tool-name]
  (cond
    (#{"bash"} tool-name)                     "exec"
    (#{"write" "edit"} tool-name)             "write"
    (#{"read" "glob" "grep" "ls"} tool-name)  "read"
    (#{"web_fetch" "web_search"} tool-name)   "network"
    :else                                      "other"))

(defn ^:async permission-check-enter
  "Check permission_request before tool execution.
   Handlers return {decision: 'allow'|'deny'|'ask', reason?: string}."
  [events ctx]
  (let [tool-name (:tool-name ctx)
        args      (:args ctx)
        result    (js-await
                   ((:emit-collect events) "permission_request"
                                           #js {:tool     tool-name
                                                :args     (clj->js args)
                                                :category (categorize-tool tool-name)
                                                :path     (or (get args :path) (get args "path"))}))]
    (if (= (get result "decision") "deny")
      (assoc ctx :cancelled true
             :cancel-reason (or (get result "reason") "Permission denied"))
      ctx)))

(defn permission-check-interceptor
  "Interceptor that emits permission_request for custom approval workflows."
  [events]
  {:name  :permission-check
   :enter (fn [ctx] (permission-check-enter events ctx))})

(def prepare-arguments-interceptor
  "Interceptor that calls tool.prepareArguments if defined, transforming args before execution."
  {:name  :prepare-arguments
   :enter (fn [ctx]
            (if-let [prep (and (:tool ctx) (.-prepareArguments (:tool ctx)))]
              (let [prepared (prep (clj->js (:args ctx)))]
                (assoc ctx :args prepared))
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
                                    (permission-check-interceptor events)
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
