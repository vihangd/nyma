(ns events-v2.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus all-event-types]]))

;; ── emit-collect tests ───────────────────────────────────────

(defn ^:async test-collect-empty []
  (let [bus (create-event-bus)
        result (js-await ((:emit-collect bus) "test_event" #js {:data 1}))]
    (-> (expect (count result)) (.toBe 0))))

(defn ^:async test-collect-nil-return []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] nil))
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (count result)) (.toBe 0)))))

(defn ^:async test-collect-single-return []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] {"block" true "reason" "nope"}))
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (get result "block")) (.toBe true))
      (-> (expect (get result "reason")) (.toBe "nope")))))

(defn ^:async test-collect-boolean-or []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] {"block" false}) 10)
    ((:on bus) "test" (fn [_] {"block" true}) 5)
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (get result "block")) (.toBe true)))))

(defn ^:async test-collect-concat-collections []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] {"inject-messages" [{:role "user" :content "a"}]}))
    ((:on bus) "test" (fn [_] {"inject-messages" [{:role "user" :content "b"}]}))
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (count (get result "inject-messages"))) (.toBe 2)))))

(defn ^:async test-collect-scalar-last-wins []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] {"input" "first"}) 10)
    ((:on bus) "test" (fn [_] {"input" "second"}) 5)
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (get result "input")) (.toBe "second")))))

(defn ^:async test-collect-async-handler []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] (js/Promise.resolve #js {:block true})))
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (get result "block")) (.toBe true)))))

(defn ^:async test-collect-mixed-sync-async []
  (let [bus (create-event-bus)]
    ((:on bus) "test" (fn [_] {"cancel" true}))
    ((:on bus) "test" (fn [_] (js/Promise.resolve #js {:reason "async handler"})))
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (-> (expect (get result "cancel")) (.toBe true))
      (-> (expect (get result "reason")) (.toBe "async handler")))))

(defn ^:async test-collect-error-isolation []
  (let [bus  (create-event-bus)
        orig js/console.error]
    (set! js/console.error (fn [& _]))
    ((:on bus) "test" (fn [_] (throw (js/Error. "boom"))) 10)
    ((:on bus) "test" (fn [_] {"block" true}) 5)
    (let [result (js-await ((:emit-collect bus) "test" #js {}))]
      (set! js/console.error orig)
      (-> (expect (get result "block")) (.toBe true)))))

(defn ^:async test-collect-priority-ordering []
  (let [bus   (create-event-bus)
        order (atom [])]
    ((:on bus) "test" (fn [_] (swap! order conj "low") nil) 1)
    ((:on bus) "test" (fn [_] (swap! order conj "high") nil) 10)
    (js-await ((:emit-collect bus) "test" #js {}))
    (-> (expect (first @order)) (.toBe "high"))
    (-> (expect (second @order)) (.toBe "low"))))

;; ── Handler cache tests ──────────────────────────────────────

(defn test-cache-invalidation []
  (let [bus (create-event-bus)
        order (atom [])]
    ((:on bus) "test" (fn [_] (swap! order conj "low")) 1)
    ((:on bus) "test" (fn [_] (swap! order conj "high")) 10)
    ((:emit bus) "test" nil)
    (-> (expect (first @order)) (.toBe "high"))
    ;; Add another handler — cache should invalidate
    (reset! order [])
    ((:on bus) "test" (fn [_] (swap! order conj "mid")) 5)
    ((:emit bus) "test" nil)
    (-> (expect (first @order)) (.toBe "high"))
    (-> (expect (second @order)) (.toBe "mid"))))

(defn test-off-invalidates-cache []
  (let [bus (create-event-bus)
        h   (fn [_] nil)]
    ((:on bus) "test" h 10)
    ((:emit bus) "test" nil)  ;; caches
    ((:off bus) "test" h)
    ;; After off, no handlers should run
    (let [called (atom false)]
      ((:emit bus) "test" nil)
      (-> (expect @called) (.toBe false)))))

;; ── New event types ──────────────────────────────────────────

(defn test-new-event-types []
  (let [types (set all-event-types)]
    (-> (expect (contains? types "before_provider_request")) (.toBe true))
    (-> (expect (contains? types "tool_execution_update")) (.toBe true))
    (-> (expect (contains? types "model_select")) (.toBe true))
    (-> (expect (contains? types "user_bash")) (.toBe true))
    (-> (expect (contains? types "session_before_fork")) (.toBe true))
    (-> (expect (contains? types "session_before_tree")) (.toBe true))
    (-> (expect (contains? types "session_tree")) (.toBe true))
    (-> (expect (contains? types "session_directory")) (.toBe true))
    (-> (expect (contains? types "resources_discover")) (.toBe true))
    (-> (expect (contains? types "reload")) (.toBe true))))

;; ── describe blocks ──────────────────────────────────────────

(describe "emit-collect" (fn []
  (it "returns empty map when no handlers" test-collect-empty)
  (it "returns empty map for nil returns" test-collect-nil-return)
  (it "collects a single handler return" test-collect-single-return)
  (it "merges boolean keys with OR" test-collect-boolean-or)
  (it "concatenates collection keys" test-collect-concat-collections)
  (it "last-writer-wins for scalar keys" test-collect-scalar-last-wins)
  (it "handles async (Promise) returns" test-collect-async-handler)
  (it "handles mixed sync/async returns" test-collect-mixed-sync-async)
  (it "isolates errors from other handlers" test-collect-error-isolation)
  (it "respects handler priority" test-collect-priority-ordering)))

(describe "handler sort cache" (fn []
  (it "invalidates on new handler registration" test-cache-invalidation)
  (it "invalidates when handler removed" test-off-invalidates-cache)))

(describe "new event types" (fn []
  (it "all-event-types includes pi-compat events" test-new-event-types)))
