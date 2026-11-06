(ns agent.loop
  "`run`, `steer`, `follow-up`."
  (:require ["ai" :refer [streamText stepCountIs]]
            [agent.context :refer [build-context get-active-tools get-active-tools-filtered]]
            [agent.utils.stall :as stall]
            [agent.middleware :refer [wrap-tools-with-middleware]]
            [agent.model-info :as model-info]
            [agent.sessions.compaction :as compaction]
            [agent.pricing :refer [calculate-turn-cost model-cost-key] :as pricing]
            [agent.providers.fallback :as fallback]
            [agent.thinking :as thinking]
            [agent.token-estimation :as te]
            [agent.debug :as dbg]
            [agent.utils.pdf :as pdf]
            [clojure.string :as str]
            [agent.providers.builtins :as builtins]))

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

(defn output-cap
  "`max_tokens` for a request: the user's setting, the model's own limit, or
   8000 when neither is known, kept inside the context window. Some servers
   reject input + max_tokens over the window, and 16 models here declare an
   output limit as large as the window itself.

   `input` is the estimate for this pass's first request. A run's context grows
   after that, hence half the window at most: there is no later point to adjust."
  [setting model-key & [window input]]
  (let [cap (or setting (model-info/max-output model-key) 8000)]
    (if (and (number? window) (pos? window))
      ;; The floor is for the window's share only: a lower setting or model
      ;; limit is an instruction, and a server may refuse more than its limit.
      (min cap (max 1024 (min (js/Math.floor (/ window 2))
                              (- window (js/Math.ceil (* 1.1 (or input 0)))))))
      cap)))

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
   report. The report is also the handoff when the run earns another block of
   steps: tool results do not outlive a pass, so it is all that survives.

   `digest` is what the pass did. The report call runs without those results,
   and a report written blind said \"I can't see this turn's tool results\"."
  [max-steps & [digest]]
  (str "You have used all " max-steps " steps of this turn and cannot call "
       "any more tools. Answer now with what you already have: report what "
       "you found or changed so far, which files you touched, what remains "
       "undone, the next step, and the exact command or error for anything "
       "still failing."
       (when (seq digest)
         (str "\n\nWhat this turn did, latest last:\n" digest))))

(defn repeat-stop-nudge
  "The report call for a run stopped for repeating itself."
  [digest]
  (str "This turn was stopped because the same tool calls kept returning the "
       "same result. You cannot call any more tools. Report what you were "
       "trying to do, why it kept failing, and what you would need to get "
       "past it."
       (when (seq digest)
         (str "\n\nWhat this turn did, latest last:\n" digest))))

(def step-extension-nudge
  (str "Continue the task from your report above, with a fresh step budget. "
       "Tool output from earlier steps is gone, so read again whatever you need "
       "and do not redo finished work."))

(defn ^:async maybe-extend!
  "After a step-cap report: give the run another block of steps if a `step_cap`
   handler can show progress. Another pass rather than a bigger limit, so the
   context a block built up is dropped instead of re-sent on every further step.

   The bound lives here because the follow-queue drain has none. Anything
   already queued goes first, and it runs with tools anyway."
  [agent notify!]
  (let [state  (:state agent)
        config (:config agent)
        used   (or (:step-extensions @state) 0)
        limit  (or (:max-extensions config) 0)
        signal (some-> (:abort-controller agent) deref .-signal)]
    (when (and (< used limit)
               (empty? @(:follow-queue agent))
               (not (some-> signal .-aborted)))
      (let [answer (js-await ((:emit-collect (:events agent)) "step_cap"
                                                              #js {:steps         (:max-steps config)
                                                                   :extensions    used
                                                                   :maxExtensions limit}))]
        (dbg/warn (str "[loop] step cap: " (if (get answer "continue")
                                                 (str "extending, " (get answer "reason"))
                                                 "no progress shown, stopping")
                       " (" used " of " limit " used)"))
        (when (get answer "continue")
          (swap! state assoc :step-extensions (inc used))
          (notify! (str "Step limit reached (" (:max-steps config) "): progress made ("
                        (get answer "reason") "), continuing, extension "
                        (inc used) " of " limit "."))
          (follow-up agent {:role "user" :content step-extension-nudge}))))))

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

