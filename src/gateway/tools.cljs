(ns gateway.tools
  "Gateway-common tools available to the agent in gateway mode.

   These tools give the LLM the ability to interact with the conversation channel
   itself — sending follow-up messages, showing typing indicators, uploading files,
   requesting human approval, and handing off to a human operator.

   When the config defines `gateway.projects`, the LLM also gets a `run_in_project`
   tool that dispatches actual coding work to an ACP coding agent (Claude Code,
   Gemini, etc.) running in that project's directory. The router LLM picks a
   project + agent from the natural-language inbound message; this tool does the
   subprocess and streaming.

   ─── Injection pattern ────────────────────────────────────────────────────

   Because the response context is per-request (bound to one inbound message),
   these tools capture a mutable atom that holds the current context. Call
   `set-ctx!` before each agent run so all tools see the correct context:

     (let [tool-set (create-gateway-tool-set opts)]
       ;; Before each run:
       ((:set-ctx! tool-set) current-response-ctx)
       ;; Pass tools to create-agent:
       {:tools (:tools tool-set)})

   ─── Tool metadata ────────────────────────────────────────────────────────

   All gateway tools declare :modes #{:gateway} and no :filesystem or :execution
   capabilities, so they pass the default gateway tool filter automatically."
  (:require [clojure.string :as str]
            [agent.tool-metadata :as tool-metadata]
            [agent.extensions.agent-shell.api :as as-api]))

;;; ─── Tool result helpers ──────────────────────────────────────────────

(defn- ok-result [text & [details]]
  #js {:content #js [#js {:type "text" :text (or text "ok")}]
       :details (clj->js (or details {}))})

(defn- err-result [text]
  #js {:content #js [#js {:type "text" :text text}]
       :isError true})

;;; ─── Individual tool definitions ─────────────────────────────────────

(defn make-send-message-tool
  "Tool that lets the LLM send an additional message to the conversation
   (separate from the main streaming response)."
  [ctx-atom]
  #js {:name        "send_message"
       :description "Send an additional message to the current conversation channel."
       :parameters  #js {:type       "object"
                         :properties #js {:text #js {:type        "string"
                                                     :description "Message text to send"}}
                         :required   #js ["text"]}
       :label       "Send message"
       :execute     (fn [args _ext-ctx]
                      (let [ctx @ctx-atom
                            text (or (.-text args) (get args "text") "")]
                        (if (nil? ctx)
                          (js/Promise.resolve (err-result "No active response context"))
                          (.. ((:send! ctx) {:text text})
                              (then (fn [_] (ok-result (str "Sent: " text))))
                              (catch (fn [e] (err-result (str "Send failed: " (.-message e)))))))))})

(defn make-typing-indicator-tool
  "Tool that shows or hides the typing indicator in the channel."
  [ctx-atom]
  #js {:name        "typing_indicator"
       :description "Show or hide the typing indicator in the current conversation."
       :parameters  #js {:type       "object"
                         :properties #js {:visible #js {:type        "boolean"
                                                        :description "true to show, false to hide"}}
                         :required   #js ["visible"]}
       :label       "Typing indicator"
       :execute     (fn [args _ext-ctx]
                      (let [ctx     @ctx-atom
                            visible (.-visible args)]
                        (if (nil? ctx)
                          (js/Promise.resolve (err-result "No active response context"))
                          (if-not (contains? (:capabilities ctx) :typing)
                            (js/Promise.resolve (ok-result "Channel does not support typing indicators"))
                            (.. ((:meta! ctx) (if visible :typing-start :typing-stop) {})
                                (then (fn [_] (ok-result (if visible "Typing started" "Typing stopped"))))
                                (catch (fn [e] (err-result (str "Meta failed: " (.-message e))))))))))})

(defn make-conversation-info-tool
  "Tool that returns metadata about the current conversation."
  [ctx-atom]
  #js {:name        "conversation_info"
       :description "Get information about the current conversation: channel, conversation ID, and available capabilities."
       :parameters  #js {:type "object" :properties #js {} :required #js []}
       :label       "Conversation info"
       :execute     (fn [_args _ext-ctx]
                      (let [ctx @ctx-atom]
                        (if (nil? ctx)
                          (js/Promise.resolve (err-result "No active response context"))
                          (let [info {:conversation-id (:conversation-id ctx)
                                      :channel         (:channel-name ctx)
                                      :capabilities    (vec (:capabilities ctx))}
                                text (str "Channel: " (:channel info)
                                          "\nConversation: " (:conversation-id info)
                                          "\nCapabilities: " (str/join ", " (map name (:capabilities info))))]
                            (js/Promise.resolve (ok-result text info))))))})

