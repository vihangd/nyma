(ns gateway.tools
  "Gateway-common tools available to the agent in gateway mode.

   These tools give the LLM the ability to interact with the conversation channel
   itself — sending follow-up messages, showing typing indicators, uploading files,
   requesting human approval, and handing off to a human operator.

   ─── Injection pattern ────────────────────────────────────────────────────

   Because the response context is per-request (bound to one inbound message),
   these tools capture a mutable atom that holds the current context. Call
   `set-ctx!` before each agent run so all tools see the correct context:

     (let [tool-set (create-gateway-tool-set)]
       ;; Before each run:
       ((:set-ctx! tool-set) current-response-ctx)
       ;; Pass tools to create-agent:
       {:tools (:tools tool-set)})

   ─── Tool metadata ────────────────────────────────────────────────────────

   All gateway tools declare :modes #{:gateway} and no :filesystem or :execution
   capabilities, so they pass the default gateway tool filter automatically."
  (:require [clojure.string :as str]
            [agent.tool-metadata :as tool-metadata]))

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

;;; ─── Tool set factory ─────────────────────────────────────────────────

(defn create-gateway-tool-set
  "Create the full set of gateway-common tools bound to a shared context atom.

   Returns:
     :set-ctx!  — (fn [response-ctx]) call before each agent run
     :tools     — {tool-name → tool-object} map for passing to create-agent

   Example usage in gateway.loop:
     (let [ts (create-gateway-tool-set)]
       ((:set-ctx! ts) response-ctx)
       (create-session {:tools (:tools ts) ...}))"
  []
  (let [ctx-atom (atom nil)]
    {:set-ctx! (fn [ctx] (reset! ctx-atom ctx))
     :tools    {"send_message"      (make-send-message-tool ctx-atom)
                "typing_indicator"  (make-typing-indicator-tool ctx-atom)
                "conversation_info" (make-conversation-info-tool ctx-atom)
                "handoff_to_human"  (make-handoff-to-human-tool ctx-atom)
                "request_approval"  (make-request-approval-tool ctx-atom)}}))

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
    :long-running? true}))
