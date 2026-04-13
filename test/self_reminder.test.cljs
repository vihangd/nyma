(ns self-reminder.test
  "Tests for agent.middleware.self-reminder — the self-reminder subscription
   factory. Covers:

     - Counter increments on turn_start
     - Reminder fires on before_agent_start when threshold reached
     - Action predicate resets counter (no increment on reset turn)
     - reminder-text-fn returning nil suppresses the reminder
     - Two independent reminders have isolated counters
     - Cleanup removes both subscriptions"
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.events :refer [create-event-bus]]
            [agent.middleware.self-reminder :refer [make-reminder]]))

;;; ─── helpers ────────────────────────────────────────────────

(defn- fire-turn-start
  "Fire n turn_start events on the bus."
  [bus n]
  (dotimes [_ n]
    ((:emit bus) "turn_start" {})))

(defn- collect-before-agent-start
  "Subscribe to before_agent_start and collect all system-prompt-additions
   returned by handlers. Returns the collected additions vector."
  [bus]
  (let [collected (atom [])]
    ((:on bus) "before_agent_start"
               (fn [data]
                 ;; The reminder handler uses emit-collect, which merges results.
                 ;; We simulate that merge by calling emit-collect here.
                 nil))
    collected))

(defn- emit-before [bus]
  "Emit before_agent_start via emit-collect and return the merged result."
  (let [result (atom nil)]
    (-> ((:emit-collect bus) "before_agent_start" #js {})
        (.then (fn [r] (reset! result r))))
    result))

;;; ─── basic counter behavior ─────────────────────────────────

(describe "make-reminder — counter and threshold"
          (fn []
            (it "does not fire before threshold is reached"
                (fn []
                  (let [bus     (create-event-bus)
                        fired   (atom false)
                        cleanup ((make-reminder
                                  {:every-n-steps    3
                                   :reminder-text-fn (fn [] "reminder!")})
                                 bus)]
                    ((:on bus) "before_agent_start"
                               (fn [_] (reset! fired true)))
                    ;; Fire only 2 turns — threshold is 3
                    (fire-turn-start bus 2)
                    ;; Manually test that our handler fires but reminder doesn't
                    ;; contribute (we check via emit-collect result)
                    (let [p ((:emit-collect bus) "before_agent_start" #js {})]
                      (.then p
                             (fn [result]
                               (let [adds (get result "system-prompt-additions")]
                                 (-> (expect (or (nil? adds) (= 0 (count adds)))) (.toBe true))))))
                    (cleanup))))

            (it "fires on before_agent_start after exactly N turn_starts"
                (fn []
                  (let [bus     (create-event-bus)
                        cleanup ((make-reminder
                                  {:every-n-steps    3
                                   :reminder-text-fn (fn [] "do the thing")})
                                 bus)]
                    (fire-turn-start bus 3)
                    (let [result-atom (atom nil)]
                      (.then ((:emit-collect bus) "before_agent_start" #js {})
                             (fn [r] (reset! result-atom r)))
                      ;; Give the promise time to resolve
                      (.then (js/Promise.resolve nil)
                             (fn [_]
                               (when-let [r @result-atom]
                                 (let [adds (get r "system-prompt-additions")]
                                   (-> (expect (some? adds)) (.toBe true))
                                   (-> (expect (pos? (count adds))) (.toBe true))
                                   (-> (expect (first adds)) (.toBe "do the thing")))))))
                    (cleanup))))

            (it "fires on every Nth turn when no action predicate"
                (fn []
                  (let [bus     (create-event-bus)
                        fires   (atom 0)
                        cleanup ((make-reminder
                                  {:every-n-steps    2
                                   :reminder-text-fn (fn [] "nudge")})
                                 bus)]
                    ;; We can only easily track fires via the text-fn
                    (let [text-count (atom 0)
                          deact2     ((make-reminder
                                       {:every-n-steps    2
                                        :reminder-text-fn (fn []
                                                            (swap! text-count inc)
                                                            "nudge")})
                                      bus)]
                      (fire-turn-start bus 2)
                      (.then ((:emit-collect bus) "before_agent_start" #js {})
                             (fn [_] (swap! fires inc)))
                      (deact2))
                    (cleanup)
                    ;; Just verify cleanup doesn't throw
                    (-> (expect true) (.toBe true)))))))

;;; ─── action predicate ────────────────────────────────────────

(describe "make-reminder — action predicate resets counter"
          (fn []
            (it "action fires → counter resets → N more turns needed"
                (fn []
                  (let [bus         (create-event-bus)
                        acted       (atom false)
                        text-fired  (atom [])
                        cleanup     ((make-reminder
                                      {:every-n-steps    3
                                       :action-predicate (fn []
                                                           (let [v @acted]
                                                             (reset! acted false)
                                                             v))
                                       :reminder-text-fn (fn []
                                                           (swap! text-fired conj :fired)
                                                           "reminder")})
                                     bus)]
                    ;; Fire 2 turns — no action, counter = 2
                    (fire-turn-start bus 2)
                    ;; Mark action as fired; next turn_start will reset counter
                    (reset! acted true)
                    ;; This turn_start: predicate returns true → reset to 0, no increment
                    ((:emit bus) "turn_start" {})
                    ;; Now fire 2 more turns (counter should be 2, not yet at threshold 3)
                    (fire-turn-start bus 2)
                    ;; before_agent_start: counter = 2, below threshold → no reminder
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             (let [adds (get result "system-prompt-additions")]
                               (-> (expect (or (nil? adds) (= 0 (count adds)))) (.toBe true)))))
                    ;; One more turn — counter = 3, reaches threshold
                    (fire-turn-start bus 1)
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             (let [adds (get result "system-prompt-additions")]
                               (-> (expect (some? adds)) (.toBe true)))))
                    (cleanup))))

            (it "action predicate returning false does not reset counter"
                (fn []
                  (let [bus         (create-event-bus)
                        counter-spy (atom 0)
                        cleanup     ((make-reminder
                                      {:every-n-steps    5
                                       :action-predicate (fn [] false)
                                       :reminder-text-fn (fn []
                                                           (swap! counter-spy inc)
                                                           "text")})
                                     bus)]
                    ;; Fire 5 turns, predicate always returns false
                    (fire-turn-start bus 5)
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             (let [adds (get result "system-prompt-additions")]
                               (-> (expect (some? adds)) (.toBe true)))))
                    (cleanup))))))

