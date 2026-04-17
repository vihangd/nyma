(ns agent.loop
  (:require ["ai" :refer [streamText stepCountIs]]
            [agent.context :refer [build-context get-active-tools get-active-tools-filtered]]
            [agent.middleware :refer [wrap-tools-with-middleware]]
            [agent.pricing :refer [calculate-cost]]
            [agent.token-estimation :as te]
            [agent.debug :as dbg]
            [clojure.string :as str]))

(def stream-event-types
  "Maps AI SDK stream chunk types to internal event names."
  {"text-start"      "message_start"
   "text-delta"      "message_update"
   "text-end"        "message_end"
   "reasoning-start" "reasoning_start"
   "reasoning-delta" "reasoning_delta"
   "reasoning-end"   "reasoning_end"
   "tool-call"       "tool_call"
   "tool-result"     "tool_result"
   "finish-step"     "turn_end"
   "finish"          "agent_end"})

(defn- event-type [chunk]
  (get stream-event-types (.-type chunk)))

(defn wrap-tools-with-before-hook
  "DEPRECATED: Use wrap-tools-with-middleware instead.
   Wrap each tool's execute fn to emit before_tool_call.
   If any handler sets cancelled=true on the context, returns a cancellation message."
  [tools events]
  (let [emit    (:emit events)
        entries (js/Object.entries (clj->js tools))]
    (into {}
          (map (fn [entry]
                 (let [tool-name (aget entry 0)
                       t         (aget entry 1)]
                   [tool-name
                    (js/Object.assign
                     #js {} t
                     #js {:execute
                          (fn [args]
                            (let [ctx #js {:name      tool-name
                                           :args      args
                                           :cancelled false}]
                              (emit "before_tool_call" ctx)
                              (if (.-cancelled ctx)
                                (str "Tool call '" tool-name "' was cancelled by extension")
                                ((.-execute t) args))))})])))
          entries)))

(defn- inject-steer-messages!
  "Move steer queue messages into agent state between tool steps."
  [agent]
  (let [queued @(:steer-queue agent)]
    (when (seq queued)
      (if-let [store (:store agent)]
        (doseq [msg queued]
          ((:dispatch! store) :message-added {:message msg}))
        (swap! (:state agent) update :messages into queued))
      (reset! (:steer-queue agent) []))))

