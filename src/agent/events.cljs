(ns agent.events
  "Typed event bus (sync + async)."
  (:require [agent.debug :as d]))

(def pi-compat-event-types
  "Names declared for pi API parity that NYMA ITSELF NEVER EMITS.

   Kept so a pi extension can subscribe without erroring, and separated from
   the core list so `test/event_emitter_lint.test.cljs` can insist that every
   core name has a producer. Anything moved in here is inert by design; adding
   a producer means moving the name back out."
  ["session_before_tree" "session_tree" "session_directory"
   "branch_summarized" "context"])

(def event-registry
  "Every event nyma emits, in one table: name → {:kind :doc}. Kinds:
     :emit     fire-and-forget, sync
     :async    awaited (`emit-async`) — a post-turn boundary handlers may block
     :collect  emit-collect: the handlers' RETURN is the point
   `:wire? false` marks collect hooks a JSON line on stdout cannot answer, so
   rpc mode does not advertise them (`wire-event-types`).

   The lint requires a producer for every name here; the README's event
   table and `docs/event-map.md` are generated from it (`bun run
   gen:events-doc`), so a name with no `:doc` shows as \"—\" in both — the
   nudge to write one."
  [["session_start"         {:kind :emit :doc "Session attached (startup, /resume, /fork)"}]
   ["session_end"           {:kind :emit :doc "Session closing"}]
   ["session_before_switch" {:kind :emit :doc "About to switch to another session"}]
   ["session_switch"        {:kind :emit :doc "Switched session"}]
   ["session_before_fork"   {:kind :emit :doc "About to fork the session"}]
   ["session_shutdown"      {:kind :emit :doc "Process shutting down (SIGINT, /exit)"}]
   ;; A request to leave, not a report that it happened: `/exit` emits it and
   ;; cli.cljs runs the async shutdown (the same one SIGINT takes). `/exit`
   ;; used to call process.exit itself, which killed MCP and LSP mid-socket.
   ["exit"                  {:kind :emit :doc "A request to leave: cli.cljs runs the same async shutdown SIGINT takes"}]
   ;; Everything loaded, session attached, model resolved — the one point
   ;; where ui.available is guaranteed true. Fires at startup AND after
   ;; /reload, so anything set up on it comes back after a reload.
   ["session_ready"         {:kind :async :doc "Everything loaded, session attached, model resolved; the one point where ui.available is guaranteed. Fires at startup AND after /reload"}]
   ["session_end_summary"   {:kind :emit :doc "Stats snapshot just before session_end (desktop_notify reads it)"}]
   ["agent_start"           {:kind :async :doc "A run begins; awaited, so an extension can finish registering tools before the first turn"}]
   ["agent_end"             {:kind :emit :doc "A turn's provider call finished: `{text, usage, finishReason}`"}]
   ["turn_start"            {:kind :emit :doc "A provider call is about to start"}]
   ["turn_end"              {:kind :emit :doc "One model step finished (AI SDK StepResult)"}]
   ;; Awaited post-turn boundary (emit-async). Fires after agent_end on the
   ;; normal + abort exit paths, BEFORE the follow-queue drain, so a handler
   ;; (e.g. plan-mode's approval gate) can enqueue a follow-up that the drain
   ;; then picks up. Distinct from agent_end (sync, fire-and-forget) so slow
   ;; external handlers like Stop hooks never block the loop.
   ["turn_finalize"         {:kind :async :doc "Awaited post-turn boundary, before the follow-up drain: `{error, toolCalls, noOpTurns, finishReason}`"}]
   ["message_start"         {:kind :emit :doc "A streamed text block begins"}]
   ["message_update"        {:kind :emit :doc "A streamed text delta"}]
   ["message_end"           {:kind :emit :doc "A streamed text block ends"}]
   ["tool_call"             {:kind :emit :doc "The model requested a tool call"}]
   ["tool_result"           {:kind :emit :doc "A tool call returned"}]
   ["tool_execution_start"  {:kind :emit :doc "Middleware: a tool began executing"}]
   ["tool_execution_update" {:kind :emit :doc "Middleware: progress from a long-running tool"}]
   ["tool_execution_end"    {:kind :emit :doc "Middleware: a tool finished"}]
   ["before_tool_call"      {:kind :collect :doc "Before a tool runs — set `ctx.cancelled = true` to block, or return `{skip: true, result}` to short-circuit"}]
   ["before_provider_request" {:kind :collect :doc "Receives the mutable streamText config; mutate in place or return `{block: true, reason}` to skip the LLM call"}]
   ;; Provider pipeline hooks (all emit-collect from loop.cljs). Emitted for
   ;; a long time without being declared here, so they were invisible to the
   ;; emitter lint, the event map, and rpc mode's advertised channel list.
   ["model_resolve"         {:kind :collect :wire? false :doc "Pick which model to use for this turn; return `{model}` to override the agent default"}]
   ["before_message_send"   {:kind :collect :wire? false :doc "Final transform after `context_assembly` and before the LLM call; same return shape"}]
   ["provider_error"        {:kind :collect :wire? false :doc "Fires on LLM call failure; return `{retry: true}` to retry once"}]
   ["stream_filter"         {:kind :collect :wire? false :doc "Per text delta during streaming; receives `{delta, chunk, type}` and may return `{abort: true, reason, inject: [...]}` to abort the stream and re-run with the injected messages (max 2 retries)"}]
   ["message_before_store"  {:kind :collect :wire? false :doc "Last chance to rewrite assistant content before it lands in the store"}]
   ;; Another extension asking model_roles (the owner of :active-role) to
   ;; switch: spec_driven's phase binding, agent_shell's plan handoff.
   ["role_change"           {:kind :emit :doc "Ask model_roles (owner of :active-role) to switch role"}]
   ["before_agent_start"    {:kind :collect :doc "First step of each run; return `{systemPromptAddition, system-prompt-additions, prompt-sections, volatile-additions, inject-messages}` to shape the run. `volatile-additions` is per-turn text: it lands after a `---` boundary at the END of the system prompt so the stable prefix stays byte-identical for the provider's prompt cache"}]
   ["input"                 {:kind :collect :doc "User input before it becomes a turn"}]
   ["compact"               {:kind :emit :doc "Compaction happened"}]
   ["before_compact"        {:kind :async :doc "Compaction about to run; a handler may set `ctx.summary`"}]
   ["before_branch_switch"  {:kind :emit :doc "Session tree branch about to change"}]
   ["resources_discover"    {:kind :emit :doc "Resources (skills, prompts, themes) rediscovered"}]
   ["model_select"          {:kind :emit :doc "The active model changed"}]
   ["user_bash"             {:kind :emit :doc "A `!command` typed in the editor ran"}]
   ["reload"                {:kind :emit :doc "/reload finished"}]
   ["context_assembly"      {:kind :collect :doc "After messages are built; return `{messages, system}` to replace either"}]
   ["after_provider_request" {:kind :emit :doc "Fired after a successful LLM call with `{usage, model, cachedTokens, turnCount}`"}]
   ;; ACP agent shell events
   ["acp_connect"           {:kind :emit :doc "ACP agent connected"}]
   ["acp_disconnect"        {:kind :emit :doc "ACP agent disconnected"}]
   ["acp_message"           {:kind :emit :doc "ACP agent message"}]
   ["acp_tool_start"        {:kind :emit :doc "ACP agent tool call started"}]
   ["acp_tool_update"       {:kind :emit :doc "ACP agent tool call updated"}]
   ["acp_usage"             {:kind :emit :doc "ACP agent usage report"}]
   ["acp_mode_change"       {:kind :emit :doc "ACP agent mode changed"}]
   ["acp_thought"           {:kind :emit :doc "ACP agent thought block"}]
   ["acp_plan"              {:kind :emit :doc "ACP agent plan update"}]
   ["acp_commands_update"   {:kind :emit :doc "ACP agent commands changed"}]
   ;; Native provider reasoning events (AI SDK interleaved-thinking)
   ["reasoning_start"       {:kind :emit :doc "Provider reasoning block begins"}]
   ["reasoning_delta"       {:kind :emit :doc "Provider reasoning delta"}]
   ["reasoning_end"         {:kind :emit :doc "Provider reasoning block ends"}]
   ;; UI events. Seven more sat here — overlay_open/dismiss,
   ;; autocomplete_open/close/select, keybinding_activated, acp_permission —
   ;; with no emitter, no listener and no mention in pi, so extensions could
   ;; subscribe to them forever and rpc mode advertised them as channels that
   ;; could never carry traffic. The permission flow's real future name is
   ;; acp_permission_request (roadmap §7c).
   ["editor_change"         {:kind :emit :doc "User typing in editor — `{text: string}` payload"}]
   ["notification"          {:kind :emit :doc "A notification was shown"}]
   ["session_clear"         {:kind :emit :doc "`/clear` invoked — extensions may reset their agent sessions"}]
   ["user_eval"             {:kind :emit :doc "A `$expr` typed in the editor ran"}]
   ["tool_complete"         {:kind :emit :doc "A tool call completed with its result (stats, checkpoints)"}]
   ["permission_request"    {:kind :collect :doc "Per-tool approval; return `{decision: \"allow\"|\"deny\"|\"ask\"}`"}]
   ["tool_access_check"     {:kind :collect :doc "Filter the tool list for the next call; return `{allowed: [name, ...]}` (merged by intersection)"}]
   ["input_submit"          {:kind :emit :doc "Editor submit"}]
   ;; An extension asking for a turn to START. `sendUserMessage` only ever
   ;; QUEUES (steer or follow-up) and neither begins one, so anything that
   ;; wanted to kick off work — /spec run arming the loop, an import's
   ;; decomposition seed — had to tell the user to "send any message". The
   ;; interactive mode owns the submit path (lock, streaming state, UI
   ;; wiring), so it subscribes and dispatches; nothing else can.
   ["turn_request"          {:kind :emit :doc "An extension asking for a turn to start: `{text, echo}`; interactive mode dispatches it"}]])

(def core-event-types
  "Event names nyma emits. The lint requires a producer for every one."
  (mapv first event-registry))

(def collect-hook-event-types
  "emit-collect hooks whose RETURN is the point and that a JSON line on stdout
   cannot answer (`:wire? false`), so rpc mode does not advertise them."
  (vec (keep (fn [[n m]] (when (false? (:wire? m)) n)) event-registry)))

(def all-event-types
  "Everything an extension may subscribe to: the events nyma produces, plus the
   pi-compat names it does not."
  (into core-event-types pi-compat-event-types))

(def wire-event-types
  "What rpc mode forwards: everything except the collect hooks."
  (vec (remove (set collect-hook-event-types) all-event-types)))

;; ── Boolean keys are merged with OR (any true wins) ──────────────
(def ^:private boolean-keys
  #{"block" "cancel" "handle" "blocked" "cancelled" "skip"})

;; ── Collection keys are concatenated ─────────────────────────────
(def ^:private collection-keys
  #{"inject-messages" "system-prompt-additions" "volatile-additions" "paths"
    "skillPaths" "promptPaths" "themePaths" "prompt-sections"})

;; ── Precedence keys: rules pre-empt policy, order-independent (NOT last-writer) ──
;; permission_request handlers each return {:decision ...}; several may fire for
;; one tool. The merge resolves them by precedence, not handler order:
;;   deny                 — an explicit deny hard-blocks (most authoritative).
;;   allow_always_project — an explicit allow that also persists.
;;   allow                — an explicit allow: a handler that VETTED this exact
;;                          call (e.g. bash_suite cleared a safe command) — it
;;                          pre-empts a policy-level ask.
;;   ask                  — the weakest signal: "I have no rule for this, prompt
;;                          the user" (the mode policy default). Yields to any
;;                          explicit allow/deny above.
;; A deny from any handler always wins; an explicit allow beats a bare ask.
(def decision-order
  "Permission-decision precedence, most-authoritative first. Shared by the
   cross-handler merge AND model_roles' two-axis (mode+role) combine, so both
   layers use ONE ordering: deny hard-blocks; an explicit allow beats a bare
   ask; ask is the weakest 'no rule, prompt' signal."
  ["deny" "allow_always_project" "allow" "ask"])

(def ^:private precedence-keys
  {"decision" decision-order})

(defn precedence-pick
  "Return whichever of a/b ranks earlier (higher precedence) in `order`.
   An unknown value (index -1) always loses to a known one; nil yields the other."
  [order a b]
  (let [ia (.indexOf order (str a))
        ib (.indexOf order (str b))]
    (cond
      (neg? ia) b
      (neg? ib) a
      (<= ia ib) a
      :else b)))

(defn combine-decision
  "Combine two permission decisions by `decision-order` (most-restrictive/
   most-authoritative wins). nil-safe: (combine nil x) → x. Use to resolve a
   decision across independent axes (e.g. mode policy + role policy)."
  [a b]
  (precedence-pick decision-order a b))

