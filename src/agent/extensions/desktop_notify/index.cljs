(ns agent.extensions.desktop-notify.index
  "Desktop notifications via OSC 777 escape sequence.
   Fires a terminal notification when a prompt response takes >N seconds.
   Works in Ghostty, iTerm2, WezTerm, Kitty (silent on unsupported terminals).

   Config in .nyma/settings.json:
   {\"desktop-notify\": {\"enabled\": true, \"threshold-ms\": 3000}}

   Also controllable via /flag desktop-notify__enabled true")

;;; ─── Config ────────────────────────────────────────────────
;;; Read through the settings manager, not by opening settings.json here: the
;;; hand-rolled reader saw the PROJECT file only, so a `~/.nyma/settings.json`
;;; that disabled notifications was ignored, and the defaults below now live in
;;; extension.json where /settings can show them.

(defn- load-config [api]
  (let [section (.settings api "desktop-notify")
        enabled (:enabled section)
        ms      (:threshold-ms section)]
    ;; The guards survive the migration: an api without a settings manager
    ;; (loader smoke, test stubs) hands back an empty section.
    ;;
    ;; The 3000 is DELIBERATELY mirrored from extension.json rather than moved
    ;; out the way todos' 5 was. This branch is reached only when the value is
    ;; not a number, and a threshold of 0 there would fire a notification on
    ;; every turn — punishing a typo with spam. A wrong threshold should behave
    ;; like the default, not like an alarm.
    {:enabled      (if (some? enabled) enabled true)
     :threshold-ms (if (number? ms) ms 3000)}))

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
  (let [config       (load-config api)
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
                (let [enabled (if (and (.-getFlag api))
                                (let [flag-val (.getFlag api "enabled")]
                                  (if (some? flag-val) flag-val true))
                                true)]
                  (when enabled
                    (send-notification! "nyma" "Response ready")
                    (when (.-emitGlobal api)
                      (.emitGlobal api "notification"
                                   {:title  "nyma"
                                    :body   "Response ready"
                                    :source "desktop-notify"}))))))))

        check-enabled
        (fn []
          (if (.-getFlag api)
            (let [flag-val (.getFlag api "enabled")]
              (if (some? flag-val) flag-val true))
            true))

        ;; Notify on long-running tool completions (>10s)
        on-tool-complete
        (fn [data _ctx]
          (let [dur (or (.-duration data) 0)]
            (when (and (> dur 10000) (check-enabled))
              (send-notification! "nyma"
                                  (str (.-toolName data) " completed ("
                                       (js/Math.round (/ dur 1000)) "s)")))))

        ;; Notify with session summary on exit
        on-session-end
        (fn [data _ctx]
          (when (check-enabled)
            (send-notification! "nyma"
                                (str "Session: " (or (.-turnCount data) 0) " turns, $"
                                     (.toFixed (or (.-totalCost data) 0) 2)))))]

    ;; Register enable/disable flag
    (when (.-registerFlag api)
      (.registerFlag api "enabled"
                     #js {:description "Enable desktop notifications"
                          :default     (:enabled config)}))

    ;; Listen to lifecycle events
    (.on api "turn_start" on-turn-start)
    (.on api "turn_end" on-turn-end)
    (.on api "tool_complete" on-tool-complete)
    (.on api "session_end_summary" on-session-end)

    ;; Return deactivator
    (fn []
      (.off api "turn_start" on-turn-start)
      (.off api "turn_end" on-turn-end)
      (.off api "tool_complete" on-tool-complete)
      (.off api "session_end_summary" on-session-end))))
