(ns agent.sessions.settings
  "A session's setup — model, thinking level, permission mode, model role —
   recorded in its own file, so resuming it brings the setup back.

   Stored as `session-settings` entries; the last one wins, as `session-name`
   does. Every context builder whitelists conversation roles, so these never
   reach the model.")

(def entry-role "session-settings")

(defn- model-spec [cfg]
  (let [m    (.-model cfg)
        prov (str (or (aget cfg "active-provider-name") ""))]
    (cond
      (nil? m)    nil
      ;; setModel falls back to the raw spec when no provider resolves it
      (string? m) m
      :else       (let [id (.-modelId m)]
                    (when id (if (seq prov) (str prov "/" id) (str id)))))))

(defn snapshot
  "The agent's current setup, or nil while it is wearing something temporary:
   plan mode's planner model or an escalation's stronger one. Recording those
   would resume a session on a model the user never picked."
  [agent]
  (let [st @(:state agent)]
    (when-not (or (:plan-mode st) (:escalated-to st))
      {:model           (model-spec (:config agent))
       :thinking        (str @(:thinking-level agent))
       :permission-mode (str (:permission-mode st))
       :role            (str (:active-role st))})))

(defn last-saved
  "The last recorded setup in `session`, or nil."
  [session]
  (some->> ((:get-entries session))
           (filter #(= entry-role (:role %)))
           last
           :metadata))

(defn- canon [m]
  (js/JSON.stringify #js [(:model m) (:thinking m) (:permission-mode m) (:role m)]))

(defn record!
  "Append the current setup when it differs from the last one recorded."
  [agent]
  (when-let [session (some-> (:session agent) deref)]
    (when ((:get-file-path session))
      (when-let [snap (snapshot agent)]
        (when-not (= (canon snap) (canon (last-saved session)))
          ((:append session) {:role entry-role :metadata snap}))))))

(defn attach-recorder!
  "Record after every run, before leaving a session, and on the way out — so a
   /model with no run since is not lost."
  [agent]
  (let [on  (:on (:events agent))
        rec (fn [_] (try (record! agent) (catch :default _ nil)))]
    (on "agent_end" rec)
    (on "session_before_switch" rec)
    (on "session_shutdown" rec)))
