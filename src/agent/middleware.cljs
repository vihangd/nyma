(ns agent.middleware
  "Middleware pipeline for tool execution."
  (:require [agent.tool-metadata :as tool-metadata]
            [agent.interceptors :as ic]
            [agent.extension-context :refer [create-extension-context]]
            [agent.tool-result-policy :as policy]
            [agent.utils.ansi :refer [truncate-text]]
            [agent.utils.ui :refer [ui-prompt-ready?]]
            [agent.debug :as d]
            [agent.utils.shell-grant :as shell-grant]
            [agent.utils.sandbox :as sandbox]
            [agent.utils.decide :as decide]
            [clojure.string :as str]))

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
                       ;; a nested call also stops with its caller (:signal,
                       ;; a code-mode script's timeout)
                       (aset ext-ctx "abortSignal"
                             (let [sigs (filterv some? [(some-> (:abort-controller ctx) deref .-signal)
                                                        (:signal ctx)])]
                               (if (< (count sigs) 2) (first sigs) (js/AbortSignal.any (clj->js sigs)))))
                       (aset ext-ctx "onUpdate"
                             (fn [data]
                               (when events
                                 ((:emit events) "tool_execution_update"
                                                 #js {:toolName (:tool-name ctx)
                                                      :execId   (:exec-id ctx)
                                                      :data     data}))))
                       ;; Active model id as a string, so a tool can adapt to the model.
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
   or {args: modified} to transform args. Then pi's tool_call, which can do the
   same to `input`: a guard written for pi must hold here.

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
        ;; After, so it sees args a handler above rewrote. Its own copy: pi's
        ;; handlers edit `input` in place.
        sent   (js/JSON.stringify (or (get result "args") (clj->js (:args ctx))))
        pi     #js {:type       "tool_call"
                    :toolName   (:tool-name ctx)
                    :toolCallId (:exec-id ctx)
                    :input      (js/JSON.parse (or sent "{}"))}
        pi-res (when-not (or (get result "block") (get result "cancel"))
                 (js-await ((:emit-collect events) "tool_call" pi)))
        edited (let [now (js/JSON.stringify (.-input pi))]
                 (when (not= now sent) (.-input pi)))
        result (cond-> result
                 (get pi-res "block") (assoc "block" true "reason" (get pi-res "reason"))
                 edited               (assoc "args" edited))]
    (cond
      (or (get result "block") (get result "cancel"))
      (assoc ctx :cancelled true
             ;; pi reports a hook's block as a failed tool
             :result-is-error (boolean (get pi-res "block"))
             :cancel-reason (or (get result "reason") "Blocked by extension"))

      ;; Skip execution — return a canned result string without calling the tool.
      ;; tool_result / tool_complete / tool_execution_end events still fire normally.
      ;; Use this for clean deny-with-explanation, result caching, and mock flows.
      (get result "skip")
      (assoc ctx :skip? true
             :result (or (get result "result") ""))

      ;; Allow arg transformation via returned {args: ...}. The pre-rewrite
      ;; args are kept for the permission prompt: bash_suite prefixes every
      ;; command with `unset LD_PRELOAD …`, and the prompt truncated to that
      ;; wrapper before the user's own command appeared.
      (get result "args")
      (assoc ctx :args (get result "args") :original-args (:args ctx))

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

(defn- formatter-failed
  "A display formatter threw. Still nil — a broken formatter must not break
   the tool call — but logged: silent swallowing once hid 13 dead formatters."
  [e]
  (d/debug "middleware" (str "display formatter threw: " (or (.-message e) e)))
  nil)

(defn- safe-call
  "Call f with arg, returning nil if f is nil or throws (logged at debug)."
  [f arg]
  (when f
    (try (f arg) (catch :default e (formatter-failed e)))))

(defn- safe-call2
  "Two-arg `safe-call`. Same contract."
  [f a b]
  (when f
    (try (f a b) (catch :default e (formatter-failed e)))))

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
                                                        :nested     (boolean (:nested? ctx))
                                                        :details    (:result-details ctx)})))
        ctx        (if-let [mod-result (get complete-result "result")]
                     (assoc ctx :result (str mod-result))
                     ctx)
        ;; Apply per-tool result policy: truncates to :max-string-length and builds
        ;; structured envelope {:ok :summary :data :error :error-kind}.
        ;; ctx :result is replaced with the policy-truncated model-visible string.
        ;; The full envelope is stored as :result-envelope and carried on
        ;; `tool_result` for rpc clients and extensions.
        ;; Measured either side of the policy, because these are the only two
        ;; points where both numbers exist: `raw-bytes` is what the tool
        ;; produced, `model-bytes` is what actually enters the context window.
        ;; Tool output dominates a transcript — 92% of message bytes in sampled
        ;; sessions — so without this there is no way to tell whether a change
        ;; aimed at it helped, or how much the per-tool caps already save.
        raw-bytes  (count (str (:result ctx)))
        envelope   (policy/apply-policy (:result ctx) (:tool-name ctx))
        ctx        (-> ctx
                       ;; what hooks left, before the model-facing cut — a
                       ;; nested caller (code mode) reads this, not raw output
                       (assoc :hooked-result (:result ctx))
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
            (let [;; The model's own id when the AI SDK gave one, so a
                  ;; consumer can join tool_execution_* to the toolCall block
                  ;; in the assistant message (pi-rpc, ACP). Nested script
                  ;; calls have none and keep a generated id.
                  exec-id    (or (:tool-call-id ctx)
                                 (str (js/Date.now) "-" (.toString (js/Math.random) 36)))
                  start-time (js/Date.now)
                  display-fields (extract-display-fields (:tool ctx) (:tool-name ctx) (:args ctx))]
              (when events
                ;; merge of squint maps is already a plain JS object with
                ;; camelCase keys — no clj->js. NOTE: :args ALIASES the live
                ;; args object the tool will execute with — handlers must
                ;; treat event payloads as read-only.
                ((:emit events) "tool_execution_start"
                                (merge {:toolName (:tool-name ctx) :execId exec-id :args (:args ctx)
                                        ;; a call made from inside a script, not by the model
                                        :nested (boolean (:nested? ctx))
                                        :label (when-let [t (:tool ctx)] (.-label t))}
                                       display-fields)))
              (when store
                ((:dispatch! store) :tool-execution-started
                                    {:tool-name (:tool-name ctx) :exec-id exec-id}))
              (assoc ctx :exec-id exec-id :start-time start-time)))
   :leave (fn [ctx]
            (tool-tracking-leave events store ctx))
   ;; A THROW unwinds through :error, not :leave, and without this stage the
   ;; execution was never closed: `:tool-execution-ended` was never dispatched,
   ;; so the exec id sat in `:active-executions` for the rest of the process.
   ;; `isIdle` then answered false forever and `waitForIdle` spun a 100ms
   ;; timeout chain that could never resolve, after any failed or interrupted
   ;; tool call. Deliberately NOT the full leave path: there is no result to
   ;; shape, so this closes the execution and tells the UI, nothing more.
   :error (fn [ctx]
            (let [duration (- (js/Date.now) (or (:start-time ctx) 0))
                  err      (:error ctx)
                  msg      (str (or (some-> err .-message) err "tool failed"))]
              (when events
                ((:emit events) "tool_execution_end"
                                {:toolName (:tool-name ctx)
                                 :execId   (:exec-id ctx)
                                 :args     (:args ctx)
                                 :duration duration
                                 :result   msg
                                 :isError  true}))
              (when store
                ((:dispatch! store) :tool-execution-ended
                                    {:exec-id (:exec-id ctx) :duration duration}))
              ctx))})