;; ── Intersection keys: allowlists combine by set-INTERSECTION (NOT last-writer) ──
;; tool_access_check handlers each return {:allowed [...]}; several may fire
;; (model_roles role/mode restriction + small_model per-profile/editStrategy
;; restriction). Each is an allowlist, so the effective set is their
;; INTERSECTION — most-restrictive-wins, order-independent. Last-writer would let
;; one handler silently widen past another's restriction.
(def ^:private intersection-keys #{"allowed"})

(defn- intersect-allow
  "Intersect two allowlists (vectors of tool-name strings), preserving a's order."
  [a b]
  (let [bs (set (map str b))]
    (vec (filter #(contains? bs (str %)) a))))

(defn- merge-results
  "Merge a sequence of handler return maps with semantic merging:
   - boolean keys → OR (any true wins)
   - collection keys → concatenated
   - scalar keys → last-writer-wins"
  [results]
  (reduce
   (fn [acc result]
     ;; Non-map returns are ignored, not merged: a handler that ends in
     ;; `(reset! …)`/`(swap! …)` returns a boolean, and reduce-kv over a
     ;; primitive throws ("true is not iterable") on squint >= 0.14.203,
     ;; where `iterable` became strict. Previously this read
     ;; `(if (map? result) result result)` — a no-op that let primitives through.
     (if-not (map? result)
       acc
       (let [m result]
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

                (contains? precedence-keys ks)
                (assoc a k (if (contains? a k)
                             (precedence-pick (get precedence-keys ks) (get a k) v)
                             v))

                (contains? intersection-keys ks)
                (assoc a k (if (contains? a k)
                             (intersect-allow (get a k) v)
                             v))

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
          (d/error
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
          (d/error
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
                     (d/error
                      (str "[nyma] Extension handler error on '" event "':") e))))))

     :emit-async (fn [event data]
                   (run-handlers-async handlers sorted-cache event data))

     :emit-collect (fn [event data]
                     (run-handlers-collect handlers sorted-cache event data))

     :off  (fn [event handler]
             (swap! handlers update event
                    (fn [hs] (vec (remove #(= (:handler %) handler) hs))))
             ;; Invalidate cache for this event
             (swap! sorted-cache dissoc event))

     :handler-count (fn [event] (count (get @handlers event [])))
     ;; Total across every event — the number a leak test compares before
     ;; and after an extension's lifetime.
     :handler-total (fn [] (reduce + 0 (map count (vals @handlers))))}))