(defn ^:async run
  "Execute the agent loop. Yields events via the event bus.
   Returns when the LLM stops calling tools and all queues are empty."
  [agent user-message]
  (let [{:keys [events config state tool-registry middleware]} agent
        ;; Bind event bus functions as locals — prevents formatter from
        ;; corrupting ((:emit events) ...) double-paren calls.
        emit         (:emit events)
        emit-collect (:emit-collect events)]

    (dbg/dbg "[loop/run] user-message:" (.slice (str user-message) 0 200)
             "| msg-count:" (count (:messages @state)))

    ;; Append user message via event store (or direct swap for backwards compat)
    (if-let [store (:store agent)]
      ((:dispatch! store) :message-added {:message {:role "user" :content user-message}})
      (swap! state update :messages conj {:role "user" :content user-message}))

    (emit "agent_start" {})

    ;; Main loop — re-enters for follow-up messages
    (loop []
      (let [messages  (build-context agent)
            raw-tools (js-await (get-active-tools-filtered agent))
            tools     (if middleware
                        (wrap-tools-with-middleware raw-tools middleware events)
                        (wrap-tools-with-before-hook raw-tools events))

            ;; Allow extensions to modify system prompt via before_agent_start
            before-result (js-await
                           (emit-collect "before_agent_start"
                                         #js {:userMessage  (last messages)
                                              :systemPrompt (:system-prompt config)}))

            ;; Build effective prompt from additions and base
            effective-prompt
            (let [base      (:system-prompt config)
                  additions (get before-result "system-prompt-additions")
                  addition  (get before-result "systemPromptAddition")
                  sections  (get before-result "prompt-sections")
                  sections-text
                  (when (seq sections)
                    (let [sorted (sort-by #(- (or (get % "priority") 0)) sections)]
                      (str/join "\n\n" (map #(get % "content") sorted))))]
              (cond-> base
                addition        (str "\n\n" addition)
                (seq additions) (str "\n\n" (str/join "\n\n" additions))
                sections-text   (str "\n\n" sections-text)))

            ;; Inject messages from extensions
            inject-msgs (get before-result "inject-messages")

            ;; Resolve model — model_resolve lets extensions (e.g. model roles) override
            resolve-result (js-await
                            (emit-collect "model_resolve"
                                          #js {:default   (or (:runtime-model @state) (:model config))
                                               :context   "generation"
                                               :turnCount (or (:turn-count @state) 0)}))
            active-model (or (get resolve-result "model")
                             (or (:runtime-model @state) (:model config)))
            _ (when-not active-model
                (throw (js/Error. "No model configured. Set ANTHROPIC_API_KEY or configure a provider via /login")))

            ;; Compute token budget for context_assembly
            model-id       (str (or (.-modelId active-model) active-model "unknown"))
            model-registry (:model-registry agent)
            context-window (if model-registry
                             ((:context-window model-registry) model-id)
                             100000)
            input-budget   (- context-window (js/Math.floor (* context-window 0.3)))]

        ;; Inject messages from extensions
        (when (seq inject-msgs)
          (doseq [msg inject-msgs]
            (if-let [store (:store agent)]
              ((:dispatch! store) :message-added {:message msg})
              (swap! state update :messages conj msg))))

        ;; context_assembly — extensions can replace messages or system prompt
        (let [assembly-result
              (js-await
               (emit-collect "context_assembly"
                             #js {:messages    (clj->js messages)
                                  :systemPrompt effective-prompt
                                  :tokenBudget #js {:contextWindow context-window
                                                    :inputBudget   input-budget
                                                    :tokensUsed    (te/estimate-messages-tokens messages)
                                                    :model         model-id}
                                  :providers   (clj->js @(:context-providers agent))}))

              ;; Apply replacements from context_assembly
              effective-prompt (if-let [sys (get assembly-result "system")]
                                 sys effective-prompt)
              messages (if-let [msgs (get assembly-result "messages")]
                         (vec (map (fn [m]
                                     (if (map? m) m
                                         {:role (.-role m) :content (.-content m)}))
                                   msgs))
                         messages)

              ;; G20 — before_message_send: final transform before LLM call
              send-result (js-await
                           (emit-collect "before_message_send"
                                         #js {:messages (clj->js messages)
                                              :system   effective-prompt
                                              :model    model-id}))
              messages (if-let [msgs (get send-result "messages")]
                         (vec (map (fn [m]
                                     (if (map? m) m
                                         {:role (.-role m) :content (.-content m)}))
                                   msgs))
                         messages)
              effective-prompt (or (get send-result "system") effective-prompt)

              ;; Build mutable streamText config
              ;; maxRetries: number of RETRIES on transient errors (429, 503,
              ;; "high load"). AI SDK default is 2 (3 attempts total) which is
              ;; too aggressive for providers under sustained load. We set 5
              ;; retries (6 total attempts) with AI SDK's built-in exponential
              ;; backoff (2s initial, 2× factor) + respect for retry-after
              ;; headers from the provider. Matches :retry :max-retries in
              ;; settings defaults.
              st-config #js {:model           active-model
                             :system          effective-prompt
                             :messages        (clj->js messages)
                             :tools           (reduce-kv (fn [acc k v] (doto acc (aset k v))) #js {} tools)
                             :maxRetries      5
                             :stopWhen        (stepCountIs (:max-steps config))
                             :providerOptions #js {}
                             :onError         (fn [e] (throw (.-error e)))
                             :onStepFinish    (fn [step]
                                                (emit "turn_end" step)
                                                (inject-steer-messages! agent))}

              ;; before_provider_request — extensions can MUTATE st-config in place
              provider-result (js-await
                               (emit-collect "before_provider_request" st-config))]

          ;; If before_provider_request blocked, skip LLM call
          (if (get provider-result "block")
            (let [block-msg (or (get provider-result "reason") "Blocked by extension")]
              (if-let [store (:store agent)]
                ((:dispatch! store) :message-added {:message {:role "assistant" :content block-msg}})
                (swap! state update :messages conj {:role "assistant" :content block-msg}))
              (emit "agent_end" {:text block-msg :usage nil}))

            ;; Normal LLM call
            (do
              (emit "turn_start" {})

              ;; G1/G2 — retry loop: stream_filter can abort and re-run up to 2 times
              (loop [attempt 0]
                (reset! (:retry-state agent) nil)

                ;; AI SDK call — with provider_error emit-collect fallback
                (let [result
                      (try
                        (js-await (streamText st-config))
                        (catch :default e
                          (let [err-result (js-await
                                            (emit-collect "provider_error"
                                                          #js {:error   e
                                                               :message (.-message e)
                                                               :model   model-id
                                                               :config  st-config}))]
                            (if (get err-result "retry")
                              (js-await (streamText st-config))
                              (throw e)))))
                      accumulated (atom "")
                      aborted     (atom false)]

                  ;; Stream events with stream_filter on text deltas
                  (let [iter (.call (aget (.-fullStream result) js/Symbol.asyncIterator)
                                    (.-fullStream result))]
                    (loop []
                      (let [chunk (js-await (.next iter))]
                        (when (and (not (.-done chunk)) (not @aborted))
                          (let [chunk-val (.-value chunk)
                                evt-type  (event-type chunk-val)]
                            (if (= evt-type "message_update")
                              (do
                                (swap! accumulated str (or (.-textDelta chunk-val) ""))
                                (let [filter-result
                                      (js-await
                                       (emit-collect "stream_filter"
                                                     #js {:delta @accumulated
                                                          :chunk (or (.-textDelta chunk-val) "")
                                                          :type  evt-type}))]
                                  (when (get filter-result "abort")
                                    (reset! aborted true)
                                    (reset! (:retry-state agent)
                                            {:reason (get filter-result "reason")
                                             :inject (or (get filter-result "inject") [])})))
                                (when-not @aborted (emit evt-type chunk-val)))
                              (emit evt-type chunk-val)))
                          (when-not @aborted (recur))))))

                  ;; Aborted → inject messages and retry (max 2 times)
                  (if-let [retry @(:retry-state agent)]
                    (do
                      (reset! (:retry-state agent) nil)
                      (doseq [msg (:inject retry)]
                        (if-let [store (:store agent)]
                          ((:dispatch! store) :message-added {:message msg})
                          (swap! state update :messages conj msg)))
                      (if (< attempt 2)
                        (recur (inc attempt))
                        ;; Max retries exceeded
                        (emit "agent_end" {:text @accumulated :usage nil})))

                    ;; Normal completion — capture final state, track usage
                    (let [final-text (js-await (.-text result))
                          usage      (js-await (.-totalUsage result))
                          store      (:store agent)]

                      ;; message_before_store — extensions can modify content before storage
                      (let [store-result (js-await
                                          (emit-collect "message_before_store"
                                                        #js {:role    "assistant"
                                                             :content final-text
                                                             :model   model-id}))
                            final-text   (or (get store-result "content") final-text)]
                        (if store
                          ((:dispatch! store) :message-added {:message {:role "assistant" :content final-text}})
                          (swap! state update :messages conj {:role "assistant" :content final-text})))

                      ;; Dispatch usage to event-sourced store
                      (when (and usage store)
                        (let [input-tokens  (or (.-inputTokens usage) 0)
                              output-tokens (or (.-outputTokens usage) 0)
                              cost-model-id (str (:model (:config agent)))
                              cost          (calculate-cost cost-model-id input-tokens output-tokens)]
                          ((:dispatch! store) :usage-updated
                                              {:input-tokens input-tokens :output-tokens output-tokens :cost cost})
                          ;; after_provider_request — inform extensions of usage/cache metrics
                          (emit "after_provider_request"
                                #js {:usage        #js {:inputTokens  input-tokens
                                                        :outputTokens output-tokens
                                                        :cost         cost}
                                     :model        cost-model-id
                                     :cachedTokens (.-cachedTokens usage)
                                     :turnCount    (or (:turn-count @state) 0)})))

                      (emit "agent_end" {:text final-text :usage usage})))))

              ;; Process follow-up queue — outside retry loop, recur → outer run-loop
              (when-let [next (first @(:follow-queue agent))]
                (dbg/dbg "[loop/follow-queue] drain content:" (.slice (str (:content next)) 0 200)
                         "| remaining:" (count (rest @(:follow-queue agent))))
                (swap! (:follow-queue agent) #(vec (rest %)))
                (if-let [store (:store agent)]
                  ((:dispatch! store) :message-added {:message {:role "user" :content (:content next)}})
                  (swap! state update :messages conj {:role "user" :content (:content next)}))
                (recur)))))))))

(defn steer
  "Queue a steering message — delivered after current tool execution."
  [agent message]
  (swap! (:steer-queue agent) conj message))

(defn follow-up
  "Queue a follow-up message — delivered after agent finishes all work."
  [agent message]
  (dbg/dbg "[follow-up] queued content:" (.slice (str (:content message)) 0 200)
           "| stack:" (.-stack (js/Error.)))
  (swap! (:follow-queue agent) conj message))
