(ns ext-desktop-notify.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [clojure.string :as str]
            [agent.extensions.desktop-notify.index :as desktop-notify-ext]))

;;; ─── Mock API ───────────────────────────────────────────────

(defn- make-mock-api []
  (let [flags          (atom {})
        event-handlers (atom {})
        global-emits   (atom [])]
    #js {:registerFlag  (fn [name opts]
                          (swap! flags assoc name opts))
         :getFlag       (fn [name]
                          (when-let [opts (get @flags name)]
                            (.-default opts)))
         :on            (fn [evt handler _priority]
                          (swap! event-handlers update evt (fnil conj []) handler))
         :off           (fn [evt handler]
                          (swap! event-handlers update evt
                                 (fn [hs] (filterv #(not= % handler) (or hs [])))))
         :emit          (fn [evt data]
                          (doseq [h (get @event-handlers evt [])]
                            (h data nil)))
         :emitGlobal    (fn [evt data]
                          (swap! global-emits conj {:event evt :data data}))
         :_flags        flags
         :_events       event-handlers
         :_global_emits global-emits}))

(defn- activate [api]
  ((.-default desktop-notify-ext) api))

;;; ─── Activation ─────────────────────────────────────────────

(describe "desktop-notify:activation" (fn []
                                        (it "activates and returns a deactivator function"
                                            (fn []
                                              (let [api   (make-mock-api)
                                                    deact (activate api)]
                                                (-> (expect (fn? deact)) (.toBe true)))))

                                        (it "registers enabled flag on activate"
                                            (fn []
                                              (let [api (make-mock-api)
                                                    _   (activate api)]
                                                (-> (expect (contains? @(.-_flags api) "enabled")) (.toBe true)))))

                                        (it "enabled flag defaults to true"
                                            (fn []
                                              (let [api (make-mock-api)
                                                    _   (activate api)
                                                    f   (get @(.-_flags api) "enabled")]
                                                (-> (expect (.-default f)) (.toBe true)))))

                                        (it "wires turn_start event handler"
                                            (fn []
                                              (let [api (make-mock-api)
                                                    _   (activate api)]
                                                (-> (expect (pos? (count (get @(.-_events api) "turn_start" [])))) (.toBe true)))))

                                        (it "wires turn_end event handler"
                                            (fn []
                                              (let [api (make-mock-api)
                                                    _   (activate api)]
                                                (-> (expect (pos? (count (get @(.-_events api) "turn_end" [])))) (.toBe true)))))

                                        (it "deactivator removes turn_start handler"
                                            (fn []
                                              (let [api   (make-mock-api)
                                                    deact (activate api)]
                                                (deact)
                                                (-> (expect (count (get @(.-_events api) "turn_start" []))) (.toBe 0)))))

                                        (it "deactivator removes turn_end handler"
                                            (fn []
                                              (let [api   (make-mock-api)
                                                    deact (activate api)]
                                                (deact)
                                                (-> (expect (count (get @(.-_events api) "turn_end" []))) (.toBe 0)))))

                                        (it "activating multiple times registers independent handlers"
                                            (fn []
                                              (let [api1 (make-mock-api)
                                                    api2 (make-mock-api)
                                                    _    (activate api1)
                                                    _    (activate api2)]
                                                (-> (expect (pos? (count (get @(.-_events api1) "turn_end" [])))) (.toBe true))
                                                (-> (expect (pos? (count (get @(.-_events api2) "turn_end" [])))) (.toBe true)))))))

;;; ─── Notification threshold ──────────────────────────────────

(describe "desktop-notify:notification-threshold" (fn []
                                                    (it "does NOT send OSC 777 when turn_end fires immediately after turn_start"
                                                        (fn []
                                                          (let [api    (make-mock-api)
                                                                _      (activate api)
                                                                writes (atom [])
                                                                orig   (.-write (.-stdout js/process))]
                                                            (set! (.-write (.-stdout js/process)) (fn [data] (swap! writes conj data) nil))
                                                            (.emit api "turn_start" nil)
                                                            (.emit api "turn_end" nil)  ;; elapsed ≈ 0ms, below 3000ms threshold
                                                            (set! (.-write (.-stdout js/process)) orig)
                                                            (-> (expect (count (filterv #(str/includes? % "777") @writes))) (.toBe 0)))))

                                                    (it "sends OSC 777 escape when elapsed exceeds threshold"
                                                        (fn []
                                                          (let [api      (make-mock-api)
                                                                _        (activate api)
                                                                writes   (atom [])
                                                                orig-now (.-now js/Date)
                                                                orig-w   (.-write (.-stdout js/process))
                                                                call-n   (atom 0)]
        ;; Stub Date.now: 1st call returns 0 (turn_start), later calls return 5000
                                                            (set! (.-now js/Date) (fn []
                                                                                    (let [n @call-n]
                                                                                      (swap! call-n inc)
                                                                                      (if (= n 0) 0 5000))))
                                                            (set! (.-write (.-stdout js/process)) (fn [data] (swap! writes conj data) nil))
                                                            (.emit api "turn_start" nil)
                                                            (.emit api "turn_end" nil)
                                                            (set! (.-now js/Date) orig-now)
                                                            (set! (.-write (.-stdout js/process)) orig-w)
                                                            (-> (expect (pos? (count (filterv #(str/includes? % "777") @writes)))) (.toBe true)))))

                                                    (it "notification body contains 'nyma' as the title"
                                                        (fn []
                                                          (let [api      (make-mock-api)
                                                                _        (activate api)
                                                                writes   (atom [])
                                                                orig-now (.-now js/Date)
                                                                orig-w   (.-write (.-stdout js/process))
                                                                call-n   (atom 0)]
                                                            (set! (.-now js/Date) (fn []
                                                                                    (if (= 0 @call-n)
                                                                                      (do (swap! call-n inc) 0)
                                                                                      5000)))
                                                            (set! (.-write (.-stdout js/process)) (fn [data] (swap! writes conj data) nil))
                                                            (.emit api "turn_start" nil)
                                                            (.emit api "turn_end" nil)
                                                            (set! (.-now js/Date) orig-now)
                                                            (set! (.-write (.-stdout js/process)) orig-w)
                                                            (-> (expect (str/includes? (str/join "" @writes) "nyma")) (.toBe true)))))

                                                    (it "notification body contains 'Response ready'"
                                                        (fn []
                                                          (let [api      (make-mock-api)
                                                                _        (activate api)
                                                                writes   (atom [])
                                                                orig-now (.-now js/Date)
                                                                orig-w   (.-write (.-stdout js/process))
                                                                call-n   (atom 0)]
                                                            (set! (.-now js/Date) (fn []
                                                                                    (if (= 0 @call-n)
                                                                                      (do (swap! call-n inc) 0)
                                                                                      5000)))
                                                            (set! (.-write (.-stdout js/process)) (fn [data] (swap! writes conj data) nil))
                                                            (.emit api "turn_start" nil)
                                                            (.emit api "turn_end" nil)
                                                            (set! (.-now js/Date) orig-now)
                                                            (set! (.-write (.-stdout js/process)) orig-w)
                                                            (-> (expect (str/includes? (str/join "" @writes) "Response ready")) (.toBe true)))))

                                                    (it "no notification when turn_end fires without prior turn_start"
                                                        (fn []
                                                          (let [api    (make-mock-api)
                                                                _      (activate api)
                                                                writes (atom [])
                                                                orig   (.-write (.-stdout js/process))]
                                                            (set! (.-write (.-stdout js/process)) (fn [data] (swap! writes conj data) nil))
                                                            (.emit api "turn_end" nil)  ;; no turn_start — turn-start atom is nil
                                                            (set! (.-write (.-stdout js/process)) orig)
                                                            (-> (expect (count (filterv #(str/includes? % "777") @writes))) (.toBe 0)))))))

(describe "desktop-notify:notification-event"
  (fn []
    (it "emits notification event via emitGlobal when elapsed exceeds threshold"
      (fn []
        (let [api      (make-mock-api)
              _        (activate api)
              orig-now (.-now js/Date)
              orig-w   (.-write (.-stdout js/process))
              call-n   (atom 0)]
          (set! (.-now js/Date) (fn []
                                  (let [n @call-n]
                                    (swap! call-n inc)
                                    (if (= n 0) 0 5000))))
          (set! (.-write (.-stdout js/process)) (fn [_] nil))
          (.emit api "turn_start" nil)
          (.emit api "turn_end" nil)
          (set! (.-now js/Date) orig-now)
          (set! (.-write (.-stdout js/process)) orig-w)
          (let [emits @(.-_global_emits api)]
            (-> (expect (pos? (count emits))) (.toBe true))
            (-> (expect (:event (first emits))) (.toBe "notification"))))))

    (it "notification event payload has :title :body :source keys"
      (fn []
        (let [api      (make-mock-api)
              _        (activate api)
              orig-now (.-now js/Date)
              orig-w   (.-write (.-stdout js/process))
              call-n   (atom 0)]
          (set! (.-now js/Date) (fn []
                                  (let [n @call-n]
                                    (swap! call-n inc)
                                    (if (= n 0) 0 5000))))
          (set! (.-write (.-stdout js/process)) (fn [_] nil))
          (.emit api "turn_start" nil)
          (.emit api "turn_end" nil)
          (set! (.-now js/Date) orig-now)
          (set! (.-write (.-stdout js/process)) orig-w)
          (let [payload (:data (first @(.-_global_emits api)))]
            (-> (expect (contains? payload :title))  (.toBe true))
            (-> (expect (contains? payload :body))   (.toBe true))
            (-> (expect (contains? payload :source)) (.toBe true))
            (-> (expect (:source payload)) (.toBe "desktop-notify"))))))

    (it "does NOT emit notification event when elapsed is below threshold"
      (fn []
        (let [api (make-mock-api)
              _   (activate api)]
          (.emit api "turn_start" nil)
          (.emit api "turn_end" nil)  ;; elapsed ~ 0ms
          (-> (expect (count @(.-_global_emits api))) (.toBe 0)))))))