(defn categorize-tool
  "Permission category from the tool's safety metadata — one table for
   built-ins (`tool_metadata/builtin-metadata`) and extension tools
   (`registerTool` reads a `:safety` field off the def). A tool with no
   metadata is \"other\", which no role policy maps, so the gate allows
   it — an extension tool that edits files and says nothing about itself is
   invisible to /plan and ask-mode."
  [tool-name]
  (let [s    (tool-metadata/tool-safety tool-name)
        caps (or (:capabilities s) #{})]
    (cond
      (contains? caps :shell)                  "exec"
      (tool-metadata/file-editing? tool-name)  "write"
      (:destructive? s)                        "write"
      (:network? s)                            "network"
      (:read-only? s)                          "read"
      :else                                    "other")))

(defn- agent-ui
  "The live interactive UI handle (or nil). Reached from the agent's extension
   API so the permission gate can prompt the user directly."
  [agent]
  (when agent
    (when-let [api (.-extension-api agent)] (.-ui api))))

(defn- agent-state
  "The agent's state map, or nil for the bare JS-object agents tests hand in."
  [agent]
  (when agent
    (when-let [st (:state agent)]
      (try @st (catch :default _ nil)))))

(defn skill-allows-tool?
  "True when an active skill lists `tool-name` in its `allowed-tools`.
   `activate-skill` writes `:skill-allowed-tools {name #{tool…}}` onto the
   state; reading it here keeps this namespace free of the skills loader
   (which requires the extension API, which requires this)."
  [state tool-name]
  (boolean
   (some (fn [[sname allowed]]
           (and (contains? (:active-skills state) sname)
                (contains? allowed tool-name)))
         (:skill-allowed-tools state))))

(defonce ^:private session-allowed
  ;; "Allow for this session" FALLBACK, for callers that hand
  ;; permission-check-enter a ctx without a pipeline (tests). A real pipeline
  ;; owns its own set (`:session-allows` on the ctx, see create-pipeline):
  ;; this used to be the only set, process-wide and never reset — so a grant
  ;; outlived /new and /resume, and in the gateway, where one process serves
  ;; many conversations, one chat's grant applied to every chat.
  (atom #{}))

(defn- allows-of [ctx] (or (:session-allows ctx) session-allowed))

(defn session-allowed? [tool-name] (contains? @session-allowed tool-name))
(defn allow-for-session! [tool-name] (swap! session-allowed conj tool-name))
(defn reset-session-allows! [] (reset! session-allowed #{}))

(defn- arg-get
  "`:args` is a CLJS map, or a JS object when a tool's prepareArguments ran
   first (that interceptor precedes this one). Read both."
  [args k]
  (when args
    (let [v (get args (keyword k))]
      (if (some? v) v (aget args k)))))

(defn- one-line [s n]
  (let [t (first (str/split-lines (str s)))]
    (if (> (count t) n) (str (subs t 0 (dec n)) "…") t)))

(defn permission-prompt-text
  "What the user is approving, in ≤5 lines: the tool and its category, then
   the bash command or the file path, then for an edit the first line that
   changes as `- old` / `+ new`, then the policy's reason when it gave one.
   The old prompt was `Allow 'bash'?` and nothing else — approving a command
   you cannot see. Pure."
  [tool-name category args reason]
  (let [command (arg-get args "command")
        path    (arg-get args "path")
        old-s   (arg-get args "old_string")
        new-s   (arg-get args "new_string")
        content (arg-get args "content")
        ;; codemode__script: what it will call (its prepareArguments ran
        ;; first), then the opening lines of the source
        calls   (arg-get args "calls")
        code    (arg-get args "code")
        diff    (when (or old-s new-s)
                  (let [o (str/split-lines (str (or old-s "")))
                        n (str/split-lines (str (or new-s "")))
                        [o1 n1] (or (first (drop-while (fn [[a b]] (= a b)) (map vector o n)))
                                    [(first o) (first n)])
                        more (- (max (count o) (count n)) 1)]
                    (cond-> []
                      (some? o1) (conj (str "- " (one-line o1 100)))
                      (some? n1) (conj (str "+ " (one-line n1 100)))
                      (pos? more) (conj (str "  … " more " more line" (when (> more 1) "s"))))))]
    (str/join "\n"
              (cond-> [(str "Allow '" tool-name "'?  (" category ")")]
                command (conj (str "$ " (one-line command 110)))
                calls   (conj (str "calls: " (one-line calls 110)))
                code    (into (map #(str "  " (one-line % 108))
                               (take 2 (remove str/blank? (str/split-lines (str code))))))
                (and path (not command)) (conj (str (one-line path 110)
                                                    (when (and content (not diff))
                                                      (str "  (" (count (str/split-lines (str content))) " lines)"))))
                diff    (into diff)
                (seq (str reason)) (conj (str "Reason: " (one-line reason 110)))))))

(def no-whole-tool-grant
  "Tools whose \"Allow always\" / \"for this session\" must never cover the
   whole tool: bash (a grant is a command pattern instead) and a code-mode
   script, which is arbitrary code that preapproves every tool it names — one
   session grant on a read-only script would have run every later script,
   writes and bash included, unasked."
  #{"bash" "codemode__script" "bash (unsandboxed)"})

(defn ^:async resolve-ask
  "An 'ask' decision: prompt the user ONCE. Choices: Allow once / Allow for
   this session / Allow always (this tool, this project) / Deny. With no
   interactive UI (e.g. headless -p) an 'ask' resolves to DENY — never a
   silent allow. Returns the updated ctx. Exposed for tests."
  [settings ctx ui tool-name reason]
  (if-not (ui-prompt-ready? ui)
    (assoc ctx :cancelled true
           :cancel-reason (or reason (str "Permission required for '" tool-name
                                          "' but no interactive approval is available")))
    (let [prompt (str (when-let [s (:source ctx)] (str "Subagent " s " asks:\n"))
                      (permission-prompt-text tool-name (categorize-tool tool-name)
                                              (or (:original-args ctx) (:args ctx)) reason))
          ;; The run is now blocked on the user. Announced so desktop_notify
          ;; can say so: a turn stuck on an unseen prompt was the classic
          ;; "came back to nothing".
          _      (when-let [emit (some-> (:events ctx) :emit)]
                   (emit "permission_prompt" {:tool tool-name}))
          ;; bash: "Allow always" remembers a command PATTERN (`git push *`),
          ;; never the whole tool — one click on `ls` used to approve every
          ;; future command in the project. A compound command gets no
          ;; always-option at all.
          bash?  (= tool-name "bash")
          base   (when bash? (shell-grant/grant-base (get (or (:original-args ctx) (:args ctx)) :command)))
          once?  (and (contains? no-whole-tool-grant tool-name) (not bash?))
          always (cond
                   once?       nil
                   (not bash?) "Allow always (this tool, this project)"
                   base        (str "Allow always: " base " * (this project)")
                   :else       nil)
          choice (js-await
                  (.select ui prompt
                           (clj->js (cond-> ["Allow once"]
                                      (not once?) (conj "Allow for this session")
                                      always      (conj always)
                                      true        (conj "Deny")))))
          ;; beside the judge's verdict, if it sent this ask here
          _      (decide/answered! (decide/subject-of tool-name (or (:original-args ctx) (:args ctx)))
                                   (boolean (and choice (.startsWith choice "Allow"))))]
      (cond
        ;; only "Allow once" is offered; any other Allow is still this once
        (and choice once? (.startsWith choice "Allow")) ctx

        (and choice (.startsWith choice "Allow always") bash?)
        (do (when (and settings (:append-bash-allow! settings)) ((:append-bash-allow! settings) base)) ctx)

        (and choice (.startsWith choice "Allow always"))
        (do (when settings ((:append-allow-tool! settings) tool-name)) ctx)

        ;; bash: the session remembers the PATTERN, like Allow always — a
        ;; session grant of the whole tool on `ls` answered every later ask,
        ;; `git push --force` included. No pattern (compound, interpreter):
        ;; it is this once.
        (and choice (.startsWith choice "Allow for this session") bash?)
        (do (when base (swap! (allows-of ctx) conj (str "bash:" base))) ctx)

        (and choice (.startsWith choice "Allow for this session"))
        (do (swap! (allows-of ctx) conj tool-name) ctx)

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
            ;; Never for bash: skipping permission_request skipped bash_suite's
            ;; denylist with it. A bash grant answers an `ask` below instead.
            (and (not (contains? no-whole-tool-grant tool-name))
                 (or (contains? @(allows-of ctx) tool-name)
                     (and settings ((:tool-allowed? settings) tool-name)))))
      ctx
      (let [result   (js-await
                      ((:emit-collect events) "permission_request"
                                              #js {:tool     tool-name
                                                   :args     (clj->js args)
                                                   ;; what a hook rewrote `args` from
                                                   :originalArgs (clj->js (:original-args ctx))
                                                   :category (categorize-tool tool-name)
                                                   :path     (get args :path)}))
            decision (get result "decision")
            reason   (get result "reason")]
        (cond
          (= decision "deny")
          (assoc ctx :cancelled true :cancel-reason (or reason "Permission denied"))

          ;; Leaving the sandbox always asks, full-auto included, and only
          ;; for this once. A worktree child has no one to ask.
          (and (= tool-name "bash") (true? (get args :unsandboxed)) @sandbox/root)
          (if (.. js/process -env -NYMA_WRITE_ROOT)
            (assoc ctx :cancelled true
                   :cancel-reason "A worktree subagent cannot run outside the sandbox; report what you need to the parent.")
            (js-await (resolve-ask settings ctx (agent-ui (:agent ctx)) "bash (unsandboxed)"
                                   "Runs OUTSIDE the sandbox: no limits on writes, reads or network")))

          ;; An active skill that declared the tool in `allowed-tools` has
          ;; already answered the question for the user — but only the ask.
          ;; A deny is decided above and a skill cannot lift it.
          (= decision "ask")
          (cond
            (skill-allows-tool? (agent-state (:agent ctx)) tool-name) ctx
            ;; A code-mode script listed this tool and the user approved the
            ;; script with that list in front of them. Answers the ask only.
            (contains? (:preapproved ctx) tool-name) ctx
            ;; bash grants: a remembered pattern, this session's allow, or a
            ;; whole-tool grant from before patterns existed. Answers the ask
            ;; only — a deny (denylist, destructive) above still wins.
            (and (= tool-name "bash")
                 (or (some #(and (str/starts-with? % "bash:")
                                 (shell-grant/grant-matches? (subs % 5) (get args :command)))
                           @(allows-of ctx))
                     (and settings ((:tool-allowed? settings) "bash"))
                     (and settings (:bash-allowed? settings)
                          ((:bash-allowed? settings) (get args :command)))))
            ctx
            :else (js-await (resolve-ask settings ctx (agent-ui (:agent ctx)) tool-name reason)))

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
        approval-checks (atom [])
        ;; This agent's "Allow for this session" grants: they end with the
        ;; session — /new, /resume and /fork emit session_start, a branch
        ;; switch emits session_switch. Noticed through the bus's emit count,
        ;; not a handler, so a fresh agent still owns zero handlers.
        session-allows  (atom #{})
        epoch           (fn [] (if (fn? (:emit-count events))
                                 (+ ((:emit-count events) "session_start")
                                    ((:emit-count events) "session_switch"))
                                 0))
        seen-epoch      (atom (epoch))
        sync-epoch!     (fn []
                          (let [e (epoch)]
                            (when (not= e @seen-epoch)
                              (reset! seen-epoch e)
                              (reset! session-allows #{}))))]
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
     ;; `opts` is for calls made from inside another tool (a code-mode script):
     ;;   :preapproved  set of tool names the user already approved for this
     ;;                 call — answers an `ask`, never a deny
     ;;   :nested?      true — the parent's own result is what gets persisted
     ;;   :signal       AbortSignal the call also stops on
     ;;   :tool-call-id the AI SDK's toolCallId, used as the execution id
     (fn [tool-name tool args & [opts]]
       (sync-epoch!)
       (let [agent    (when agent-ref @agent-ref)
             ext-ctx  (when agent (create-extension-context agent))
             ctx      {:tool-name         tool-name
                       :preapproved       (or (:preapproved opts) #{})
                       :nested?           (boolean (:nested? opts))
                       :signal            (:signal opts)
                       :tool-call-id      (:tool-call-id opts)
                       :session-allows    session-allows
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

     ;; The hooks alone, for the same child: .nymaignore, the destructive
     ;; block, pi guards. Resolves the ctx, as the pipeline would see it.
     :check-hooks
     (fn [tool-name args exec-id]
       (before-hook-compat-enter events {:tool-name tool-name :args args :exec-id exec-id
                                         :cancelled false}))

     ;; The gate alone, for a tool that runs in a subagent. Its bus has no
     ;; subscribers, so without this "no decision" meant allow. `source` names
     ;; the child in the prompt. Resolves the ctx; :cancelled means refused.
     :check-permission
     (fn [tool-name args source & [original-args]]
       (sync-epoch!)
       (let [ctx {:tool-name tool-name :args args :source source
                  :original-args original-args
                  :preapproved #{} :session-allows session-allows
                  :cancelled false :events events
                  :agent (when agent-ref @agent-ref)}]
         (-> (permission-check-enter events settings ctx)
             (.then (fn [c] (if (:cancelled c)
                              c
                              ((:enter (approval-check-interceptor approval-checks)) c)))))))

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
                                   (fn [args call-opts]
                                     (let [result-promise ((:execute pipeline) tool-name t args
                                                           (when-let [id (some-> call-opts .-toolCallId)]
                                                             {:tool-call-id id}))]
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
  (let [persist!
        (fn [ctx text error?]
          (let [session   (some-> @agent-ref :session deref)
                file-path (when session ((:get-file-path session)))]
            ;; Nested (script) calls are not persisted: their results are
            ;; inputs the script consumed, and the script's own result is
            ;; the entry. Persisting them replayed hundreds of KB on resume.
            (when (and session file-path (not (:cancelled ctx)) (not (:nested? ctx)))
              ((:append session)
               {:role     "tool_call"
                :content  (str text)
                :metadata (cond-> {:tool-name    (:tool-name ctx)
                                   :tool-call-id (:exec-id ctx)
                                   :args         (:args ctx)}
                            error? (assoc :is-error true))}))))]
    {:name  :tool-persistence
     :leave (fn [ctx]
              (persist! ctx (:result ctx) (:result-is-error ctx))
              ctx)
     ;; A failed call is history too: the model saw the failure and acted on it.
     :error (fn [ctx]
              (let [e (:error ctx)]
                (persist! ctx (or (some-> e .-message) e "tool failed") true))
              ctx)}))
