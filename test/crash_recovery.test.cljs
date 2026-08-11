(ns crash-recovery.test
  "pi-tui throws on an over-wide line from inside its own render timer, after
   calling stop(). No `try` around `.requestRender` can catch it — the stack
   has unwound — and pi-tui offers no error hook, so `uncaughtException` is
   the only interception point and nyma had none.

   Recovery works because pi-tui stops the terminal BEFORE throwing and
   `start()` clears its stopped flag, so the same instance comes back up.
   These tests pin the policy (when to restart vs give up) and the mechanism
   (that a restart actually happens and a repeat does not loop)."
  (:require ["bun:test" :refer [describe it expect]]
            ["@mariozechner/pi-tui" :refer [TUI]]
            [agent.ui.width-guard :refer [attach-guarded-children! clamps]]
            [agent.ui.crash-recovery :refer [width-error? decide resume-hint install!]]))

(defn- width-err []
  ;; The message pi-tui builds at tui.js:967, verbatim in shape.
  (js/Error. (str "Rendered line 2314 exceeds terminal width (170 > 167).\n\n"
                  "This is likely caused by a custom TUI component not truncating its output.")))

(describe "width-error?"
          (fn []
            (it "recognises pi-tui's over-wide throw"
                (fn []
                  (-> (expect (width-error? (width-err))) (.toBe true))))

            (it "does not match unrelated errors"
                (fn []
                  ;; A match too loose would turn any bug into a silent
                  ;; restart loop, so this must stay narrow.
                  (doseq [m ["something about width" "terminal width is 80"
                             "TypeError: undefined is not an object"
                             "ECONNRESET" ""]]
                    (-> (expect (width-error? (js/Error. m))) (.toBe false)))))

            (it "tolerates a non-Error being thrown"
                (fn []
                  (-> (expect (width-error? nil)) (.toBe false))
                  (-> (expect (width-error? "just a string")) (.toBe false))))))

(describe "decide"
          (fn []
            (it "restarts on a first width error"
                (fn []
                  (let [[action st] (decide (width-err) {:restarts 0 :last-at nil} 1000)]
                    (-> (expect action) (.toBe "restart"))
                    (-> (expect (:restarts st)) (.toBe 1)))))

            (it "gives up when the same crash recurs immediately"
                (fn []
                  ;; The line survived the restart — restarting again loops.
                  (let [[a1 s1] (decide (width-err) {:restarts 0 :last-at nil} 1000)
                        [a2 _]  (decide (width-err) s1 1200)]
                    (-> (expect a1) (.toBe "restart"))
                    (-> (expect a2) (.toBe "give-up")))))

            (it "restarts again once the repeat window has passed"
                (fn []
                  (let [[_ s1] (decide (width-err) {:restarts 0 :last-at nil} 1000)
                        [a2 _] (decide (width-err) s1 60000)]
                    (-> (expect a2) (.toBe "restart")))))

            (it "stops restarting after a cap, even when well spaced"
                (fn []
                  ;; A slow loop is still a loop.
                  (let [final (reduce (fn [st i]
                                        (second (decide (width-err) st (* i 100000))))
                                      {:restarts 0 :last-at nil}
                                      (range 1 7))
                        [a _] (decide (width-err) final 900000)]
                    (-> (expect a) (.toBe "give-up")))))

            (it "never restarts on a non-width error"
                (fn []
                  (let [[a _] (decide (js/Error. "boom") {:restarts 0 :last-at nil} 1000)]
                    (-> (expect a) (.toBe "give-up")))))))

(describe "resume-hint"
          (fn []
            (it "names the session file and the command to get back in"
                (fn []
                  (let [h (resume-hint "/home/u/.nyma/sessions/123.jsonl")]
                    (-> (expect (.includes h "nyma -c")) (.toBe true))
                    (-> (expect (.includes h "/home/u/.nyma/sessions/123.jsonl")) (.toBe true))
                    (-> (expect (.includes h "pi-crash.log")) (.toBe true)))))

            (it "still tells the user how to resume with no path"
                (fn []
                  (let [h (resume-hint nil)]
                    (-> (expect (.includes h "nyma -c")) (.toBe true))
                    (-> (expect (.includes h "--session")) (.toBe false)))))))

