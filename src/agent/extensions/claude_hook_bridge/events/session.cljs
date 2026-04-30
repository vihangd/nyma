(ns agent.extensions.claude-hook-bridge.events.session
  "SessionStart and SessionEnd hook events.

   nyma → CC:
     - session_start{reason=...}    → SessionStart matcher=reason
     - session_end / session_shutdown → SessionEnd matcher=reason

   SessionStart's `additionalContext` is injected as a system reminder
   on the next turn. SessionEnd is observational.

   nyma's reasons map to CC's matcher values:
     new      → startup
     fork     → resume   (closest analog)
     resume   → resume
     tree     → resume
   For SessionEnd:
     exit       → other
     sigint     → other
     user-exit  → other
     clear      → clear"
  (:require [agent.extensions.claude-hook-bridge.dispatch :as dispatch]))

(def ^:private bridge-priority 200)

(def ^:private start-reason->matcher
  {"new"    "startup"
   "fork"   "resume"
   "resume" "resume"
   "tree"   "resume"})

(def ^:private end-reason->matcher
  {"clear"      "clear"
   "exit"       "other"
   "sigint"     "other"
   "user-exit"  "other"})

(defn- payload [event-name reason]
  #js {:session_id      "session"
       :transcript_path ""
       :cwd             (js/process.cwd)
       :hook_event_name event-name
       :source          (or reason "startup")
       :reason          (or reason "other")})

(defn register!
  [{:keys [api hooks-map cwd]}]
  (let [events (.-events api)

        start-handler
        (fn [data]
          (let [r (str (or (.-reason data) "new"))
                m (or (get start-reason->matcher r) "startup")
                stdin (payload "SessionStart" m)]
            (-> (dispatch/dispatch
                 {:hooks-map     hooks-map
                  :event-name    "SessionStart"
                  :discriminator m
                  :stdin-payload stdin
                  :cwd           cwd})
                (.then (fn [merged]
                         (when (and merged (seq (:additional-context merged)))
                           ;; Surface the context as a system reminder
                           ;; injected into the next turn. We append a
                           ;; user-role message tagged so the model
                           ;; treats it as situational context.
                           (try
                             (.sendMessage api
                                           #js {:role    "user"
                                                :content (str "<system-reminder>"
                                                              (:additional-context merged)
                                                              "</system-reminder>")})
                             (catch :default _e nil))))))))

        end-handler
        (fn [data]
          (let [r (str (or (.-reason data) "exit"))
                m (or (get end-reason->matcher r) "other")
                stdin (payload "SessionEnd" m)]
            (dispatch/dispatch
             {:hooks-map     hooks-map
              :event-name    "SessionEnd"
              :discriminator m
              :stdin-payload stdin
              :cwd           cwd})))]

    ((:on events) "session_start" start-handler bridge-priority)
    ((:on events) "session_end" end-handler bridge-priority)
    ((:on events) "session_shutdown" end-handler bridge-priority)

    (fn []
      ((:off events) "session_start" start-handler)
      ((:off events) "session_end" end-handler)
      ((:off events) "session_shutdown" end-handler))))