(defn make-handoff-to-human-tool
  "Tool that signals the gateway to transfer this conversation to a human operator."
  [ctx-atom]
  #js {:name        "handoff_to_human"
       :description "Transfer this conversation to a human operator. Use when the request is outside your capabilities or requires human judgment."
       :parameters  #js {:type       "object"
                         :properties #js {:reason  #js {:type        "string"
                                                        :description "Why the handoff is needed"}
                                          :summary #js {:type        "string"
                                                        :description "Brief summary of the conversation for the human operator"}}
                         :required   #js ["reason"]}
       :label       "Handoff to human"
       :execute     (fn [args _ext-ctx]
                      (let [ctx     @ctx-atom
                            reason  (or (.-reason args) "")
                            summary (or (.-summary args) "")]
                        (if (nil? ctx)
                          (js/Promise.resolve (err-result "No active response context"))
                          (.. ((:meta! ctx) :handoff {:reason reason :summary summary})
                              (then (fn [_]
                                      (ok-result
                                       (str "Handoff requested. Reason: " reason)
                                       {:type :handoff :reason reason :summary summary})))
                              (catch (fn [_]
                                       ;; meta! may not support :handoff on all channels;
                                       ;; fall back to sending a message
                                       ((:send! ctx)
                                        {:text (str "🔄 Transferring to a human operator.\nReason: " reason)})
                                       (ok-result "Handoff message sent")))))))})

(defn make-request-approval-tool
  "Tool that pauses execution and asks the gateway's approval pipeline to approve
   or deny a proposed action. Resolves when the human responds."
  [ctx-atom]
  #js {:name        "request_approval"
       :description "Ask for human approval before proceeding with a sensitive action. The agent will pause until approved or denied."
       :parameters  #js {:type       "object"
                         :properties #js {:action      #js {:type        "string"
                                                            :description "Description of the action to approve"}
                                          :consequence #js {:type        "string"
                                                            :description "What will happen if approved"}}
                         :required   #js ["action"]}
       :label       "Request approval"
       :execute     (fn [args _ext-ctx]
                      (let [ctx         @ctx-atom
                            action      (or (.-action args) "")
                            consequence (or (.-consequence args) "")]
                        (if (nil? ctx)
                          (js/Promise.resolve (err-result "No active response context"))
                          (.. ((:meta! ctx) :approval-request {:action action :consequence consequence})
                              (then (fn [result]
                                      (let [approved (or (and result (.-approved result))
                                                         (and result (:approved result))
                                                         false)]
                                        (if approved
                                          (ok-result "Approved — proceed with the action."
                                                     {:approved true})
                                          (ok-result "Denied — do not proceed with the action."
                                                     {:approved false
                                                      :reason (or (and result (.-reason result))
                                                                  "No reason given")})))))
                              (catch (fn [_]
                                       ;; Channel doesn't support approval; default deny for safety
                                       (ok-result "Approval channel unavailable — denying for safety."
                                                  {:approved false})))))))})

;;; ─── run_in_project — multi-project routing tool ─────────────────────

(defn- gateway-spawn-api
  "Build the minimal `api`-shaped object that `agent-shell.acp.pool` needs to
   spawn ACP subprocesses. The interactive UI passes the full scoped extension
   API here; the gateway has no extensions to scope, so we provide just
   `.spawn` (the only required method) and stub the optional `.emitGlobal` /
   `.ui` so guard checks in pool.cljs short-circuit cleanly."
  []
  #js {:spawn      (fn [cmd args opts]
                     (let [cmd-args (into [cmd] (or (when args (vec args)) []))
                           js-opts  (or opts #js {})
                           proc     (js/Bun.spawn (clj->js cmd-args)
                                                  #js {:cwd    (or (.-cwd js-opts) (js/process.cwd))
                                                       :stdout "pipe"
                                                       :stderr "pipe"
                                                       :stdin  "pipe"
                                                       :env    (or (.-env js-opts) js/process.env)})]
                       #js {:pid    (.-pid proc)
                            :stdin  (.-stdin proc)
                            :stdout (.-stdout proc)
                            :stderr (.-stderr proc)
                            :kill   (fn [& [sig]] (.kill proc (or sig "SIGTERM")))
                            :exited (.-exited proc)
                            :ref    proc}))
       :emitGlobal (fn [_ev _data] nil)
       :ui         #js {:available false}})

