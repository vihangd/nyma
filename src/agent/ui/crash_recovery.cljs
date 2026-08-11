(ns agent.ui.crash-recovery
  "Survive a pi-tui render crash instead of losing the session to it.

   pi-tui throws when a rendered line exceeds the terminal width. The throw
   happens inside its own render timer (`setTimeout`), so the caller's stack
   has already unwound — no `try` around `.requestRender` can ever catch it,
   and pi-tui exposes no error hook. The only interception point is
   `process.on('uncaughtException')`, and nyma had none, so the process died.

   Recovery is possible because of two details of the throw site:

     - pi-tui calls `this.stop()` BEFORE throwing (`tui.js:965`), so raw mode,
       the cursor and bracketed paste are already restored.
     - `start()` sets `this.stopped = false` (`tui.js:284`), so the same TUI
       instance can be brought back up.

   This is a NET, not the defence. `agent.ui.width-guard` is the defence: it
   clamps component output, which also covers the silent half of the problem
   (pi-tui only width-checks on its incremental path — `fullRender` writes
   lines unchecked, where an over-wide line soft-wraps and desynchronizes the
   renderer without throwing at all). Restarting only ever addresses the loud
   half, and restarting into a component that still emits the same bad line
   would crash-loop — which is what the repeat window below is for."
  (:require [agent.ui.width-guard :as guard]))

(def ^:private width-error-marker
  ;; The message pi-tui builds at tui.js:967. Matching on a message string is
  ;; fragile across versions, but it is the only signal pi-tui gives — there
  ;; is no error class and no code. Deliberately matched on this exact phrase
  ;; rather than something loose like /width/, because a match that is too
  ;; broad would turn an unrelated bug into a silent restart loop.
  "exceeds terminal width")

(defn width-error?
  "True when `err` is pi-tui's over-wide-line throw and nothing else."
  [err]
  (let [msg (str (or (and err (.-message err)) err ""))]
    (.includes msg width-error-marker)))

(def ^:private repeat-window-ms
  "Two width crashes closer together than this means the offending line is
   still being emitted and restarting would loop. Long enough to cover a
   render burst, short enough that two unrelated crashes an hour apart are
   both recovered."
  5000)

(def ^:private max-restarts 5)

(defn decide
  "Pure: given the error and prior recovery state, say what to do.

   Returns [action next-state] where action is :restart or :give-up. Split out
   from the handler so the policy is testable without installing a real
   process handler or a real TUI."
  [err {:keys [restarts last-at] :as _state} now]
  (cond
    (not (width-error? err))
    [:give-up {:restarts (or restarts 0) :last-at last-at}]

    ;; Same crash again, immediately: the line survived the restart.
    (and last-at (< (- now last-at) repeat-window-ms))
    [:give-up {:restarts (inc (or restarts 0)) :last-at now}]

    (>= (or restarts 0) max-restarts)
    [:give-up {:restarts (inc (or restarts 0)) :last-at now}]

    :else
    [:restart {:restarts (inc (or restarts 0)) :last-at now}]))

(defn resume-hint
  "The one thing a user needs after a crash: where the conversation went and
   how to get back into it. Everything up to the last completed turn is
   already on disk — appends are synchronous, per message."
  [session-path]
  (str "\nSession saved" (when (seq (str (or session-path ""))) (str ": " session-path)) "\n"
       "Resume it with:  nyma -c\n"
       (when (seq (str (or session-path "")))
         (str "         or:  nyma --session " session-path "\n"))
       "Render debug log: ~/.pi/agent/pi-crash.log\n"))

(defn install!
  "Install the recovery handler.

   opts:
     :tui              the TUI to restart
     :session-path-fn  () -> current session file path, for the resume hint
     :on-recovered     (msg) -> nil, to surface the recovery in the chat
     :now-fn           () -> ms, injectable for tests
     :exit-fn          (code) -> nil, injectable for tests

   Returns a function that uninstalls it."
  [{:keys [tui session-path-fn on-recovered now-fn exit-fn]}]
  (let [state   (atom {:restarts 0 :last-at nil})
        now-fn  (or now-fn (fn [] (js/Date.now)))
        exit-fn (or exit-fn (fn [code] (js/process.exit code)))
        path-fn (or session-path-fn (fn [] nil))

        give-up!
        (fn [err]
          ;; For a width error pi-tui already stopped; for anything else the
          ;; terminal is still in raw mode and must be restored before we
          ;; print, or the message lands in a screen nobody can read.
          (when-not (width-error? err)
            (try (when tui (.stop tui)) (catch :default _ nil)))
          (js/console.error (str "\n" (or (and err (.-stack err)) (.-message err) err)))
          (js/console.error (resume-hint (path-fn)))
          (exit-fn 1))

        handler
        (fn [err]
          (let [[action next-state] (decide err @state (now-fn))]
            (reset! state next-state)
            (if (= action :restart)
              (do
                (when on-recovered
                  (on-recovered
                   (str "Recovered from a render error — the display was reset. "
                        "Nothing in this conversation was lost. "
                        "Details: ~/.pi/agent/pi-crash.log")))
                (try
                  (.start tui)
                  (catch :default e
                    ;; If it cannot even come back up, stop pretending.
                    (give-up! e))))
              (give-up! err))))

        ;; `main` is invoked without a .catch (cli.cljs), so a rejected promise
        ;; would otherwise print a bare trace over a live TUI. Never restart on
        ;; one — a rejection is not the render timer.
        rejection-handler
        (fn [reason] (give-up! reason))]

    (.on js/process "uncaughtException" handler)
    (.on js/process "unhandledRejection" rejection-handler)
    (fn uninstall! []
      (.off js/process "uncaughtException" handler)
      (.off js/process "unhandledRejection" rejection-handler))))

(defn guard-clamp-count
  "How many lines the width guard has had to cut this session. If a render
   crash happens while this is zero, the bad line came from somewhere the
   guard does not cover."
  []
  (:count @guard/clamps))
