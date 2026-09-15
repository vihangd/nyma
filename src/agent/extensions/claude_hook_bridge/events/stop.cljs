(ns agent.extensions.claude-hook-bridge.events.stop
  "Stop and StopFailure hook events.

   - nyma's `agent_end` → Stop
   - nyma's `provider_error` → StopFailure (matcher = error type)

   Stop is observational in nyma (we already streamed the response;
   we can't make the model continue from a hook). We still fire it so
   logging/notification hooks (TTS, audit trails, etc.) work."
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]
            [agent.extensions.claude-hook-bridge.events.common :as common]))

(def ^:private bridge-priority 200)

(defn- stop-payload [api data]
  (js/Object.assign
   (common/base api "Stop")
   ;; CC's Stop input: `stop_hook_active` (true only when CC is already
   ;; continuing because a stop hook blocked — nyma never continues from
   ;; a hook, so always false) and `last_assistant_message`.
   #js {:stop_hook_active       false
        :last_assistant_message (str (or (.-text data) ""))
        ;; nyma extras. agent_end carries {:text :usage :finishReason} —
        ;; there is no top-level stopReason/outputTokens (the old reads
        ;; always yielded "end_turn" / 0).
        :stop_reason            (str (or (.-finishReason data) "end_turn"))
        :output_tokens          (or (some-> (.-usage data) .-outputTokens) 0)}))

(defn- stop-failure-payload [api data]
  (let [m (.-message data)
        msg (or (when (string? m) m)
                (and (object? m) (.-error m))
                "")]
    (js/Object.assign
     (common/base api "StopFailure")
     ;; CC's StopFailure input: `error` is the error TYPE (the matcher
     ;; value), `error_details` the message.
     #js {:error         (str (or (.-errorType data) "unknown"))
          :error_details (str msg)})))

(defn register!
  [{:keys [api hooks-atom cwd]}]
  (let [stop-handler
        (fn [data]
          (dispatch/dispatch
           {:hooks-map     @hooks-atom
            :event-name    "Stop"
            :discriminator nil
            :stdin-payload (stop-payload api data)
            :cwd           cwd
            :api           api}))

        fail-handler
        (fn [data]
          (let [t (str (or (.-errorType data) "unknown"))]
            (dispatch/dispatch
             {:hooks-map     @hooks-atom
              :event-name    "StopFailure"
              :discriminator t
              :stdin-payload (stop-failure-payload api data)
              :cwd           cwd
              :api           api})))]

    (.on api "agent_end" stop-handler bridge-priority)
    (.on api "provider_error" fail-handler bridge-priority)

    (fn []
      (.off api "agent_end" stop-handler)
      (.off api "provider_error" fail-handler))))