(defn- cache-split
  "The cached share of a usage object's input, as [read write]."
  [u]
  [(or (some-> u .-inputTokenDetails .-cacheReadTokens) 0)
   (or (some-> u .-inputTokenDetails .-cacheWriteTokens) 0)])

(defn- request-cost-key
  "Price what answered: a fallback moves the request, not the session's model."
  [agent st-config]
  (or (fallback/request-spec st-config) (model-cost-key (:config agent))))

(defn- add-live-step
  "One finished step into the figures the status line shows while a run is going.
   Totals only move at run end, so a long run otherwise reads as free. Separate
   from :usage-updated, which stays once per run: rows, `turn-count` and the
   after_provider_request subscribers all count runs."
  [st cost-key u]
  (let [[cr cw] (cache-split u)]
    (-> st
        (update :run-steps (fnil inc 0))
        (update :run-cost (fnil + 0)
                (calculate-turn-cost cost-key
                                     {:input-tokens       (some-> u .-inputTokens)
                                      :output-tokens      (some-> u .-outputTokens)
                                      :cache-read-tokens  cr
                                      :cache-write-tokens cw}))
        ;; Sticky, like the total it qualifies: the spend stays uncounted after
        ;; the session moves to a priced model.
        (cond-> (and (pos? (or (some-> u .-inputTokens) 0))
                     (not (pricing/priced? cost-key)))
          (assoc :cost-unknown? true)))))

