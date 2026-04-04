(ns events.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.events :refer [create-event-bus all-event-types run-handlers-async]]))

(describe "agent.events"
  (fn []
    (it "create-event-bus returns object with on, emit, off"
      (fn []
        (let [bus (create-event-bus)]
          (-> (expect (fn? (:on bus))) (.toBe true))
          (-> (expect (fn? (:emit bus))) (.toBe true))
          (-> (expect (fn? (:off bus))) (.toBe true)))))

    (it "calls registered handler when matching event is emitted"
      (fn []
        (let [bus     (create-event-bus)
              called  (atom false)]
          ((:on bus) "test_event" (fn [_] (reset! called true)))
          ((:emit bus) "test_event" {})
          (-> (expect @called) (.toBe true)))))

    (it "handler receives the emitted data"
      (fn []
        (let [bus      (create-event-bus)
              received (atom nil)]
          ((:on bus) "evt" (fn [data] (reset! received data)))
          ((:emit bus) "evt" {:value 42})
          (-> (expect (:value @received)) (.toBe 42)))))

    (it "does not call handler for non-matching event"
      (fn []
        (let [bus    (create-event-bus)
              called (atom false)]
          ((:on bus) "a" (fn [_] (reset! called true)))
          ((:emit bus) "b" {})
          (-> (expect @called) (.toBe false)))))

    (it "multiple handlers on same event called in registration order"
      (fn []
        (let [bus   (create-event-bus)
              order (atom [])]
          ((:on bus) "evt" (fn [_] (swap! order conj "first")))
          ((:on bus) "evt" (fn [_] (swap! order conj "second")))
          ((:emit bus) "evt" {})
          (-> (expect (count @order)) (.toBe 2))
          (-> (expect (first @order)) (.toBe "first"))
          (-> (expect (second @order)) (.toBe "second")))))

    (it "off removes a specific handler"
      (fn []
        (let [bus     (create-event-bus)
              called  (atom 0)
              handler (fn [_] (swap! called inc))]
          ((:on bus) "evt" handler)
          ((:emit bus) "evt" {})
          (-> (expect @called) (.toBe 1))
          ((:off bus) "evt" handler)
          ((:emit bus) "evt" {})
          (-> (expect @called) (.toBe 1)))))

    (it "off only removes the exact function reference"
      (fn []
        (let [bus    (create-event-bus)
              a-calls (atom 0)
              b-calls (atom 0)
              handler-a (fn [_] (swap! a-calls inc))
              handler-b (fn [_] (swap! b-calls inc))]
          ((:on bus) "evt" handler-a)
          ((:on bus) "evt" handler-b)
          ((:off bus) "evt" handler-a)
          ((:emit bus) "evt" {})
          (-> (expect @a-calls) (.toBe 0))
          (-> (expect @b-calls) (.toBe 1)))))

    (it "emitting event with no handlers does not throw"
      (fn []
        (let [bus (create-event-bus)]
          ((:emit bus) "ghost" {}))))

    (it "all-event-types is a non-empty vector"
      (fn []
        (-> (expect (> (count all-event-types) 0)) (.toBe true))))

    (it "all-event-types includes before_tool_call"
      (fn []
        (-> (expect (some #(= % "before_tool_call") all-event-types)) (.toBeTruthy))))

    ;; Error boundary tests
    (it "throwing handler does not prevent subsequent handlers"
      (fn []
        (let [bus    (create-event-bus)
              called (atom false)]
          ((:on bus) "evt" (fn [_] (throw (js/Error. "boom"))))
          ((:on bus) "evt" (fn [_] (reset! called true)))
          ((:emit bus) "evt" {})
          (-> (expect @called) (.toBe true)))))

    (it "error is logged to console.error"
      (fn []
        (let [bus     (create-event-bus)
              orig    js/console.error
              logged  (atom nil)]
          (set! js/console.error (fn [& args] (reset! logged args)))
          ((:on bus) "evt" (fn [_] (throw (js/Error. "test-error"))))
          ((:emit bus) "evt" {})
          (set! js/console.error orig)
          (-> (expect @logged) (.toBeTruthy)))))

    (it "non-throwing handlers receive correct data after a throw"
      (fn []
        (let [bus      (create-event-bus)
              received (atom nil)
              orig     js/console.error]
          (set! js/console.error (fn [& _]))
          ((:on bus) "evt" (fn [_] (throw (js/Error. "fail"))))
          ((:on bus) "evt" (fn [d] (reset! received d)))
          ((:emit bus) "evt" {:val 99})
          (set! js/console.error orig)
          (-> (expect (:val @received)) (.toBe 99)))))

    ;; Priority tests
    (it "default priority preserves registration order"
      (fn []
        (let [bus   (create-event-bus)
              order (atom [])]
          ((:on bus) "evt" (fn [_] (swap! order conj "a")))
          ((:on bus) "evt" (fn [_] (swap! order conj "b")))
          ((:emit bus) "evt" {})
          (-> (expect (first @order)) (.toBe "a"))
          (-> (expect (second @order)) (.toBe "b")))))

    (it "higher priority handlers run first"
      (fn []
        (let [bus   (create-event-bus)
              order (atom [])]
          ((:on bus) "evt" (fn [_] (swap! order conj "low")) 0)
          ((:on bus) "evt" (fn [_] (swap! order conj "high")) 10)
          ((:emit bus) "evt" {})
          (-> (expect (first @order)) (.toBe "high"))
          (-> (expect (second @order)) (.toBe "low")))))

    (it "mixed priorities sort correctly"
      (fn []
        (let [bus   (create-event-bus)
              order (atom [])]
          ((:on bus) "evt" (fn [_] (swap! order conj "mid")) 5)
          ((:on bus) "evt" (fn [_] (swap! order conj "high")) 10)
          ((:on bus) "evt" (fn [_] (swap! order conj "low")) 1)
          ((:emit bus) "evt" {})
          (-> (expect (nth @order 0)) (.toBe "high"))
          (-> (expect (nth @order 1)) (.toBe "mid"))
          (-> (expect (nth @order 2)) (.toBe "low")))))

    (it "off works with prioritized handlers"
      (fn []
        (let [bus     (create-event-bus)
              called  (atom 0)
              handler (fn [_] (swap! called inc))]
          ((:on bus) "evt" handler 5)
          ((:emit bus) "evt" {})
          (-> (expect @called) (.toBe 1))
          ((:off bus) "evt" handler)
          ((:emit bus) "evt" {})
          (-> (expect @called) (.toBe 1)))))))

;; --- Async emit tests ---

(defn ^:async test-emit-async-awaits-handler []
  (let [bus     (create-event-bus)
        result  (atom nil)]
    ((:on bus) "evt" (fn [_]
                       (js/Promise. (fn [resolve _]
                                      (js/setTimeout (fn []
                                                       (reset! result "done")
                                                       (resolve nil))
                                                     10)))))
    (js-await ((:emit-async bus) "evt" {}))
    (-> (expect @result) (.toBe "done"))))

(defn ^:async test-emit-async-preserves-priority []
  (let [bus   (create-event-bus)
        order (atom [])]
    ((:on bus) "evt" (fn [_] (swap! order conj "low")) 0)
    ((:on bus) "evt" (fn [_]
                       (js/Promise.resolve (swap! order conj "high")))
      10)
    (js-await ((:emit-async bus) "evt" {}))
    (-> (expect (first @order)) (.toBe "high"))))

(defn ^:async test-emit-async-error-isolation []
  (let [bus    (create-event-bus)
        called (atom false)
        orig   js/console.error]
    (set! js/console.error (fn [& _]))
    ((:on bus) "evt" (fn [_] (js/Promise.reject (js/Error. "async-boom"))))
    ((:on bus) "evt" (fn [_] (reset! called true)))
    (js-await ((:emit-async bus) "evt" {}))
    (set! js/console.error orig)
    (-> (expect @called) (.toBe true))))

(defn ^:async test-emit-async-mixed-sync-async []
  (let [bus    (create-event-bus)
        order  (atom [])]
    ((:on bus) "evt" (fn [_] (swap! order conj "sync")))
    ((:on bus) "evt" (fn [_] (js/Promise.resolve (swap! order conj "async"))))
    (js-await ((:emit-async bus) "evt" {}))
    (-> (expect (count @order)) (.toBe 2))))

(defn ^:async test-emit-async-returns-after-all []
  (let [bus    (create-event-bus)
        done   (atom 0)]
    ((:on bus) "evt" (fn [_]
                       (js/Promise. (fn [resolve _]
                                      (js/setTimeout (fn [] (swap! done inc) (resolve nil)) 5)))))
    ((:on bus) "evt" (fn [_]
                       (js/Promise. (fn [resolve _]
                                      (js/setTimeout (fn [] (swap! done inc) (resolve nil)) 10)))))
    (js-await ((:emit-async bus) "evt" {}))
    (-> (expect @done) (.toBe 2))))

(describe "emit-async" (fn []
  (it "awaits async handlers" test-emit-async-awaits-handler)
  (it "preserves priority ordering" test-emit-async-preserves-priority)
  (it "isolates async errors" test-emit-async-error-isolation)
  (it "handles mixed sync and async" test-emit-async-mixed-sync-async)
  (it "completes after all handlers finish" test-emit-async-returns-after-all)))
