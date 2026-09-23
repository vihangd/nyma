(ns agent.interceptors
  "Pedestal-style interceptor chain: enter left-to-right, then unwind
   right-to-left running :leave — or :error while the context carries an
   :error. That one rule replaces the four nested loops this used to be:
   whether an error was raised during enter or during leave, and whether an
   :error handler cleared it or not, the unwind decides per interceptor from
   the context it is handed.")

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

(defn- ^:async run-stage
  "Run `stage` (:enter/:leave/:error) of `ic` on `ctx`, if it has one. A
   throw becomes `:error` on the context, attributed to the interceptor."
  [ic stage ctx]
  (if-let [f (get ic stage)]
    (try
      (js-await (maybe-await (f ctx)))
      (catch :default e
        (assoc ctx :error e :error-interceptor (:name ic))))
    ctx))

(defn ^:async execute
  "Execute an interceptor chain against a context map.
   Enter stages run left-to-right, leave stages run right-to-left.
   If any enter stage adds :error to context, remaining enters are skipped
   and :error stages run right-to-left from the error point.
   An :error handler may remove :error to resume normal :leave processing;
   a :leave that throws hands the rest of the unwind to :error handlers.
   Returns the final context map."
  [chain initial-ctx]
  ;; Enter: left-to-right until the chain ends or an :error appears. The
  ;; interceptor that raised the error IS on the stack, so its own :error
  ;; handler is the first to see it on the way back.
  (let [entered (loop [remaining chain
                       ctx       initial-ctx
                       executed  []]
                  (if (or (empty? remaining) (get ctx :error))
                    {:ctx ctx :executed executed}
                    (let [ic (first remaining)]
                      (recur (rest remaining)
                             (js-await (run-stage ic :enter ctx))
                             (conj executed ic)))))]
    ;; Unwind: right-to-left; the context's current state picks the stage.
    (loop [stack (reverse (:executed entered))
           ctx   (:ctx entered)]
      (if (empty? stack)
        ctx
        (recur (rest stack)
               (js-await (run-stage (first stack)
                                    (if (get ctx :error) :error :leave)
                                    ctx)))))))
