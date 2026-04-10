(ns agent.ui.countdown-timer
  "Drift-safe countdown primitive.

   Instead of counting ticks (which drifts when a tick is missed due to
   a slow render or blocked event loop), we store the absolute deadline
   timestamp and compute the remaining seconds on every render.

   * `seconds-remaining` is a pure helper suitable for unit tests.
   * `use-countdown` is a React hook that re-renders every 250 ms until
     the deadline is reached, then stops ticking. Passing nil opts
     out — callers can safely thread an optional deadline through
     without branching."
  (:require ["react" :refer [useState useEffect]]))

(defn seconds-remaining
  "Return the whole seconds remaining between now-ms and deadline-ms.
   Clamped to 0. Pure — both arguments are explicit so tests don't
   need to stub the clock."
  [deadline-ms now-ms]
  (cond
    (or (nil? deadline-ms) (nil? now-ms)) 0
    :else
    (max 0 (js/Math.ceil (/ (- deadline-ms now-ms) 1000)))))

(defn use-countdown
  "React hook. Given an absolute deadline in ms-since-epoch, return the
   whole seconds remaining and trigger a re-render at 250 ms intervals
   until the deadline passes.

   When `deadline-ms` is nil, returns nil and does not schedule an
   interval — callers can safely pass nil to opt out."
  [deadline-ms]
  (let [[now set-now] (useState (js/Date.now))]
    (useEffect
      (fn []
        (when deadline-ms
          (let [id (js/setInterval
                     (fn []
                       (let [t (js/Date.now)]
                         (set-now t)
                         ;; Small optimisation: once the deadline has
                         ;; passed we can stop ticking.
                         (when (>= t deadline-ms)
                           (js/clearInterval id))))
                     250)]
            (fn [] (js/clearInterval id)))))
      #js [deadline-ms])
    (when deadline-ms
      (seconds-remaining deadline-ms now))))