(defn- inject-steer-messages!
  "Move steer queue messages into agent state between tool steps. Returns them:
   state only reaches the model on the next run, so this run has to be told."
  [agent]
  (let [queued @(:steer-queue agent)]
    (when (seq queued)
      (if-let [store (:store agent)]
        (doseq [msg queued]
          ((:dispatch! store) :message-added {:message msg}))
        (swap! (:state agent) update :messages into queued))
      (reset! (:steer-queue agent) [])
      queued)))

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

    (swap! state dissoc :step-extensions :stop-run :run-cost :run-steps)

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
            ;; pi extensions write `event.systemPromptOptions.sections[name]`
            ;; instead of returning; read back after the handlers ran.
            pi-sections   #js {}
            before-result (js-await
                           (emit-collect "before_agent_start"
                                         #js {:userMessage  (last messages)
                                              :systemPrompt (:system-prompt config)
                                              :systemPromptOptions #js {:sections pi-sections}}))

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
                  sections  (concat (get before-result "prompt-sections")
                                    (map (fn [k] {"content" (aget pi-sections k)})
                                         (js/Object.keys pi-sections)))
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
                (let [spec     (or (:base-model-spec @state)
                                   (aget (:config agent) "active-provider-name"))
                      ;; Name THIS provider's key and login, not Anthropic's
                      ;; for everyone.
                      provider (first (str/split (str (or spec "")) #"/"))
                      env      (get builtins/key-env provider)]
                  (throw (js/Error.
                          (str "No model configured"
                               (when (seq (str (or spec ""))) (str " for '" spec "'"))
                               ". "
                               (cond
                                 (:model-error @state) (:model-error @state)
                                 env               (str "Set " env " or run /login " provider ".")
                                 (seq provider)    (str "Run /login " provider ", or check its entry in ~/.nyma/settings.json.")
                                 :else             "Configure a provider with /login <provider>."))))))

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
              ;; A PDF goes to the provider as a file part only if the model
              ;; reads PDFs; otherwise its text. Done here, at the wire, so
              ;; the session keeps the PDF for a later capable model.
              messages (js-await (pdf/lower-for-model messages model-key))

              ;; Per-step usage sum, the pass's bill. `totalUsage` covers one
              ;; streamText call, so a retry drops every step the discarded
              ;; attempt paid for, and on abort it resolves null-usage, which
              ;; would record ZERO for the runaway run a budget abort killed.
              step-usage (atom {:input 0 :output 0 :cache-read 0 :cache-write 0})

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
              ;; st-config, for :onStepFinish: it cannot name itself in its own
              ;; initializer, for the same reason as above.
              request-config  (atom nil)
              ;; Messages steered in during this pass. The SDK rebuilds each
              ;; step's prompt from the request and its own responses, so they
              ;; go back in at every step (:prepareStep).
              steered         (atom [])
              ;; Did this pass's usage reach the store? Every step before a
              ;; failure was paid for, and the run-end dispatch never comes.
              usage-billed?   (atom false)
              ;; What this pass did, for the report call that cannot see it.
              pass-digest     (atom {})

              ;; What we are about to send, by our own reckoning. Recorded after
              ;; every replacement hook has had its say, so it describes the
              ;; request that actually goes out. The provider's reported input
              ;; tokens minus this is what a gateway injected on top.
              ;;
              ;; TOOL SCHEMAS count: they are part of every request and can run
              ;; to thousands of tokens, so leaving them out made the residual
              ;; look like injection when it was our own payload. Functions do
              ;; not survive JSON, which is what we want — this weighs the
              ;; descriptions and input schemas that actually go on the wire.
              _content-est    (swap! state assoc :last-content-estimate
                                     (+ (te/estimate-tokens effective-prompt)
                                        (te/estimate-messages-tokens messages)
                                        (te/estimate-tokens
                                         (try (js/JSON.stringify (clj->js tools))
                                              (catch :default _e "")))))

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
                             :maxOutputTokens (output-cap
                                               (or (:max-output-tokens config)
                                                   (when model-registry (:max-tokens ((:get model-registry) model-key))))
                                               (model-info/model-key
                                                (aget (:config agent) "active-provider-name")
                                                active-model)
                                               context-window
                                               (:last-content-estimate @state))
                             :maxRetries      (or (:max-retries config) 5)
                             :stopWhen        #js [(stepCountIs (if final-report? 1 (:max-steps config)))
                                                  ;; An extension saw a loop.
                                                  (fn [_] (boolean (:stop-run @state)))]
                             ;; Extended thinking is opt-in per request: with no
                             ;; `thinking` field a Claude model returns no
                             ;; reasoning at all, measured against Opus 5.
                             :providerOptions (or (thinking/level->provider-options
                                                   (when-let [tl (:thinking-level agent)] @tl)
                                                   active-model)
                                                  #js {})
                             :onError         (fn [e] (throw (.-error e)))
                             :prepareStep     (fn [o]
                                                (when (seq @steered)
                                                  ;; Not `messages`: the SDK carries what
                                                  ;; this returns into the next step's.
                                                  #js {:messages (.concat (.-initialMessages o) (.-responseMessages o)
                                                                          (clj->js @steered))}))
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
                                                (swap! pass-digest stall/add-step step)
                                                (when-let [u (.-usage step)]
                                                  ;; How full the context is: what
                                                  ;; THIS request sent, and the
                                                  ;; ground truth for the compaction
                                                  ;; trigger — the provider's count,
                                                  ;; not our estimate, which walks
                                                  ;; the whole session tree while
                                                  ;; what is sent is pruned at
                                                  ;; context_assembly and capped by
                                                  ;; priority_assembly. Not
                                                  ;; `totalUsage`: every step re-sends
                                                  ;; the conversation, so its sum read
                                                  ;; ctx:385% on a 7% full window.
                                                  ;; Skipped when a provider drops
                                                  ;; usage, so one bad step cannot
                                                  ;; zero the meter.
                                                  (when (pos? (or (.-inputTokens u) 0))
                                                    (swap! state assoc
                                                           :last-input-tokens (.-inputTokens u)
                                                           :last-cache-read (first (cache-split u))))
                                                  (swap! step-usage
                                                         (fn [t] (let [[cr cw] (cache-split u)]
                                                                   (-> t
                                                                       (update :input + (or (.-inputTokens u) 0))
                                                                       (update :output + (or (.-outputTokens u) 0))
                                                                       (update :cache-read + cr)
                                                                       (update :cache-write + cw))))))
                                                (when-let [tc (.-toolCalls step)]
                                                  (swap! tools-this-turn + (count tc)))
                                                ;; Before the emit, so a handler
                                                ;; reads this step's figures. Caught
                                                ;; because the SDK swallows a throw
                                                ;; here, which would skip the rest.
                                                (try
                                                  (swap! state add-live-step
                                                         (request-cost-key agent @request-config)
                                                         (.-usage step))
                                                  (catch :default e
                                                    (dbg/error "loop" (str "step cost: " (.-message e)))))
                                                (emit "turn_end" step)
                                                (swap! steered into (inject-steer-messages! agent)))}

              _ (reset! request-config st-config)
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
                ;; This pass ended on an overflow it could not recover from.
                overflowed? (atom false)
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
                  (let [;; Ends this attempt's request alone: a filter abort must
                        ;; not reach the run, which retries.
                        attempt-ctrl (js/AbortController.)
                        _ (aset st-config "abortSignal"
                                (if-let [c (:abort-controller agent)]
                                  (js/AbortSignal.any #js [(.-signal @c) (.-signal attempt-ctrl)])
                                  (.-signal attempt-ctrl)))
                        result
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
                                ;; Looped, not single-shot: a fallback chain
                                ;; with two entries could otherwise never get
                                ;; past the first, because a second throw here
                                ;; left no one to ask. Bounded at 4 — a chain
                                ;; longer than that is a configuration to fix,
                                ;; not a condition to ride out.
                                (loop [n 0 last-err e]
                                  (let [r (try
                                            {:ok (js-await (streamText st-config))}
                                            (catch :default e2 {:err e2}))]
                                    (cond
                                      (:ok r) (:ok r)
                                      (>= n 3) (throw (with-provider-detail! (:err r)))
                                      :else
                                      (let [again (js-await
                                                   (emit-collect
                                                    "provider_error"
                                                    #js {:error   (:err r)
                                                         :message (.-message (:err r))
                                                         :model   model-id
                                                         :config  st-config}))]
                                        (if (get again "retry")
                                          (recur (inc n) (:err r))
                                          (throw (with-provider-detail! (:err r))))))))
                                ;; Attach the provider's own explanation
                                ;; before this leaves the loop — it is the
                                ;; only place the raw error is still in hand.
                                (throw (with-provider-detail! e))))))
                        accumulated (atom "")
                        aborted     (atom false)
                        ;; The provider's own explanation, if the stream carried
                        ;; one. Kept so it can be attached to whatever the SDK
                        ;; throws afterwards.
                        stream-error (atom nil)]

                  ;; Stream events with stream_filter on text deltas
                  ;; Read once: each read of the getter tees the stream, and a
                  ;; branch nobody drains buffers the whole response.
                    (let [full (.-fullStream result)
                          iter (.call (aget full js/Symbol.asyncIterator) full)]
                      (loop []
                        (let [chunk (js-await (.next iter))]
                          (when (and (not (.-done chunk)) (not @aborted))
                            (let [chunk-val (.-value chunk)
                                  evt-type  (event-type chunk-val)]
                              ;; An error part is a real turn outcome, not a
                              ;; chunk to forward under a nil event name.
                              (when (= "error" (.-type chunk-val))
                                ;; The status text alone says nothing: "prompt
                                ;; is too long" is in the response body.
                                (let [e   (.-error chunk-val)
                                      _   (when (and e (not (string? e))) (with-provider-detail! e))
                                      msg (stream-error-message chunk-val)]
                                  (reset! stream-error (or msg "stream error"))
                                  (dbg/error "loop" (str "provider stream error: " (or msg chunk-val)))))
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
                                      ;; Only before a tool has run, as for a
                                      ;; provider retry: a retry restarts the
                                      ;; pass, and what ran would run again.
                                      (when (and (get filter-result "abort")
                                                 (zero? @tools-this-turn))
                                        (reset! aborted true)
                                        (reset! (:retry-state agent)
                                                {:reason (get filter-result "reason")
                                                 :inject (or (get filter-result "inject") [])})))
                                    (when-not @aborted (emit evt-type chunk-val))))
                                (emit evt-type chunk-val)))
                            (when-not @aborted (recur)))))
                      ;; `return` alone leaves the request open: it cancels one
                      ;; branch of the tee, and the provider keeps generating.
                      (when @aborted
                        (.abort attempt-ctrl "stream_filter")
                        (-> (.return iter) (.catch (fn [_] nil)))))

                  ;; A stream `error` part — not a throw — is how an unreachable
                  ;; or overloaded provider actually arrives. Verified against
                  ;; ai@7.0.127: `streamText` pointed at a refused port does NOT
                  ;; throw, it yields parts ["start","error"]. So the catch
                  ;; around `streamText` above never sees the outage case, and a
                  ;; fallback chain wired only there could not fire for
                  ;; ECONNREFUSED — the exact failure it was written for.
                  ;;
                  ;; Emitted here as a COLLECT carrying `:config`, so a handler
                  ;; can swap the model on it and ask for a retry. Rather than
                  ;; recurring directly, it sets `retry-state`, which hands the
                  ;; work to the bounded retry immediately below: same attempt
                  ;; cap, same st-config object the handler just mutated, and no
                  ;; second retry path to keep in step with this one.
                  ;;
                  ;; Emitted for EVERY stream error, retryable or not: gating
                  ;; the emit on the attempt count meant the last attempt's
                  ;; failure reached no listener (claude_hook_bridge's
                  ;; StopFailure among them). `:final` tells a handler its
                  ;; retry will not be honoured.
                    (when @stream-error
                      ;; Only before a tool has run: a retry restarts from this
                      ;; pass's opening messages, so what already ran would run
                      ;; again, commits and deploys included.
                      (let [can-retry? (and (nil? @(:retry-state agent)) (< attempt 2)
                                            (zero? @tools-this-turn))
                            _          (reset! overflowed?
                                               (compaction/context-overflow-error? @stream-error))
                            ;; Context overflow: compact and retry ONCE, ahead of
                            ;; any fallback. The threshold trigger cannot help
                            ;; when the declared window is wrong or missing, the
                            ;; case for relay and local providers. A second
                            ;; overflow is a real error, never a loop.
                            recovered? (when (and @overflowed? can-retry? (not @overflow-recovered?))
                                         (reset! overflow-recovered? true)
                                         (js-await (compaction/recover-from-overflow! agent)))]
                        (if recovered?
                          (do
                            (aset st-config "messages"
                                  (clj->js (js-await (pdf/lower-for-model (build-context agent) model-key))))
                            (reset! overflowed? false)
                            (reset! stream-error nil)
                            (reset! accumulated "")
                            (reset! (:retry-state agent)
                                    {:reason "context-overflow" :inject []}))
                          (let [r (js-await
                                   (emit-collect "provider_error"
                                                 #js {:message @stream-error
                                                      :source  "stream"
                                                      :model   model-id
                                                      :final   (not can-retry?)
                                                      :config  st-config}))]
                            (when (and can-retry? (get r "retry"))
                              (reset! overflowed? false)
                              (reset! stream-error nil)
                              (reset! accumulated "")
                              (reset! (:retry-state agent)
                                      {:reason "provider-fallback" :inject []}))))))

                  ;; Aborted → inject messages and retry (max 2 times)
                    (if-let [retry @(:retry-state agent)]
                      (do
                        (reset! (:retry-state agent) nil)
                        (doseq [msg (:inject retry)]
                          (if-let [store (:store agent)]
                            ((:dispatch! store) :message-added {:message msg})
                            (swap! state update :messages conj msg)))
                        ;; The request was built before these existed.
                        (aset st-config "messages"
                              (.concat (aget st-config "messages") (clj->js (:inject retry))))
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
                            ;; An overflow fails even with text: that is the
                            ;; step before it, mid-work, not an answer.
                            _              (when (and @stream-error
                                                      (or @overflowed? (str/blank? (str final-text))))
                                             (throw (js/Error. @stream-error)))
                            usage          (let [{:keys [input output cache-read cache-write]} @step-usage]
                                             #js {:inputTokens  input
                                                  :outputTokens output
                                                  :totalTokens  (+ input output)
                                                  :inputTokenDetails
                                                  #js {:cacheReadTokens  cache-read
                                                       :cacheWriteTokens cache-write}})
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
                                [cache-read cache-write] (cache-split usage)
                                cost-model-id (request-cost-key agent st-config)
                                ;; Each step at the rates of the model that ran
                                ;; it: a fallback can change model mid-pass.
                                cost          (or (:run-cost @state) 0)]
                            (reset! usage-billed? true)
                            ((:dispatch! store) :usage-updated
                                                {:input-tokens input-tokens :output-tokens output-tokens
                                                 :cache-read-tokens cache-read :cache-write-tokens cache-write
                                                 :steps @steps-this-run
                                                 :request-spec (fallback/request-spec st-config)
                                                 :cost cost})
                            (when (and input-tokens (pos? input-tokens))
                              ;; Learn what a GATEWAY injects. `unpriced-providers`
                              ;; is the existing marker for "this is a relay, its
                              ;; rates and ids are its own"; first-party providers
                              ;; are left alone, so nothing they report can change
                              ;; how we budget for them.
                              ;;
                              ;; SINGLE-STEP RUNS ONLY. `usage` here is the sum
                              ;; across every step of the run, while the estimate above describes the FIRST
                              ;; request. Comparing them on a five-step tool turn
                              ;; reported about five times the content as
                              ;; injection. A one-step turn is the only one where
                              ;; both halves describe the same request.
                              (when (and (= 1 @steps-this-run)
                                         (contains? @pricing/unpriced-providers
                                                    (str (aget (:config agent) "active-provider-name"))))
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

           (let [{:keys [input output cache-read cache-write]} @step-usage]
             (when (and (:store agent) (not @usage-billed?) (pos? (+ input output)))
               ((:dispatch! (:store agent)) :usage-updated
                                            {:input-tokens input :output-tokens output
                                             :cache-read-tokens cache-read :cache-write-tokens cache-write
                                             :steps @steps-this-run
                                             :request-spec (fallback/request-spec st-config)
                                             :cost (or (:run-cost @state) 0)})))

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
                (when (and (:stop-run @st) (not final-report?))
                  ;; Ended for repeating itself. It gets the report, not more
                  ;; steps: use up its extensions.
                  (dbg/warn "[loop] run stopped: it was repeating itself")
                  (swap! st assoc :step-extensions (:max-extensions config))
                  (follow-up agent {:role "user"
                                    :content (repeat-stop-nudge (stall/digest-text @pass-digest))
                                    :turn {:final-report true}}))

                (when (and step-capped? (not (:stop-run @st)))
                  (dbg/warn (str "[loop] step limit reached (" (:max-steps config) ") — the model was still working"))
                  (if (and (:step-cap-report config) (not final-report?))
                    ;; Queue the tool-less report turn; the follow-up drain
                    ;; below picks it up. Never re-armed from the report turn
                    ;; itself (max-steps 1 would otherwise loop).
                    (do (notify! (str "Step limit reached (" (:max-steps config)
                                      ") with work still in progress. Asking the model to report "
                                      "what it has; send a message to continue, or raise `max-steps`."))
                        (follow-up agent {:role "user" :content (step-cap-nudge (:max-steps config)
                                                                                (stall/digest-text @pass-digest))
                                          :turn {:final-report true}}))
                    (notify! (str "Step limit reached (" (:max-steps config)
                                  ") with work still in progress. Send a message to continue, "
                                  "or raise `max-steps` in settings."))))

                (when final-report?
                  (js-await (maybe-extend! agent notify!)))

                ;; Only once the session has asked for work: two one-word
                ;; conversational turns tripped this. Three in a row after a
                ;; tool has run is the collapse; escalate keeps its own count.
                (when (and (= 3 (:no-op-turns @st)) (:worked? @st))
                  (dbg/warn "[loop] three turns in a row ran no tools — the model may have stopped making progress (/refine)")
                  (notify! "Three turns ran no tools — the model may have stopped making progress. Run /refine to see the pattern."))))

            (js-await ((:emit-async events) "turn_finalize"
                                            #js {:error        (or (boolean @turn-error) blocked?)
                                                 :errorMessage (some-> @turn-error .-message)
                                                 :toolCalls    @tools-this-turn
                                                 :noOpTurns    (:no-op-turns @(:state agent))
                                                 ;; So a consumer can tell a stall from a
                                                 ;; response that was merely cut off.
                                                 :finishReason @turn-finish
                                                 ;; The step limit stopped this turn: its text is
                                                 ;; mid-work, not a finished answer.
                                                 :stepCapped   (and (not @turn-error) (not final-report?)
                                                                    (>= @steps-this-run (:max-steps config)))
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
            ;; The exception: left as it is, the next prompt overflows too.
            (when (and @overflowed? (not @overflow-recovered?))
              (js-await (compaction/recover-from-overflow! agent)))

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
