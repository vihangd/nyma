(ns ext-desktop-notify.test
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.desktop-notify.index :as desktop-notify-ext]))

;;; ─── Mock API ───────────────────────────────────────────────

(defn- make-mock-api []
  (let [flags          (atom {})
        event-handlers (atom {})
        global-emits   (atom [])]
    #js {;; Stands in for the settings manager, which merges extension.json's
         ;; declared `desktop-notify` defaults under the user's values — the
         ;; extension no longer opens .nyma/settings.json itself.
         :settings      (fn [& [section]]
                          (let [all {:desktop-notify {:enabled true :threshold-ms 3000}}]
                            (if section (or (get all section) {}) all)))
         :registerFlag  (fn [name opts]
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

;;; ─── Environment control ────────────────────────────────────
;;; Under `bun test`, stdout is a pipe and isTTY is falsy — which is exactly the
;;; state the new gate refuses to write in. Every notification test has to say
;;; which world it is in, rather than inheriting the runner's.

(defn- with-env
  "Run `f` with stdout.isTTY = `tty?` and NYMA_ONE_SHOT set iff `one-shot?`,
   restoring both afterwards. Returns whatever `f` returns."
  [tty? one-shot? f]
  (let [stdout    (.-stdout js/process)
        orig-tty  (.-isTTY stdout)
        orig-shot (.. js/process -env -NYMA_ONE_SHOT)]
    (set! (.-isTTY stdout) tty?)
    (if one-shot?
      (aset js/process.env "NYMA_ONE_SHOT" "1")
      (js-delete js/process.env "NYMA_ONE_SHOT"))
    (try
      (f)
      (finally
        (set! (.-isTTY stdout) orig-tty)
        (if orig-shot
          (aset js/process.env "NYMA_ONE_SHOT" orig-shot)
          (js-delete js/process.env "NYMA_ONE_SHOT"))))))

(defn- capture
  "Record every write to both streams while `f` runs.
   Returns {:stdout [...] :stderr [...]}."
  [f]
  (let [out   (atom [])
        err   (atom [])
        o-w   (.-write (.-stdout js/process))
        e-w   (.-write (.-stderr js/process))]
    (set! (.-write (.-stdout js/process)) (fn [d] (swap! out conj (str d)) true))
    (set! (.-write (.-stderr js/process)) (fn [d] (swap! err conj (str d)) true))
    (try
      (f)
      (finally
        (set! (.-write (.-stdout js/process)) o-w)
        (set! (.-write (.-stderr js/process)) e-w)))
    {:stdout @out :stderr @err}))

(defn- slow-turn!
  "Fire turn_start then turn_end with a clock that makes the turn take 5s."
  [api]
  (let [orig-now (.-now js/Date)
        call-n   (atom 0)]
    (set! (.-now js/Date) (fn []
                            (let [n @call-n]
                              (swap! call-n inc)
                              (if (= n 0) 0 5000))))
    (try
      (.emit api "turn_start" nil)
      (.emit api "turn_end" nil)
      (finally (set! (.-now js/Date) orig-now)))))

(defn- osc? [writes]
  (some #(str/includes? % "777") writes))

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

(describe "desktop-notify:notification-threshold"
          (fn []
            (it "does NOT send OSC 777 when turn_end fires immediately after turn_start"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env true false
                              (fn [] (capture (fn []
                                                (.emit api "turn_start" nil)
                                        ;; elapsed ≈ 0ms, below the 3000ms threshold
                                                (.emit api "turn_end" nil)))))]
                    (-> (expect (osc? (:stderr w))) (.toBeFalsy)))))

            (it "sends OSC 777 when elapsed exceeds threshold"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env true false (fn [] (capture (fn [] (slow-turn! api)))))]
                    (-> (expect (osc? (:stderr w))) (.toBe true)))))

            (it "notification carries 'nyma' as the title and 'Response ready' as the body"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env true false (fn [] (capture (fn [] (slow-turn! api)))))
                        txt (str/join "" (:stderr w))]
                    (-> (expect (str/includes? txt "nyma")) (.toBe true))
                    (-> (expect (str/includes? txt "Response ready")) (.toBe true)))))

            (it "no notification when turn_end fires without prior turn_start"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env true false
                              (fn [] (capture (fn [] (.emit api "turn_end" nil)))))]
                    (-> (expect (osc? (:stderr w))) (.toBeFalsy)))))))

