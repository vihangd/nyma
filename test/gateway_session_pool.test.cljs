(ns gateway-session-pool.test
  "Unit tests for gateway.session-pool — pure atom semantics, lane
   serialization, eviction policies, dedup cache."
  (:require ["bun:test" :refer [describe it expect]]
            [gateway.session-pool :as pool]))

;;; ─── create-session-pool ─────────────────────────────────────

(describe "gateway.session-pool/create-session-pool" (fn []
                                                       (it "uses provided opts"
                                                           (fn []
                                                             (let [p (pool/create-session-pool {:idle-evict-ms 100
                                                                                                :dedup-ttl-ms  50
                                                                                                :default-policy :idle-evict})]
                                                               (-> (expect (:idle-evict-ms p)) (.toBe 100))
                                                               (-> (expect (:dedup-ttl-ms p)) (.toBe 50))
                                                               (-> (expect (:default-policy p)) (.toBe :idle-evict)))))

                                                       (it "defaults when opts are empty"
                                                           (fn []
                                                             (let [p (pool/create-session-pool)]
                                                               (-> (expect (:idle-evict-ms p)) (.toBe 3600000))
                                                               (-> (expect (:dedup-ttl-ms p)) (.toBe 300000))
                                                               (-> (expect (:default-policy p)) (.toBe :persistent)))))

                                                       (it "starts with empty sessions and dedup atoms"
                                                           (fn []
                                                             (let [p (pool/create-session-pool)]
                                                               (-> (expect (count @(:sessions p))) (.toBe 0))
                                                               (-> (expect (count @(:dedup p))) (.toBe 0)))))))

;;; ─── get-entry / get-or-create-entry! ────────────────────────

(describe "gateway.session-pool/get-entry" (fn []
                                             (it "returns nil for unknown key"
                                                 (fn []
                                                   (let [p (pool/create-session-pool)]
                                                     (-> (expect (pool/get-entry p "nope")) (.toBeUndefined)))))

                                             (it "returns the same entry twice for get-or-create-entry!"
                                                 (fn []
                                                   (let [p (pool/create-session-pool)
                                                         e1 (pool/get-or-create-entry! p "k1")
                                                         e2 (pool/get-or-create-entry! p "k1")]
                                                     (-> (expect (identical? e1 e2)) (.toBe true)))))

                                             (it "creates entries with the pool's default policy"
                                                 (fn []
                                                   (let [p (pool/create-session-pool {:default-policy :ephemeral})
                                                         e (pool/get-or-create-entry! p "k1")]
                                                     (-> (expect (:policy e)) (.toBe :ephemeral)))))

                                             (it "opts :policy overrides default-policy"
                                                 (fn []
                                                   (let [p (pool/create-session-pool {:default-policy :ephemeral})
                                                         e (pool/get-or-create-entry! p "k1" {:policy :idle-evict})]
                                                     (-> (expect (:policy e)) (.toBe :idle-evict)))))))

;;; ─── enqueue! lane serialization ─────────────────────────────

