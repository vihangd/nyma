(ns agent.interceptors)

(defn interceptor
  "Create an interceptor map. All stage fns receive and return a context map.
   Stage fns may return a Promise (auto-awaited).
   Usage: (interceptor :name {:enter fn :leave fn :error fn})"
  [name opts]
  (cond-> {:name name}
    (:enter opts) (assoc :enter (:enter opts))
    (:leave opts) (assoc :leave (:leave opts))
    (:error opts) (assoc :error (:error opts))))

(defn- promise? [x]
  (and x (fn? (.-then x))))

(defn- ^:async maybe-await [x]
  (if (promise? x) (js-await x) x))

(defn ^:async execute
  "Execute an interceptor chain against a context map.
   Enter stages run left-to-right, leave stages run right-to-left.
   If any enter stage adds :error to context, remaining enters are skipped
   and :error stages run right-to-left from the error point.
   An :error handler may remove :error to resume normal :leave processing.
   Returns the final context map."
  [chain initial-ctx]
  (let [;; Enter phase: run left-to-right, build stack of executed interceptors
        enter-result
        (js-await
          (loop [remaining chain
                 ctx       initial-ctx
                 executed  []]
            (if (or (empty? remaining) (get ctx :error))
              {:ctx ctx :executed executed}
              (let [ic   (first remaining)
                    efn  (get ic :enter)]
                (if efn
                  (let [next-ctx (try
                                   (js-await (maybe-await (efn ctx)))
                                   (catch :default e
                                     (assoc ctx :error e :error-interceptor (:name ic))))]
                    (recur (rest remaining) next-ctx (conj executed ic)))
                  (recur (rest remaining) ctx (conj executed ic)))))))

        ctx      (get enter-result :ctx)
        executed (get enter-result :executed)

        ;; Error phase: if :error set, run :error stages right-to-left
        ctx
        (if (get ctx :error)
          (js-await
            (loop [stack (reverse executed)
                   ctx   ctx]
              (if (empty? stack)
                ctx
                (let [ic  (first stack)
                      efn (get ic :error)]
                  (if efn
                    (let [next-ctx (try
                                     (js-await (maybe-await (efn ctx)))
                                     (catch :default e
                                       (assoc ctx :error e :error-interceptor (:name ic))))]
                      ;; If error was cleared, switch to leave phase for remaining
                      (if (get next-ctx :error)
                        (recur (rest stack) next-ctx)
                        ;; Error cleared — run leave for remaining executed interceptors
                        (js-await
                          (loop [leave-stack (rest stack)
                                 ctx        next-ctx]
                            (if (empty? leave-stack)
                              ctx
                              (let [lic (first leave-stack)
                                    lfn (get lic :leave)]
                                (if lfn
                                  (let [next-ctx (try
                                                   (js-await (maybe-await (lfn ctx)))
                                                   (catch :default e
                                                     (assoc ctx :error e :error-interceptor (:name lic))))]
                                    (recur (rest leave-stack) next-ctx))
                                  (recur (rest leave-stack) ctx))))))))
                    (recur (rest stack) ctx))))))
          ;; Leave phase: run right-to-left (no error)
          (js-await
            (loop [stack (reverse executed)
                   ctx   ctx]
              (if (empty? stack)
                ctx
                (let [ic  (first stack)
                      lfn (get ic :leave)]
                  (if lfn
                    (let [next-ctx (try
                                     (js-await (maybe-await (lfn ctx)))
                                     (catch :default e
                                       (assoc ctx :error e :error-interceptor (:name ic))))]
                      (if (get next-ctx :error)
                        ;; Error during leave — run remaining error handlers
                        (js-await
                          (loop [err-stack (rest stack)
                                 ctx      next-ctx]
                            (if (empty? err-stack)
                              ctx
                              (let [eic (first err-stack)
                                    efn (get eic :error)]
                                (if efn
                                  (let [next-ctx (try
                                                   (js-await (maybe-await (efn ctx)))
                                                   (catch :default e
                                                     (assoc ctx :error e :error-interceptor (:name eic))))]
                                    ;; If error was cleared, resume leave for remaining
                                    (if (get next-ctx :error)
                                      (recur (rest err-stack) next-ctx)
                                      (js-await
                                        (loop [leave-rem (rest err-stack)
                                               ctx       next-ctx]
                                          (if (empty? leave-rem)
                                            ctx
                                            (let [lic (first leave-rem)
                                                  lfn (get lic :leave)]
                                              (if lfn
                                                (let [next-ctx (try
                                                                 (js-await (maybe-await (lfn ctx)))
                                                                 (catch :default e
                                                                   (assoc ctx :error e :error-interceptor (:name lic))))]
                                                  (recur (rest leave-rem) next-ctx))
                                                (recur (rest leave-rem) ctx))))))))
                                  (recur (rest err-stack) ctx))))))
                        (recur (rest stack) next-ctx)))
                    (recur (rest stack) ctx)))))))]
    ctx))

(defn into-chain
  "Compose multiple chains/interceptors into a single chain vector."
  [& chains]
  (vec (mapcat #(if (map? %) [%] %) chains)))