;;; ─── where the escape goes, and when ────────────────────────
;;; The OSC 777 went to STDOUT unconditionally. On a terminal that is
;;; invisible; through a pipe it is not. `nyma -p --output-format json | jq`
;;; got an escape sequence glued to the front of the JSON and died on it.

(describe "desktop-notify does not corrupt piped output"
          (fn []
            (it "writes the escape to stderr, never stdout"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env true false (fn [] (capture (fn [] (slow-turn! api)))))]
                    (-> (expect (osc? (:stderr w))) (.toBe true))
                    (-> (expect (osc? (:stdout w))) (.toBeFalsy)))))

            (it "writes nothing at all when stdout is not a TTY"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env false false (fn [] (capture (fn [] (slow-turn! api)))))]
                    (-> (expect (count (:stderr w))) (.toBe 0))
                    (-> (expect (count (:stdout w))) (.toBe 0)))))

            (it "writes nothing in a one-shot run, TTY or not"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)
                        w   (with-env true true (fn [] (capture (fn [] (slow-turn! api)))))]
                    (-> (expect (count (:stderr w))) (.toBe 0)))))

            (it "the session summary is gated too — it used to fire unconditionally"
                (fn []
          ;; session_end_summary is the LAST thing a one-shot run emits, so an
          ;; ungated notification landed after the JSON, past any point a
          ;; consumer could recover from.
                  (let [api  (make-mock-api)
                        _    (activate api)
                        piped (with-env false false
                                (fn [] (capture (fn [] (.emit api "session_end_summary"
                                                              #js {:turnCount 3 :totalCost 0.1})))))
                        tty   (with-env true false
                                (fn [] (capture (fn [] (.emit api "session_end_summary"
                                                              #js {:turnCount 3 :totalCost 0.1})))))]
                    (-> (expect (count (:stderr piped))) (.toBe 0))
                    (-> (expect (count (:stdout piped))) (.toBe 0))
                    (-> (expect (osc? (:stderr tty))) (.toBe true)))))

            (it "notifications-possible? is false unless stdout is a TTY and this is not a one-shot"
                (fn []
                  (-> (expect (with-env true false desktop-notify-ext/notifications-possible?))
                      (.toBe true))
                  (-> (expect (with-env false false desktop-notify-ext/notifications-possible?))
                      (.toBe false))
                  (-> (expect (with-env true true desktop-notify-ext/notifications-possible?))
                      (.toBeFalsy))))))

;;; ─── notification event ──────────────────────────────────────

(describe "desktop-notify:notification-event"
          (fn []
            (it "emits notification event via emitGlobal when elapsed exceeds threshold"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)]
                    (with-env true false (fn [] (capture (fn [] (slow-turn! api)))))
                    (let [emits @(.-_global_emits api)]
                      (-> (expect (pos? (count emits))) (.toBe true))
                      (-> (expect (:event (first emits))) (.toBe "notification"))))))

            (it "notification event payload has :title :body :source keys"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)]
                    (with-env true false (fn [] (capture (fn [] (slow-turn! api)))))
                    (let [payload (:data (first @(.-_global_emits api)))]
                      (-> (expect (contains? payload :title))  (.toBe true))
                      (-> (expect (contains? payload :body))   (.toBe true))
                      (-> (expect (contains? payload :source)) (.toBe true))
                      (-> (expect (:source payload)) (.toBe "desktop-notify"))))))

            (it "does NOT emit notification event when elapsed is below threshold"
                (fn []
                  (let [api (make-mock-api)
                        _   (activate api)]
                    (with-env true false
                      (fn [] (capture (fn []
                                        (.emit api "turn_start" nil)
                                        (.emit api "turn_end" nil)))))
                    (-> (expect (count @(.-_global_emits api))) (.toBe 0)))))

            (it "does NOT emit the notification event through a pipe either"
                (fn []
          ;; The event feeds other extensions' UI. Firing it in a headless run
          ;; is the same mistake one layer up.
                  (let [api (make-mock-api)
                        _   (activate api)]
                    (with-env false false (fn [] (capture (fn [] (slow-turn! api)))))
                    (-> (expect (count @(.-_global_emits api))) (.toBe 0)))))))
