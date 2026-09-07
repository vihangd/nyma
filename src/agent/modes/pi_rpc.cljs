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
  (:require ["node:readline" :as readline]
            [agent.loop :refer [run steer follow-up]]
            [agent.model-info :as model-info]
            [clojure.string :as str]))

;; ── stdout: the one and only protocol writer ───────────────────

(defn- write-event!
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

;; ── pi message shaping ─────────────────────────────────────────

(defn- part->pi
  "nyma content part → pi content item."
  [p]
  (cond
    (= (:type p) "text")
    {:type "text" :text (or (:text p) "")}

    (or (= (:type p) "tool-call") (= (:type p) "toolCall"))
    {:type "toolCall"
     :id (or (:toolCallId p) (:id p) "")
     :name (or (:toolName p) (:name p) "")
     :arguments (or (:args p) (:input p) (:arguments p) {})}

    :else {:type "text" :text (str (or (:text p) ""))}))

(defn- ->pi-message
  "nyma state message {:role :content} → pi message object."
  [m]
  (let [content (:content m)]
    {:role (or (:role m) "assistant")
     :content (cond
                (string? content) [{:type "text" :text content}]
                (sequential? content) (mapv part->pi content)
                :else [])
     :timestamp (js/Date.now)}))

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

(defn- assistant-msg [text]
  {:role "assistant" :content [{:type "text" :text text}] :timestamp (js/Date.now)})

(defn- subscribe-events!
  "Wire nyma events → pi wire events. Returns an unsubscribe thunk."
  [agent st]
  (let [events (:events agent)
        on     (:on events)
        off    (:off events)
        acc    (:acc st)
        idx    (:content-index st)
        handlers
        [["agent_start"
          (fn [_]
            (reset! acc "")
            (reset! idx 0)
            (reset! (:streaming? st) true)
            (write-event! {:type "agent_start"}))]

         ["message_start"
          (fn [_]
            (reset! acc "")
            (write-event! {:type "message_start" :message (assistant-msg "")}))]

         ["message_update"
          (fn [chunk]
            ;; Send only the incremental delta — the elisp appends via
            ;; assistantMessageEvent.delta. Re-sending the full accumulated
            ;; :message on every token is O(n^2) CPU + stdout. The full text
            ;; ships once at message_end. (:message is optional/guarded there.)
            ;; `.text` first: message_update carries the AI SDK v7 fullStream
            ;; part. Reading only `.textDelta` — the OBJECT stream's field —
            ;; made every delta shipped to the frontend an empty string.
            (let [d (or (.-text chunk) (.-textDelta chunk) "")]
              (swap! acc str d)
              (write-event! {:type "message_update"
                             :assistantMessageEvent {:type "text_delta"
                                                     :contentIndex @idx
                                                     :delta d}})))]

         ["message_end"
          (fn [_]
            (write-event! {:type "message_end"
                           :message (assoc (assistant-msg @acc) :stopReason "stop")})
            (swap! idx inc))]

         ["reasoning_start"
          (fn [_]
            (write-event! {:type "message_update"
                           :assistantMessageEvent {:type "thinking_start" :contentIndex @idx}}))]

         ["reasoning_delta"
          (fn [chunk]
            (let [d (or (.-textDelta chunk) (.-text chunk) (.-delta chunk) "")]
              (write-event! {:type "message_update"
                             :assistantMessageEvent {:type "thinking_delta"
                                                     :contentIndex @idx :delta d}})))]

         ["reasoning_end"
          (fn [chunk]
            (write-event! {:type "message_update"
                           :assistantMessageEvent {:type "thinking_end"
                                                   :contentIndex @idx
                                                   :content (or (.-text chunk) "")}}))]

         ["tool_execution_start"
          (fn [data]
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
            (write-event! {:type "tool_execution_end"
                           :toolCallId (:execId data)
                           :toolName (:toolName data)
                           :result {:content [{:type "text" :text (str (:result data))}]
                                    :details (or (:details data) {})}
                           :isError (boolean (:isError data))}))]

         ["before_compact"
          (fn [_]
            (reset! (:compacting? st) true)
            (write-event! {:type "compaction_start" :reason "overflow"}))]

         ["compact"
          (fn [_]
            (reset! (:compacting? st) false)
            (write-event! {:type "compaction_end"}))]

         ["agent_end"
          (fn [_]
            (reset! (:streaming? st) false)
            (write-event! {:type "agent_end" :messages []}))]]]
    (doseq [[ev h] handlers] (on ev h))
    (fn [] (doseq [[ev h] handlers] (off ev h)))))

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
  (str (.. js/process -env -HOME) "/.nyma/sessions/" (js/Date.now) ".jsonl"))

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
          (do (follow-up agent {:role "user" :content (.-message cmd)})
              (write-response! cmd))
          (do (reset! (:running? st) true)
              (write-response! cmd)
              (-> (run agent (.-message cmd))
                  (.catch (fn [e] (js/console.error "[pi-rpc] run error:" e)))
                  (.finally (fn [] (reset! (:running? st) false))))))

        "steer"
        (do (steer agent {:role "user" :content (.-message cmd)})
            (write-response! cmd))

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
        (write-response! cmd {:messages (mapv ->pi-message (:messages @(:state agent)))})

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
              (set! (.-model (:config agent)) m))
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

        "new_session"
        (if (call-session agent :switch-file (new-session-path))
          (write-response! cmd {:cancelled false})
          (write-error! cmd "new_session failed"))

        "switch_session"
        (if (call-session agent :switch-file (.-sessionPath cmd))
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

(defn ^:async start
  "Enter pi-rpc mode: wire the event translator + permission handler, then read
   JSONL commands from stdin until EOF. Returns a cleanup thunk."
  [agent]
  (let [st {:acc           (atom "")
            :content-index (atom 0)
            :streaming?    (atom false)
            :running?      (atom false)
            :compacting?   (atom false)
            :pending-ui    (atom {})}
        unsub      (subscribe-events! agent st)
        perm-h     (make-permission-handler st)
        events     (:events agent)]
    ((:on events) "permission_request" perm-h)
    (let [rl (readline/createInterface
              #js {:input js/process.stdin :output js/process.stdout :terminal false})]
      (.on rl "line" (fn [line] (handle-line agent st line)))
      ;; On stdin EOF (Emacs exited) release any pending dialog so a wedged
      ;; permission gate can't keep the process alive, then tear down.
      (.on rl "close" (fn [] (drain-pending-ui! st #js {:confirmed false :cancelled true})))
      (fn []
        (unsub)
        ((:off events) "permission_request" perm-h)
        (drain-pending-ui! st #js {:confirmed false :cancelled true})
        (.close rl)))))
