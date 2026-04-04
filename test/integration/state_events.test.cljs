(ns integration.state-events.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.state :refer [create-agent-store]]
            [agent.events :refer [create-event-bus]]))

;; Integration: state store + event bus working together

(defn ^:async test-dispatch-emits-event []
  (let [events (create-event-bus)
        store  (create-agent-store {:messages [] :active-tools #{} :model nil})
        captured (atom nil)]
    ;; Subscribe to state changes and emit events
    ((:subscribe store) (fn [evt-type state]
                          ((:emit events) (str "state:" (str evt-type))
                            {:state state})))
    ((:on events) "state:message-added"
      (fn [data] (reset! captured (:state data))))
    ((:dispatch! store) :message-added {:message {:role "user" :content "hi"}})
    (-> (expect (count (:messages @captured))) (.toBe 1))))

(defn ^:async test-state-history-replay []
  (let [store (create-agent-store {:messages [] :active-tools #{} :model nil})]
    ((:dispatch! store) :message-added {:message {:role "user" :content "first"}})
    ((:dispatch! store) :message-added {:message {:role "assistant" :content "second"}})
    ((:dispatch! store) :messages-cleared {})
    (let [h ((:history store))]
      (-> (expect (count h)) (.toBe 3))
      (-> (expect (:type (first h))) (.toBe :message-added))
      (-> (expect (:type (last h))) (.toBe :messages-cleared)))))

(defn ^:async test-custom-reducer-registered []
  (let [store (create-agent-store {:messages [] :active-tools #{} :model nil :custom 0})]
    ((:register store) :custom-bump (fn [state _] (update state :custom inc)))
    ((:dispatch! store) :custom-bump {})
    ((:dispatch! store) :custom-bump {})
    (-> (expect (:custom ((:get-state store)))) (.toBe 2))))

(defn ^:async test-subscriber-sees-all-transitions []
  (let [store (create-agent-store {:messages [] :active-tools #{} :model nil})
        events-seen (atom [])]
    ((:subscribe store) (fn [evt-type _state]
                          (swap! events-seen conj evt-type)))
    ((:dispatch! store) :message-added {:message {:role "user" :content "hi"}})
    ((:dispatch! store) :tools-changed {:active-tools #{"read"}})
    ((:dispatch! store) :model-changed {:model "gpt-4"})
    (-> (expect (count @events-seen)) (.toBe 3))
    (-> (expect (clj->js @events-seen))
        (.toEqual #js [:message-added :tools-changed :model-changed]))))

(defn ^:async test-emit-async-with-state []
  (let [events (create-event-bus)
        store  (create-agent-store {:messages [] :active-tools #{} :model nil})
        result (atom nil)]
    ((:on events) "check-state"
      (fn [_] (reset! result (count (:messages ((:get-state store)))))))
    ((:dispatch! store) :message-added {:message {:role "user" :content "test"}})
    (js-await ((:emit-async events) "check-state" {}))
    (-> (expect @result) (.toBe 1))))

(describe "integration: state + events" (fn []
  (it "dispatch triggers event bus" test-dispatch-emits-event)
  (it "history records all transitions" test-state-history-replay)
  (it "custom reducers work alongside core" test-custom-reducer-registered)
  (it "subscriber sees all event types" test-subscriber-sees-all-transitions)
  (it "async event can read state" test-emit-async-with-state)))
