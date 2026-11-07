(ns agent.modes.pi-rpc
  "pi's JSONL-over-stdio RPC protocol (pi 1.2 `rpc-types`), so pi RPC clients
   drive nyma unchanged, the Emacs frontends (`pi-coding-agent.el`, pilish)
   among them.

   Two halves:
   1. Command loop — reads JSONL commands from stdin, each carrying {type,id,…};
      replies with a {type:\"response\", command, id, success, data} envelope.
      The elisp BLOCKS on the matching id, so every command MUST get a response.
   2. Event translator — subscribes to nyma's event bus and rewrites events into
      the pi wire shapes (stateful: per-turn message accumulator; reasoning→thinking;
      nyma's flat text-delta → nested assistantMessageEvent).

   stdout is the protocol channel — only JSONL goes there. Everything else
   (errors, debug) goes to stderr via js/console.error."
  (:require [agent.utils.content :as content]
            [agent.utils.jsonl-stdin :refer [read-lines!]]
            [agent.sessions.manager :as sessions]
            [agent.loop :refer [run steer follow-up]]
            [agent.commands.builtins :as builtins]
            [agent.model-info :as model-info]
            [agent.utils.event-json :refer [tool-results]]
            [agent.utils.pi-shape :as shape]
            [agent.pricing :as pricing]
            [agent.sessions.compaction :as compaction]
            [agent.commands.share :as share]
            [agent.ui.editor-bash :as editor-bash]
            [agent.thinking :as thinking]
            [agent.sessions.pi-mirror :as pi-mirror]
            [agent.tool-result-policy :as policy-result]
            [agent.ui.think-tag-parser :as think-tags]
            ["node:fs" :as fs]
            [clojure.string :as str]))

;; ── stdout: the one and only protocol writer ───────────────────

(defn write-event!
  "Serialize a CLJS map to one JSONL line on stdout. The sole stdout writer."
  [m]
  (println (js/JSON.stringify (clj->js m))))

(defn- write-response!
  "Reply to a command. Echoes :command and :id so the elisp can route + mutate
   state (missing :command silently skips state updates on the elisp side)."
  ([cmd] (write-response! cmd nil))
  ([cmd data]
   (write-event! (cond-> {:type "response"
                          :command (.-type cmd)
                          :success true}
                   (some? (.-id cmd)) (assoc :id (.-id cmd))
                   (some? data) (assoc :data data)))))

(defn- write-error!
  [cmd msg]
  (write-event! (cond-> {:type "response" :command (.-type cmd) :success false :error msg}
                  (some? (.-id cmd)) (assoc :id (.-id cmd)))))

;; ── get_state ──────────────────────────────────────────────────

(defn- model-obj [agent]
  ;; `config.model` holds the RESOLVED AI SDK model object, not an id. Sending it
  ;; through as `mid` meant the client received "[object Object]" as both id and
  ;; name, and the registry lookup stringified to the same, so contextWindow was
  ;; ALWAYS the 100000 default regardless of model.
  (let [cfg  (:config agent)
        m    (.-model cfg)
        prov (or (aget cfg "active-provider-name") "")
        mid  (cond
               (nil? m)    ""
               (string? m) m
               :else       (str (or (.-modelId m) "")))
        cw   (try ((:context-window (:model-registry agent))
                   (model-info/model-key prov m))
                  (catch :default _ nil))]
    ;; pi's Model shape: a client reads cost, reasoning and input off it
    ;; (ThinkRail's model picker reads model.cost.input).
    (let [[in out] (try (pricing/lookup-cost (model-info/model-key prov m)) (catch :default _ nil))]
      {:id mid :name mid :provider prov :api prov :baseUrl ""
       :reasoning false :input ["text"]
       :cost {:input (or in 0) :output (or out 0) :cacheRead 0 :cacheWrite 0}
       :contextWindow (or cw 100000)
       :maxTokens (or (aget cfg "max-tokens") (.-maxTokens cfg) 8192)})))

(defn- session-of [agent]
  (some-> (:session agent) deref))

(defn- auto-compaction? [agent]
  (try (:enabled? (compaction/settings->opts (compaction/resolve-settings (:settings agent))))
       (catch :default _ true)))

(defn- get-state-data [agent st]
  (let [s    @(:state agent)
        sess (session-of agent)
        file (when sess (try ((:get-file-path sess)) (catch :default _ nil)))
        name (when sess (try ((:get-session-name sess)) (catch :default _ nil)))]
    (cond-> {:model (model-obj agent)
             :thinkingLevel (or @(:thinking-level agent) "off")
             :isStreaming @(:streaming? st)
             :isCompacting @(:compacting? st)
             ;; How nyma's loop delivers them; set_*_mode accepts only these.
             :steeringMode "all"
             :followUpMode "one-at-a-time"
             ;; The file's name, not its path: a consumer keys files by it
             ;; (ThinkRail's todo store does).
             :sessionId (or (shape/session-id file) name "session")
             ;; With pi.sessions on, the file a pi client can read.
             :sessionFile (or (pi-mirror/public-file file) "")
             :autoCompactionEnabled (auto-compaction? agent)
             :messageCount (count (:messages s))
             :pendingMessageCount (+ (count (some-> (:steer-queue agent) deref))
                                     (count @(:follow-queue agent)))}
      name (assoc :sessionName name))))

;; ── event translator ───────────────────────────────────────────

(defn drain-queues!
  "Empty both message queues and return what was in them, as pi's
   `{steering, followUp}` of plain strings.

   Returned rather than discarded because the frontend shows the user what it
   just threw away — dropping the text silently is the difference between
   \"cancelled\" and \"lost\"."
  [agent]
  ;; (get agent k), not (k agent): squint compiles a keyword to a plain
  ;; string, so calling it as a function is a TypeError at runtime.
  (let [drain (fn [k]
                (let [a (get agent k)
                      q @a]
                  (reset! a [])
                  (shape/queue-texts q)))]
    {:steering (drain :steer-queue)
     :followUp (drain :follow-queue)}))

(defn open-turn!
  "Emit a pi `turn_start` unless one is already unmatched.

   Idempotent so it can be called from both nyma's own turn_start and from the
   first turn_end of a step that began without one."
  [st]
  (when-not @(:turn-open? st)
    (reset! (:turn-open? st) true)
    (write-event! {:type "turn_start"})))

(defn turn-end-event
  "pi's `{type, message, toolResults}` for a step. `msg` and `results` are the
   assistant message and toolResult messages the translator built; without
   them (a step that streamed nothing) both come from the StepResult.

   Pure so the mapping can be pinned field by field: a wrongly-named field here
   is invisible — the event still arrives, the consumer just reads nothing."
  [step & [msg results]]
  {:type    "turn_end"
   :message (or msg
                (let [text (or (and step (.-text step)) "")]
                  (assoc (shape/assistant-message {})
                         :content [{:type "text" :text text}]
                         :stopReason (shape/stop-reason (and step (.-finishReason step)))
                         :usage (shape/usage step))))
   :toolResults (or results (tool-results step))})

(defn- model-meta [agent]
  (try (let [m (model-obj agent)] {:api (:api m) :provider (:provider m) :model (:id m)})
       (catch :default _ {})))

(defn- update!
  "A message_update as pi's RPC mode writes it (modes/json-event.js): the
   message so far is NOT sent — pi strips `partial` and `message` from the
   wire, so a client builds the message from the deltas and gets it whole at
   message_end."
  [st ame]
  (write-event! {:type "message_update" :usage (:usage @(:msg st))
                 :assistantMessageEvent ame}))

(defn- ensure-msg!
  "The open assistant message, starting one (and its message_start) if none."
  [agent st]
  (when-not @(:msg st)
    ;; nyma's turn_start is per loop iteration, not per step
    (open-turn! st)
    (reset! (:msg st) (shape/assistant-message (model-meta agent)))
    (reset! (:msg-start st) (js/Date.now))
    (write-event! {:type "message_start" :message @(:msg st)})))

(defn- add-block!
  "Append `block` to the open message; returns its index."
  [agent st block]
  (ensure-msg! agent st)
  (let [[m i] (shape/add-block @(:msg st) block)]
    (reset! (:msg st) m)
    i))

(defn- open-tool-call!
  "The toolCall block for `id`, and its toolcall_start, unless it has one.
   Returns its index."
  [agent st id tool-name]
  (or (some-> @(:msg st) (shape/tool-call-index id))
      (let [i (add-block! agent st {:type "toolCall" :id (str id) :name (str tool-name) :arguments {}})]
        (update! st {:type "toolcall_start" :contentIndex i :id (str id) :toolName (str tool-name)})
        i)))

(defn- add-tool-call!
  "Finish the toolCall block for `id`, once. Both the stream's tool-call part and
   tool_execution_start lead here: the AI SDK can start running a tool before
   the loop has read its tool-call part."
  [agent st id tool-name args]
  (when (and (seq (str id)) (not (contains? @(:calls-done st) id)))
    (let [i (open-tool-call! agent st id tool-name)]
      (swap! (:calls-done st) conj id)
      (swap! (:msg st) assoc-in [:content i :arguments] (or args {}))
      (update! st {:type "toolcall_end" :contentIndex i
                   :toolCall (get-in @(:msg st) [:content i])}))))

(defn- record! [st m] (swap! (:run-msgs st) conj m))

(defn- close-msg!
  "End the open assistant message with `fields` merged in, then the toolResult
   messages of its tool calls. Returns [message results], or nil if none open."
  [st fields]
  (when-let [m @(:msg st)]
    (let [final   (update (merge m fields) :content
                          ;; the block opened at message_start stays empty when
                          ;; a <think> came first
                          #(filterv (fn [b] (not (and (= "text" (:type b)) (= "" (:text b)))))
                                    (shape/split-think %)))
          results @(:results st)]
      (reset! (:msg st) nil)
      (reset! (:text-i st) nil)
      (reset! (:think-i st) nil)
      (reset! (:results st) [])
      (reset! (:calls-done st) #{})
      (write-event! {:type "message_end" :message final})
      (record! st final)
      (doseq [r results]
        (write-event! {:type "message_start" :message r})
        (write-event! {:type "message_end" :message r})
        (record! st r))
      [final results])))

(defn- end-turn!
  "Close the step: its assistant message (one is made if the step streamed
   nothing), the toolResults, then turn_end."
  [agent st step fields]
  (when-not @(:msg st)
    (ensure-msg! agent st)
    (when-let [t (and step (seq (str (.-text step))) (.-text step))]
      (swap! (:msg st) (fn [m] (first (shape/add-block m {:type "text" :text t}))))))
  (reset! (:turn-open? st) false)
  (let [[m rs] (close-msg! st fields)]
    (write-event! (turn-end-event step m rs))))

(defn- end-run!
  "pi's agent_end, carrying the run's messages; ThinkRail reads the last
   assistant message's stopReason/errorMessage off it as the run's outcome."
  [st]
  (reset! (:streaming? st) false)
  (write-event! {:type "agent_end" :messages @(:run-msgs st) :willRetry false})
  (reset! (:run-msgs st) []))

(defn- compaction-reason [data]
  (if (= "manual" (some-> data (aget "trigger"))) "manual" "threshold"))

(defn- aborted? [agent]
  (boolean (some-> agent :abort-controller deref .-signal .-aborted)))

(defn subscribe-events!
  "Wire nyma events → pi wire events. Returns an unsubscribe thunk.

   One assistant message per step: nyma's text-start/text-end mark a text
   PART, so they become text_start/text_end inside the message, which closes
   at the step's turn_end with every block in it."
  [agent st]
  (let [events (:events agent)
        on     (:on events)
        off    (:off events)
        nested (atom #{})
        text-start!
        (fn []
          (let [i (add-block! agent st {:type "text" :text ""})]
            (reset! (:text-i st) i)
            (update! st {:type "text_start" :contentIndex i})))
        text-delta!
        (fn [d]
          (when (nil? @(:text-i st)) (text-start!))
          (let [i @(:text-i st)]
            (swap! (:msg st) #(shape/append-at % i :text d))
            (update! st {:type "text_delta" :contentIndex i :delta d})))
        text-end!
        (fn []
          (when-let [i @(:text-i st)]
            (reset! (:text-i st) nil)
            (update! st {:type "text_end" :contentIndex i
                         :content (get-in @(:msg st) [:content i :text])})))
        think-start!
        (fn []
          (let [i (add-block! agent st {:type "thinking" :thinking ""})]
            (reset! (:think-i st) i)
            (update! st {:type "thinking_start" :contentIndex i})))
        think-delta!
        (fn [d]
          (when (nil? @(:think-i st))
            (reset! (:think-i st) (add-block! agent st {:type "thinking" :thinking ""})))
          (let [i @(:think-i st)]
            (swap! (:msg st) #(shape/append-at % i :thinking d))
            (update! st {:type "thinking_delta" :contentIndex i :delta d})))
        think-end!
        (fn []
          (when-let [i @(:think-i st)]
            (reset! (:think-i st) nil)
            (update! st {:type "thinking_end" :contentIndex i
                         :content (get-in @(:msg st) [:content i :thinking])})))
        handlers
        [["agent_start"
          (fn [_]
            (when-not @(:streaming? st)
              (reset! (:streaming? st) true)
              (write-event! {:type "agent_start"})))]

         ["message_start"
          (fn [_] (text-start!))]

         ["message_update"
          (fn [chunk]
            ;; `.text` first: message_update carries the AI SDK v7 fullStream
            ;; part. `.textDelta` is the OBJECT stream's field.
            ;; A model's inline <think> rides this channel; a client renders
            ;; deltas as they land, so it is split here and not at message_end.
            (let [[state pieces] (think-tags/scan @(:scan st) (or (.-text chunk) (.-textDelta chunk) ""))]
              (reset! (:scan st) state)
              (doseq [[kind d] pieces]
                (case kind
                  :text  (text-delta! d)
                  :think (think-delta! d)
                  :open  (do (text-end!) (think-start!))
                  :close (think-end!)))))]

         ["message_end"
          (fn [_]
            (let [{:keys [in? held]} @(:scan st)]
              (reset! (:scan st) nil)
              (when (seq held) (if in? (think-delta! held) (text-delta! held)))
              (when in? (think-end!))
              (text-end!)))]

         ["reasoning_start" (fn [_] (think-start!))]

         ["reasoning_delta"
          (fn [chunk]
            (think-delta! (or (.-textDelta chunk) (.-text chunk) (.-delta chunk) "")))]

         ["reasoning_end" (fn [_] (think-end!))]

         ["tool_input_start"
          (fn [data] (open-tool-call! agent st (.-id data) (.-toolName data)))]

         ["tool_input_delta"
          (fn [data]
            (when-let [i (some-> @(:msg st) (shape/tool-call-index (.-id data)))]
              (update! st {:type "toolcall_delta" :contentIndex i :delta (str (.-delta data))})))]

         ;; The stream's tool-call part. The middleware emits a tool_call of
         ;; its own (no toolCallId, also for script calls); only the stream's
         ;; is the model's.
         ["tool_call"
          (fn [data]
            (when-let [id (.-toolCallId data)]
              (add-tool-call! agent st id (.-toolName data) (.-input data))))]

         ["tool_execution_start"
          (fn [data]
            (if (:nested data)
              (swap! nested conj (:execId data))
              (add-tool-call! agent st (:execId data) (:toolName data) (:args data)))
            (write-event! {:type "tool_execution_start"
                           :toolCallId (:execId data)
                           :toolName (:toolName data)
                           :args (or (:args data) {})}))]

         ["tool_execution_update"
          (fn [data]
            (write-event! {:type "tool_execution_update"
                           :toolCallId (:execId data)
                           :toolName (:toolName data)
                           ;; update payload carries the streaming chunk under
                           ;; :data (middleware onUpdate), not :result
                           :partialResult (let [d (:data data)]
                                            (if (and (object? d) (.-content d))
                                              d          ; a pi tool's onUpdate({content, details})
                                              {:content [{:type "text" :text (str (or d ""))}]
                                               :details {}}))}))]

         ["tool_execution_end"
          (fn [data]
            ;; What the model saw. `:result` is the terminal's copy: wrapped to
            ;; its width, full of escapes and capped for the screen.
            (let [id   (:execId data)
                  text (str (if-let [env (:resultEnvelope data)]
                              (policy-result/model-string env)
                              (:result data)))]
              (write-event! {:type "tool_execution_end"
                             :toolCallId id
                             :toolName (:toolName data)
                             :result {:content [{:type "text" :text text}]
                                      :details (or (:details data) {})}
                             :isError (boolean (:isError data))
                             :durationMs (or (:duration data) 0)})
              (if (contains? @nested id)
                (swap! nested disj id)
                (swap! (:results st) conj
                       (shape/tool-result-message {:id id :name (:toolName data)
                                                   :text text :details (:details data)
                                                   :error? (:isError data)
                                                   :duration (:duration data)})))))]

         ["before_compact"
          (fn [data]
            (reset! (:compacting? st) true)
            (write-event! {:type "compaction_start" :reason (compaction-reason data)}))]

         ["compact"
          (fn [data]
            (reset! (:compacting? st) false)
            (write-event! {:type "compaction_end" :reason (compaction-reason data)
                           :result {:summary (str (or (:summary data) "")) :firstKeptEntryId ""
                                    :tokensBefore (or (:before data) 0)
                                    :estimatedTokensAfter (or (:after data) 0)}
                           :aborted false :willRetry false}))]

         ;; A turn is one assistant response plus the tool calls it caused.
         ;; nyma emits turn_start once per run-loop iteration and turn_end once
         ;; per STEP (from onStepFinish), so a 5-tool turn upstream is 1 start
         ;; and 5 ends. pi's contract is a pair, so the wire alternates
         ;; strictly: every turn_end is preceded by exactly one turn_start.
         ["turn_start"
          (fn [_]
            ;; A prompt that arrived during the post-turn compaction drains into
            ;; the loop after its run was already reported over: a new run to pi.
            (when-not @(:streaming? st)
              (reset! (:streaming? st) true)
              (write-event! {:type "agent_start"}))
            (open-turn! st))]

         ["turn_end"
          (fn [step]
            (end-turn! agent st step
                       (cond-> {:stopReason   (shape/stop-reason (and step (.-finishReason step)))
                                :usage        (shape/usage step (pricing/model-cost-key (:config agent)))
                                :thinkingLevel (or (some-> (:thinking-level agent) deref) "off")}
                         @(:msg-start st) (assoc :durationMs (- (js/Date.now) @(:msg-start st)))
                         (some-> step .-response .-id) (assoc :responseId (.. step -response -id))
                         (some-> step .-rawFinishReason) (assoc :rawStopReason (.-rawFinishReason step)))))]

         ["agent_end"
          (fn [_]
            ;; Open only when the run stopped mid-step: an abort, or the
            ;; stream filter giving up.
            (when @(:msg st)
              (end-turn! agent st nil {:stopReason (if (aborted? agent) "aborted" "stop")}))
            ;; Not end-run!: nyma ends a run per loop pass and pi runs follow-ups
            ;; inside one agent_start/agent_end, and the loop's own nudges are
            ;; queued after this event. turn_finalize knows; see below.
            nil)]

         ;; The session-level run has settled: no retry, no compaction retry
         ;; and no queued follow-up will continue it. Distinct from agent_end,
         ;; which fires per run.
         ["turn_finalize"
          (fn [data]
            ;; A provider error skips agent_end — it is emitted inside the try
            ;; whose catch only stashes the error — while turn_finalize always
            ;; runs. So the run is ended here, with an assistant message that
            ;; carries the error: without one a consumer reads a failed run as
            ;; a clean end.
            ;; Not while a follow-up is queued on a clean turn: the loop drains
            ;; it into the same run. An error stops the loop with it queued.
            (when (and @(:streaming? st)
                       (or (.-error data) (empty? @(:follow-queue agent))))
              (let [ab? (aborted? agent)]
                (when (or @(:msg st) (.-error data))
                  (end-turn! agent st nil
                             (cond-> {:stopReason (cond ab? "aborted" (.-error data) "error" :else "stop")}
                               (.-errorMessage data) (assoc :errorMessage (.-errorMessage data)))))
                (end-run! st)))
            ;; turn_finalize fires once per TURN and before the follow-queue
            ;; drain, so the loop can recur into another turn afterwards.
            ;; agent_settled means the run will not continue on its own, so it
            ;; waits until nothing is queued.
            (when (or (.-error data) (empty? @(:follow-queue agent)))
              (write-event! {:type "agent_settled" :aborted (aborted? agent)})))]]

        ;; User messages: the prompt, and steering or follow-ups the loop
        ;; injects between steps. All reach state through the store.
        unsub-store
        (when-let [store (:store agent)]
          ((:subscribe store)
           (fn [ev state data]
             ;; Not a session switch reseeding history: those turns were sent
             ;; once already, and would start the next agent_end's messages.
             (when (and (= ev :message-added) (not (:replaying-session? state)))
               (let [m (:message data)]
                 (when (= "user" (:role m))
                   ;; pi opens the run first; the loop adds the prompt first.
                   (when (and @(:running? st) (not @(:streaming? st)))
                     (reset! (:streaming? st) true)
                     (write-event! {:type "agent_start"})
                     (open-turn! st))
                   (when (and @(:running? st) (not @(:system-sent? st)))
                     (reset! (:system-sent? st) true)
                     (let [sys {:role "system" :timestamp (js/Date.now)
                                :content [{:type "text" :text (str (:system-prompt (:config agent)))}]}]
                       (write-event! {:type "message_start" :message sys})
                       (write-event! {:type "message_end" :message sys})))
                   (let [pm (shape/->pi-message m)]
                     (write-event! {:type "message_start" :message pm})
                     (write-event! {:type "message_end" :message pm})
                     (record! st pm))))))))
        queue-update!
        (fn [_k _r _o _n]
          (write-event! {:type "queue_update"
                         :steering (shape/queue-texts (some-> (:steer-queue agent) deref))
                         :followUp (shape/queue-texts (some-> (:follow-queue agent) deref))}))
        queues (filter some? [(:steer-queue agent) (:follow-queue agent)])]
    (doseq [[ev h] handlers] (on ev h))
    (doseq [q queues] (add-watch q :pi-rpc-queue queue-update!))
    (fn []
      (doseq [[ev h] handlers] (off ev h))
      (doseq [q queues] (remove-watch q :pi-rpc-queue))
      (when unsub-store (unsub-store)))))

;; ── extension ui over RPC ──────────────────────────────────────

(defn- ui-request!
  "Send an extension_ui_request; resolves with the client's response object
   (or the cancellation drain-pending-ui! hands out on abort and EOF)."
  [st m]
  (js/Promise.
   (fn [resolve _]
     (let [id (str "ui-" (js/Date.now) "-" (.toString (js/Math.random) 36))]
       (swap! (:pending-ui st) assoc id resolve)
       (write-event! (assoc m :type "extension_ui_request" :id id))))))

(defn- ui-value [r] (when-not (.-cancelled r) (.-value r)))

(defn rpc-ui
  "An extension `api.ui` whose dialogs are pi's extension_ui_request methods,
   so an extension asking the user works in any pi RPC frontend. The
   permission gate asks through it too: the user's permission mode decides,
   as in the TUI, and an ask arrives as a select of Allow once / for this
   session / always / Deny. (Before dialogs went over RPC, pi-rpc confirmed
   every tool call, read-only ones included.)"
  [st]
  (let [tell! (fn [m] (write-event! (assoc m :type "extension_ui_request"
                                           :id (str "ui-" (js/Date.now) "-" (.toString (js/Math.random) 36)))))]
    #js {:available true
         :select  (fn [prompt items _opts]
                    (let [items (vec (or items []))
                          by    (into {} (map (fn [i] [(str i) i]) items))]
                      (-> (ui-request! st {:method "select" :title (str prompt) :options (mapv str items)})
                          (.then (fn [r] (some->> (ui-value r) (get by)))))))
         :confirm (fn [message _opts]
                    (-> (ui-request! st {:method "confirm" :title (str message) :message (str message)})
                        (.then (fn [r] (true? (.-confirmed r))))))
         :input   (fn [prompt placeholder _opts]
                    (-> (ui-request! st (cond-> {:method "input" :title (str prompt)}
                                          placeholder (assoc :placeholder (str placeholder))))
                        (.then ui-value)))
         :editor  (fn [title prefill]
                    (-> (ui-request! st (cond-> {:method "editor" :title (str title)}
                                          prefill (assoc :prefill (str prefill))))
                        (.then ui-value)))
         :notify  (fn [msg level]
                    (tell! {:method "notify" :message (str msg)
                            :notifyType (case (str level) ("warn" "warning") "warning" "error" "error" "info")}))
         :setWidget (fn [k lines _pos]
                      (tell! {:method "setWidget" :widgetKey (str k) :widgetLines (vec (or lines []))}))
         :clearWidget (fn [k] (tell! {:method "setWidget" :widgetKey (str k) :widgetLines nil}))
         :setStatus (fn [k text]
                      (tell! (cond-> {:method "setStatus" :statusKey (str k)}
                               text (assoc :statusText (str text)))))
         :setTitle (fn [title] (tell! {:method "setTitle" :title (str title)}))
         :setEditorValue (fn [v] (tell! {:method "set_editor_text" :text (str v)}))}))

;; ── command handlers ───────────────────────────────────────────

(defn pi-messages
  "The conversation as pi messages. From the session's branch when there is
   one, since only it keeps tool calls; state holds user and final assistant
   text alone."
  [agent]
  (let [f  (some-> (session-of agent) :get-branch-entries)
        ms (when f (shape/entries->messages (f)))]
    (if (seq ms) ms (mapv shape/->pi-message (:messages @(:state agent))))))

(defn- commands-list [agent]
  (let [c (some-> (:commands agent) deref)]
    (cond
      (map? c) (mapv (fn [[k v]] {:name (str k)
                                  :source (or (some-> (get v :source) str) "extension")
                                  :sourceInfo {:scope "user"}
                                  :description (or (get v :description) "")})
                     c)
      (sequential? c) (mapv (fn [v] {:name (str (or (:name v) v))
                                     :source "extension"
                                     :sourceInfo {:scope "user"}
                                     :description (or (:description v) "")})
                            c)
      :else [])))

(defn- ->pi-model
  "A catalogue entry → pi's Model."
  [agent m]
  (let [[in out] (:cost m)]
    {:id (:id m) :name (:name m) :provider (:provider m) :api (:provider m)
     ;; Unknown counts as yes: a client offers thinking levels off this, and
     ;; nyma clamps per provider anyway; GLM refuses "off" outright.
     :reasoning (not (false? (:reasoning m))) :input ["text"]
     :cost {:input (or in 0) :output (or out 0) :cacheRead 0 :cacheWrite 0}
     :contextWindow (or (:context-window m) 100000)
     :maxTokens (:maxTokens (model-obj agent))}))

(defn available-models
  "Every model the provider registry knows, in pi's Model shape. Never empty:
   the current model stands in when the catalogue has nothing, since the Emacs
   frontend mapcars the list."
  [agent]
  ;; Most recently used first, as /model orders its picker.
  (let [ms (try (builtins/current-models agent) (catch :default _ []))]
    (if (seq ms)
      (mapv #(->pi-model agent %) ms)
      [(model-obj agent)])))

(defn- set-model!
  "Switch through the extension api's setModel, which resolves the provider's
   model object; false when there is no api to do it."
  [agent provider model-id]
  ;; cli.cljs sets it with `.-extension-api`, which squint writes as extension_api
  (if-let [api (.-extension_api agent)]
    (do (.setModel api (if (seq (str provider)) (str provider "/" model-id) (str model-id))) true)
    false))

(defn- session-stats
  "pi's SessionStats from the store's running totals."
  [agent]
  (let [s    @(:state agent)
        ms   (pi-messages agent)
        n    (fn [role] (count (filter #(= role (:role %)) ms)))
        in   (or (:total-input-tokens s) 0)
        out  (or (:total-output-tokens s) 0)
        sess (session-of agent)]
    {:sessionFile (when sess (try ((:get-file-path sess)) (catch :default _ nil)))
     :sessionId (or (when sess (try ((:get-session-name sess)) (catch :default _ nil))) "session")
     :userMessages (n "user") :assistantMessages (n "assistant")
     :toolCalls (n "toolResult") :toolResults (n "toolResult")
     :totalMessages (count ms)
     :tokens {:input in :output out
              :cacheRead (or (:total-cache-read-tokens s) 0)
              :cacheWrite (or (:total-cache-write-tokens s) 0)
              :total (+ in out)}
     :cost (or (:total-cost s) 0)
     :costUnknown (boolean (:cost-unknown? s))}))

(defn- new-session-path []
  (sessions/new-session-path (str (.. js/process -env -HOME) "/.nyma/sessions")))

(defn- content->text
  "Flatten a nyma message :content (string or vector of parts) to plain text."
  [c]
  (cond
    (string? c) c
    (sequential? c) (str/join "\n" (keep #(when (= (:type %) "text") (:text %)) c))
    :else ""))

(defn- last-assistant-text [agent]
  (let [msgs (:messages @(:state agent))
        la   (last (filter #(= (:role %) "assistant") msgs))]
    (content->text (:content la))))

(defn- call-session
  "Invoke session-manager fn `k` with args; returns true on success, false if the
   session/fn is missing or the call throws. Lets callers report real failure to
   the elisp instead of always claiming success."
  [agent k & args]
  (if-let [sess (session-of agent)]
    (if-let [f (get sess k)]
      (try (apply f args) true (catch :default _ false))
      false)
    false))

(defn- drain-pending-ui!
  "Resolve every outstanding reverse-RPC dialog (so an awaiting permission gate
   can never hang) with the given response. Used on abort and on shutdown."
  [st resp]
  (let [pend @(:pending-ui st)]
    (reset! (:pending-ui st) {})
    (doseq [[_ resolver] pend] (resolver resp))))

(def ^:private thinking-cycle ["off" "low" "medium" "high"])

(defn- next-thinking [cur]
  (let [i (.indexOf thinking-cycle (str cur))]
    (nth thinking-cycle (mod (inc (max 0 i)) (count thinking-cycle)))))

(defn- start-run! [agent st content]
  (reset! (:running? st) true)
  (-> (run agent content)
      (.catch (fn [e]
                (js/console.error "[pi-rpc] run error:" e)
                ;; Thrown before the turn wrapper, so turn_finalize never ran:
                ;; without this the client streams forever and sees no error.
                (when @(:streaming? st)
                  (end-turn! agent st nil {:stopReason "error" :errorMessage (str (.-message e))})
                  (end-run! st)
                  (write-event! {:type "agent_settled" :aborted false}))))
      (.finally (fn [] (reset! (:running? st) false)))))

(defn- cycle-model!
  "The next catalogue model after the current one, switched to; nil when there
   is nothing to cycle to."
  [agent]
  (let [ms  (available-models agent)
        cur (model-obj agent)
        i   (first (keep-indexed (fn [i m] (when (and (= (:id m) (:id cur)) (= (:provider m) (:provider cur))) i)) ms))
        nxt (when (> (count ms) 1) (nth ms (mod (inc (or i -1)) (count ms))))]
    (when (and nxt (set-model! agent (:provider nxt) (:id nxt)))
      {:model (model-obj agent) :thinkingLevel (or @(:thinking-level agent) "off") :isScoped false})))

(defn- ^:async run-bash
  "pi's BashResult for `command`, run the way `!cmd` runs in the TUI. Unless
   the client excludes it, the output joins the context as the user's own
   message, as pi's bash execution does."
  [agent command exclude?]
  (let [r   (js-await (editor-bash/run-bash! agent command))
        out (editor-bash/format-bash-output r)]
    (when-not exclude?
      ((:dispatch! (:store agent)) :message-added
                                   {:message {:role "user" :content (str "$ " command "\n" out)}}))
    {:output (str (or (:stdout r) "") (when (seq (:stderr r)) (str (when (seq (:stdout r)) "\n") (:stderr r))))
     :exitCode (:exit-code r) :cancelled (boolean (:blocked? r)) :truncated false}))

(defn ^:async handle-line
  "Dispatch one JSONL command line. Every branch must emit a response (or resolve a
   pending dialog) — the elisp blocks on the id otherwise."
  [agent st line]
  (try
    (let [cmd  (js/JSON.parse line)
          type (.-type cmd)]
      (case type
        "prompt"
        ;; Guard against overlapping runs: a prompt arriving mid-turn would start
        ;; a second `run` sharing the same state atoms and interleave deltas.
        ;; Queue it the way the client asked (pi's streamingBehavior), as a
        ;; follow-up by default.
        (let [msg {:role "user" :content (content/prompt-content (.-message cmd) (content/cmd-attachments cmd))}]
          (if @(:running? st)
            (do (if (= "steer" (.-streamingBehavior cmd)) (steer agent msg) (follow-up agent msg))
                (write-response! cmd {:disposition "queued"}))
            (do (start-run! agent st (:content msg))
                (write-response! cmd {:disposition "started"}))))

        "steer"
        (do (steer agent {:role "user" :content (content/prompt-content (.-message cmd) (content/cmd-attachments cmd))})
            (write-response! cmd {:disposition "queued"}))

        "follow_up"
        (let [c (content/prompt-content (.-message cmd) (content/cmd-attachments cmd))]
          (if @(:running? st)
            (do (follow-up agent {:role "user" :content c})
                (write-response! cmd {:disposition "queued"}))
            (do (start-run! agent st c)
                (write-response! cmd {:disposition "handled"}))))

        "clear_queue"
        ;; Remove queued steering and follow-up messages and hand their text
        ;; back, so the frontend can show what was dropped.
        (write-response! cmd (drain-queues! agent))

        "abort"
        ;; Abort the in-flight turn AND release any pending permission dialog so
        ;; the awaiting gate (emit-collect, no timeout) can't hang post-abort.
        (do (try (.abort @(:abort-controller agent)) (catch :default _ nil))
            (drain-pending-ui! st #js {:confirmed false :cancelled true})
            (write-response! cmd))

        "get_state"
        (write-response! cmd (get-state-data agent st))

        "get_commands"
        (write-response! cmd {:commands (commands-list agent)})

        "get_available_models"
        (write-response! cmd {:models (available-models agent)})

        "get_messages"
        (write-response! cmd {:messages (pi-messages agent)})

        "get_last_assistant_text"
        (write-response! cmd {:text (last-assistant-text agent)})

        "get_session_stats"
        (write-response! cmd (session-stats agent))

        "set_model"
        ;; Without a provider, the path below decides which one the id belongs to.
        (if (and (.-modelId cmd) (.-provider cmd) (set-model! agent (.-provider cmd) (.-modelId cmd)))
          (write-response! cmd (model-obj agent))
          (do (when-let [m (.-modelId cmd)]
                (set! (.-model (:config agent)) m)
              ;; A client may send a model without a provider. Leaving the
              ;; PREVIOUS provider name attached to the NEW id produced a
              ;; provider-qualified key for a pair that never existed, and
              ;; pricing, the context window, compaction and the gateway
              ;; overhead all key off it. Keep the old name only if the
              ;; registry actually knows that pair; otherwise drop it and let
              ;; the key degrade to a bare id, which is merely ambiguous
              ;; rather than wrong.
                (when-not (.-provider cmd)
                  (let [prov (aget (:config agent) "active-provider-name")
                        mr   (:model-registry agent)
                        known? (and mr (seq (str (or prov "")))
                                    (some? ((:get mr) (str prov "/" m))))]
                    (when-not known?
                      (aset (:config agent) "active-provider-name" nil)))))
              (when-let [p (.-provider cmd)]
                (aset (:config agent) "active-provider-name" p))
              (write-response! cmd (model-obj agent))))

        "cycle_model"
        (write-response! cmd (cycle-model! agent))

        "get_available_thinking_levels"
        (write-response! cmd {:levels thinking/levels})

        ;; nyma's loop injects every queued steering message at the next step
        ;; and drains follow-ups one per turn; there is no other mode to set.
        "set_steering_mode"
        (if (= "all" (.-mode cmd))
          (write-response! cmd)
          (write-error! cmd "nyma delivers steering messages all at once"))

        "set_follow_up_mode"
        (if (= "one-at-a-time" (.-mode cmd))
          (write-response! cmd)
          (write-error! cmd "nyma delivers follow-ups one at a time"))

        "set_auto_compaction"
        (if-let [s (:settings agent)]
          (let [cur (or (:compaction (compaction/resolve-settings s)) {})]
            ((:set-override s) :compaction (assoc cur :enabled (boolean (.-enabled cmd))))
            (write-response! cmd))
          (write-error! cmd "no settings"))

        ;; nyma retries a failed request inside the turn; there is no toggle,
        ;; and nothing waits between attempts for abort_retry to cut short.
        "set_auto_retry"
        (write-error! cmd "nyma's provider retry cannot be switched off")

        "abort_retry"
        (write-response! cmd)

        "bash"
        (write-response! cmd (js-await (run-bash agent (str (.-command cmd)) (.-excludeFromContext cmd))))

        "abort_bash"
        (write-error! cmd "abort_bash is not supported")

        "set_thinking_level"
        (let [level (or (.-level cmd) "off")]
          (reset! (:thinking-level agent) level)
          (write-response! cmd)
          (write-event! {:type "thinking_level_changed" :level level}))

        "cycle_thinking_level"
        (let [nl (next-thinking @(:thinking-level agent))]
          (reset! (:thinking-level agent) nl)
          (write-response! cmd {:level nl})
          (write-event! {:type "thinking_level_changed" :level nl}))

        ;; Both go through the same reseed the slash commands use: switching
        ;; the file alone left the old conversation in state, kept the old
        ;; session's skill grants and tools alive, and re-appended those turns
        ;; into the new file on the next turn.
        "new_session"
        (if (try (builtins/replay-session! agent (new-session-path)) true
                 (catch :default _e false))
          (write-response! cmd {:cancelled false})
          (write-error! cmd "new_session failed"))

        "switch_session"
        (if (and (fs/existsSync (str (.-sessionPath cmd)))
                 (try (builtins/replay-session! agent (pi-mirror/nyma-session (.-sessionPath cmd))) true
                      (catch :default _e false)))
          (write-response! cmd {:cancelled false})
          (write-error! cmd "switch_session failed"))

        "set_session_name"
        (if (call-session agent :set-session-name (.-name cmd))
          (do (write-event! {:type "session_info_changed" :name (.-name cmd)})
              (write-response! cmd))
          (write-error! cmd "set_session_name failed"))

        "fork"
        ;; A ctx with a quiet notify: without one it logs to stdout, the protocol.
        (if (try (builtins/branch-at! agent #js {:ui #js {:notify (fn [_ _] nil)}}
                                      (session-of agent) (.-entryId cmd) "")
                 true
                 (catch :default _e false))
          (write-response! cmd {:text ""})
          (write-error! cmd "fork failed"))

        "get_fork_messages"
        (let [sess (session-of agent)
              entries (when sess (try ((:get-entries sess)) (catch :default _ nil)))]
          (write-response! cmd {:messages (mapv (fn [e] {:entryId (or (:id e) "")
                                                         :text (content->text (:content e))})
                                                (or entries []))}))

        "compact"
        (if-let [sess (session-of agent)]
          (let [r (js-await
                   (compaction/compact sess (:model (:config agent)) (:events agent)
                                       (merge (compaction/settings->opts (compaction/resolve-settings (:settings agent)))
                                              {:model-registry (:model-registry agent)
                                               :state-atom     (:state agent)
                                               :force?         true
                                               :custom-instructions (.-customInstructions cmd)
                                               :model-key      (model-info/config-model-key (:config agent))})))]
            (if r
              (write-response! cmd {:summary "" :firstKeptEntryId ""
                                    :tokensBefore (:before r) :estimatedTokensAfter (:after r)})
              (write-error! cmd "Nothing to compact")))
          (write-error! cmd "no session"))

        "export_html"
        (let [sess (session-of agent)
              file (when sess (try ((:get-file-path sess)) (catch :default _ nil)))
              out  (or (.-outputPath cmd)
                       (when file (str/replace file #"\.jsonl$" ".html"))
                       (str (js/process.cwd) "/session.html"))]
          (fs/writeFileSync out (share/messages->html (:messages @(:state agent))
                                                      (or (when sess ((:get-session-name sess))) "session")))
          (write-response! cmd {:path out}))

        "extension_ui_response"
        (let [id (.-id cmd)
              resolver (get @(:pending-ui st) id)]
          (when resolver
            (swap! (:pending-ui st) dissoc id)
            (resolver cmd)))

        ;; Unknown command — still answer so the elisp doesn't hang on the id.
        (write-error! cmd (str "Unknown command: " type))))
    (catch :default e
      ;; A command that throws still answers, with the error: a client
      ;; waiting on the id would otherwise hang (set_model to a provider with
      ;; no key throws, and ThinkRail timed out session.create on it).
      (if-let [cmd (try (js/JSON.parse line) (catch :default _ nil))]
        (write-error! cmd (or (.-message e) (str e)))
        (write-error! #js {:type "parse"} (str "Failed to parse command: " (.-message e)))))))

;; ── start ──────────────────────────────────────────────────────

(defn new-st
  "The translator's mutable state for one pi-rpc channel."
  []
  {:scan          (atom nil)   ; inline <think> across chunks
   :msg           (atom nil)   ; the open assistant message
   :text-i        (atom nil)   ; its open text block
   :think-i       (atom nil)   ; its open thinking block
   :results       (atom [])    ; toolResult messages for it
   :calls-done    (atom #{})   ; its toolCall ids whose arguments are complete
   :msg-start     (atom nil)   ; when it opened
   ;; pi leads a session with its system message. `--mode pi-rpc` frontends
   ;; store what they get, and ThinkRail keeps its own system prompt.
   :system-sent?  (atom true)
   :run-msgs      (atom [])    ; the run's messages, for agent_end
   :streaming?    (atom false)
   :running?      (atom false)
   :compacting?   (atom false)
   ;; Is a pi `turn_start` currently unmatched? nyma's turn_start is
   ;; per run-loop iteration while turn_end is per STEP, so the two are
   ;; not a pair upstream — this makes them one on the wire.
   :turn-open?    (atom false)
   :pending-ui    (atom {})})

(defn ^:async start-json
  "`--mode json`: pi's session header, then the RPC event stream for one run."
  [agent prompt]
  (let [st    (new-st)
        _     (reset! (:system-sent? st) false)
        unsub (subscribe-events! agent st)
        sess  @(:session agent)
        file  (when sess ((:get-file-path sess)))]
    (write-event! {:type "session" :version 3
                   :id (or (shape/session-id file) (str (js/crypto.randomUUID)))
                   :timestamp (.toISOString (js/Date.))
                   :cwd (js/process.cwd)})
    (js-await (start-run! agent st prompt))
    (unsub)))

(defn ^:async start
  "Enter pi-rpc mode: wire the event translator and the RPC-backed ui, then read
   JSONL commands from stdin until EOF. Returns a cleanup thunk.
   `on-eof` runs after stdin closes; cli exits there, as pi's rpc mode does,
   so a frontend that dies does not leave the agent (and its MCP servers)
   running."
  [agent & [{:keys [on-eof send-system?]}]]
  (let [st         (new-st)
        _          (when send-system? (reset! (:system-sent? st) false))
        unsub      (subscribe-events! agent st)]
    ;; Layered over the headless ui, like ACP's, so methods with no RPC form
    ;; (overlays, custom components) stay the no-ops they were.
    (when-let [api (.-extension_api agent)]
      (aset api "ui" (js/Object.assign #js {} (.-ui api) (rpc-ui st))))
    ;; Not node:readline: it also splits on U+2028/U+2029, which are legal
    ;; inside a JSON string and routine in model output, so one record arrived
    ;; as two unparseable fragments. See agent.utils.jsonl-stdin.
    (let [stop-reading
          (read-lines! js/process.stdin
                       (fn [line] (handle-line agent st line))
                       ;; On stdin EOF (the frontend exited) release any pending
                       ;; dialog so a wedged permission gate can't keep the
                       ;; process alive.
                       (fn []
                         (drain-pending-ui! st #js {:confirmed false :cancelled true})
                         (when on-eof (on-eof))))]
      (fn []
        (unsub)
        (drain-pending-ui! st #js {:confirmed false :cancelled true})
        (stop-reading)))))
