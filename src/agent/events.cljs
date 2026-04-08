(ns agent.events)

(def all-event-types
  ["session_start" "session_end" "session_before_switch" "session_switch"
   "session_before_fork" "session_before_tree" "session_tree"
   "session_directory" "session_shutdown"
   "agent_start" "agent_end"
   "turn_start" "turn_end"
   "message_start" "message_update" "message_end"
   "tool_call" "tool_result"
   "tool_execution_start" "tool_execution_update" "tool_execution_end"
   "before_tool_call"
   "before_provider_request"
   "context" "before_agent_start" "input"
   "compact" "before_compact"
   "before_branch_switch" "branch_summarized"
   "resources_discover" "model_select" "user_bash" "reload"
   "context_assembly" "after_provider_request"
   ;; ACP agent shell events
   "acp_connect" "acp_disconnect" "acp_message"
   "acp_tool_start" "acp_tool_update"
   "acp_usage" "acp_permission" "acp_mode_change"
   "acp_thought" "acp_plan" "acp_commands_update"
   ;; Native provider reasoning events (AI SDK interleaved-thinking)
   "reasoning_start" "reasoning_delta" "reasoning_end"
   ;; UI events
   "editor_change"
   ;; Session lifecycle
   "session_clear"])

;; ── Boolean keys are merged with OR (any true wins) ──────────────
(def ^:private boolean-keys
  #{"block" "cancel" "handle" "blocked" "cancelled"})

;; ── Collection keys are concatenated ─────────────────────────────
(def ^:private collection-keys
  #{"inject-messages" "system-prompt-additions" "paths"
    "skillPaths" "promptPaths" "themePaths" "prompt-sections"})

(defn- merge-results
  "Merge a sequence of handler return maps with semantic merging:
   - boolean keys → OR (any true wins)
   - collection keys → concatenated
   - scalar keys → last-writer-wins"
  [results]
  (reduce
    (fn [acc result]
      (if (nil? result)
        acc
        (let [m (if (map? result) result result)]
          (reduce-kv
            (fn [a k v]
              (let [ks (str k)]
                (cond
                  (contains? boolean-keys ks)
                  (assoc a k (or (get a k false) (boolean v)))

                  (contains? collection-keys ks)
                  (update a k (fn [existing]
                                (into (or existing [])
                                      (if (sequential? v) v [v]))))

                  :else
                  (assoc a k v))))
            acc m))))
    {}
    results))

(defn- get-sorted-handlers
  "Get sorted handlers for an event from the cache, rebuilding if stale."
  [handlers sorted-cache event]
  (or (get @sorted-cache event)
      (let [sorted (vec (sort-by :priority > (get @handlers event [])))]
        (swap! sorted-cache assoc event sorted)
        sorted)))

(defn ^:async run-handlers-async
  "Run handlers in priority order, awaiting any that return Promises."
  [handlers sorted-cache event data]
  (let [hs (get-sorted-handlers handlers sorted-cache event)]
    (doseq [{:keys [handler]} hs]
      (try
        (let [result (handler data)]
          (when (and result (.-then result))
            (js-await result)))
        (catch :default e
          (js/console.error
            (str "[nyma] Async handler error on '" event "':") e))))))

(defn ^:async run-handlers-collect
  "Run handlers in priority order, collect non-nil returns, merge them.
   Returns the merged result map (empty map if no handler returned anything)."
  [handlers sorted-cache event data]
  (let [hs      (get-sorted-handlers handlers sorted-cache event)
        results (atom [])]
    (doseq [{:keys [handler]} hs]
      (try
        (let [result (handler data)]
          (when (and result (.-then result))
            (let [resolved (js-await result)]
              (when (some? resolved)
                (swap! results conj resolved))))
          (when (and (some? result) (not (.-then result)))
            (swap! results conj result)))
        (catch :default e
          (js/console.error
            (str "[nyma] Collect handler error on '" event "':") e))))
    (merge-results @results)))

(defn create-event-bus
  "Typed event emitter with priority ordering, error isolation, and handler caching.
   Higher priority handlers run first. Default priority is 0.
   :emit is synchronous (fire-and-forget).
   :emit-async awaits handlers that return Promises (discards returns).
   :emit-collect awaits handlers and merges non-nil return values."
  []
  (let [handlers     (atom {})
        sorted-cache (atom {})]
    {:on   (fn [event handler & [priority]]
             (swap! handlers update event (fnil conj [])
               {:handler handler :priority (or priority 0)})
             ;; Invalidate cache for this event
             (swap! sorted-cache dissoc event))

     :emit (fn [event data]
             (let [hs (get-sorted-handlers handlers sorted-cache event)]
               (doseq [{:keys [handler]} hs]
                 (try
                   (handler data)
                   (catch :default e
                     (js/console.error
                       (str "[nyma] Extension handler error on '" event "':") e))))))

     :emit-async (fn [event data]
                   (run-handlers-async handlers sorted-cache event data))

     :emit-collect (fn [event data]
                     (run-handlers-collect handlers sorted-cache event data))

     :off  (fn [event handler]
             (swap! handlers update event
               (fn [hs] (vec (remove #(= (:handler %) handler) hs))))
             ;; Invalidate cache for this event
             (swap! sorted-cache dissoc event))}))
