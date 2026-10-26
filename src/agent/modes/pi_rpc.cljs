(ns agent.modes.pi-rpc
  "Adapter mode that speaks the pi-coding-agent Emacs frontend's JSONL-over-stdio
   RPC protocol, so the existing `pi-coding-agent.el` package can drive nyma with
   no .el edits (point `pi-coding-agent-extra-args` at `--mode pi-rpc`).

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
    {:id mid :name mid :provider prov :api prov
     :contextWindow (or cw 100000)
     :maxTokens (or (aget cfg "max-tokens") (.-maxTokens cfg) 8192)}))

(defn- session-of [agent]
  (some-> (:session agent) deref))

(defn- get-state-data [agent st]
  (let [s    @(:state agent)
        sess (session-of agent)
        file (when sess (try ((:get-file-path sess)) (catch :default _ nil)))
        name (when sess (try ((:get-session-name sess)) (catch :default _ nil)))]
    {:model (model-obj agent)
     :thinkingLevel (or @(:thinking-level agent) "off")
     :isStreaming @(:streaming? st)
     :isCompacting @(:compacting? st)
     :sessionId (or name file "session")
     :sessionFile (or file "")
     :messageCount (count (:messages s))
     :pendingMessageCount (count @(:follow-queue agent))}))

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

(defn- update! [st ame]
  (let [m @(:msg st)]
    (write-event! {:type "message_update" :message m
                   :assistantMessageEvent (assoc ame :partial m)})))

(defn- ensure-msg!
  "The open assistant message, starting one (and its message_start) if none."
  [agent st]
  (when-not @(:msg st)
    ;; nyma's turn_start is per loop iteration, not per step
    (open-turn! st)
    (reset! (:msg st) (shape/assistant-message (model-meta agent)))
    (write-event! {:type "message_start" :message @(:msg st)})))

(defn- add-block!
  "Append `block` to the open message; returns its index."
  [agent st block]
  (ensure-msg! agent st)
  (let [[m i] (shape/add-block @(:msg st) block)]
    (reset! (:msg st) m)
    i))

(defn- add-tool-call!
  "Add a toolCall block for `id` unless the message already has one. Both the
   stream's tool-call part and tool_execution_start lead here: the AI SDK can
   start running a tool before the loop has read its tool-call part."
  [agent st id tool-name args]
  (when (and (seq (str id))
             (nil? (some-> @(:msg st) (shape/tool-call-index id))))
    (let [block {:type "toolCall" :id (str id) :name (str tool-name) :arguments (or args {})}
          i     (add-block! agent st block)]
      (update! st {:type "toolcall_start" :contentIndex i})
      (update! st {:type "toolcall_end" :contentIndex i :toolCall block}))))

(defn- record! [st m] (swap! (:run-msgs st) conj m))

