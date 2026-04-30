(ns agent.extensions.claude-hook-bridge.events.stop
  "Stop and StopFailure hook events.

   - nyma's `agent_end` → Stop
   - nyma's `provider_error` → StopFailure (matcher = error type)

   Stop is observational in nyma (we already streamed the response;
   we can't make the model continue from a hook). We still fire it so
   logging/notification hooks (TTS, audit trails, etc.) work."
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]))

(def ^:private bridge-priority 200)

(defn- stop-payload [data]
  #js {:session_id      "session"
       :transcript_path ""
       :cwd             (js/process.cwd)
       :hook_event_name "Stop"
       :stop_reason     (str (or (.-stopReason data) "end_turn"))
       :output_tokens   (or (.-outputTokens data) 0)})

(defn- stop-failure-payload [data]
  (let [m (.-message data)
        msg (or (when (string? m) m)
                (and (object? m) (.-error m))
                "")]
    #js {:session_id      "session"
         :transcript_path ""
         :cwd             (js/process.cwd)
         :hook_event_name "StopFailure"
         :error_type      (str (or (.-errorType data) "unknown"))
         :error_message   (str msg)}))

(defn register!
  [{:keys [api hooks-map cwd]}]
  (let [events (.-events api)

        stop-handler
        (fn [data]
          (dispatch/dispatch
           {:hooks-map     hooks-map
            :event-name    "Stop"
            :discriminator nil
            :stdin-payload (stop-payload data)
            :cwd           cwd
            :api           api}))

        fail-handler
        (fn [data]
          (let [t (str (or (.-errorType data) "unknown"))]
            (dispatch/dispatch
             {:hooks-map     hooks-map
              :event-name    "StopFailure"
              :discriminator t
              :stdin-payload (stop-failure-payload data)
              :cwd           cwd
              :api           api})))]

    ((:on events) "agent_end" stop-handler bridge-priority)
    ((:on events) "provider_error" fail-handler bridge-priority)

    (fn []
      ((:off events) "agent_end" stop-handler)
      ((:off events) "provider_error" fail-handler))))
