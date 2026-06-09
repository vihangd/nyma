(ns agent.extensions.subagent.concurrency
  "Bounded-concurrency mapping for parallel subagent fan-out.

   Mirrors pi's mapWithConcurrencyLimit: run `f` over `items` with at
   most `limit` in flight at once, preserving result order. A worker
   that throws resolves its slot to nil (caller filters).

   NOTE (Squint gotcha): inline (fn ^:async ...) is silently broken, so
   the worker is a top-level (defn ^:async ...). `f` must be a sync fn
   that RETURNS a promise (e.g. a thin wrapper over a top-level async
   defn); the worker awaits it.")

(defn ^:async run-worker
  "Pull the next index off the shared counter and run `f` until drained.
   ctx = {:items :out :next :n :f}. Recurses (async) rather than using
   loop/recur+js-await."
  [ctx]
  (let [{:keys [items out next n f]} ctx
        i @next]
    (swap! next inc)
    (when (< i n)
      (try
        (let [r (js-await (f (nth items i) i))]
          (aset out i r))
        (catch :default _e
          (aset out i nil)))
      (js-await (run-worker ctx)))))

(defn ^:async map-with-limit
  "Apply `f` (sync fn item idx -> Promise) over `items` with at most
   `limit` concurrent. Returns a Promise of a result vector in input
   order; failed items become nil."
  [items limit f]
  (let [items (vec items)
        n     (count items)
        out   (js/Array. n)
        limit (max 1 (min limit (if (zero? n) 1 n)))
        next  (atom 0)]
    (if (zero? n)
      []
      (let [ctx     {:items items :out out :next next :n n :f f}
            workers (vec (for [_ (range limit)] (run-worker ctx)))]
        (js-await (js/Promise.all (clj->js workers)))
        (vec out)))))
