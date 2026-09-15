(ns state.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.middleware :refer [skill-allows-tool?]]
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

  (it "subscribers receive the dispatch payload as a third argument"
    (fn []
      (let [store    (create-agent-store {})
            captured (atom nil)]
        ((:subscribe store) (fn [_evt _state data] (reset! captured data)))
        ((:dispatch! store) :usage-updated {:input-tokens 7 :output-tokens 0 :cost 0})
        (-> (expect (:input-tokens @captured)) (.toBe 7)))))

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

  (it "messages-cleared ends every active skill, so its tool allowance does not outlive /new"
      (fn []
        (let [store (create-agent-store {:messages [{:role "user" :content "hi"}]
                                         :active-skills #{"deploy"}
                                         :skill-allowed-tools {"deploy" #{"bash"}}
                                         :active-tools #{} :model nil})]
          (-> (expect (skill-allows-tool? ((:get-state store)) "bash")) (.toBe true))
          ((:dispatch! store) :messages-cleared {})
          (let [s ((:get-state store))]
            (-> (expect (skill-allows-tool? s "bash")) (.toBe false))
            (-> (expect (count (:active-skills s))) (.toBe 0))
            (-> (expect (count (:skill-allowed-tools s))) (.toBe 0))))))

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

;; ── usage: the cache split ────────────────────────────────────────
;; The loop read cacheReadTokens per turn for costing and then dropped them, so
;; nothing kept a session total and `-p --output-format json` reported
;; cache_read_input_tokens: 0 for every session ever run. Cache hit rate is the
;; single largest cost lever on an agent loop; it has to be countable.
(describe "state/usage-updated cache accounting"
          (fn []
            (it "accumulates cache reads and writes across turns"
                (fn []
                  (let [store (create-agent-store {})]
                    ((:dispatch! store) :usage-updated
         {:input-tokens 1000 :output-tokens 50
          :cache-read-tokens 800 :cache-write-tokens 120 :cost 0.01})
                    ((:dispatch! store) :usage-updated
         {:input-tokens 1200 :output-tokens 60
          :cache-read-tokens 1100 :cache-write-tokens 0 :cost 0.01})
                    (let [st ((:get-state store))]
                      (-> (expect (:total-cache-read-tokens st)) (.toBe 1900))
                      (-> (expect (:total-cache-write-tokens st)) (.toBe 120))
                      ;; input already includes them — a breakdown, not an addition
                      (-> (expect (:total-input-tokens st)) (.toBe 2200))
                      (-> (expect (:turn-count st)) (.toBe 2))))))

            (it "treats a provider that reports no cache detail as zero, not nil"
                (fn []
                  (let [store (create-agent-store {})]
                    ((:dispatch! store) :usage-updated
         {:input-tokens 100 :output-tokens 10 :cost 0})
                    (-> (expect (:total-cache-read-tokens ((:get-state store)))) (.toBe 0)))))))
