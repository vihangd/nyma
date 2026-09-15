(ns agent.extensions.claude-hook-bridge.events.common
  "The common input fields every Claude Code hook receives
   (hooks reference, \"Common input fields\"):

     session_id, transcript_path, cwd, permission_mode, hook_event_name

   Read live from the agent on every fire, never cached: a hook that groups
   by session_id needs the id of THIS session, and permission_mode changes
   mid-session."
  (:require ["node:path" :as path]))

(defn- session-file [api]
  (try (some-> (.getSessionFile api) str)
       (catch :default _e nil)))

(defn base
  "The common-field object for `event-name`; event modules spread their
   own fields on top with js/Object.assign."
  [api event-name]
  (let [file  (session-file api)
        state (try (.getState api) (catch :default _e nil))]
    #js {:session_id      (if (seq file)
                            (path/basename file (path/extname file))
                            "session")
         :transcript_path (or file "")
         :cwd             (js/process.cwd)
         :permission_mode (str (or (and state (:permission-mode state)) "default"))
         :hook_event_name event-name}))
