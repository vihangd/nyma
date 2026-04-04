(ns state.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.state :refer [create-store create-agent-store core-reducers]]))

(describe "create-store" (fn []
  (it "returns initial state"
    (fn []
      (let [store (create-store {:count 0})]
        (-> (expect ((:get-state store))) (.toEqual #js {:count 0})))))

  (it "dispatch! applies reducer and updates state"
    (fn []
      (let [store (create-store {:count 0})]
        ((:register store) :increment (fn [state _] (update state :count inc)))
        ((:dispatch! store) :increment {})
        (-> (expect (:count ((:get-state store)))) (.toBe 1)))))

  (it "dispatch! applies multiple reducers for same event"
    (fn []
      (let [store (create-store {:a 0 :b 0})]
        ((:register store) :bump (fn [state _] (update state :a inc)))
        ((:register store) :bump (fn [state _] (update state :b inc)))
        ((:dispatch! store) :bump {})
        (-> (expect (:a ((:get-state store)))) (.toBe 1))
        (-> (expect (:b ((:get-state store)))) (.toBe 1)))))

  (it "records history"
    (fn []
      (let [store (create-store {:x 0})]
        ((:register store) :set-x (fn [state data] (assoc state :x (:value data))))
        ((:dispatch! store) :set-x {:value 42})
        (let [h ((:history store))]
          (-> (expect (count h)) (.toBe 1))
          (-> (expect (:type (first h))) (.toBe :set-x))))))

  (it "notifies subscribers on dispatch"
    (fn []
      (let [store    (create-store {:v 0})
            captured (atom nil)]
        ((:register store) :set-v (fn [state data] (assoc state :v (:v data))))
        ((:subscribe store) (fn [evt-type state] (reset! captured {:evt evt-type :v (:v state)})))
        ((:dispatch! store) :set-v {:v 99})
        (-> (expect (:evt @captured)) (.toBe :set-v))
        (-> (expect (:v @captured)) (.toBe 99)))))

  (it "unsubscribe stops notifications"
    (fn []
      (let [store (create-store {:v 0})
            count-atom (atom 0)]
        ((:register store) :bump (fn [state _] (update state :v inc)))
        (let [unsub ((:subscribe store) (fn [_ _] (swap! count-atom inc)))]
          ((:dispatch! store) :bump {})
          (unsub)
          ((:dispatch! store) :bump {}))
        (-> (expect @count-atom) (.toBe 1)))))

  (it "unknown event type does not change state but records history"
    (fn []
      (let [store (create-store {:x 1})]
        ((:dispatch! store) :nonexistent {})
        (-> (expect (:x ((:get-state store)))) (.toBe 1))
        ;; History IS recorded even for unknown events (no reducer, but tracked)
        (-> (expect (count ((:history store)))) (.toBe 1)))))

  (it "backwards compat: deref returns state"
    (fn []
      (let [store (create-store {:x 42})]
        (-> (expect (:x ((:deref store)))) (.toBe 42)))))

  (it "backwards compat: swap applies function"
    (fn []
      (let [store (create-store {:count 0})]
        ((:swap store) (fn [s] (update s :count inc)))
        (-> (expect (:count ((:get-state store)))) (.toBe 1)))))

  (it "backwards compat: reset replaces state"
    (fn []
      (let [store (create-store {:old true})]
        ((:reset store) {:new true})
        (-> (expect (:new ((:get-state store)))) (.toBe true)))))))

(describe "create-agent-store" (fn []
  (it "pre-registers core reducers"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil})]
        ((:dispatch! store) :message-added {:message {:role "user" :content "hi"}})
        (-> (expect (count (:messages ((:get-state store))))) (.toBe 1)))))

  (it "message-added appends to messages"
    (fn []
      (let [store (create-agent-store {:messages [{:role "user" :content "first"}]
                                       :active-tools #{} :model nil})]
        ((:dispatch! store) :message-added {:message {:role "assistant" :content "second"}})
        (-> (expect (count (:messages ((:get-state store))))) (.toBe 2))
        (-> (expect (:content (second (:messages ((:get-state store)))))) (.toBe "second")))))

  (it "messages-cleared empties messages"
    (fn []
      (let [store (create-agent-store {:messages [{:role "user" :content "hi"}]
                                       :active-tools #{} :model nil})]
        ((:dispatch! store) :messages-cleared {})
        (-> (expect (count (:messages ((:get-state store))))) (.toBe 0)))))

  (it "tools-changed updates active tools"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil})]
        ((:dispatch! store) :tools-changed {:active-tools #{"read" "write"}})
        (let [tools (:active-tools ((:get-state store)))]
          (-> (expect (contains? tools "read")) (.toBe true))))))

  (it "model-changed updates model"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil})]
        ((:dispatch! store) :model-changed {:model "gpt-4"})
        (-> (expect (:model ((:get-state store)))) (.toBe "gpt-4")))))))

(describe "tool-call-started / tool-call-ended reducers" (fn []
  (it "tool-call-started adds entry with status running"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})]
        ((:dispatch! store) :tool-call-started
          {:exec-id "abc-123" :tool-name "read" :args {:path "/tmp/x"} :start-time 1000})
        (let [tc (:tool-calls ((:get-state store)))]
          (-> (expect (:status (get tc "abc-123"))) (.toBe "running"))
          (-> (expect (:tool-name (get tc "abc-123"))) (.toBe "read"))))))

  (it "tool-call-started preserves existing tool calls"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil
                                       :tool-calls {"existing" {:tool-name "bash" :status "running"}}})]
        ((:dispatch! store) :tool-call-started
          {:exec-id "new-1" :tool-name "read" :args {} :start-time 2000})
        (let [tc (:tool-calls ((:get-state store)))]
          (-> (expect (get tc "existing")) (.toBeTruthy))
          (-> (expect (get tc "new-1")) (.toBeTruthy))))))

  (it "tool-call-ended updates status to done with duration and result"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil
                                       :tool-calls {"abc" {:tool-name "read" :status "running"}}})]
        ((:dispatch! store) :tool-call-ended
          {:exec-id "abc" :duration 150 :result "file contents"})
        (let [entry (get (:tool-calls ((:get-state store))) "abc")]
          (-> (expect (:status entry)) (.toBe "done"))
          (-> (expect (:duration entry)) (.toBe 150))
          (-> (expect (:result entry)) (.toBe "file contents"))))))

  (it "tool-call-ended does not affect other entries"
    (fn []
      (let [store (create-agent-store {:messages [] :active-tools #{} :model nil
                                       :tool-calls {"a" {:tool-name "read" :status "running"}
                                                    "b" {:tool-name "bash" :status "running"}}})]
        ((:dispatch! store) :tool-call-ended {:exec-id "a" :duration 100 :result "done"})
        (let [tc (:tool-calls ((:get-state store)))]
          (-> (expect (:status (get tc "a"))) (.toBe "done"))
          (-> (expect (:status (get tc "b"))) (.toBe "running"))))))))
