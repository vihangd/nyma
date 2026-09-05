(ns agent.extensions.agent-shell.acp.notifications
  "Handle all ACP session/update notification types.
   Renders agent output into nyma's UI via the extension API."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.client :as client]
            [clojure.string :as str]))

;;; ─── Message chunk handling ────────────────────────────────

(defn- handle-message-chunk
  "Accumulate agent text output and stream to UI."
  [conn upd api]
  (when-let [content (.-content upd)]
    (when (= (.-type content) "text")
      (let [text (.-text content)]
        ;; Accumulate in prompt-state for final result
        (swap! (:prompt-state conn) update :text str text)
        ;; Stream chunk to UI/gateway via callback (per-conn takes precedence over global)
        (when-let [cb (or (and (:callbacks conn) (:on-stream @(:callbacks conn)))
                          @shared/stream-callback)]
          (cb text))
        ;; Emit acp_message so extensions can observe agent output
        (when-let [emit (:emit conn)]
          (emit "acp_message" #js {:agent-key (:agent-key conn)
                                   :text      text
                                   :type      "chunk"}))))))

(defn- handle-thought-chunk
  "Handle agent thinking/reasoning chunks."
  [conn upd api]
  (when-let [content (.-content upd)]
    (let [text (if (string? content) content (.-text content))]
      (when text
        ;; Stream thinking chunk to UI (per-conn takes precedence)
        (when-let [cb (or (and (:callbacks conn) (:on-thought @(:callbacks conn)))
                          @shared/thought-callback)]
          (cb text))
        (when-let [emit (:emit conn)]
          (emit "acp_thought" #js {:agent-key (:agent-key conn) :text text}))))))

;;; ─── Tool call handling ────────────────────────────────────

(defn tool-path
  "First location path an ACP tool call reports, or nil.

   `locations` is what the spec provides for \"follow-along\" — which file the
   agent is on right now — and it is more useful in a one-line renderer than
   the title, which is prose."
  [upd]
  (let [locs (.-locations upd)]
    (when (and locs (pos? (.-length locs)))
      (.-path (aget locs 0)))))

(defn- handle-tool-call
  "Handle tool_call notification — tool invocation start."
  [conn upd api]
  (let [tool-id (.-toolCallId upd)
        title   (.-title upd)
        kind    (.-kind upd)
        status  (.-status upd)]
    ;; Track in prompt state
    (swap! (:prompt-state conn) update :tool-calls conj
           {:id tool-id :title title :kind kind :status status})
    ;; Some agents report a terminal status on the initial call.
    (shared/record-tool-call! (:pool-key conn) kind status)
    ;; Render it. Without this the whole working period of a turn is blank —
    ;; the events below have no subscriber anywhere in the repo.
    (when-let [cb (or (and (:callbacks conn) (:on-tool @(:callbacks conn)))
                      @shared/tool-callback)]
      (cb {:id tool-id :title title :kind kind :status status
           :path (tool-path upd)}))
    ;; Surface it on the status line too — during a long tool call the pane is
    ;; static, and the one row that is always visible should say what is
    ;; running.
    (shared/update-agent-state! (:agent-key conn) :current-tool
                                {:kind kind :title title :path (tool-path upd)})
    ;; Emit for UI rendering (reuse nyma's tool execution events)
    (when-let [emit (:emit conn)]
      (emit "acp_tool_start"
            #js {:agent-key   (:agent-key conn)
                 :tool-id     tool-id
                 :title       (or title "tool")
                 :kind        kind
                 :status      status}))))

(defn- handle-tool-call-update
  "Handle tool_call_update notification — status/result updates."
  [conn upd api]
  (let [tool-id (.-toolCallId upd)
        status  (.-status upd)
        content (.-content upd)]
    ;; Update tracked tool call
    (swap! (:prompt-state conn) update :tool-calls
           (fn [calls]
             (mapv #(if (= (:id %) tool-id)
                      (cond-> (assoc % :status status)
                        content (assoc :content (if (string? content) content
                                                    (js/JSON.stringify content))))
                      %)
                   calls)))
    ;; The usual path: the call lands here with its final status. A denied or
    ;; failed write must NOT count as a change to the tree.
    (let [kind (some (fn [c] (when (= (:id c) tool-id) (:kind c)))
                     (:tool-calls @(:prompt-state conn)))]
      (shared/record-tool-call! (:pool-key conn) kind status))
    ;; Render the change in place. Every field but toolCallId is optional on an
    ;; update, so nils here mean "unchanged" — the renderer merges rather than
    ;; replaces, or a status-only update would blank the title.
    (when-let [cb (or (and (:callbacks conn) (:on-tool @(:callbacks conn)))
                      @shared/tool-callback)]
      (cb {:id tool-id :title (.-title upd) :kind (.-kind upd)
           :status status :path (tool-path upd)}))
    ;; Clear the status-line tool once it settles, so a finished call does not
    ;; sit there looking live.
    (when (contains? #{"completed" "failed"} (str status))
      (shared/update-agent-state! (:agent-key conn) :current-tool nil))
    ;; Emit for UI
    (when-let [emit (:emit conn)]
      (emit "acp_tool_update"
            #js {:agent-key (:agent-key conn)
                 :tool-id   tool-id
                 :status    status
                 :content   content}))))

;;; ─── Plan handling ─────────────────────────────────────────

(defn- handle-plan
  "Handle plan notification — agent's execution plan."
  [conn upd api]
  (let [entries (when-let [e (.-entries upd)] (seq e))]
    (when entries
      (let [plan-data (mapv (fn [entry]
                              {:content  (.-content entry)
                               :priority (.-priority entry)
                               :status   (.-status entry)})
                            entries)]
        (shared/update-agent-state! (:agent-key conn) :plan plan-data)
        ;; Stream plan to UI (per-conn takes precedence)
        (when-let [cb (or (and (:callbacks conn) (:on-plan @(:callbacks conn)))
                          @shared/plan-callback)]
          (cb plan-data))
        (when-let [emit (:emit conn)]
          (emit "acp_plan" (clj->js {:agent-key (:agent-key conn) :entries plan-data})))))))

;;; ─── Command updates ───────────────────────────────────────

(defn- handle-commands-update
  "Handle available_commands_update — register agent slash commands in nyma."
  [conn upd api]
  (let [commands (when-let [c (.-commands upd)] (seq c))
        agent-key (:agent-key conn)]
    (when commands
      ;; Unregister previous dynamic commands
      (doseq [cmd-name (shared/get-agent-state agent-key :dynamic-commands)]
        (try (.unregisterCommand api cmd-name) (catch :default _ nil)))
      ;; Register new ones. Tag each with :forward-to so that the
      ;; /help splitter and the slash picker can tell agent-provided
      ;; commands apart from nyma's built-ins.
      (let [cmd-names (atom [])]
        (doseq [cmd commands]
          (let [name (.-name cmd)
                desc (or (.-description cmd) "")]
            (.registerCommand api name
                              #js {:description desc
                                   :forward-to  "agent-shell"
                                   :agent-key   agent-key
                                   :handler    (fn [args _ctx]
                                                 (let [conn (shared/find-conn-by-agent agent-key)
                                                       text (str "/" name
                                                                 (when (seq args) (str " " (str/join " " args))))]
                                                   (when conn
                                                     (client/send-prompt conn text))))})
            (swap! cmd-names conj name)))
        (shared/update-agent-state! agent-key :dynamic-commands @cmd-names)
        ;; Emit event so other extensions can react to command changes
        (when-let [emit (:emit conn)]
          (emit "acp_commands_update"
                (clj->js {:agent-key agent-key :commands @cmd-names})))))))

;;; ─── Mode/config updates ───────────────────────────────────

(defn- handle-mode-update
  "Handle current_mode_update — agent changed mode."
  [conn upd _api]
  (let [mode-id (.-modeId upd)]
    (shared/update-agent-state! (:agent-key conn) :mode mode-id)
    (when-let [emit (:emit conn)]
      (emit "acp_mode_change" #js {:agent-key (:agent-key conn) :mode mode-id}))))

(defn- handle-config-update
  "Handle config_option_update — extract model list and other config."
  [conn upd _api]
  (let [options (when-let [o (.-configOptions upd)] (seq o))]
    (when options
      (shared/update-agent-state! (:agent-key conn) :config-options (shared/js->clj* options))
      ;; Extract model list if present
      (doseq [opt options]
        (when (= (.-configId opt) "model")
          (when-let [values (.-values opt)]
            (let [models (mapv (fn [v]
                                 {:id      (.-value v)
                                  :display (or (.-displayName v) (.-label v) (.-value v))})
                               (seq values))]
              (shared/update-agent-state! (:agent-key conn) :models models)))
          ;; Also track current model value
          (when-let [current (.-value opt)]
            (shared/update-agent-state! (:agent-key conn) :model current)))))))

;;; ─── Usage updates ─────────────────────────────────────────

(defn- handle-usage-update
  "Handle usage_update — context window usage and cost."
  [conn upd _api]
  (let [used (.-used upd)
        size (.-size upd)
        cost (when-let [c (.-cost upd)]
               {:amount   (.-amount c)
                :currency (or (.-currency c) "USD")})]
    (shared/update-agent-state! (:agent-key conn) :usage
                                (cond-> {}
                                  used (assoc :used used)
                                  size (assoc :size size)
                                  cost (assoc :cost cost)))
    (when-let [emit (:emit conn)]
      (emit "acp_usage" #js {:agent-key (:agent-key conn)
                             :used used :size size
                             :cost (when cost (clj->js cost))}))))

;;; ─── Session info ──────────────────────────────────────────

(defn- handle-session-info
  "Handle session_info_update — session title and metadata."
  [conn upd _api]
  (when-let [title (.-title upd)]
    (shared/update-agent-state! (:agent-key conn) :session-title title)))

;;; ─── Main dispatcher ───────────────────────────────────────

(defn- chunk-text
  "Text out of an ACP content block, tolerating the string shorthand."
  [upd]
  (let [c (.-content upd)]
    (cond
      (nil? c)      nil
      (string? c)   c
      :else         (when (= (.-type c) "text") (.-text c)))))

(defn replay-turn!
  "Record one replayed turn under `role`, and hand it to the renderer.

   Replay is the ONE context where `user_message_chunk` carries data — live it
   is dropped (\"replay only\"), because the prompt is recorded at the call
   site instead. So this is the only path that can rebuild what was said, and
   without it /plan-capture is empty after a resume.

   Chunks arrive fragmented; `append-turn!` appends per chunk rather than per
   message, which is acceptable because the transcript is read as a
   conversation and consecutive same-role turns concatenate naturally."
  [conn role upd]
  (when-let [text (chunk-text upd)]
    (when (seq (str text))
      (shared/append-turn! (:pool-key conn) role text)
      (when-let [cb @shared/replay-callback]
        (cb {:role role :text text})))))

(defn dispatch-notification
  "Route a session/update notification to the appropriate handler."
  [conn parsed api]
  (when (= (.-method parsed) "session/update")
    (let [upd   (.. parsed -params -update)
          utype (.-sessionUpdate upd)]
      (if (and (:replaying? conn) @(:replaying? conn))
        ;; History, not activity. Rebuild the conversation and ignore
        ;; everything else: a replayed tool_call must not count toward the
        ;; edit warning, and a replayed plan must not overwrite the live one.
        (case utype
          "user_message_chunk"  (replay-turn! conn "user" upd)
          "agent_message_chunk" (replay-turn! conn "assistant" upd)
          "session_info_update" (handle-session-info conn upd api)
          nil)
        (case utype
        "agent_message_chunk"       (handle-message-chunk conn upd api)
        "agent_thought_chunk"       (handle-thought-chunk conn upd api)
        "user_message_chunk"        nil ;; replay only
        "tool_call"                 (handle-tool-call conn upd api)
        "tool_call_update"          (handle-tool-call-update conn upd api)
        "plan"                      (handle-plan conn upd api)
        "available_commands_update" (handle-commands-update conn upd api)
        "current_mode_update"       (handle-mode-update conn upd api)
        "config_option_update"      (handle-config-update conn upd api)
        "usage_update"              (handle-usage-update conn upd api)
        "session_info_update"       (handle-session-info conn upd api)
        nil)))))
