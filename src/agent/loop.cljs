(ns agent.loop
  "`run`, `steer`, `follow-up`."
  (:require ["ai" :refer [streamText stepCountIs]]
            [agent.context :refer [build-context get-active-tools get-active-tools-filtered]]
            [agent.middleware :refer [wrap-tools-with-middleware]]
            [agent.model-info :as model-info]
            [agent.sessions.compaction :as compaction]
            [agent.pricing :refer [calculate-turn-cost model-cost-key] :as pricing]
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
   ;; NOTE: "finish-step" and "finish" are deliberately unmapped. onStepFinish
   ;; already emits "turn_end" with the richer StepResult, and the loop emits
   ;; "agent_end" itself with {:text :usage :finishReason}; mapping the stream
   ;; chunks too made each fire TWICE (budget double-counted usage and aborted
   ;; at half its cap, per-turn counters ran 2x, every agent_end listener saw a
   ;; raw finish chunk first and the real payload second).
   })

(defn turn-outcome
  "Pure: how did the turn end, and what should react to it?

   `finish-reason` is the provider's own verdict. \"length\" means the response
   was CUT OFF at the output-token cap — until this existed, nothing in the
   codebase read that value, so a truncated response was indistinguishable from
   a model that had stopped working, and fed the stall counter that drives the
   two-turn warning and escalate's model swap.

   Returns:
     :cut-off?     the response hit the output cap — nudge for concision, and it
                   is NOT a stall no matter how many tools ran first
     :count-stall? whether :no-op-turns should be incremented
     :step-capped? the step limit stopped a model that was still working. Counted,
                   never inferred from finish-reason, which an abort path
                   (budget, stream filter) produces too."
  [finish-reason tool-calls steps max-steps]
  (let [cut-off? (= (str finish-reason) "length")
        no-tools? (zero? (or tool-calls 0))]
    {:cut-off?     cut-off?
     :count-stall? (and no-tools? (not cut-off?))
     :step-capped? (boolean (and (pos? (or max-steps 0))
                                 (>= (or steps 0) max-steps)))}))

(def cut-off-nudge
  "Borrowed from mini-swe-agent's format_error_template, which answers a
   length-truncated response with an instruction instead of silence."
  (str "Your previous response reached the output token limit and was "
       "cut off before it finished. Continue from where you stopped, and "
       "respond more concisely — take the next concrete action with a tool "
       "rather than restating the plan."))

(def volatile-boundary
  "Separator between the stable system prompt and the per-turn tail built from
   `volatile-additions`. A `---` line, so the prompt reads as one document and
   token_suite/kv_cache can put its cache breakpoint exactly here."
  "\n\n---\n\n")

(defn step-cap-nudge
  "Borrowed from apprentice's force-final-answer: the step cap used to end a
   run in silence, so a capped subagent handed its parent whatever text it
   had mid-work. One more call with the tools taken away turns that into a
   report. It is one tool-less call, not an auto-continue — the work itself
   still stops here."
  [max-steps]
  (str "You have used all " max-steps " steps of this turn and cannot call "
       "any more tools. Answer now with what you already have: report what "
       "you found or changed so far, and what remains undone."))

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

