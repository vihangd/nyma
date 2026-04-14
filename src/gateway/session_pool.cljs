(ns gateway.session-pool
  "Per-conversation session pool with serialized execution lanes, policy-based
   eviction, and event-idempotency dedup cache.

   ─── Concepts ────────────────────────────────────────────────────────────

   Session entry — one per unique conversation-id:
     :lane-atom     atom<Promise>  serial queue; enqueued thunks run in order
     :data          atom<{}>       arbitrary attached data (agent session, etc.)
     :created-at    long           epoch ms
     :last-active   atom<long>     updated on every enqueue
     :policy        keyword        controls eviction behaviour

   Eviction policies:
     :ephemeral     — agent session cleared after every run; history is per-run only
     :idle-evict    — session data cleared after :idle-evict-ms of no activity
     :persistent    — never auto-evict (manual evict! or restart clears it)
     :capped        — like persistent but (:data :sdk-session) auto-truncated

   Dedup cache:
     Map of event-id → expiry-ms. Prevents reprocessing webhook deliveries.
     TTL defaults to 5 minutes; configurable via :dedup-ttl-ms in pool config.

   ─── Thread safety ───────────────────────────────────────────────────────
   Lane serialization is achieved by chaining Promises, not locking.
   All state mutations use atoms; atom reads/swaps are safe within Bun's
   single-threaded event loop.")

(defn create-session-pool
  "Create a new session pool.

   Config opts:
     :idle-evict-ms   long     idle timeout for :idle-evict policy (default 1 hour)
     :dedup-ttl-ms    long     dedup cache TTL in ms (default 5 minutes)
     :default-policy  keyword  policy applied to new sessions (default :persistent)"
  [& [opts]]
  (let [{:keys [idle-evict-ms dedup-ttl-ms default-policy]
         :or   {idle-evict-ms  3600000
                dedup-ttl-ms   300000
                default-policy :persistent}} (or opts {})
        sessions (atom {})
        dedup    (atom {})]
    {:sessions      sessions
     :dedup         dedup
     :idle-evict-ms idle-evict-ms
     :dedup-ttl-ms  dedup-ttl-ms
     :default-policy default-policy}))

;;; ─── Session entry management ─────────────────────────────────────────

(defn get-entry
  "Return the session entry for key, or nil."
  [pool session-key]
  (get @(:sessions pool) session-key))

(defn get-or-create-entry!
  "Return the session entry for key, creating it if absent.
   Optionally pass :policy to override the pool's default-policy."
  [pool session-key & [opts]]
  (let [existing (get @(:sessions pool) session-key)]
    (if existing
      existing
      (let [entry {:lane-atom   (atom (js/Promise.resolve nil))
                   :data        (atom {})
                   :created-at  (js/Date.now)
                   :last-active (atom (js/Date.now))
                   :policy      (or (:policy opts) (:default-policy pool) :persistent)}]
        (swap! (:sessions pool) assoc session-key entry)
        entry))))

(defn enqueue!
  "Enqueue a thunk on session's serial lane. Returns a Promise that resolves
   to the thunk's return value when it executes.

   The lane is repaired on error — a failing thunk doesn't stall future work."
  [pool session-key thunk]
  (let [entry     (get-or-create-entry! pool session-key)
        lane-atom (:lane-atom entry)]
    (reset! (:last-active entry) (js/Date.now))
    (let [next (.then @lane-atom
                      (fn []
                        (try
                          (thunk)
                          (catch :default e
                            (js/console.error
                             (str "[gateway] Lane thunk error [" session-key "]:") e)
                            nil))))]
      ;; Reset lane to a resolved promise so errors don't stall future enqueues
      (reset! lane-atom (.catch next (fn [_] nil)))
      next)))

;;; ─── Session data helpers ─────────────────────────────────────────────

(defn get-data
  "Read a key from a session's data atom."
  [pool session-key k]
  (when-let [entry (get-entry pool session-key)]
    (get @(:data entry) k)))

(defn set-data!
  "Set a key in a session's data atom."
  [pool session-key k v]
  (when-let [entry (get-or-create-entry! pool session-key)]
    (swap! (:data entry) assoc k v)))

;;; ─── Eviction ─────────────────────────────────────────────────────────

(defn evict!
  "Remove a session from the pool entirely (clears lane + data)."
  [pool session-key]
  (swap! (:sessions pool) dissoc session-key))

(defn evict-idle!
  "Evict all :idle-evict sessions that have been inactive for longer than
   the pool's :idle-evict-ms threshold. Call periodically (e.g. every hour)."
  [pool]
  (let [now      (js/Date.now)
        ttl      (:idle-evict-ms pool)
        sessions @(:sessions pool)]
    (doseq [[k entry] sessions]
      (when (and (= (:policy entry) :idle-evict)
                 (> (- now @(:last-active entry)) ttl))
        (evict! pool k)))))

(defn evict-ephemeral-data!
  "For :ephemeral sessions, clear :data after a run completes.
   Call at the end of each successful or failed handle-message."
  [pool session-key]
  (when-let [entry (get-entry pool session-key)]
    (when (= (:policy entry) :ephemeral)
      (reset! (:data entry) {}))))

;;; ─── Pool stats ────────────────────────────────────────────────────────

(defn pool-stats
  "Return a summary map of pool state for monitoring."
  [pool]
  (let [entries (vals @(:sessions pool))]
    {:total-sessions (count entries)
     :by-policy      (reduce (fn [acc e]
                               (update acc (:policy e) (fnil inc 0)))
                             {}
                             entries)
     :dedup-cache-size (count @(:dedup pool))}))

;;; ─── Event dedup cache ────────────────────────────────────────────────

(defn seen-event?
  "True if event-id has been seen and not yet expired."
  [pool event-id]
  (let [expires (get @(:dedup pool) event-id)]
    (and (some? expires) (> expires (js/Date.now)))))

(defn mark-seen!
  "Record event-id as seen for :dedup-ttl-ms ms."
  [pool event-id]
  (let [expires (+ (js/Date.now) (or (:dedup-ttl-ms pool) 300000))]
    (swap! (:dedup pool) assoc event-id expires)))

(defn prune-dedup!
  "Remove expired entries from the dedup cache. Call periodically."
  [pool]
  (let [now (js/Date.now)]
    (swap! (:dedup pool)
           (fn [m]
             (into {} (filter (fn [[_ expires]] (> expires now)) m))))))
