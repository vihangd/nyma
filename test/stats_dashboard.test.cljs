(ns stats-dashboard.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [agent.sessions.storage :refer [create-sqlite-store]]))

;;; ─── Aggregate usage queries ────────────────────────────

(def ^:private test-db-path ":memory:")

(defn- seed-usage [store]
  ;; Insert usage records for testing aggregation
  ((:upsert-usage store) {:session-file "s1" :turn-id "t1" :model "claude-sonnet"
                           :input-tokens 1000 :output-tokens 500 :cost-usd 0.05
                           :timestamp 1712700000000})
  ((:upsert-usage store) {:session-file "s1" :turn-id "t2" :model "claude-sonnet"
                           :input-tokens 2000 :output-tokens 800 :cost-usd 0.08
                           :timestamp 1712700060000})
  ((:upsert-usage store) {:session-file "s2" :turn-id "t3" :model "gpt-4o-mini"
                           :input-tokens 500 :output-tokens 200 :cost-usd 0.01
                           :timestamp 1712786400000})
  ((:upsert-usage store) {:session-file "s2" :turn-id "t4" :model "claude-opus"
                           :input-tokens 5000 :output-tokens 2000 :cost-usd 0.50
                           :timestamp 1712786460000}))

(describe "stats-dashboard:get-usage-totals" (fn []
  (let [store (atom nil)]
    (beforeEach (fn []
      (let [s (create-sqlite-store test-db-path)]
        ((:init-schema s))
        (reset! store s))))
    (afterEach (fn []
      (when @store ((:close @store)))))

    (it "returns zeros for empty database"
      (fn []
        (let [totals ((:get-usage-totals @store))]
          (-> (expect (:total-input totals)) (.toBe 0))
          (-> (expect (:total-cost totals)) (.toBe 0))
          (-> (expect (:turn-count totals)) (.toBe 0)))))

    (it "sums all usage correctly"
      (fn []
        (seed-usage @store)
        (let [totals ((:get-usage-totals @store))]
          (-> (expect (:total-input totals)) (.toBe 8500))
          (-> (expect (:total-output totals)) (.toBe 3500))
          (-> (expect (:turn-count totals)) (.toBe 4))
          (-> (expect (:session-count totals)) (.toBe 2)))))

    (it "total cost is sum of all costs"
      (fn []
        (seed-usage @store)
        (let [totals ((:get-usage-totals @store))]
          ;; 0.05 + 0.08 + 0.01 + 0.50 = 0.64
          (-> (expect (js/Math.round (* (:total-cost totals) 100)))
              (.toBe 64))))))))

(describe "stats-dashboard:get-usage-by-model" (fn []
  (let [store (atom nil)]
    (beforeEach (fn []
      (let [s (create-sqlite-store test-db-path)]
        ((:init-schema s))
        (reset! store s))))
    (afterEach (fn []
      (when @store ((:close @store)))))

    (it "returns empty vector for no usage"
      (fn []
        (-> (expect (count ((:get-usage-by-model @store)))) (.toBe 0))))

    (it "groups by model with correct counts"
      (fn []
        (seed-usage @store)
        (let [by-model ((:get-usage-by-model @store))]
          (-> (expect (count by-model)) (.toBe 3))
          ;; Ordered by cost desc — opus ($0.50) first
          (-> (expect (:model (first by-model))) (.toBe "claude-opus"))
          (-> (expect (:turns (first by-model))) (.toBe 1)))))

    (it "sonnet has 2 turns"
      (fn []
        (seed-usage @store)
        (let [by-model ((:get-usage-by-model @store))
              sonnet   (first (filter #(= (:model %) "claude-sonnet") by-model))]
          (-> (expect (:turns sonnet)) (.toBe 2))))))))

(describe "stats-dashboard:get-usage-by-day" (fn []
  (let [store (atom nil)]
    (beforeEach (fn []
      (let [s (create-sqlite-store test-db-path)]
        ((:init-schema s))
        (reset! store s))))
    (afterEach (fn []
      (when @store ((:close @store)))))

    (it "returns empty vector for no usage"
      (fn []
        (-> (expect (count ((:get-usage-by-day @store) 7))) (.toBe 0))))

    (it "groups by day"
      (fn []
        (seed-usage @store)
        (let [by-day ((:get-usage-by-day @store) 30)]
          ;; 2 different days based on timestamps
          (-> (expect (count by-day)) (.toBeGreaterThanOrEqual 1)))))

    (it "respects limit parameter"
      (fn []
        (seed-usage @store)
        (let [by-day ((:get-usage-by-day @store) 1)]
          (-> (expect (count by-day)) (.toBeLessThanOrEqual 1))))))))
