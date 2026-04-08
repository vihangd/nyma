(ns agent.extensions.desktop-notify.index
  "Desktop notifications via OSC 777 escape sequence.
   Fires a terminal notification when a prompt response takes >N seconds.
   Works in Ghostty, iTerm2, WezTerm, Kitty (silent on unsupported terminals).

   Config in .nyma/settings.json:
   {\"desktop-notify\": {\"enabled\": true, \"threshold-ms\": 3000}}

   Also controllable via /flag desktop-notify__enabled true"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

;;; ─── Config ────────────────────────────────────────────────

(def default-threshold 3000) ;; 3 seconds

(defn- load-config []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")]
    (if (fs/existsSync settings-path)
      (try
        (let [raw    (fs/readFileSync settings-path "utf8")
              parsed (js/JSON.parse raw)
              section (.-desktop-notify parsed)]
          (if section
            {:enabled      (if (some? (.-enabled section)) (.-enabled section) true)
             :threshold-ms (or (.-threshold-ms section) default-threshold)}
            {:enabled true :threshold-ms default-threshold}))
        (catch :default _ {:enabled true :threshold-ms default-threshold}))
      {:enabled true :threshold-ms default-threshold})))

;;; ─── OSC 777 notification ──────────────────────────────────

(defn- send-notification!
  "Send OSC 777 terminal notification. Silent on unsupported terminals."
  [title body]
  (try
    (.write (.-stdout js/process)
      (str "\u001b]777;notify;" title ";" body "\u0007"))
    (catch :default _ nil)))

;;; ─── Extension activation ──────────────────────────────────

(defn ^:export default [api]
  (let [config       (load-config)
        turn-start   (atom nil)
        threshold-ms (:threshold-ms config)

        on-turn-start
        (fn [_data _ctx]
          (reset! turn-start (js/Date.now)))

        on-turn-end
        (fn [_data _ctx]
          (when-let [start @turn-start]
            (let [elapsed (- (js/Date.now) start)]
              (when (> elapsed threshold-ms)
                ;; Check flag (allows runtime toggle)
                (let [enabled (if (and (.-getFlag api))
                                (let [flag-val (.getFlag api "enabled")]
                                  (if (some? flag-val) flag-val true))
                                true)]
                  (when enabled
                    (send-notification! "nyma" "Response ready")))))))]

    ;; Register enable/disable flag
    (when (.-registerFlag api)
      (.registerFlag api "enabled"
        #js {:description "Enable desktop notifications"
             :default     (:enabled config)}))

    ;; Listen to turn lifecycle events
    (.on api "turn_start" on-turn-start)
    (.on api "turn_end" on-turn-end)

    ;; Return deactivator
    (fn []
      (.off api "turn_start" on-turn-start)
      (.off api "turn_end" on-turn-end))))
