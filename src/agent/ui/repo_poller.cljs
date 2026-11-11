(ns agent.ui.repo-poller
  "Keeps the status line's git figures current without touching a render.

   `status_bar/render` is synchronous and re-walks the whole segment registry on
   every frame — up to 10 Hz while streaming, plus on every output chunk and
   keystroke. `git status --porcelain` is an `execSync`. Calling it from a
   segment would block the UI at render cadence on a large repo, which is why
   every extension that shows live data closes over an atom something else
   updates. This is that something else for `git` and `path`.

   Two rules the design turns on:

   - **Only notify on a CHANGE.** The status-line tick stops at idle on purpose:
     re-rendering moves the terminal scroll position. A poller that requested a
     render every interval would undo that, so it compares against the last
     value and stays silent when nothing moved.
   - **Decide once whether this is a repo.** Outside a repo every poll would be
     two wasted spawns forever, so a non-repo stops the poller instead of
     retrying."
  (:require [agent.debug :as d]))

(def default-interval-ms
  "5s. Slow enough that a large repo's porcelain call is not felt, fast enough
   that a branch switch shows up before you wonder whether it did."
  5000)

(defn state->bar
  "Repo state → the keys `seg-ctx` hands the `git` segment. Pure.

   `git-seg` wants `:git-branch` and a `:git-status` map of counts; splitting it
   here keeps the segment unchanged and means the shape is asserted in one place."
  [rs]
  (when rs
    {:git-branch (or (:branch rs) (when (:detached? rs) "detached"))
     :git-status {:staged     (or (:staged rs) 0)
                  :unstaged   (or (:unstaged rs) 0)
                  :untracked  (or (:untracked rs) 0)
                  :conflicted (or (:conflicted rs) 0)
                  :ahead      (or (:ahead rs) 0)
                  :behind     (or (:behind rs) 0)}}))

(defn changed?
  "Did anything a segment would draw actually move? Pure.

   Compared on the RENDERED fields rather than the raw map, so a field no segment
   shows cannot trigger a render — and a render at idle scrolls the terminal."
  [before after]
  (not= (state->bar before) (state->bar after)))

(defn make-poller
  "A poller over `fetch`, a 0-arg fn returning repo state or nil.

   Returns `{:poll! :stop! :value}`. `poll!` is exposed so a test can step it
   without timers; `start!` below is the same thing on an interval.

   `on-change` is called with the bar keys ONLY when they differ from the last
   poll, never on an unchanged poll. The first poll always reports — including
   nil for a non-repo, because the caller sets non-git facts from this callback
   and would otherwise never hear anything outside a repo."
  [{:keys [fetch on-change]}]
  (let [last-val (atom ::none)
        stopped  (atom false)
        failed   (fn [e]
                   (d/debug "repo-poller" (str "fetch failed: " (or (.-message e) (str e))))
                   nil)
        settle!  (fn [next]
                   (let [prev @last-val]
                     ;; nil means "not a repo" or a failed shell-out. Either way
                     ;; there is nothing to poll for, so stop rather than spawn two
                     ;; processes every interval for the life of the session.
                     (if (nil? next)
                       (do (reset! stopped true)
                           ;; Report nil unless nil was already reported. Including
                           ;; on the FIRST poll: the caller sets non-git facts (the
                           ;; `path` segment) from this callback, so staying silent
                           ;; outside a repo hid a segment that has nothing to do
                           ;; with git. Reporting once also clears a stale branch if
                           ;; the repo goes away mid-session.
                           (when (not= prev nil)
                             (reset! last-val nil)
                             (when on-change (on-change nil)))
                           nil)
                       (do
                         (when (or (= prev ::none) (changed? prev next))
                           (when on-change (on-change (state->bar next))))
                         (reset! last-val next)
                         next))))]
    {:value (fn [] (let [v @last-val] (when-not (= v ::none) v)))
     :stop! (fn [] (reset! stopped true) nil)
     ;; `fetch` may answer with a promise (`git-files/repo-state-async`); the
     ;; poll then settles when it does, and nothing waits on it. A plain value
     ;; settles on the spot, so a test can step the poller without timers.
     :poll! (fn []
              (when-not @stopped
                (let [next (try (fetch) (catch :default e (failed e)))]
                  (if (and (some? next) (fn? (.-then next)))
                    (.then next settle! (fn [e] (settle! (failed e))))
                    (settle! next)))))}))

(defn start!
  "Poll on an interval. Returns a stop fn.

   `enabled?` false does nothing at all — not a poller that skips, no timer and
   no first poll — because the point of the setting is a repo where the porcelain
   call is expensive."
  [{:keys [fetch on-change interval-ms enabled?] :or {enabled? true}}]
  (if-not enabled?
    (fn [])
    (let [{:keys [poll! stop!]} (make-poller {:fetch fetch :on-change on-change})
          ;; First poll immediately: waiting 5s to show the branch you are
          ;; looking at defeats the purpose.
          _     (poll!)
          timer (js/setInterval poll! (or interval-ms default-interval-ms))]
      (fn []
        (stop!)
        (js/clearInterval timer)
        nil))))