(defn ^:async test-enqueue-runs-in-order []
  (let [p        (pool/create-session-pool)
        order    (atom [])
        settle   (fn [ms label]
                   (js/Promise. (fn [resolve]
                                  (js/setTimeout
                                   (fn []
                                     (swap! order conj label)
                                     (resolve label))
                                   ms))))]
    ;; Enqueue 3 thunks on the same session key with descending delays
    (let [p1 (pool/enqueue! p "k1" (fn [] (settle 30 :a)))
          p2 (pool/enqueue! p "k1" (fn [] (settle 20 :b)))
          p3 (pool/enqueue! p "k1" (fn [] (settle 10 :c)))]
      (js-await (js/Promise.all #js [p1 p2 p3]))
      ;; If lane serialization works, order must be [:a :b :c], not [:c :b :a]
      (-> (expect (nth @order 0)) (.toBe :a))
      (-> (expect (nth @order 1)) (.toBe :b))
      (-> (expect (nth @order 2)) (.toBe :c)))))

(defn ^:async test-enqueue-parallel-across-keys []
  (let [p      (pool/create-session-pool)
        order  (atom [])
        settle (fn [ms label]
                 (js/Promise. (fn [resolve]
                                (js/setTimeout
                                 (fn []
                                   (swap! order conj label)
                                   (resolve label))
                                 ms))))]
    ;; Different session keys → can run concurrently, so fastest settles first
    (let [p1 (pool/enqueue! p "k1" (fn [] (settle 30 :slow)))
          p2 (pool/enqueue! p "k2" (fn [] (settle 5  :fast)))]
      (js-await (js/Promise.all #js [p1 p2]))
      (-> (expect (first @order)) (.toBe :fast))
      (-> (expect (second @order)) (.toBe :slow)))))

(defn ^:async test-enqueue-error-does-not-stall-lane []
  (let [p     (pool/create-session-pool)
        later (atom nil)]
    ;; First thunk throws; second must still run
    (js-await
     (pool/enqueue! p "k1" (fn [] (throw (js/Error. "boom")))))
    (js-await
     (pool/enqueue! p "k1" (fn [] (reset! later :ran) :ok)))
    (-> (expect @later) (.toBe :ran))))

(describe "gateway.session-pool/enqueue! — lane serialization" (fn []
                                                                 (it "runs thunks in registration order per session key"
                                                                     test-enqueue-runs-in-order)
                                                                 (it "runs lanes concurrently across different keys"
                                                                     test-enqueue-parallel-across-keys)
                                                                 (it "error in one thunk does not stall the lane"
                                                                     test-enqueue-error-does-not-stall-lane)))

;;; ─── get-data / set-data! ────────────────────────────────────

(describe "gateway.session-pool/get-data + set-data!" (fn []
                                                        (it "round-trips a value through a session's data atom"
                                                            (fn []
                                                              (let [p (pool/create-session-pool)]
                                                                (pool/set-data! p "k1" :sdk-session "session-obj")
                                                                (-> (expect (pool/get-data p "k1" :sdk-session)) (.toBe "session-obj")))))

                                                        (it "returns nil for missing session or missing key"
                                                            (fn []
                                                              (let [p (pool/create-session-pool)]
                                                                (-> (expect (pool/get-data p "missing" :foo)) (.toBeUndefined))
                                                                (pool/set-data! p "k1" :foo "bar")
                                                                (-> (expect (pool/get-data p "k1" :absent-key)) (.toBeUndefined)))))))

;;; ─── evict! / evict-idle! / evict-ephemeral-data! ────────────

(describe "gateway.session-pool/evict!" (fn []
                                          (it "removes a session from the pool"
                                              (fn []
                                                (let [p (pool/create-session-pool)]
                                                  (pool/get-or-create-entry! p "k1")
                                                  (-> (expect (some? (pool/get-entry p "k1"))) (.toBe true))
                                                  (pool/evict! p "k1")
                                                  (-> (expect (pool/get-entry p "k1")) (.toBeUndefined)))))))

(describe "gateway.session-pool/evict-idle!" (fn []
                                               (it "evicts :idle-evict sessions past the TTL"
                                                   (fn []
                                                     (let [p (pool/create-session-pool {:idle-evict-ms 1})
                                                           e (pool/get-or-create-entry! p "k1" {:policy :idle-evict})]
          ;; Force last-active into the far past
                                                       (reset! (:last-active e) 0)
                                                       (pool/evict-idle! p)
                                                       (-> (expect (pool/get-entry p "k1")) (.toBeUndefined)))))

                                               (it "does NOT evict :persistent sessions"
                                                   (fn []
                                                     (let [p (pool/create-session-pool {:idle-evict-ms 1})
                                                           e (pool/get-or-create-entry! p "k1" {:policy :persistent})]
                                                       (reset! (:last-active e) 0)
                                                       (pool/evict-idle! p)
                                                       (-> (expect (some? (pool/get-entry p "k1"))) (.toBe true)))))

                                               (it "does NOT evict :idle-evict sessions still within TTL"
                                                   (fn []
                                                     (let [p (pool/create-session-pool {:idle-evict-ms 10000000})
                                                           _ (pool/get-or-create-entry! p "k1" {:policy :idle-evict})]
                                                       (pool/evict-idle! p)
                                                       (-> (expect (some? (pool/get-entry p "k1"))) (.toBe true)))))))

(describe "gateway.session-pool/evict-ephemeral-data!" (fn []
                                                         (it "clears :data for :ephemeral sessions"
                                                             (fn []
                                                               (let [p (pool/create-session-pool)
                                                                     _ (pool/get-or-create-entry! p "k1" {:policy :ephemeral})
                                                                     _ (pool/set-data! p "k1" :sdk-session "s")]
                                                                 (pool/evict-ephemeral-data! p "k1")
                                                                 (-> (expect (pool/get-data p "k1" :sdk-session)) (.toBeUndefined))
          ;; Entry itself remains
                                                                 (-> (expect (some? (pool/get-entry p "k1"))) (.toBe true)))))

                                                         (it "leaves non-ephemeral session data alone"
                                                             (fn []
                                                               (let [p (pool/create-session-pool)
                                                                     _ (pool/get-or-create-entry! p "k1" {:policy :persistent})
                                                                     _ (pool/set-data! p "k1" :sdk-session "s")]
                                                                 (pool/evict-ephemeral-data! p "k1")
                                                                 (-> (expect (pool/get-data p "k1" :sdk-session)) (.toBe "s")))))))

;;; ─── dedup cache ─────────────────────────────────────────────

(describe "gateway.session-pool/dedup cache" (fn []
                                               (it "seen-event? is false before mark-seen!"
                                                   (fn []
                                                     (let [p (pool/create-session-pool)]
                                                       (-> (expect (pool/seen-event? p "evt-1")) (.toBe false)))))

                                               (it "seen-event? is true after mark-seen!"
                                                   (fn []
                                                     (let [p (pool/create-session-pool {:dedup-ttl-ms 60000})]
                                                       (pool/mark-seen! p "evt-1")
                                                       (-> (expect (pool/seen-event? p "evt-1")) (.toBe true)))))

                                               (it "mark-seen! stores expiry in the future"
                                                   (fn []
                                                     (let [p   (pool/create-session-pool {:dedup-ttl-ms 100000})
                                                           now (js/Date.now)]
                                                       (pool/mark-seen! p "evt-1")
                                                       (let [exp (get @(:dedup p) "evt-1")]
                                                         (-> (expect (> exp now)) (.toBe true))
                                                         (-> (expect (<= exp (+ now 100000))) (.toBe true))))))

                                               (it "prune-dedup! removes expired entries"
                                                   (fn []
                                                     (let [p (pool/create-session-pool)]
          ;; Manually install an expired entry
                                                       (swap! (:dedup p) assoc "old" 1)
                                                       (swap! (:dedup p) assoc "new" (+ (js/Date.now) 60000))
                                                       (pool/prune-dedup! p)
                                                       (-> (expect (contains? @(:dedup p) "old")) (.toBe false))
                                                       (-> (expect (contains? @(:dedup p) "new")) (.toBe true)))))))

;;; ─── pool-stats ──────────────────────────────────────────────

(describe "gateway.session-pool/pool-stats" (fn []
                                              (it "reports total and by-policy counts"
                                                  (fn []
                                                    (let [p (pool/create-session-pool)]
                                                      (pool/get-or-create-entry! p "k1" {:policy :persistent})
                                                      (pool/get-or-create-entry! p "k2" {:policy :persistent})
                                                      (pool/get-or-create-entry! p "k3" {:policy :ephemeral})
                                                      (pool/mark-seen! p "evt-1")
                                                      (let [s (pool/pool-stats p)]
                                                        (-> (expect (:total-sessions s)) (.toBe 3))
                                                        (-> (expect (get (:by-policy s) :persistent)) (.toBe 2))
                                                        (-> (expect (get (:by-policy s) :ephemeral)) (.toBe 1))
                                                        (-> (expect (:dedup-cache-size s)) (.toBe 1))))))))