;;; ─── the mechanism ───────────────────────────────────────────────────────
;;; A fake TUI records start() calls; a fake clock and exit let the whole
;;; policy run without touching the real process.

(defn- fake-tui []
  (let [starts (atom 0) stops (atom 0)]
    {:starts starts :stops stops
     :obj #js {:start (fn [] (swap! starts inc) nil)
               :stop  (fn [] (swap! stops inc) nil)}}))

(defn- capture-console []
  (let [orig (.-error js/console)
        out  (atom [])]
    (set! (.-error js/console) (fn [& args] (swap! out conj (.join (to-array args) " ")) nil))
    {:out out :restore (fn [] (set! (.-error js/console) orig))}))

(describe "install!"
          (fn []
            (it "restarts the TUI in place instead of dying"
                (fn []
                  (let [{:keys [starts obj]} (fake-tui)
                        exits   (atom [])
                        msgs    (atom [])
                        clock   (atom 1000)
                        uninst  (install! {:tui obj
                                           :session-path-fn (fn [] "/tmp/s.jsonl")
                                           :on-recovered (fn [m] (swap! msgs conj m))
                                           :now-fn (fn [] @clock)
                                           :exit-fn (fn [c] (swap! exits conj c))})]
                    (.emit js/process "uncaughtException" (width-err))
                    (-> (expect @starts) (.toBe 1))
                    (-> (expect (count @exits)) (.toBe 0))
                    ;; The user must be told, or a silently reset display
                    ;; looks like lost work.
                    (-> (expect (count @msgs)) (.toBe 1))
                    (-> (expect (.includes (first @msgs) "Nothing in this conversation was lost"))
                        (.toBe true))
                    (uninst))))

            (it "exits with the resume hint when the crash repeats"
                (fn []
                  (let [{:keys [starts obj]} (fake-tui)
                        exits  (atom [])
                        clock  (atom 1000)
                        cons   (capture-console)
                        uninst (install! {:tui obj
                                          :session-path-fn (fn [] "/tmp/s.jsonl")
                                          :now-fn (fn [] @clock)
                                          :exit-fn (fn [c] (swap! exits conj c))})]
                    (.emit js/process "uncaughtException" (width-err))
                    (reset! clock 1300)
                    (.emit js/process "uncaughtException" (width-err))
                    ((:restore cons))
                    (-> (expect @starts) (.toBe 1))          ;; not restarted twice
                    (-> (expect @exits) (.toEqual [1]))
                    (-> (expect (.includes (.join (to-array @(:out cons)) "\n") "nyma -c"))
                        (.toBe true))
                    (uninst))))

            (it "stops the TUI before printing for a non-width error"
                (fn []
                  ;; Otherwise the message lands in a raw-mode screen nobody
                  ;; can read. For a width error pi-tui already stopped, so we
                  ;; must not stop twice.
                  (let [{:keys [starts stops obj]} (fake-tui)
                        exits  (atom [])
                        cons   (capture-console)
                        uninst (install! {:tui obj
                                          :now-fn (fn [] 1000)
                                          :exit-fn (fn [c] (swap! exits conj c))})]
                    (.emit js/process "uncaughtException" (js/Error. "boom"))
                    ((:restore cons))
                    (-> (expect @starts) (.toBe 0))
                    (-> (expect @stops) (.toBe 1))
                    (-> (expect @exits) (.toEqual [1]))
                    (uninst))))

            (it "gives up rather than restarting on an unhandled rejection"
                (fn []
                  (let [{:keys [starts obj]} (fake-tui)
                        exits  (atom [])
                        cons   (capture-console)
                        uninst (install! {:tui obj
                                          :now-fn (fn [] 1000)
                                          :exit-fn (fn [c] (swap! exits conj c))})]
                    (.emit js/process "unhandledRejection" (js/Error. "rejected"))
                    ((:restore cons))
                    (-> (expect @starts) (.toBe 0))
                    (-> (expect @exits) (.toEqual [1]))
                    (uninst))))

            (it "gives up when the TUI cannot be brought back up"
                (fn []
                  (let [exits  (atom [])
                        cons   (capture-console)
                        bad    #js {:start (fn [] (throw (js/Error. "terminal gone")))
                                    :stop  (fn [] nil)}
                        uninst (install! {:tui bad
                                          :now-fn (fn [] 1000)
                                          :exit-fn (fn [c] (swap! exits conj c))})]
                    (.emit js/process "uncaughtException" (width-err))
                    ((:restore cons))
                    (-> (expect @exits) (.toEqual [1]))
                    (uninst))))

            (it "uninstalls cleanly, leaving no handler behind"
                (fn []
                  (let [{:keys [starts obj]} (fake-tui)
                        uninst (install! {:tui obj :now-fn (fn [] 1000)
                                          :exit-fn (fn [_] nil)})]
                    (uninst)
                    (.emit js/process "uncaughtException" (width-err))
                    (-> (expect @starts) (.toBe 0)))))))