(defn- close-msg!
  "End the open assistant message with `fields` merged in, then the toolResult
   messages of its tool calls. Returns [message results], or nil if none open."
  [st fields]
  (when-let [m @(:msg st)]
    (let [final   (merge m fields)
          results @(:results st)]
      (reset! (:msg st) nil)
      (reset! (:text-i st) nil)
      (reset! (:think-i st) nil)
      (reset! (:results st) [])
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

(defn- aborted? [agent]
  (boolean (some-> agent :abort-controller deref .-signal .-aborted)))

(defn subscribe-events!
  "Wire nyma events → pi wire events. Returns an unsubscribe thunk.

   One assistant message per step: nyma's text-start/text-end mark a text
   PART, so they become text_start/text_end inside the message, which closes
   at the step's turn_end. Every message_update carries the whole message so
   far as `partial` — pi does, and ThinkRail renders only that. It is O(n²)
   on the wire for a long reply, as it is in pi; the Emacs frontend reads
   `delta` and ignores it."
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
        handlers
        [["agent_start"
          (fn [_]
            (reset! (:streaming? st) true)
            (write-event! {:type "agent_start"}))]

         ["message_start"
          (fn [_] (text-start!))]

         ["message_update"
          (fn [chunk]
            ;; `.text` first: message_update carries the AI SDK v7 fullStream
            ;; part. `.textDelta` is the OBJECT stream's field.
            (let [d (or (.-text chunk) (.-textDelta chunk) "")]
              (when (nil? @(:text-i st)) (text-start!))
              (let [i @(:text-i st)]
                (swap! (:msg st) #(shape/append-at % i :text d))
                (update! st {:type "text_delta" :contentIndex i :delta d}))))]

         ["message_end"
          (fn [_]
            (when-let [i @(:text-i st)]
              (reset! (:text-i st) nil)
              (update! st {:type "text_end" :contentIndex i
                           :content (get-in @(:msg st) [:content i :text])})))]

         ["reasoning_start"
          (fn [_]
            (let [i (add-block! agent st {:type "thinking" :thinking ""})]
              (reset! (:think-i st) i)
              (update! st {:type "thinking_start" :contentIndex i})))]

         ["reasoning_delta"
          (fn [chunk]
            (let [d (or (.-textDelta chunk) (.-text chunk) (.-delta chunk) "")]
              (when (nil? @(:think-i st))
                (reset! (:think-i st) (add-block! agent st {:type "thinking" :thinking ""})))
              (let [i @(:think-i st)]
                (swap! (:msg st) #(shape/append-at % i :thinking d))
                (update! st {:type "thinking_delta" :contentIndex i :delta d}))))]

         ["reasoning_end"
          (fn [_]
            (when-let [i @(:think-i st)]
              (reset! (:think-i st) nil)
              (update! st {:type "thinking_end" :contentIndex i
                           :content (get-in @(:msg st) [:content i :thinking])})))]

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
                           :partialResult {:content [{:type "text" :text (str (or (:data data) ""))}]
                                           :details {}}}))]

         ["tool_execution_end"
          (fn [data]
            (let [id (:execId data)]
              (write-event! {:type "tool_execution_end"
                             :toolCallId id
                             :toolName (:toolName data)
                             :result {:content [{:type "text" :text (str (:result data))}]
                                      :details (or (:details data) {})}
                             :isError (boolean (:isError data))})
              (if (contains? @nested id)
                (swap! nested disj id)
                (swap! (:results st) conj
                       (shape/tool-result-message {:id id :name (:toolName data)
                                                   :text (:result data) :details (:details data)
                                                   :error? (:isError data)})))))]

         ["before_compact"
          (fn [_]
            (reset! (:compacting? st) true)
            (write-event! {:type "compaction_start" :reason "overflow"}))]

         ["compact"
          (fn [_]
            (reset! (:compacting? st) false)
            (write-event! {:type "compaction_end"}))]

         ;; A turn is one assistant response plus the tool calls it caused.
         ;; nyma emits turn_start once per run-loop iteration and turn_end once
         ;; per STEP (from onStepFinish), so a 5-tool turn upstream is 1 start
         ;; and 5 ends. pi's contract is a pair, so the wire alternates
         ;; strictly: every turn_end is preceded by exactly one turn_start.
         ["turn_start"
          (fn [_] (open-turn! st))]

         ["turn_end"
          (fn [step]
            (end-turn! agent st step {:stopReason (shape/stop-reason (and step (.-finishReason step)))
                                      :usage (shape/usage step)}))]

         ["agent_end"
          (fn [_]
            ;; Open only when the run stopped mid-step: an abort, or the
            ;; stream filter giving up.
            (when @(:msg st)
              (end-turn! agent st nil {:stopReason (if (aborted? agent) "aborted" "stop")}))
            (end-run! st))]

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
            (when @(:streaming? st)
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
            (when (empty? @(:follow-queue agent))
              (write-event! {:type "agent_settled" :aborted (aborted? agent)})))]]

        ;; User messages: the prompt, and steering or follow-ups the loop
        ;; injects between steps. All reach state through the store.
        unsub-store
        (when-let [store (:store agent)]
          ((:subscribe store)
           (fn [ev _state data]
             (when (= ev :message-added)
               (let [m (:message data)]
                 (when (= "user" (:role m))
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

;; ── permission reverse-RPC ─────────────────────────────────────

(defn- make-permission-handler
  "emit-collect handler on permission_request. Pops a `confirm` dialog in Emacs and
   blocks (returns a Promise) until extension_ui_response arrives, then returns a
   concrete {decision} that composes with the mode/role policy via precedence."
  [st]
  (fn [data]
    (js/Promise.
     (fn [resolve _reject]
       (let [id   (str "perm-" (js/Date.now) "-" (.toString (js/Math.random) 36))
             tool (str (.-tool data))]
         (swap! (:pending-ui st) assoc id
                (fn [resp]
                  (resolve (if (.-confirmed resp)
                             #js {:decision "allow"}
                             #js {:decision "deny"}))))
         (write-event! {:type "extension_ui_request"
                        :id id
                        :method "confirm"
                        :title "Permission request"
                        :message (str "Allow tool: " tool "?")}))))))

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
                                  :description (or (get v :description) "")})
                     c)
      (sequential? c) (mapv (fn [v] {:name (str (or (:name v) v))
                                     :source "extension"
                                     :description (or (:description v) "")})
                            c)
      :else [])))

(defn- available-models [agent]
  ;; nyma has no central model catalogue (provider-registry :list yields provider
  ;; names, not models), so v1 returns the current model. The elisp mapcars this,
  ;; so it must be a non-empty vector — never nil.
  [(let [mo (model-obj agent)] {:id (:id mo) :name (:name mo) :provider (:provider mo)})])

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
        ;; a second `run` sharing the same accumulator/state atoms and interleave
        ;; deltas. Deliver it as a follow-up (after the current turn) instead.
        (if @(:running? st)
          (do (follow-up agent {:role "user" :content (content/prompt-content (.-message cmd) (content/cmd-attachments cmd))})
              (write-response! cmd))
          (do (reset! (:running? st) true)
              (write-response! cmd)
              (-> (run agent (content/prompt-content (.-message cmd) (content/cmd-attachments cmd)))
                  (.catch (fn [e] (js/console.error "[pi-rpc] run error:" e)))
                  (.finally (fn [] (reset! (:running? st) false))))))

        "steer"
        (do (steer agent {:role "user" :content (content/prompt-content (.-message cmd) (content/cmd-attachments cmd))})
            (write-response! cmd))

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
        (let [msgs (:messages @(:state agent))]
          (write-response! cmd {:tokens {:input 0 :output 0 :total 0 :cacheRead 0 :cacheWrite 0}
                                :cost 0
                                :userMessages (count (filter #(= (:role %) "user") msgs))
                                :toolCalls 0}))

        "set_model"
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
            (write-response! cmd (model-obj agent)))

        "set_thinking_level"
        (do (reset! (:thinking-level agent) (or (.-level cmd) "off"))
            (write-response! cmd))

        "cycle_thinking_level"
        (let [nl (next-thinking @(:thinking-level agent))]
          (reset! (:thinking-level agent) nl)
          (write-response! cmd {:level nl}))

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
        (if (try (builtins/replay-session! agent (.-sessionPath cmd)) true
                 (catch :default _e false))
          (write-response! cmd {:cancelled false})
          (write-error! cmd "switch_session failed"))

        "set_session_name"
        (if (call-session agent :set-session-name (.-name cmd))
          (write-response! cmd)
          (write-error! cmd "set_session_name failed"))

        "fork"
        (if (call-session agent :branch (.-entryId cmd))
          (write-response! cmd {:text ""})
          (write-error! cmd "fork failed"))

        "get_fork_messages"
        (let [sess (session-of agent)
              entries (when sess (try ((:get-entries sess)) (catch :default _ nil)))]
          (write-response! cmd {:messages (mapv (fn [e] {:entryId (or (:id e) "")
                                                         :text (content->text (:content e))})
                                                (or entries []))}))

        "compact"
        (write-response! cmd)

        "export_html"
        (write-response! cmd {:path ""})

        "extension_ui_response"
        (let [id (.-id cmd)
              resolver (get @(:pending-ui st) id)]
          (when resolver
            (swap! (:pending-ui st) dissoc id)
            (resolver cmd)))

        ;; Unknown command — still answer so the elisp doesn't hang on the id.
        (write-error! cmd (str "Unknown command: " type))))
    (catch :default e
      (js/console.error "[pi-rpc] handle-line error:" e))))

;; ── start ──────────────────────────────────────────────────────

(defn new-st
  "The translator's mutable state for one pi-rpc channel."
  []
  {:msg           (atom nil)   ; the open assistant message
   :text-i        (atom nil)   ; its open text block
   :think-i       (atom nil)   ; its open thinking block
   :results       (atom [])    ; toolResult messages for it
   :run-msgs      (atom [])    ; the run's messages, for agent_end
   :streaming?    (atom false)
   :running?      (atom false)
   :compacting?   (atom false)
   ;; Is a pi `turn_start` currently unmatched? nyma's turn_start is
   ;; per run-loop iteration while turn_end is per STEP, so the two are
   ;; not a pair upstream — this makes them one on the wire.
   :turn-open?    (atom false)
   :pending-ui    (atom {})})

(defn ^:async start
  "Enter pi-rpc mode: wire the event translator + permission handler, then read
   JSONL commands from stdin until EOF. Returns a cleanup thunk."
  [agent]
  (let [st         (new-st)
        unsub      (subscribe-events! agent st)
        perm-h     (make-permission-handler st)
        events     (:events agent)]
    ((:on events) "permission_request" perm-h)
    ;; Not node:readline: it also splits on U+2028/U+2029, which are legal
    ;; inside a JSON string and routine in model output, so one record arrived
    ;; as two unparseable fragments. See agent.utils.jsonl-stdin.
    (let [stop-reading
          (read-lines! js/process.stdin
                       (fn [line] (handle-line agent st line))
                       ;; On stdin EOF (the frontend exited) release any pending
                       ;; dialog so a wedged permission gate can't keep the
                       ;; process alive.
                       (fn [] (drain-pending-ui! st #js {:confirmed false :cancelled true})))]
      (fn []
        (unsub)
        ((:off events) "permission_request" perm-h)
        (drain-pending-ui! st #js {:confirmed false :cancelled true})
        (stop-reading)))))
