(ns agent.loop
  (:require ["ai" :refer [streamText stepCountIs]]
            [agent.context :refer [build-context get-active-tools]]
            [agent.middleware :refer [wrap-tools-with-middleware]]
            [agent.pricing :refer [calculate-cost]]
            [agent.token-estimation :as te]
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
  (let [entries (js/Object.entries (clj->js tools))]
    (into {}
      (map (fn [entry]
             (let [tool-name (aget entry 0)
                   t         (aget entry 1)]
               [tool-name
                (js/Object.assign #js {} t
                  #js {:execute
                       (fn [args]
                         (let [ctx #js {:name      tool-name
                                        :args      args
                                        :cancelled false}]
                           ((:emit events) "before_tool_call" ctx)
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
  (let [{:keys [events config state tool-registry middleware]} agent]

    ;; Append user message via event store (or direct swap for backwards compat)
    (if-let [store (:store agent)]
      ((:dispatch! store) :message-added {:message {:role "user" :content user-message}})
      (swap! state update :messages conj {:role "user" :content user-message}))

    ((:emit events) "agent_start" {})

    ;; Main loop — re-enters for follow-up messages
    (loop []
      (let [messages (build-context agent)
            tools    (if middleware
                       (wrap-tools-with-middleware
                         (get-active-tools agent) middleware events)
                       (wrap-tools-with-before-hook
                         (get-active-tools agent) events))

            ;; Allow extensions to modify system prompt via before_agent_start (emit-collect)
            before-result (js-await
                            ((:emit-collect events) "before_agent_start"
                              #js {:userMessage (last messages)
                                   :systemPrompt (:system-prompt config)}))
            ;; Build effective prompt from additions and base
            effective-prompt
            (let [base (:system-prompt config)
                  additions (get before-result "system-prompt-additions")
                  addition  (get before-result "systemPromptAddition")
                  sections  (get before-result "prompt-sections")
                  ;; Sort prompt-sections by priority (descending) and concatenate
                  sections-text
                  (when (seq sections)
                    (let [sorted (sort-by #(- (or (get % "priority") 0)) sections)]
                      (str/join "\n\n" (map #(get % "content") sorted))))]
              (cond-> base
                addition     (str "\n\n" addition)
                (seq additions) (str "\n\n" (str/join "\n\n" additions))
                sections-text (str "\n\n" sections-text)))

            ;; Inject messages from extensions
            inject-msgs (get before-result "inject-messages")

            ;; Resolve model — support runtime model switching via state
            active-model (or (:runtime-model @state) (:model config))
            _ (when-not active-model
                (throw (js/Error. "No model configured. Set ANTHROPIC_API_KEY or configure a provider via /login")))

            ;; Compute token budget for context_assembly
            model-id (str (or (.-modelId active-model) active-model "unknown"))
            model-registry (:model-registry agent)
            context-window (if model-registry
                             ((:context-window model-registry) model-id)
                             100000)
            input-budget (- context-window (js/Math.floor (* context-window 0.3)))]

        ;; Inject messages from extensions (outside let binding to avoid doseq-in-let)
        (when (seq inject-msgs)
          (doseq [msg inject-msgs]
            (if-let [store (:store agent)]
              ((:dispatch! store) :message-added {:message msg})
              (swap! state update :messages conj msg))))

        ;; context_assembly event — extensions can replace messages or system prompt
        (let [assembly-result
              (js-await
                ((:emit-collect events) "context_assembly"
                  #js {:messages     (clj->js messages)
                       :systemPrompt effective-prompt
                       :tokenBudget  #js {:contextWindow context-window
                                          :inputBudget   input-budget
                                          :tokensUsed    (te/estimate-messages-tokens messages)
                                          :model         model-id}
                       :providers    (clj->js @(:context-providers agent))}))
              ;; Apply replacements from context_assembly
              effective-prompt (if-let [sys (get assembly-result "system")]
                                 sys effective-prompt)
              ;; If extension returned replacement messages, convert JS array to CLJ vector
              messages (if-let [msgs (get assembly-result "messages")]
                         (vec (map (fn [m]
                                     (if (map? m) m
                                       {:role    (.-role m)
                                        :content (.-content m)}))
                                   msgs))
                         messages)

              ;; Build the full mutable streamText config
              st-config #js {:model          active-model
                             :system         effective-prompt
                             :messages       (clj->js messages)
                             :tools          (reduce-kv (fn [acc k v] (doto acc (aset k v))) #js {} tools)
                             :stopWhen       (stepCountIs (:max-steps config))
                             :providerOptions #js {}
                             :onError        (fn [e] (throw (.-error e)))
                             :onStepFinish   (fn [step]
                                               ((:emit events) "turn_end" step)
                                               (inject-steer-messages! agent))}

              ;; before_provider_request — extensions can MUTATE st-config in place
              provider-result (js-await
                                ((:emit-collect events) "before_provider_request"
                                  st-config))]

        ;; If before_provider_request blocked, skip LLM call
        (if (get provider-result "block")
          (let [block-msg (or (get provider-result "reason") "Blocked by extension")]
            (if-let [store (:store agent)]
              ((:dispatch! store) :message-added
                {:message {:role "assistant" :content block-msg}})
              (swap! state update :messages conj
                {:role "assistant" :content block-msg}))
            ((:emit events) "agent_end" {:text block-msg :usage nil}))

          ;; Normal LLM call
          (do
            ((:emit events) "turn_start" {})

            ;; === AI SDK does everything here — using the (possibly mutated) config ===
            (let [result (js-await (streamText st-config))]

          ;; Stream events to subscribers
          (let [iter (.call (aget (.-fullStream result) js/Symbol.asyncIterator)
                            (.-fullStream result))]
            (loop []
              (let [chunk (js-await (.next iter))]
                (when-not (.-done chunk)
                  ((:emit events) (event-type (.-value chunk)) (.-value chunk))
                  (recur)))))

          ;; Capture final state and track usage/cost
          (let [final-text (js-await (.-text result))
                usage      (js-await (.-totalUsage result))
                store      (:store agent)]

            ;; Append assistant message via event store
            (if store
              ((:dispatch! store) :message-added {:message {:role "assistant" :content final-text}})
              (swap! state update :messages conj {:role "assistant" :content final-text}))

            ;; Dispatch usage to event-sourced store
            (when (and usage store)
              (let [input-tokens  (or (.-inputTokens usage) 0)
                    output-tokens (or (.-outputTokens usage) 0)
                    cost-model-id (str (:model (:config agent)))
                    cost          (calculate-cost cost-model-id input-tokens output-tokens)]
                ((:dispatch! store) :usage-updated
                  {:input-tokens input-tokens :output-tokens output-tokens :cost cost})

                ;; after_provider_request — inform extensions of usage/cache metrics
                ((:emit events) "after_provider_request"
                  #js {:usage        #js {:inputTokens  input-tokens
                                          :outputTokens output-tokens
                                          :cost         cost}
                       :model        cost-model-id
                       :cachedTokens (.-cachedTokens usage)
                       :turnCount    (or (:turn-count @state) 0)})))

            ((:emit events) "agent_end" {:text final-text :usage usage}))

          ;; Process follow-up queue
          (when-let [next (first @(:follow-queue agent))]
            (swap! (:follow-queue agent) #(vec (rest %)))
            (if-let [store (:store agent)]
              ((:dispatch! store) :message-added {:message {:role "user" :content (:content next)}})
              (swap! state update :messages conj {:role "user" :content (:content next)}))
            (recur))))))))))

(defn steer
  "Queue a steering message — delivered after current tool execution."
  [agent message]
  (swap! (:steer-queue agent) conj message))

(defn follow-up
  "Queue a follow-up message — delivered after agent finishes all work."
  [agent message]
  (swap! (:follow-queue agent) conj message))
