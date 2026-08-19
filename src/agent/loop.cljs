(ns agent.loop
  (:require ["ai" :refer [streamText stepCountIs]]
            [agent.context :refer [build-context get-active-tools get-active-tools-filtered]]
            [agent.middleware :refer [wrap-tools-with-middleware]]
            [agent.model-info :as model-info]
            [agent.sessions.compaction :as compaction]
            [agent.pricing :refer [calculate-turn-cost model-cost-key]]
            [agent.thinking :as thinking]
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
   ;; NOTE: "finish-step" is deliberately unmapped — onStepFinish already
   ;; emits "turn_end" with the richer StepResult; mapping the stream chunk
   ;; too made turn_end fire TWICE per step (budget double-counted usage and
   ;; aborted at half its cap, per-turn counters ran 2x).
   "finish"          "agent_end"})

(defn- event-type [chunk]
  (get stream-event-types (.-type chunk)))

(defn wrap-tools-with-before-hook
  "DEPRECATED: Use wrap-tools-with-middleware instead.
   Wrap each tool's execute fn to emit before_tool_call.
   If any handler sets cancelled=true on the context, returns a cancellation message.

   IMPORTANT: do NOT pass `tools` through `clj->js`. Squint's clj->js
   recursively rebuilds JS objects with only string-keyed enumerable
   own properties — that drops Symbol-keyed properties, including the
   AI-SDK `Symbol.for(\"vercel.ai.schema\")` marker on jsonSchema-wrapped
   inputSchemas. Once that symbol is stripped, asSchema's `isSchema`
   check fails and streamText throws `schema is not a function`. Iterate
   the cljs map directly via reduce-kv to keep tool defs intact."
  [tools events]
  (let [emit (:emit events)]
    (reduce-kv
     (fn [acc tool-name t]
       (assoc acc tool-name
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
                          ((.-execute t) args))))})))
     {}
     tools)))

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

    (dbg/debug "loop/run"
               (str "user-message: " (.slice (str user-message) 0 200)
                    " | msg-count: " (count (:messages @state))))

    ;; Append user message via event store (or direct swap for backwards compat)
    (if-let [store (:store agent)]
      ((:dispatch! store) :message-added {:message {:role "user" :content user-message}})
      (swap! state update :messages conj {:role "user" :content user-message}))

    ;; Fresh AbortController per run so an abort from a previous turn doesn't
    ;; poison this one (the controller is single-use). Its signal is wired into
    ;; st-config below so an interrupt actually stops the in-flight stream.
    (when (:abort-controller agent)
      (reset! (:abort-controller agent) (js/AbortController.)))

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
            ;; Lookups use the provider-QUALIFIED key; the bare id above stays as
            ;; the label in event payloads, which extensions match on. Model ids
            ;; are not unique across providers, so a bare lookup read whichever
            ;; provider registered last. on-resolve in model_roles routes through
            ;; setModel, so active-provider-name and the active model always
            ;; describe the same choice.
            model-key      (model-info/model-key
                            (aget (:config agent) "active-provider-name")
                            active-model)
            model-registry (:model-registry agent)
            context-window (if model-registry
                             ((:context-window model-registry) model-key)
                             100000)
            input-budget   (- context-window (js/Math.floor (* context-window 0.3)))
            ;; Tokens the provider adds to every request that we never see.
            ;; A gateway that injects its own system prompt makes nyma's
            ;; estimate silently low — measured at ~6.8k on one relay — and
            ;; compaction then plans against a window it doesn't really have.
            ;; Declared per provider, since only the operator can know it:
            ;; Anthropic's count_tokens endpoint is often not proxied.
            overhead       (let [reg (:provider-registry agent)
                                 pname (aget (:config agent) "active-provider-name")
                                 entry (when (and reg (seq (str (or pname ""))))
                                         ((:get reg) (str pname)))
                                 n     (:overhead-tokens entry)]
                             (if (and (number? n) (pos? n)) n 0))]

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
                                                    ;; Counted as used, not deducted from the
                                                    ;; window: it genuinely occupies context, and
                                                    ;; every consumer of this budget already
                                                    ;; reasons about used-vs-window.
                                                    :tokensUsed    (+ (te/estimate-messages-tokens messages)
                                                                      overhead)
                                                    ;; Broken out so a consumer can tell how much
                                                    ;; of `tokensUsed` is not its own content.
                                                    :overheadTokens overhead
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

              ;; Per-step usage sum: on abort the SDK resolves totalUsage as
              ;; null-usage (finish part never fires), which would record ZERO
              ;; tokens for exactly the runaway run a budget abort killed —
              ;; fall back to the sum of completed steps.
              step-usage (atom {:input 0 :output 0})

              ;; Build mutable streamText config
              ;; maxRetries: number of RETRIES on transient errors (429, 503,
              ;; "high load"). AI SDK default is 2 (3 attempts total) which is
              ;; too aggressive for providers under sustained load. We set 5
              ;; retries (6 total attempts) with AI SDK's built-in exponential
              ;; backoff (2s initial, 2× factor) + respect for retry-after
              ;; headers from the provider. Matches :retry :max-retries in
              ;; settings defaults.
              ;; Tool calls this turn — a turn that runs none did no work. Bound
              ;; HERE, not in the inner let: :onStepFinish below closes over it,
              ;; and a binding introduced after st-config is not in that closure's
              ;; scope (a bare ReferenceError on every step that ran a tool).
              tools-this-turn (atom 0)
              ;; Model steps this run (tool call + response cycles).
              steps-this-run  (atom 0)

              st-config #js {:model           active-model
                             :system          effective-prompt
                             :messages        (clj->js messages)
                             ;; AI SDK v7 rejects role:"system" entries inside
                             ;; `messages` by default. nyma's cache/context
                             ;; extensions inject trusted system messages for
                             ;; prompt-cache control, so opt back in.
                             :allowSystemInMessages true
                             :tools           (reduce-kv (fn [acc k v] (doto acc (aset k v))) #js {} tools)
                             :abortSignal     (when-let [c (:abort-controller agent)] (.-signal @c))
                             :maxRetries      5
                             :stopWhen        (stepCountIs (:max-steps config))
                             ;; Extended thinking is opt-in per request: with no
                             ;; `thinking` field a Claude model returns no
                             ;; reasoning at all, measured against Opus 5.
                             :providerOptions (or (thinking/level->provider-options
                                                   (when-let [tl (:thinking-level agent)] @tl)
                                                   active-model)
                                                  #js {})
                             :onError         (fn [e] (throw (.-error e)))
                             :onStepFinish    (fn [step]
                                                ;; Steps, not runs. `:turn-count`
                                                ;; in the store counts agent
                                                ;; invocations because usage is
                                                ;; dispatched once from
                                                ;; result.totalUsage — so it
                                                ;; reads 1 for a whole -p task
                                                ;; and cannot calibrate a turn
                                                ;; budget. This is the number a
                                                ;; budget is measured in.
                                                (swap! steps-this-run inc)
                                                (when-let [u (.-usage step)]
                                                  (swap! step-usage
                                                         (fn [t] (-> t
                                                                     (update :input + (or (.-inputTokens u) 0))
                                                                     (update :output + (or (.-outputTokens u) 0))))))
                                                (when-let [tc (.-toolCalls step)]
                                                  (swap! tools-this-turn + (count tc)))
                                                (emit "turn_end" step)
                                                (inject-steer-messages! agent))}

              ;; before_provider_request — extensions can MUTATE st-config in place
              provider-result (js-await
                               (emit-collect "before_provider_request" st-config))]

          ;; Wrap the turn so turn_finalize ALWAYS fires — on the block path, the
          ;; normal path, AND when the provider errors. turn-error captures a
          ;; thrown provider error so the post-turn gate still runs before we
          ;; re-surface it. Without this, a flaky planning turn skipped
          ;; turn_finalize and left plan mode silently stuck ON.
          (let [turn-error (atom nil)
                ;; One overflow recovery per turn — a second is a real error.
                overflow-recovered? (atom false)
                ;; A block is NOT a real turn outcome (no plan/answer produced) —
                ;; flag it so turn_finalize carries error=true and the plan gate
                ;; skips (notifies) instead of running its approval flow on the
                ;; block message. Distinct from turn-error: a block still takes
                ;; the drain path, not the throw path.
                blocked?   (boolean (get provider-result "block"))]
           ;; If before_provider_request blocked, skip LLM call
            (if (get provider-result "block")
              (let [block-msg (or (get provider-result "reason") "Blocked by extension")]
                (if-let [store (:store agent)]
                  ((:dispatch! store) :message-added {:message {:role "assistant" :content block-msg}})
                  (swap! state update :messages conj {:role "assistant" :content block-msg}))
                (emit "agent_end" {:text block-msg :usage nil}))

            ;; Normal LLM call
              (try
                (emit "turn_start" {})

              ;; G1/G2 — retry loop: stream_filter can abort and re-run up to 2 times
                (loop [attempt 0]
                  (reset! (:retry-state agent) nil)

                ;; AI SDK call — with provider_error emit-collect fallback
                  (let [result
                        (try
                          (js-await (streamText st-config))
                          (catch :default e
                            ;; Context overflow: compact and retry ONCE. The
                            ;; threshold trigger cannot help when the declared
                            ;; window is wrong or missing, which is exactly the
                            ;; case for relay and local providers. A second
                            ;; overflow is a real error, never a loop.
                            (if (and (compaction/context-overflow-error? e)
                                     (not @overflow-recovered?))
                              (do (reset! overflow-recovered? true)
                                  (js-await (compaction/recover-from-overflow! agent st-config))
                                  (js-await (streamText st-config)))
                              (let [err-result (js-await
                                                (emit-collect "provider_error"
                                                              #js {:error   e
                                                                   :message (.-message e)
                                                                   :model   model-id
                                                                   :config  st-config}))]
                                (if (get err-result "retry")
                                  (js-await (streamText st-config))
                                  (throw e))))))
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
                                  ;; AI SDK v7 fullStream: the text-delta part
                                  ;; carries `text`. `textDelta` is the OBJECT
                                  ;; stream's field — reading it here made every
                                  ;; delta "", so stream_filter never saw text.
                                  (let [piece (or (.-text chunk-val) (.-textDelta chunk-val) "")]
                                  (swap! accumulated str piece)
                                  (let [filter-result
                                        (js-await
                                         (emit-collect "stream_filter"
                                                       #js {:delta @accumulated
                                                            :chunk piece
                                                            :type  evt-type}))]
                                    (when (get filter-result "abort")
                                      (reset! aborted true)
                                      (reset! (:retry-state agent)
                                              {:reason (get filter-result "reason")
                                               :inject (or (get filter-result "inject") [])})))
                                  (when-not @aborted (emit evt-type chunk-val))))
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
                          (emit "agent_end" {:text         @accumulated
                                             :usage        nil
                                             :finishReason "stream-filter-aborted"})))

                    ;; Normal completion — capture final state, track usage
                      (let [final-text     (js-await (.-text result))
                            usage          (let [u (js-await (.-totalUsage result))]
                                             ;; Aborted runs resolve null-usage; the
                                             ;; per-step sum is the truth then.
                                             (if (and (nil? (.-inputTokens u))
                                                      (pos? (+ (:input @step-usage) (:output @step-usage))))
                                               #js {:inputTokens  (:input @step-usage)
                                                    :outputTokens (:output @step-usage)}
                                               u))
                            ;; Normalize finishReason to a plain string here, at
                            ;; the single emit point, so every agent_end consumer
                            ;; (finalize_warn, spec_driven, …) sees a string.
                            ;; custom_provider_claude_native emits it as
                            ;; #js {:unified "stop" :raw "end_turn"}.
                            finish-reason  (let [fr (try (js-await (.-finishReason result))
                                                         (catch :default _ "unknown"))]
                                             (cond
                                               (string? fr) fr
                                               (and (object? fr) (.-unified fr)) (str (.-unified fr))
                                               :else (str fr)))
                            store          (:store agent)]

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
                                ;; inputTokens INCLUDES these; the cost fn splits
                                ;; them out. Same field the event payload below
                                ;; already reads — the cost line just ignored it,
                                ;; overstating a cache-heavy turn ~3x.
                                cache-read    (or (some-> usage .-inputTokenDetails .-cacheReadTokens) 0)
                                cache-write   (or (some-> usage .-inputTokenDetails .-cacheWriteTokens) 0)
                                cost-model-id (model-cost-key (:config agent))
                                cost          (calculate-turn-cost
                                               cost-model-id
                                               {:input-tokens  input-tokens
                                                :output-tokens output-tokens
                                                :cache-read-tokens  cache-read
                                                :cache-write-tokens cache-write})]
                            ((:dispatch! store) :usage-updated
                                                {:input-tokens input-tokens :output-tokens output-tokens
                                                 :cache-read-tokens cache-read :cache-write-tokens cache-write
                                                 :steps @steps-this-run
                                                 :cost cost})
                          ;; Ground truth for the compaction trigger: what the
                          ;; PROVIDER counted, not what we estimated. The estimate
                          ;; walks the whole session tree, while what is actually
                          ;; sent is pruned at context_assembly and capped by
                          ;; priority_assembly — two different quantities.
                          ;; Triggering off the tree meant firing on a 956k
                          ;; estimate while the requests themselves fit fine.
                            (when (and input-tokens (pos? input-tokens))
                              (swap! state assoc :last-input-tokens input-tokens))
                          ;; after_provider_request — inform extensions of usage/cache metrics
                            (emit "after_provider_request"
                                  #js {:usage        #js {:inputTokens  input-tokens
                                                          :outputTokens output-tokens
                                                          :cost         cost}
                                       :model        cost-model-id
                                       ;; AI SDK v7 reports cache reads under
                                       ;; usage.inputTokenDetails.cacheReadTokens
                                       ;; (there is no top-level cachedTokens).
                                       :cachedTokens (or (some-> usage .-inputTokenDetails .-cacheReadTokens)
                                                         (.-cachedTokens usage))
                                       :turnCount    (or (:turn-count @state) 0)})))

                      ;; agent_end stays SYNC fire-and-forget so slow external
                      ;; handlers (Stop hooks, gateway sends) never block the
                      ;; loop. Post-turn handlers that must enqueue a follow-up
                      ;; before the drain use the awaited turn_finalize below.
                        (emit "agent_end" {:text         final-text
                                           :usage        usage
                                           :finishReason finish-reason})))))
              ;; Capture a provider error so turn_finalize still runs below.
                (catch :default e (reset! turn-error e))))

           ;; turn_finalize — awaited post-turn boundary. ALWAYS fires: block,
           ;; normal, AND error paths all reach here (the try/catch above captures
           ;; provider errors). Fires BEFORE the follow-queue drain so a handler
           ;; (e.g. plan-mode's approval gate) can enqueue a follow-up that the
           ;; drain then picks up. {:error bool} lets handlers skip side effects
           ;; (e.g. auto-execute) when the turn failed.
           ;; A turn that ran no tools produced text and nothing else. One is
           ;; normal (an answer, a question). A RUN of them is the signature of
           ;; the context-rot collapse: in the session that prompted this work,
           ;; 56% of turns did no work and the last 12 in a row did none, while
           ;; the user typed "continue" 44 times.
           ;;
           ;; Counted BEFORE turn_finalize, not after: handlers that react to a
           ;; stall (escalation) read this on the very turn that finished, and
           ;; updating afterwards made every one of them a turn stale.
            (when-not @turn-error
              (let [st (:state agent)]
                (if (zero? @tools-this-turn)
                  (swap! st update :no-op-turns (fnil inc 0))
                  (swap! st assoc :no-op-turns 0))
                (when (= 2 (:no-op-turns @st))
                  (dbg/warn "[loop] two turns in a row ran no tools — the model may have stopped making progress (/refine)")
                  (when-let [ui (some-> (.-extension-api agent) .-ui)]
                    (when (.-notify ui)
                      (.notify ui "Two turns ran no tools — the model may have stopped making progress. Run /refine to see the pattern."
                               "warning"))))))

            (js-await ((:emit-async events) "turn_finalize"
                                            #js {:error     (or (boolean @turn-error) blocked?)
                                                 :toolCalls @tools-this-turn
                                                 :noOpTurns (:no-op-turns @(:state agent))}))

           ;; Auto-compaction. Deliberately BETWEEN turns: a compaction landing
           ;; mid-task is documented to send the model off the rails. Skipped on
           ;; the error path so a failed turn is not summarized as progress.
            (when-not @turn-error
              (js-await (compaction/maybe-auto-compact! agent)))

            (if @turn-error
             ;; Surface the error after the gate had its chance (don't drain).
              (throw @turn-error)
             ;; Process follow-up queue — recur → outer run-loop
              (when-let [next (first @(:follow-queue agent))]
                (dbg/debug "loop/follow-queue"
                           (str "drain content: " (.slice (str (:content next)) 0 200)
                                " | remaining: " (count (rest @(:follow-queue agent)))))
                (swap! (:follow-queue agent) #(vec (rest %)))
                (if-let [store (:store agent)]
                  ((:dispatch! store) :message-added {:message {:role "user" :content (:content next)}})
                  (swap! state update :messages conj {:role "user" :content (:content next)}))
                (recur)))))))))

(defn ^:async run-turn-with-update-handler
  "Register on-chunk for message_update, drive run-fn, always deregister.
   Used by the UI submit path and directly testable without React."
  [agent on-chunk run-fn]
  (let [events (:events agent)]
    ((:on events) "message_update" on-chunk)
    (try
      (js-await (run-fn))
      (finally
        ((:off events) "message_update" on-chunk)))))

(defn steer
  "Queue a steering message — delivered after current tool execution."
  [agent message]
  (swap! (:steer-queue agent) conj message))

(defn follow-up
  "Queue a follow-up message — delivered after agent finishes all work."
  [agent message]
  (dbg/debug "follow-up"
             (str "queued content: " (.slice (str (:content message)) 0 200)
                  " | stack: " (.-stack (js/Error.))))
  (swap! (:follow-queue agent) conj message))