;;; ─── end to end, against a real TUI ──────────────────────────────────────
;;; Everything above emits synthetic `uncaughtException` events. This drives
;;; pi-tui's actual render loop until it actually throws, which is the only
;;; way to know the handler is reachable at all: the throw crosses a
;;; setTimeout boundary, so if that assumption were wrong every unit test here
;;; would still pass and every real crash would still be fatal.
;;;
;;; Uses a fake terminal so nothing touches stdout.

(defn- fake-terminal []
  #js {:columns 80 :rows 24
       :start (fn [_on-data _on-resize] nil)
       :stop  (fn [] nil)
       :write (fn [_s] nil)
       :hideCursor (fn [] nil)
       :showCursor (fn [] nil)})

(defn- over-wide-component [wide?]
  #js {:render (fn [w] #js [(if @wide? (.repeat "x" (+ (or w 80) 40)) "ok")])
       :invalidate (fn [] nil)})

(defn- sleep [ms] (js/Promise. (fn [res] (js/setTimeout res ms))))

(describe "end to end against a real TUI"
          (fn []
            ;; NOTE: there is deliberately no test here that lets a real
            ;; throw reach the handler. `bun test` installs its own
            ;; uncaughtException handling and reports the throw as an
            ;; "Unhandled error between tests" before ours runs, so such a
            ;; test asserts the runner's behaviour, not nyma's. Verified
            ;; standalone instead — a script that builds this same TUI,
            ;; attaches this same unguarded component and calls
            ;; requestRender() recovers once (recovered=1) and then exits 1
            ;; with the resume hint when the bad line comes straight back.
            ;; Reproduce by running that setup under plain `bun`, not
            ;; `bun test`. The policy that decides all of it is covered
            ;; exhaustively by the `decide` and `install!` tests above.

            (it "never throws in the first place once the child is guarded"
                (^:async
                 fn []
                 (let [tui       (new TUI (fake-terminal))
                       wide?     (atom false)
                       recovered (atom 0)
                       exits     (atom [])
                       uninst    (install! {:tui tui
                                            :on-recovered (fn [_] (swap! recovered inc))
                                            :exit-fn (fn [c] (swap! exits conj c))})]
                   (reset! clamps {:count 0 :last nil})
                   (attach-guarded-children! tui [["bad" (over-wide-component wide?)]])
                   (.start tui)
                   (js-await (sleep 60))
                   (reset! wide? true)
                   (.requestRender tui)
                   (js-await (sleep 400))
                   (try (.stop tui) (catch :default _ nil))
                   (uninst)
                   (-> (expect @recovered) (.toBe 0))
                   (-> (expect @exits) (.toEqual []))
                   ;; And the guard says it did the work.
                   (-> (expect (:count @clamps)) (.toBeGreaterThan 0)))))))
