(ns agent.middleware
  (:require [agent.tool-metadata :as tool-metadata]
            [agent.interceptors :as ic]
            [agent.extension-context :refer [create-extension-context]]
            [agent.tool-result-policy :as policy]
            [agent.utils.ansi :refer [truncate-text]]
            [agent.utils.ui :refer [ui-prompt-ready?]]
            [agent.debug :as d]))

(defn normalize-tool-result
  "Normalize tool results to a STRING (transcript/UI value). Supports plain
   strings and pi-compatible {content: [{type:'text',text}], details} format.
   Multimodal tools return {content:[file/image parts], summary}: `:summary`
   wins so the string is short and non-text parts never stringify to
   `[object Object]` or a giant base64 blob (the image reaches the model via
   the tool's toModelOutput, not this string).

   A tool that returns an object matching NONE of those shapes used to fall to
   `(str result)`, which is `\"[object Object]\"` — and that string is what
   entered the context window. questionnaire returned `{answers, text}` and so
   the model could not read an answer the user had just spent 74 seconds
   giving; nothing failed, nothing warned. Unknown objects are now serialised
   and the tool is named in a warning, because the alternative is silence.

   `tool-name` is optional so the four existing 1-arity callers keep working."
  ([result] (normalize-tool-result result nil))
  ([result tool-name]
   (cond
     (string? result) result
     (and (some? result) (not (string? result)) (.-summary result))
     (str (.-summary result))
     (and (some? result) (not (string? result)) (.-content result))
     (let [parts (.-content result)]
       (.join
        (.map parts
              (fn [item]
                (if (= (.-type item) "text") (.-text item) (str (.-type item) " content"))))
        "\n"))
     ;; An Error stringifies to "Error: msg"; JSON.stringify gives "{}".
     (instance? js/Error result) (str result)

     ;; squint's `object?` is `constructor === Object` — PLAIN objects only.
     ;; A Map or a Set (both of which JSON.stringify to "{}") and any class
     ;; instance fall through to `(str result)`, which is what they want.
     (and (some? result) (object? result))
     (let [json (try (js/JSON.stringify result)
                     ;; Cyclic or otherwise unserialisable — nothing better to
                     ;; offer than the old behaviour, but say so.
                     (catch :default _ nil))]
       (d/warn "tool-result"
               (str (or tool-name "a tool") " returned an object with no"
                    " `content`, `summary` or string result — serialising it."
                    " Return a string or {content:[{type:\"text\",text}]}."))
       (or json (str result)))
     :else (str result))))

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
                       ;; :abort-controller is the agent's atom (fresh
                       ;; controller per run) — deref at execution time.
                       (aset ext-ctx "abortSignal"
                             (when-let [ctrl (:abort-controller ctx)]
                               (.-signal @ctrl)))
                       (aset ext-ctx "onUpdate"
                             (fn [data]
                               (when events
                                 ((:emit events) "tool_execution_update"
                                                 #js {:toolName (:tool-name ctx)
                                                      :execId   (:exec-id ctx)
                                                      :data     data}))))
                       ;; G18 — expose active model ID string so tools can adapt behaviour.
                       ;; :model in config is either a resolved provider model object
                       ;; (with a .-modelId property) or a plain string (legacy / tests).
                       (aset ext-ctx "modelId"
                             (let [m (:model (:config (:agent ctx)))]
                               (cond
                                 (nil? m)    "unknown"
                                 (string? m) m
                                 :else       (or (.-modelId m) "unknown")))))
          raw-result (js-await ((.-execute (:tool ctx)) (:args ctx) ext-ctx))
          result     (normalize-tool-result raw-result (:tool-name ctx))
          ;; Preserve structured metadata for downstream consumers
          details    (when (and (some? raw-result) (not (string? raw-result))
                                (.-details raw-result))
                       (.-details raw-result))
          is-error   (boolean (and (some? raw-result) (not (string? raw-result))
                                   (.-isError raw-result)))
          content-parts (when (and (some? raw-result) (not (string? raw-result))
                                   (.-content raw-result))
                          (.-content raw-result))]
      (cond-> (assoc ctx :result result
                     ;; Preserve the raw structured result so a multimodal tool's
                     ;; toModelOutput receives real bytes (wrap-tools-with-middleware).
                     :raw-result raw-result)
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

(defn- safe-call2
  "Two-arg `safe-call`. Same swallow-and-return-nil contract."
  [f a b]
  (when f
    (try (f a b) (catch :default _ nil))))

(defn- extract-display-fields
  "Read .display from tool object, invoke formatters on data, return custom fields map.

   formatArgs and statusText are called as (tool-name, args). Every one of the
   13 formatters in the repo is written `(fn [_name args] …)` — this called them
   with the args alone, so `args` was undefined inside, the first property
   access threw, `safe-call` swallowed it, and the field was dropped. All 13
   were dead; the only 1-arity formatter that existed was in the test that
   claimed to cover this. formatResult keeps its single string argument, which
   is what its users already expect."
  [tool tool-name data-for-formatters]
  (when-let [display (and tool (.-display tool))]
    (let [custom-args   (safe-call2 (.-formatArgs display) tool-name (clj->js data-for-formatters))
          status-text   (safe-call2 (.-statusText display) tool-name (clj->js data-for-formatters))
          icon          (.-icon display)
          verbosity     (.-verbosity display)]
      (cond-> {}
        custom-args (assoc :customOneLineArgs custom-args)
        status-text (assoc :customStatusText status-text)
        icon        (assoc :customIcon icon)
        verbosity   (assoc :customVerbosity verbosity)))))

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
                                                        :cancelled  (boolean (:cancelled ctx))
                                                        :isError    (boolean (:result-is-error ctx))
                                                        :details    (:result-details ctx)})))
        ctx        (if-let [mod-result (get complete-result "result")]
                     (assoc ctx :result (str mod-result))
                     ctx)
        ;; Apply per-tool result policy: truncates to :max-string-length and builds
        ;; structured envelope {:ok :summary :data :error :error-kind}.
        ;; ctx :result is replaced with the policy-truncated model-visible string.
        ;; The full envelope is stored as :result-envelope for UI consumers.
        ;; Measured either side of the policy, because these are the only two
        ;; points where both numbers exist: `raw-bytes` is what the tool
        ;; produced, `model-bytes` is what actually enters the context window.
        ;; Tool output dominates a transcript — 92% of message bytes in sampled
        ;; sessions — so without this there is no way to tell whether a change
        ;; aimed at it helped, or how much the per-tool caps already save.
        raw-bytes  (count (str (:result ctx)))
        envelope   (policy/apply-policy (:result ctx) (:tool-name ctx))
        ctx        (-> ctx
                       (assoc :result (policy/model-string envelope))
                       (assoc :result-envelope envelope))
        model-bytes (count (str (:result ctx)))
        result-str (truncate-text (str (:result ctx)) 500)
        display    (and (:tool ctx) (.-display (:tool ctx)))
        custom-result (when display
                        (safe-call (.-formatResult display) result-str))]
    (when events
      ;; camelCase keys — the ONE event-payload convention for tool_* events
      ;; (matches tool_complete/tool_result/before_tool_call). Kebab keys here
      ;; left 5 extensions reading `.-toolName` silently dead. Squint map
      ;; literals are already plain JS objects — no clj->js needed.
      ((:emit events) "tool_execution_end"
                      (cond-> {:toolName       (:tool-name ctx)
                               :execId         (:exec-id ctx)
                               :args           (:args ctx)
                               :duration       duration
                               :result         result-str
                               ;; `result` above is for DISPLAY: ANSI-wrapped to
                               ;; terminal width, then capped at 500 lines. Its
                               ;; length tracks neither figure — wrapping can
                               ;; make it longer than the raw output, and the
                               ;; line cap can make it far shorter. Hence
                               ;; explicit counts.
                               :rawBytes       raw-bytes
                               :modelBytes     model-bytes
                               :resultEnvelope (:result-envelope ctx)
                               :details        (:result-details ctx)
                               :isError        (:result-is-error ctx)
                               :contentParts   (:result-content-parts ctx)}
                        custom-result (assoc :customOneLineResult custom-result)
                        (and display (.-icon display)) (assoc :customIcon (.-icon display))
                        (and display (.-verbosity display)) (assoc :customVerbosity (.-verbosity display)))))
    (when store
      ((:dispatch! store) :tool-execution-ended
                          {:exec-id (:exec-id ctx) :duration duration}))
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
                  display-fields (extract-display-fields (:tool ctx) (:tool-name ctx) (:args ctx))]
              (when events
                ;; merge of squint maps is already a plain JS object with
                ;; camelCase keys — no clj->js. NOTE: :args ALIASES the live
                ;; args object the tool will execute with — handlers must
                ;; treat event payloads as read-only.
                ((:emit events) "tool_execution_start"
                                (merge {:toolName (:tool-name ctx) :execId exec-id :args (:args ctx)
                                        :label (when-let [t (:tool ctx)] (.-label t))}
                                       display-fields)))
              (when store
                ((:dispatch! store) :tool-execution-started
                                    {:tool-name (:tool-name ctx) :exec-id exec-id}))
              (assoc ctx :exec-id exec-id :start-time start-time)))
   :leave (fn [ctx]
            (tool-tracking-leave events store ctx))})

