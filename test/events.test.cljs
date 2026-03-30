(ns events.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.events :refer [create-event-bus all-event-types]]))

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
        (-> (expect (> (count all-event-types) 0)) (.toBe true))))))