(defn make-run-in-project-tool
  "Tool that delegates a natural-language instruction to a coding agent running
   in a named project directory. Project must be on the gateway config's
   `gateway.projects` allow-list; agent must be on that project's allow-list.

   Streams the agent's text output through the conversation's response-ctx so
   the chat user sees progress in real time."
  [ctx-atom {:keys [projects default-agent api run-prompt-fn]}]
  #js {:name        "run_in_project"
       :description "Delegate work to a coding agent (Claude Code, Gemini CLI, etc.) running inside one of the configured projects. Use this whenever the user asks for code changes, debugging, or anything that touches files. The agent runs in the project's directory with full filesystem and shell access."
       :parameters  #js {:type       "object"
                         :properties #js {:project      #js {:type        "string"
                                                             :description "Name of the project (must be on the configured allow-list)"
                                                             :enum        (clj->js (or (keys projects) []))}
                                          :agent        #js {:type        "string"
                                                             :description "Which coding agent to use (e.g. \"claude\", \"gemini\"). Omit for the project's default."}
                                          :instructions #js {:type        "string"
                                                             :description "What you want the coding agent to do, in plain language."}}
                         :required   #js ["project" "instructions"]}
       :label       "Run in project"
       :execute     (fn [args _ext-ctx]
                      (let [ctx          @ctx-atom
                            project-name (or (.-project args) (get args "project") "")
                            agent-name   (or (.-agent args)   (get args "agent"))
                            instructions (or (.-instructions args) (get args "instructions") "")
                            project      (get projects project-name)]
                        (cond
                          (nil? ctx)
                          (js/Promise.resolve (err-result "No active response context"))

                          (str/blank? project-name)
                          (js/Promise.resolve (err-result "project is required"))

                          (str/blank? instructions)
                          (js/Promise.resolve (err-result "instructions is required"))

                          (nil? project)
                          (js/Promise.resolve
                           (err-result (str "Unknown project '" project-name
                                            "'. Configured: " (str/join ", " (keys projects)))))

                          :else
                          (let [chosen-agent (or agent-name default-agent)]
                            (if-not (contains? (:agents project) chosen-agent)
                              (js/Promise.resolve
                               (err-result (str "Agent '" chosen-agent "' is not allowed for project '"
                                                project-name "'. Allowed: "
                                                (str/join ", " (:agents project)))))
                              (-> ((or run-prompt-fn as-api/run-prompt)
                                   api
                                   {:agent     chosen-agent
                                    :cwd       (:root project)
                                    :prompt    instructions
                                    :on-stream (fn [text-delta]
                                                 (let [c @ctx-atom]
                                                   (when (and c text-delta (seq text-delta))
                                                     (try
                                                       ((:stream! c) text-delta)
                                                       (catch :default _ nil)))))})
                                  (.then (fn [result]
                                           (ok-result
                                            (or (:text result) "(no output)")
                                            {:project    project-name
                                             :agent      chosen-agent
                                             :usage      (:usage result)
                                             :tool-calls (count (or (:tool-calls result) []))})))
                                  (.catch (fn [e]
                                            (err-result (str "run_in_project failed: "
                                                             (or (.-message e) (str e))))))))))))})

;;; ─── Tool set factory ─────────────────────────────────────────────────

(defn create-gateway-tool-set
  "Create the full set of gateway-common tools bound to a shared context atom.

   Required opts:
     (none — opts may be `{}` for chat-only gateways without project routing)

   Optional opts:
     :projects      — resolved project allow-list from config/projects-from-config
     :default-agent — fallback agent key when run_in_project omits :agent

   Returns:
     :set-ctx!  — (fn [response-ctx]) call before each agent run
     :tools     — {tool-name → tool-object} map for passing to create-agent

   When :projects is non-empty, the returned tool set includes `run_in_project`
   alongside the channel-control tools. Otherwise it's the channel tools only."
  [& [opts]]
  (let [{:keys [projects default-agent run-prompt-fn]} (or opts {})
        ctx-atom    (atom nil)
        base-tools  {"send_message"      (make-send-message-tool ctx-atom)
                     "typing_indicator"  (make-typing-indicator-tool ctx-atom)
                     "conversation_info" (make-conversation-info-tool ctx-atom)
                     "handoff_to_human"  (make-handoff-to-human-tool ctx-atom)
                     "request_approval"  (make-request-approval-tool ctx-atom)}
        with-router (if (seq projects)
                      (assoc base-tools
                             "run_in_project"
                             (make-run-in-project-tool
                              ctx-atom
                              {:projects       projects
                               :default-agent  (or default-agent "claude")
                               :api            (gateway-spawn-api)
                               :run-prompt-fn  run-prompt-fn}))
                      base-tools)]
    {:set-ctx! (fn [ctx] (reset! ctx-atom ctx))
     :tools    with-router}))

;;; ─── Metadata registration ────────────────────────────────────────────

(defn register-tool-metadata!
  "Register tool_metadata entries for the gateway-common tools.
   Call once at gateway startup so tool filters and UI badges work correctly."
  []
  (tool-metadata/register-metadata!
   "send_message"
   {:read-only?   false
    :capabilities #{:channel}
    :modes        #{:gateway}
    :cost         :free
    :timeout-ms   10000})
  (tool-metadata/register-metadata!
   "typing_indicator"
   {:read-only?   false
    :capabilities #{:channel}
    :modes        #{:gateway}
    :cost         :free
    :timeout-ms   5000})
  (tool-metadata/register-metadata!
   "conversation_info"
   {:read-only?   true
    :capabilities #{:channel}
    :modes        #{:gateway}
    :cost         :free
    :timeout-ms   5000})
  (tool-metadata/register-metadata!
   "handoff_to_human"
   {:read-only?   false
    :capabilities #{:channel}
    :modes        #{:gateway}
    :cost         :free
    :timeout-ms   30000})
  (tool-metadata/register-metadata!
   "request_approval"
   {:read-only?   false
    :capabilities #{:channel}
    :modes        #{:gateway}
    :cost         :free
    :timeout-ms   300000  ;; approval may take a while
    :long-running? true})
  (tool-metadata/register-metadata!
   "run_in_project"
   {:read-only?    false
    :capabilities  #{:channel :execution :filesystem}
    :modes         #{:gateway}
    :cost          :paid
    :timeout-ms    600000  ;; ACP send-prompt default is 10 min
    :long-running? true}))
