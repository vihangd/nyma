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
        ;; Stream chunk to UI via callback
        (when-let [cb @shared/stream-callback]
          (cb text))))))

(defn- handle-thought-chunk
  "Handle agent thinking/reasoning chunks."
  [conn upd api]
  (when-let [content (.-content upd)]
    (let [text (if (string? content) content (.-text content))]
      (when text
        ;; Stream thinking chunk to UI
        (when-let [cb @shared/thought-callback]
          (cb text))
        (when-let [emit (:emit conn)]
          (emit "acp_thought" #js {:agent-key (:agent-key conn) :text text}))))))

;;; ─── Tool call handling ────────────────────────────────────

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
        ;; Stream plan to UI
        (when-let [cb @shared/plan-callback]
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
      ;; Register new ones
      (let [cmd-names (atom [])]
        (doseq [cmd commands]
          (let [name (.-name cmd)
                desc (or (.-description cmd) "")]
            (.registerCommand api name
              #js {:description desc
                   :handler    (fn [args _ctx]
                                 (let [conn (get @shared/connections agent-key)
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

(defn dispatch-notification
  "Route a session/update notification to the appropriate handler."
  [conn parsed api]
  (when (= (.-method parsed) "session/update")
    (let [upd   (.. parsed -params -update)
          utype (.-sessionUpdate upd)]
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
        nil))))