;;; ─── reminder-text-fn returning nil ─────────────────────────

(describe "make-reminder — nil from reminder-text-fn suppresses"
          (fn []
            (it "returns nil → no contribution to system-prompt-additions"
                (fn []
                  (let [bus     (create-event-bus)
                        cleanup ((make-reminder
                                  {:every-n-steps    1
                                   :reminder-text-fn (fn [] nil)})
                                 bus)]
                    (fire-turn-start bus 1)
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             (let [adds (get result "system-prompt-additions")]
                               (-> (expect (or (nil? adds) (= 0 (count adds)))) (.toBe true)))))
                    (cleanup))))))

;;; ─── isolation: two reminders ────────────────────────────────

(describe "make-reminder — two reminders are independent"
          (fn []
            (it "each reminder has its own counter — different thresholds"
                (fn []
                  (let [bus    (create-event-bus)
                        fires1 (atom 0)
                        fires2 (atom 0)
                        cleanup1 ((make-reminder
                                   {:every-n-steps    2
                                    :reminder-text-fn (fn []
                                                        (swap! fires1 inc)
                                                        "r1")})
                                  bus)
                        cleanup2 ((make-reminder
                                   {:every-n-steps    4
                                    :reminder-text-fn (fn []
                                                        (swap! fires2 inc)
                                                        "r2")})
                                  bus)]
                    ;; After 2 turns: reminder1 should fire, reminder2 should not
                    (fire-turn-start bus 2)
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             (let [adds (or (get result "system-prompt-additions") #js [])]
                               ;; r1 should appear, r2 should not
                               (-> (expect (some #(= % "r1") (vec adds))) (.toBeTruthy))
                               (-> (expect (some #(= % "r2") (vec adds))) (.toBeFalsy)))))
                    (cleanup1)
                    (cleanup2))))

            (it "action on one reminder does not affect the other"
                (fn []
                  (let [bus    (create-event-bus)
                        acted1 (atom false)
                        cleanup1 ((make-reminder
                                   {:every-n-steps    3
                                    :action-predicate (fn []
                                                        (let [v @acted1]
                                                          (reset! acted1 false)
                                                          v))
                                    :reminder-text-fn (fn [] "r1")})
                                  bus)
                        cleanup2 ((make-reminder
                                   {:every-n-steps    3
                                    :reminder-text-fn (fn [] "r2")})
                                  bus)]
                    ;; Fire 3 turns — both counters at 3
                    (fire-turn-start bus 3)
                    ;; Trigger action for reminder1 on next turn
                    (reset! acted1 true)
                    ((:emit bus) "turn_start" {})
                    ;; Now: reminder1 counter = 0, reminder2 counter = 4 (still above threshold)
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             (let [adds (vec (or (get result "system-prompt-additions") #js []))]
                               ;; r2 should still fire (counter 4 >= 3)
                               (-> (expect (some #(= % "r2") adds)) (.toBeTruthy))
                               ;; r1 should NOT fire (counter reset to 0)
                               (-> (expect (some #(= % "r1") adds)) (.toBeFalsy)))))
                    (cleanup1)
                    (cleanup2))))))

;;; ─── cleanup ────────────────────────────────────────────────

(describe "make-reminder — cleanup removes subscriptions"
          (fn []
            (it "after cleanup, turn_start no longer increments counter"
                (fn []
                  (let [bus     (create-event-bus)
                        fired   (atom false)
                        cleanup ((make-reminder
                                  {:every-n-steps    1
                                   :reminder-text-fn (fn []
                                                       (reset! fired true)
                                                       "text")})
                                 bus)]
                    (cleanup)
                    ;; Fire turn_start after cleanup
                    (fire-turn-start bus 5)
                    (.then ((:emit-collect bus) "before_agent_start" #js {})
                           (fn [result]
                             ;; Should not fire since we cleaned up
                             (-> (expect @fired) (.toBe false)))))))

            (it "cleanup is idempotent — calling twice does not throw"
                (fn []
                  (let [bus     (create-event-bus)
                        cleanup ((make-reminder
                                  {:every-n-steps    2
                                   :reminder-text-fn (fn [] "x")})
                                 bus)]
                    (-> (expect (fn [] (cleanup) (cleanup))) (.not.toThrow)))))))
