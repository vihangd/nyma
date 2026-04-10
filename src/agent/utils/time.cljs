(ns agent.utils.time
  "Shared time formatting helpers.")

(defn relative-time
  "Format a past timestamp (ms since epoch) as a relative-time string.
   Buckets: just now / Nm ago / Nh ago / Nd ago / Nw ago."
  [ts]
  (let [now    (js/Date.now)
        diff   (- now ts)
        secs   (js/Math.floor (/ diff 1000))
        mins   (js/Math.floor (/ secs 60))
        hours  (js/Math.floor (/ mins 60))
        days   (js/Math.floor (/ hours 24))]
    (cond
      (< mins 1)   "just now"
      (< mins 60)  (str mins "m ago")
      (< hours 24) (str hours "h ago")
      (< days 7)   (str days "d ago")
      :else        (str (js/Math.floor (/ days 7)) "w ago"))))
