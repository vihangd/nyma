(ns agent.sessions.settings
  "A session's setup — model, thinking level, permission mode, model role —
   recorded in its own file, so resuming it brings the setup back.

   Stored as `session-settings` entries; the last one wins, as `session-name`
   does. Every context builder whitelists conversation roles, so these never
   reach the model."
  (:require [clojure.string :as str]
            [agent.debug :as d]
            [agent.providers.registry :as registry-utils]
            [agent.thinking :as thinking]))

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

(defn- mode-command [agent]
  (let [cmds @(:commands agent)]
    (or (get cmds "mode") (get cmds "model-roles__mode"))))

(defn restore!
  "Put `saved` back on the agent; returns the parts applied, as strings for
   the notice. Fields in `explicit` (a set of :model :thinking
   :permission-mode, from CLI flags) are left alone. A saved full-auto comes
   back as accept-edits: a resumed session never runs tools unasked by
   surprise. `headless?` skips the mode — print/json/rpc have no one to ask."
  [agent saved {:keys [explicit headless?]}]
  (let [api   (.-extension-api agent)
        cur   (or (snapshot agent) {})
        skip? #(contains? (or explicit #{}) %)
        out   (atom [])]
    (when saved
      ;; Role first: a role that carries a model re-applies it each turn, and
      ;; role_change's keep-model leaves the saved model below to stand.
      (let [r (:role saved)]
        ;; ...unless -m was given: the role's model would override it.
        (when (and (seq r) (not= r (:role cur)) (not (skip? :model)))
          ((:emit (:events agent)) "role_change" {:role r :source "session-restore"})
          (swap! out conj (str "role " r))))
      (let [m (:model saved)]
        (when (and (seq m) (not (skip? :model)) (not= m (:model cur)) api)
          (let [[prov] (registry-utils/split-model-spec m)]
            (if (and prov (not ((:get (:provider-registry agent)) prov)))
              (do (d/warn "sessions" (str "saved model " m " has no provider here; keeping " (:model cur)))
                  (swap! out conj (str "model " m " unavailable")))
              (do (.setModel api m)
                  (swap! out conj m))))))
      (let [t (:thinking saved)]
        (when (and (thinking/valid-level? t) (not (skip? :thinking)) (not= t (:thinking cur)))
          (reset! (:thinking-level agent) t)
          (swap! out conj (str "thinking " t))))
      (let [pm   (:permission-mode saved)
            pm   (if (= pm "full-auto") "accept-edits" pm)
            cmd  (mode-command agent)]
        (when (and (seq pm) cmd api (not headless?) (not (skip? :permission-mode))
                   (not= pm (:permission-mode cur)))
          ;; Through /mode, as ACP does: "plan" has to enter plan mode.
          ((:handler cmd) [pm] #js {:ui (.-ui api)})
          (swap! out conj (str "mode " pm
                               (when (= "full-auto" (:permission-mode saved))
                                 " (was full-auto)"))))))
    @out))

(defn restore-session!
  "restore! from the agent's current session, with one line saying what came
   back."
  [agent opts]
  (when-let [session (some-> (:session agent) deref)]
    (let [parts (restore! agent (last-saved session) opts)]
      (when (seq parts)
        (let [msg (str "Restored: " (str/join " · " parts))
              ui  (some-> (.-extension-api agent) .-ui)]
          (if (and ui (.-notify ui))
            (.notify ui msg "info")
            (d/info "sessions" msg))))
      parts)))
