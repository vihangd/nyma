(ns gateway.loop
  "Gateway message loop — wires inbound messages to the agent and
   maps agent events back to the response context.

   ─── Data flow ────────────────────────────────────────────────────────────

   Channel adapter
     → on-message-fn (raw inbound event)
       → wire-and-run (auth check, dedup, enqueue on lane)
         → handle-message (create/reuse sdk-session, wire events, call run)
           → IResponseContext (send!, stream!, meta!)
             → Channel adapter (sends reply to platform)

   ─── Per-session agent sessions ───────────────────────────────────────────

   Each unique conversation-id gets its own sdk-session created lazily on first
   message. The session is stored in the pool's :data atom under :sdk-session.
   This means conversation history persists across messages in the same session
   (agent keeps messages in its own state atom).

   For :ephemeral policy sessions the sdk-session is cleared after each run,
   giving per-message stateless behaviour."
  (:require [gateway.session-pool :as sp]
            [gateway.streaming :as streaming]
            [gateway.pipelines :as pipelines]
            [gateway.tools :as gtools]))

(defn- get-or-init-sdk-session!
  "Return {:sdk-session :tool-set} for this conversation, creating both lazily on
   first message.  The tool-set's ctx-atom is updated before every run so gateway
   tools always see the current response context.

   `create-session-fn` is `agent.modes.sdk/create-session` (injected to avoid
   a circular require; gateway.core threads it through)."
  [pool session-key create-session-fn agent-opts]
  (let [existing (sp/get-data pool session-key :session-bundle)]
    (if (some? existing)
      (js/Promise.resolve existing)
      ;; First message for this conversation — create tool-set + sdk-session together
      (let [tool-set   (gtools/create-gateway-tool-set)
            merged-opts (update agent-opts :tools
                                (fn [existing-tools]
                                  (merge (or existing-tools {}) (:tools tool-set))))]
        (.. (create-session-fn merged-opts)
            (then (fn [s]
                    (let [bundle {:sdk-session s :tool-set tool-set}]
                      (sp/set-data! pool session-key :session-bundle bundle)
                      bundle))))))))

(defn ^:async handle-message
  "Process one inbound message within its session lane.

   Wires agent events → response-ctx, then calls (:send sdk-session).
   On completion removes event handlers and enforces the session policy.

   Args:
     pool             — session_pool map
     session-key      — conversation-id string
     msg              — inbound-message map {:text :user-id :raw ...}
     response-ctx     — IResponseContext map
     opts             — {:create-session-fn :agent-opts :streaming-policy}"
  [pool session-key msg response-ctx opts]
  (let [{:keys [create-session-fn agent-opts streaming-policy]} opts
        bundle      (js-await
                     (get-or-init-sdk-session!
                      pool session-key create-session-fn agent-opts))
        sdk-session (:sdk-session bundle)
        tool-set    (:tool-set bundle)
        ;; Point gateway tools at the current request's response context
        _           (when tool-set ((:set-ctx! tool-set) response-ctx))
        agent       (:agent sdk-session)
        events      (:events agent)
        on-evt      (:on events)
        off-evt     (:off events)
        send-fn     (:send sdk-session)
        ;; Build streaming policy handler — sends chunks via response-ctx :stream!
        sp-handler  (streaming/create-streaming-policy
                     (:stream! response-ctx)
                     (or streaming-policy :debounce))
        ;; ── Event handlers ──────────────────────────────────────────────
        on-update     (fn [chunk]
                        (when-let [delta (.-textDelta chunk)]
                          (when (seq delta)
                            ((:on-chunk sp-handler) delta))))
        on-tool-start (fn [data]
                        ((:meta! response-ctx)
                         :tool-start
                         {:name (.-toolName data) :id (.-execId data)}))
        on-tool-end   (fn [data]
                        ((:meta! response-ctx)
                         :tool-end
                         {:name (.-toolName data) :id (.-execId data)
                          :error (.-isError data)}))
        on-agent-end  (fn [data]
                        ;; Flush the streaming buffer, then signal done
                        ((:on-end sp-handler) (.-text data))
                        ((:meta! response-ctx) :done {}))]

    (on-evt "message_update"       on-update)
    (on-evt "tool_execution_start" on-tool-start)
    (on-evt "tool_execution_end"   on-tool-end)
    (on-evt "agent_end"            on-agent-end)

    (try
      ;; Signal typing indicator if the channel supports it
      (when (contains? (:capabilities response-ctx) :typing)
        (js-await ((:meta! response-ctx) :typing-start {})))

      ;; Run the agent
      (js-await (send-fn (:text msg)))

      (catch :default e
        (js/console.error
         (str "[gateway] handle-message error [" session-key "]:") e)
        (try
          (js-await ((:send! response-ctx) {:text "An error occurred. Please try again."}))
          (catch :default _ nil)))

      (finally
        ;; Always unsubscribe — prevents handler leaks on long-running gateways
        (off-evt "message_update"       on-update)
        (off-evt "tool_execution_start" on-tool-start)
        (off-evt "tool_execution_end"   on-tool-end)
        (off-evt "agent_end"            on-agent-end)
        ;; Enforce session policy
        (sp/evict-ephemeral-data! pool session-key)))))

(defn wire-and-run
  "Entry point called by channel adapters for every inbound message.

   Steps:
     1. Dedup: skip if event-id has been seen recently
     2. Auth: run the auth pipeline; drop message if denied
     3. Enqueue: push handle-message onto the conversation's serial lane

   Returns a Promise that resolves when the message has been fully processed.

   Args:
     pool          — session_pool map
     auth-pipeline — pipelines map (from create-auth-pipeline)
     msg           — inbound-message map from channel adapter
     response-ctx  — IResponseContext map
     handle-opts   — options map threaded through to handle-message"
  [pool auth-pipeline msg response-ctx handle-opts]
  (let [session-key (:conversation-id msg)
        event-id    (:event-id msg)
        already-seen? (and event-id (sp/seen-event? pool event-id))]
    (if already-seen?
      (do
        (js/console.log (str "[gateway] Dedup skip: " event-id))
        (js/Promise.resolve nil))
      (do
        (when event-id (sp/mark-seen! pool event-id))
        (.then
         (pipelines/run-auth auth-pipeline msg)
         (fn [auth-result]
           (if (:allow? auth-result)
             (sp/enqueue! pool session-key
                          (fn [] (handle-message pool session-key msg response-ctx handle-opts)))
             (do
               (js/console.log (str "[gateway] Auth denied [" session-key "]: "
                                    (or (:reason auth-result) "no reason")))
               nil))))))))