(defn provider-error-detail
  "The provider's OWN explanation for a failed call, dug out of the AI SDK
   error. Returns a short string, or nil when there is nothing to add.

   The AI SDK surfaces only the HTTP status text, so a user sees
   `Too Many Requests` and has no idea why. The reason is in
   `APICallError.responseBody`, which nothing read: openlux answers an
   exhausted upstream with a 429 whose body says
   `当前分组上游负载已饱和` (\"this group's upstream load is saturated\") — capacity,
   not throttling, and unfixable by the retry the generic message invites.

   RetryError wraps every attempt, so the body is on an inner error, not the
   one thrown. Checks the outer error first, then the last attempt."
  [e]
  (let [body   (fn [x] (when x (.-responseBody x)))
        errs   (try (vec (or (.-errors e) #js [])) (catch :default _ []))
        raw    (or (body e)
                   (body (.-lastError e))
                   (some body (reverse errs)))
        raw    (when (and (string? raw) (seq (str/trim raw))) (str/trim raw))]
    (when raw
      ;; Providers answer with {"error":{"message":…}} or {"message":…}; fall
      ;; back to the raw body, which is often HTML from a proxy in front.
      (let [msg (try
                  (let [j (js/JSON.parse raw)]
                    (or (some-> (.-error j) .-message)
                        (.-message j)))
                  (catch :default _ nil))
            out (str (or msg raw))]
        (when (seq out)
          (if (> (count out) 300) (str (.slice out 0 300) "…") out))))))

(defn stream-error-message
  "Pure: the human-readable message inside a fullStream `error` chunk.

   The AI SDK puts the provider's real failure here and then, if no step ever
   completed, rejects `.text` with the generic \"No output generated. Check the
   stream for errors.\" nyma had no mapping for the `error` chunk type, so
   `event-type` returned nil, the chunk was emitted under a nil event name that
   nothing listens to, and the only thing the user ever saw was the sentence
   telling them to check a stream they could not see."
  [chunk]
  (let [e (.-error chunk)]
    (cond
      (nil? e)      nil
      (string? e)   (when (seq (str/trim e)) (str/trim e))
      :else (let [m (or (.-message e)
                        (some-> (.-error e) .-message)
                        (try (js/JSON.stringify e) (catch :default _ nil)))]
              (when (and m (seq (str/trim (str m)))) (str/trim (str m)))))))

(defn- with-provider-detail!
  "Append the provider's explanation to `e`'s message, in place. Mutating
   rather than rewrapping keeps the error's type and fields intact — callers
   downstream test for context-overflow and abort shapes."
  [e]
  (when-let [detail (provider-error-detail e)]
    (let [m (str (or (.-message e) ""))]
      (when-not (.includes m detail)
        (try (aset e "message" (str m " — " detail)) (catch :default _ nil)))))
  e)

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

    ;; Awaited, unlike most fire-and-forget emits. This is the only hook that
    ;; runs BEFORE `get-active-tools-filtered` reads the registry below, which
    ;; makes it the one place an extension can finish registering tools in time
    ;; for the first turn. mcp_client joins its background bring-up here; a
    ;; handler that returns nothing costs nothing.
    (js-await ((:emit-async events) "agent_start" {}))

    ;; Main loop — re-enters for follow-up messages
    (loop []
      (let [messages  (build-context agent)
            ;; One-shot: the turn after a step cap runs with no tools and a
            ;; single step, so the model must answer in prose. The option rides
            ;; the follow-up MESSAGE (:turn) and is moved into state by the
            ;; drain below, one synchronous step before this read — so it can
            ;; only ever apply to the nudge that carried it, never to another
            ;; queued follow-up or to the user's next prompt.
            final-report? (boolean (:final-report (:turn-opts @state)))
            _ (when (:turn-opts @state) (swap! state dissoc :turn-opts))
            raw-tools (if final-report? {} (js-await (get-active-tools-filtered agent)))
            tools     (if middleware
                        (wrap-tools-with-middleware raw-tools middleware events)
                        (wrap-tools-with-before-hook raw-tools events))

            ;; Allow extensions to modify system prompt via before_agent_start
            before-result (js-await
                           (emit-collect "before_agent_start"
                                         #js {:userMessage  (last messages)
                                              :systemPrompt (:system-prompt config)}))

            ;; Build effective prompt from additions and base. Layout is
            ;; cache-aware: everything that is the same from turn to turn
            ;; comes first, and anything that changes per turn (a todo
            ;; ledger, a step reminder, evidence) goes LAST, after an
            ;; explicit boundary — so the provider's prefix cache covers the
            ;; stable part and one changed line does not invalidate the whole
            ;; system block. Measured elsewhere at 41-80% cost and 13-31%
            ;; TTFT (arXiv 2601.06007). Extensions declare per-turn text as
            ;; `volatile-additions`; `token_suite/kv_cache` places its
            ;; breakpoint at `volatile-boundary`.
            effective-prompt
            (let [base      (:system-prompt config)
                  additions (get before-result "system-prompt-additions")
                  addition  (get before-result "systemPromptAddition")
                  sections  (get before-result "prompt-sections")
                  volatile  (get before-result "volatile-additions")
                  sections-text
                  (when (seq sections)
                    (let [sorted (sort-by #(- (or (get % "priority") 0)) sections)]
                      (str/join "\n\n" (map #(get % "content") sorted))))]
              (cond-> base
                addition        (str "\n\n" addition)
                (seq additions) (str "\n\n" (str/join "\n\n" additions))
                sections-text   (str "\n\n" sections-text)
                (seq volatile)  (str volatile-boundary (str/join "\n\n" volatile))))

            ;; Inject messages from extensions
            inject-msgs (get before-result "inject-messages")

            ;; Resolve model — model_resolve lets extensions (e.g. model roles) override
            resolve-result (js-await
                            (emit-collect "model_resolve"
                                          ;; `:runtime-model` used to lead this `or`. Nothing in the
                                          ;; repo ever wrote it — setModel writes config.model and the
                                          ;; reducer stores :model — so it was a permanently-nil first
                                          ;; branch in the most load-bearing resolution path here.
                                          #js {:default   (:model config)
                                               :context   "generation"
                                               :turnCount (or (:turn-count @state) 0)}))
            active-model (or (get resolve-result "model") (:model config))
            _ (when-not active-model
                ;; Say WHICH model failed to resolve. The bare sentence sent
                ;; people to /login for a provider that was never registered —
                ;; e.g. one declared in ~/.nyma/settings.json and then hidden by
                ;; a project settings file replacing the whole local-models
                ;; array.
                (let [spec (or (:base-model-spec @state)
                               (aget (:config agent) "active-provider-name"))]
                  (throw (js/Error.
                          (str "No model configured"
                               (when (seq (str (or spec ""))) (str " for '" spec "'"))
                               ". Set ANTHROPIC_API_KEY or configure a provider via /login")))))

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
                             (cond
                               ;; A declared figure always wins: an operator who
                               ;; measured properly is not second-guessed.
                               (and (number? n) (pos? n)) n
                               ;; Otherwise use what we have observed, and only
                               ;; for a gateway. First-party providers keep a
                               ;; zero, so nothing they report changes how we
                               ;; budget for them.
                               (contains? @pricing/unpriced-providers (str (or pname "")))
                               (or (te/overhead-for model-key) 0)
                               :else 0))]

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
                                                    ;; The SYSTEM PROMPT counts. The estimator used to
                                                    ;; be applied to `messages` alone, so every
                                                    ;; consumer of this budget was told it had the
                                                    ;; whole system prompt's worth of room spare —
                                                    ;; in this repo AGENTS.md alone is ~10k tokens,
                                                    ;; before the skills listing and the section
                                                    ;; injectors. priority_assembly sized its
                                                    ;; working set against that and under-pruned;
                                                    ;; headroom compressed late. Compaction was
                                                    ;; never affected: it prefers the provider's
                                                    ;; own count of the last request.
                                                    :tokensUsed    (+ (te/estimate-tokens effective-prompt)
                                                                      (te/estimate-messages-tokens messages)
                                                                      overhead)
                                                    ;; Broken out so a consumer can tell how much
                                                    ;; of `tokensUsed` is not its own content.
                                                    :overheadTokens overhead
                                                    :systemTokens  (te/estimate-tokens effective-prompt)
                                                    :model         model-id}}))

              ;; Apply replacements from context_assembly
              effective-prompt (if-let [sys (get assembly-result "system")]
                                 sys effective-prompt)
              messages (if-let [msgs (get assembly-result "messages")]
                         (vec (map (fn [m]
                                     (if (map? m) m
                                         {:role (.-role m) :content (.-content m)}))
                                   msgs))
                         messages)

              ;; before_message_send: last transform before the provider call
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

              ;; What we are about to send, by our own reckoning. Recorded after
              ;; every replacement hook has had its say, so it describes the
              ;; request that actually goes out. The provider's reported input
              ;; tokens minus this is what a gateway injected on top.
              _content-est    (swap! state assoc :last-content-estimate
                                     (+ (te/estimate-tokens effective-prompt)
                                        (te/estimate-messages-tokens messages)))

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
                             ;; Read from settings — this was hardcoded to 5
                             ;; while the comment above claimed it matched
                             ;; :retry :max-retries, so `{"retry":
                             ;; {"max-retries": 3}}` silently got 6 attempts.
                             ;; Rate limits are the single largest failure
                             ;; category in the benchmark corpus (92 of them),
                             ;; and each burns ~68s exhausting attempts that
                             ;; cannot succeed while a quota is spent.
                             ;; Sent explicitly. Absent, the provider picks —
                             ;; and every benchmark in this repo was measured at
                             ;; whatever that happened to be.
                             :temperature     (:temperature config)
                             ;; Without this the provider decides, and a model
                             ;; that plans in prose can spend an entire turn
                             ;; without ever calling a tool.
                             :maxOutputTokens (:max-output-tokens config)
                             :maxRetries      (or (:max-retries config) 5)
                             :stopWhen        (stepCountIs (if final-report? 1 (:max-steps config)))
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
                nudged?    (atom false)
                ;; The provider's own verdict on how the turn ended, carried out
                ;; to the finalize block below. "length" means the response was
                ;; CUT OFF at the output-token cap — a fact, not a heuristic, and
                ;; until now read by nothing at all.
                turn-finish (atom nil)
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

              ;; retry loop: stream_filter can abort and re-run up to 2 times
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
                                  ;; Attach the provider's own explanation
                                  ;; before this leaves the loop — it is the
                                  ;; only place the raw error is still in hand.
                                  (throw (with-provider-detail! e)))))))
                        accumulated (atom "")
                        aborted     (atom false)
                        ;; The provider's own explanation, if the stream carried
                        ;; one. Kept so it can be attached to whatever the SDK
                        ;; throws afterwards.
                        stream-error (atom nil)]

                  ;; Stream events with stream_filter on text deltas
                    (let [iter (.call (aget (.-fullStream result) js/Symbol.asyncIterator)
                                      (.-fullStream result))]
                      (loop []
                        (let [chunk (js-await (.next iter))]
                          (when (and (not (.-done chunk)) (not @aborted))
                            (let [chunk-val (.-value chunk)
                                  evt-type  (event-type chunk-val)]
                              ;; An error part is a real turn outcome, not a
                              ;; chunk to forward under a nil event name.
                              (when (= "error" (.-type chunk-val))
                                (let [msg (stream-error-message chunk-val)]
                                  (reset! stream-error (or msg "stream error"))
                                  (dbg/error "loop" (str "provider stream error: " (or msg chunk-val)))
                                  (emit "provider_error" #js {:message (or msg "stream error")
                                                              :source  "stream"})))
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
                      (let [final-text     (try
                                             (js-await (.-text result))
                                             (catch :default e
                                               ;; "No output generated. Check the stream for
                                               ;; errors." is what the SDK throws when no step
                                               ;; completed. The stream said why; say it here,
                                               ;; because this is the last point that still has
                                               ;; both halves in hand.
                                               (when-let [se @stream-error]
                                                 (try (aset e "message"
                                                            (str (or (.-message e) "") " — " se))
                                                      (aset e "cause" se)
                                                      (catch :default _ nil)))
                                               (throw e)))
                            ;; A stream that carried an error and produced no
                            ;; text is a FAILED turn, not an empty one. Some
                            ;; providers end such a stream cleanly, so `.text`
                            ;; resolves "" and the failure would otherwise be
                            ;; reported as a model that answered with nothing.
                            _              (when (and @stream-error
                                                      (str/blank? (str final-text)))
                                             (throw (js/Error. @stream-error)))
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
                            _              (reset! turn-finish finish-reason)
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
                              (swap! state assoc :last-input-tokens input-tokens)
                              ;; Learn what a GATEWAY injects. `unpriced-providers`
                              ;; is the existing marker for "this is a relay, its
                              ;; rates and ids are its own"; first-party providers
                              ;; are left alone, so nothing they report can change
                              ;; how we budget for them.
                              (when (contains? @pricing/unpriced-providers
                                               (str (aget (:config agent) "active-provider-name")))
                                ;; Same key the lookup uses, or the two halves
                                ;; never meet: `model-cost-key` is a pricing key,
                                ;; `model-key` is the provider-qualified one.
                                (te/record-overhead! model-key input-tokens
                                                     (:last-content-estimate @state))))
                          ;; after_provider_request — inform extensions of usage/cache metrics
                            (emit "after_provider_request"
                                  #js {:usage        #js {:inputTokens  input-tokens
                                                          :outputTokens output-tokens
                                                          :cost         cost}
                                       :model        cost-model-id
                                       ;; AI SDK v7 reports cache reads under
                                       ;; usage.inputTokenDetails.cacheReadTokens
                                       ;; (there is no top-level cachedTokens).
                                       ;; Cache WRITES were read above for the cost
                                       ;; line and then dropped here, so nothing
                                       ;; downstream could see what a miss cost.
                                       :cacheWriteTokens cache-write
                                       :inputTokens  input-tokens
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
              (let [st        (:state agent)
                    ;; The provider stopped mid-response at the output-token cap.
                    ;; Borrowed from mini-swe-agent, which answers this case with
                    ;; a specific instruction instead of silence.
                    {:keys [cut-off? count-stall? step-capped?]}
                    (turn-outcome @turn-finish @tools-this-turn
                                  @steps-this-run (:max-steps config))
                    ui        (some-> (.-extension-api agent) .-ui)
                    notify!   (fn [msg] (when (and ui (.-notify ui)) (.notify ui msg "warning")))]
                ;; A cut-off turn that ran no tools is NOT a stall: the model was
                ;; still talking when the cap hit. Counting it drove the two-turn
                ;; warning and escalate's stall detection to the wrong remedy —
                ;; swap the model and prune the tail — for a response that was
                ;; merely too long.
                ;; A report turn is tool-less BY DESIGN: neither a stall nor,
                ;; if it runs long, something to continue with tools restored.
                (cond
                  final-report?            nil
                  count-stall?             (swap! st update :no-op-turns (fnil inc 0))
                  (pos? @tools-this-turn)  (swap! st assoc :worked? true :no-op-turns 0)
                  :else                    nil)

                ;; The nudge is NOT conditioned on the tool count: a turn that ran
                ;; five tools and got cut off on the sixth needs it just as much.
                (when (and cut-off? (not final-report?))
                  (reset! nudged? true)
                  (dbg/warn "[loop] response hit the output-token cap and was cut off")
                  (notify! "The model's response hit its output-token limit and was cut off. Asking it to continue more concisely.")
                  (follow-up agent {:role "user" :content cut-off-nudge}))

                ;; The step cap is a limit that fired in silence: streamText just
                ;; returns, so a capped run was indistinguishable from a finished
                ;; one. Counted, not inferred from finishReason, which an abort
                ;; path can produce too.
                (when step-capped?
                  (dbg/warn (str "[loop] step limit reached (" (:max-steps config) ") — the model was still working"))
                  (if (and (:step-cap-report config) (not final-report?))
                    ;; Queue the tool-less report turn; the follow-up drain
                    ;; below picks it up. Never re-armed from the report turn
                    ;; itself (max-steps 1 would otherwise loop).
                    (do (notify! (str "Step limit reached (" (:max-steps config)
                                      ") with work still in progress. Asking the model to report "
                                      "what it has; send a message to continue, or raise `max-steps`."))
                        (follow-up agent {:role "user" :content (step-cap-nudge (:max-steps config))
                                          :turn {:final-report true}}))
                    (notify! (str "Step limit reached (" (:max-steps config)
                                  ") with work still in progress. Send a message to continue, "
                                  "or raise `max-steps` in settings."))))

                ;; Only once the session has asked for work: two one-word
                ;; conversational turns tripped this. Three in a row after a
                ;; tool has run is the collapse; escalate keeps its own count.
                (when (and (= 3 (:no-op-turns @st)) (:worked? @st))
                  (dbg/warn "[loop] three turns in a row ran no tools — the model may have stopped making progress (/refine)")
                  (notify! "Three turns ran no tools — the model may have stopped making progress. Run /refine to see the pattern."))))

            (js-await ((:emit-async events) "turn_finalize"
                                            #js {:error        (or (boolean @turn-error) blocked?)
                                                 :toolCalls    @tools-this-turn
                                                 :noOpTurns    (:no-op-turns @(:state agent))
                                                 ;; So a consumer can tell a stall from a
                                                 ;; response that was merely cut off.
                                                 :finishReason @turn-finish
                                                 ;; A continue-nudge WAS queued for this
                                                 ;; turn: a handler that wants to shape
                                                 ;; that retry (thinking off) can rely
                                                 ;; on it running next.
                                                 :nudged       @nudged?}))

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
                ;; Per-turn options ride the message, not the transcript: the
                ;; loop top reads and clears them before anything else runs.
                (when-let [t (:turn next)] (swap! state assoc :turn-opts t))
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
