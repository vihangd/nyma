(ns agent.extensions.claude-hook-bridge.events.user-prompt-submit
  "UserPromptSubmit hook event.

   nyma's `input_submit` event maps to CC's UserPromptSubmit. No
   matcher (always fires).

   Outbound effects:
     - decision: \"block\" → swallow the prompt, surface reason as a
       system reminder. We can't easily abort the in-flight turn from
       here in nyma's current shape, so block decisions instead pass
       the reason to the agent as a system reminder injected just
       before the prompt.
     - additionalContext → injected as a system reminder before the
       prompt, so the model sees it on this turn."
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]))

(def ^:private bridge-priority 200)

(defn- payload [prompt]
  #js {:session_id      "session"
       :transcript_path ""
       :cwd             (js/process.cwd)
       :permission_mode "default"
       :hook_event_name "UserPromptSubmit"
       :prompt          (str (or prompt ""))})

(defn register!
  [{:keys [api hooks-map cwd]}]
  (let [events (.-events api)
        handler
        (fn [data]
          (let [text (str (or (.-text data) (.-prompt data) ""))
                stdin (payload text)]
            (-> (dispatch/dispatch
                 {:hooks-map     hooks-map
                  :event-name    "UserPromptSubmit"
                  :discriminator nil
                  :stdin-payload stdin
                  :cwd           cwd})
                (.then (fn [merged]
                         (when merged
                           (let [ctx (:additional-context merged)
                                 reason (when (:decision-block? merged)
                                          (or (:decision-block-reason merged)
                                              "blocked by hook"))]
                             (when (or (seq ctx) reason)
                               (try
                                 (.sendMessage api
                                               #js {:role    "user"
                                                    :content (str "<system-reminder>"
                                                                  (str/join "\n"
                                                                            (filter seq [ctx reason]))
                                                                  "</system-reminder>")})
                                 (catch :default _e nil))))))))))]
    ((:on events) "input_submit" handler bridge-priority)
    (fn []
      ((:off events) "input_submit" handler))))