(defn- categorize-tool
  "Categorize a tool for permission checking. Built-ins by name; anything
   else by the safety metadata it registered (`registerTool` reads a
   `:safety` field off the tool def). No metadata → \"other\", which no
   role policy maps, so the gate allows it — an extension tool that edits
   files and says nothing about itself is invisible to /plan and ask-mode."
  [tool-name]
  (cond
    (#{"bash"} tool-name)                     "exec"
    (tool-metadata/file-editing? tool-name)   "write"
    (#{"read" "glob" "grep" "ls"} tool-name)  "read"
    (#{"web_fetch" "web_search"} tool-name)   "network"
    :else
    (let [s (tool-metadata/tool-safety tool-name)]
      (cond
        (:destructive? s)                          "write"
        (:network? s)                              "network"
        (contains? (or (:capabilities s) #{}) :shell) "exec"
        :else                                      "other"))))

(defn- agent-ui
  "The live interactive UI handle (or nil). Reached from the agent's extension
   API so the permission gate can prompt the user directly."
  [agent]
  (when agent
    (when-let [api (.-extension-api agent)] (.-ui api))))

(defn ^:async resolve-ask
  "An 'ask' decision: prompt the user ONCE (Allow / Allow always / Deny).
   With no interactive UI (e.g. headless -p) an 'ask' resolves to DENY — never
   a silent allow. Returns the updated ctx. Exposed for tests."
  [settings ctx ui tool-name reason]
  (if-not (ui-prompt-ready? ui)
    (assoc ctx :cancelled true
           :cancel-reason (or reason (str "Permission required for '" tool-name
                                          "' but no interactive approval is available")))
    (let [choice (js-await
                  (.select ui (str "Allow '" tool-name "'?")
                           (clj->js ["Allow once" "Allow always (this project)" "Deny"])))]
      (cond
        (and choice (.startsWith choice "Allow always"))
        (do (when settings ((:append-allow-tool! settings) tool-name)) ctx)

        (and choice (.startsWith choice "Allow")) ctx

        :else
        (assoc ctx :cancelled true
               :cancel-reason (or reason "Permission denied"))))))

(defn ^:async permission-check-enter
  "Check permission_request before tool execution.
   Handlers return {decision: 'allow'|'ask'|'deny'|'allow_always_project',
   reason?: string}. When several handlers fire, the merged decision is the
   most restrictive (deny > ask > allow), independent of order.

   - deny                  → cancel.
   - ask                   → prompt the user once; no UI → deny (never silent).
   - allow_always_project  → run, and persist to .nyma/settings.json so future
                             calls skip the prompt.
   - anything else         → allow.

   If settings is provided and the tool is in the project/global allow-list,
   the permission_request event is skipped entirely (no subscribers fire)."
  [events settings ctx]
  (let [tool-name (:tool-name ctx)
        args      (:args ctx)]
    ;; Already blocked or short-circuited by a before_tool_call hook: nothing
    ;; to ask, and a deny here would clobber the hook's reason/result.
    ;; Fast-path: tool is in the persistent allow-list — skip the prompt.
    (if (or (:cancelled ctx) (:skip? ctx)
            (and settings ((:tool-allowed? settings) tool-name)))
      ctx
      (let [result   (js-await
                      ((:emit-collect events) "permission_request"
                                              #js {:tool     tool-name
                                                   :args     (clj->js args)
                                                   :category (categorize-tool tool-name)
                                                   :path     (or (get args :path) (get args "path"))}))
            decision (get result "decision")
            reason   (get result "reason")]
        (cond
          (= decision "deny")
          (assoc ctx :cancelled true :cancel-reason (or reason "Permission denied"))

          (= decision "ask")
          (js-await (resolve-ask settings ctx (agent-ui (:agent ctx)) tool-name reason))

          (= decision "allow_always_project")
          (do
            (when settings
              ((:append-allow-tool! settings) tool-name))
            ctx)

          :else ctx)))))

(defn permission-check-interceptor
  "Interceptor that emits permission_request for custom approval workflows.
   Accepts an optional settings manager for allow-list persistence."
  [events & [settings]]
  {:name  :permission-check
   :enter (fn [ctx] (permission-check-enter events settings ctx))})

(def prepare-arguments-interceptor
  "Interceptor that calls tool.prepareArguments if defined, transforming args before execution."
  {:name  :prepare-arguments
   :enter (fn [ctx]
            (if-let [prep (and (:tool ctx) (.-prepareArguments (:tool ctx)))]
              (let [prepared (prep (clj->js (:args ctx)))]
                (assoc ctx :args prepared))
              ctx))})

(defn approval-check-interceptor
  "Interceptor that runs registered approval-check functions before tool execution.
   Designed for gateway channels that need a human operator to approve tool calls
   (e.g. posting a Slack confirmation prompt and waiting for a reaction).

   Each check-fn receives a plain JS object {toolName, args} and must return a
   Promise (or plain value) resolving to one of:
     {allow: true}                   — proceed
     {deny: true, reason?: string}   — block with optional message

   Checks run sequentially; the first deny short-circuits the rest.
   When approval-checks is empty (the default) this interceptor is a no-op,
   so existing :tui behaviour is completely unchanged."
  [approval-checks]
  {:name  :approval-check
   :enter (fn [ctx]
            (if (or (:cancelled ctx) (empty? @approval-checks))
              ctx
              (let [check-data #js {:toolName (:tool-name ctx)
                                    :args     (clj->js (:args ctx))}]
                ;; Sequential reduction: Promise<ctx> → run check → Promise<ctx>
                (reduce
                 (fn [p check-fn]
                   (.then p
                          (fn [c]
                            (if (:cancelled c)
                              c
                              (.. (js/Promise.resolve (check-fn check-data))
                                  (then (fn [r]
                                          (if (get r "deny")
                                            (assoc c :cancelled true
                                                   :cancel-reason
                                                   (or (get r "reason")
                                                       "Approval denied"))
                                            c))))))))
                 (js/Promise.resolve ctx)
                 @approval-checks))))})

(defn create-pipeline
  "Create a middleware pipeline for tool execution.
   Interceptors run in order: user middleware → before-hook-compat → execute-tool.
   Returns a map with :add, :remove, :execute, :chain, :add-approval-check!,
   :remove-approval-check!, and :clear-approval-checks!.
   Optionally accepts a store for tool execution tracking, an agent-ref
   atom for injecting extension context, and a settings manager for
   allow-list persistence."
  [events & [store agent-ref settings]]
  (let [chain          (atom (cond-> [(tool-tracking-interceptor events store)]
                               agent-ref (conj (tool-persistence-interceptor agent-ref))))
        ;; Approval pipeline — empty by default (approve everything).
        ;; Gateway adapters register check-fns here; :tui never touches this.
        approval-checks (atom [])]
    {:add
     (fn [interceptor & [opts]]
       (let [position (if opts (:position opts) nil)]
         (case position
           :first (swap! chain #(into [interceptor] %))
           (swap! chain conj interceptor))))

     :remove
     (fn [interceptor-name]
       ;; The two core interceptors in this chain are not an extension's to
       ;; drop: tracking feeds the UI and persistence writes the session.
       (if (contains? #{:tool-tracking :tool-persistence} interceptor-name)
         (d/warn (str "[nyma] removeMiddleware refused for core interceptor " (str interceptor-name)))
         (swap! chain (fn [c] (vec (remove #(= (:name %) interceptor-name) c))))))

     :execute
     (fn [tool-name tool args]
       (let [agent    (when agent-ref @agent-ref)
             ext-ctx  (when agent (create-extension-context agent))
             ctx      {:tool-name         tool-name
                       :tool              tool
                       :args              args
                       :cancelled         false
                       :result            nil
                       :extension-context ext-ctx
                       :events            events
                       :agent             agent
                       :abort-controller  (when agent (:abort-controller agent))}
             ;; before-hook-compat runs BEFORE the gates: a before_tool_call
             ;; handler may rewrite :args, and permission must be decided on
             ;; what will actually execute, not on what was proposed.
             full     (vec (concat @chain
                                   [prepare-arguments-interceptor
                                    (before-hook-compat events)
                                    (permission-check-interceptor events settings)
                                    (approval-check-interceptor approval-checks)
                                    tool-execution-interceptor]))]
         (ic/execute full ctx)))

     :chain (fn [] @chain)

     ;; Approval pipeline management — used by gateway channel adapters.
     ;; Each check-fn: (fn [js-obj]) → Promise<{allow}|{deny, reason?}>
     :add-approval-check!
     (fn [check-fn]
       (swap! approval-checks conj check-fn))

     :remove-approval-check!
     (fn [check-fn]
       (swap! approval-checks (fn [cs] (vec (remove #{check-fn} cs)))))

     :clear-approval-checks!
     (fn []
       (reset! approval-checks []))}))

(defn wrap-tools-with-middleware
  "Wrap each tool's execute fn to run through the middleware pipeline.
   Returns a new tools map with wrapped execute functions.

   IMPORTANT: do NOT clj->js the tools map — squint's clj->js strips
   Symbol-keyed properties recursively, which kills the AI-SDK
   `Symbol.for(\"vercel.ai.schema\")` marker on jsonSchema-wrapped
   inputSchemas and makes streamText throw `schema is not a function`."
  [tools pipeline events]
  (reduce-kv
   (fn [acc tool-name t]
     (assoc acc tool-name
            (js/Object.assign #js {} t
                              #js {:execute
                                   (fn [args]
                                     (let [result-promise ((:execute pipeline) tool-name t args)]
                                       (.then result-promise
                                              (fn [ctx]
                                                (cond
                                                  ;; A tool (or interceptor) throw is captured into
                                                  ;; :error by the interceptor chain and never
                                                  ;; re-raised — without this branch the model would
                                                  ;; see an empty result instead of the failure.
                                                  (and (:error ctx) (nil? (:result ctx)))
                                                  (str "Error: " (or (some-> (:error ctx) .-message)
                                                                     (str (:error ctx))))

                                                  ;; Return the STRUCTURED raw result only when the tool
                                                  ;; both declares toModelOutput AND actually produced
                                                  ;; content parts (an image). Text tools — including
                                                  ;; MCP text tools that carry a generic toModelOutput,
                                                  ;; and pi-compat {content:[text]} tools without one —
                                                  ;; keep the policy-truncated :result string.
                                                  (and (.-toModelOutput t) (:result-content-parts ctx))
                                                  (:raw-result ctx)

                                                  :else
                                                  (:result ctx))))))})))
   {}
   tools))

(defn tool-persistence-interceptor
  "Interceptor that persists tool call results to the session JSONL.
   This enables branch summarization to know which files were read/modified.
   Reads the session lazily off agent-ref because the session is attached
   after the pipeline is constructed (cli.cljs)."
  [agent-ref]
  {:name  :tool-persistence
   :leave (fn [ctx]
            (let [session   (some-> @agent-ref :session deref)
                  file-path (when session ((:get-file-path session)))]
              (when (and session file-path (not (:cancelled ctx)))
                ((:append session)
                 {:role     "tool_call"
                  :content  (str (:result ctx))
                  :metadata {:tool-name (:tool-name ctx)
                             :args      (:args ctx)}})))
            ctx)})
