(ns debug-tui-sink.test
  "While the TUI owns the terminal, warn/error go to the file, not stderr.

   Observed on the binary: `[error] [[loop] provider stream error:] …` and the
   compaction `[warn]` painted raw over the transcript, then arrived again as
   proper cards. The stderr mirror is right at startup and wrong once pi-tui
   is mounted; interactive `start` turns it off."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.debug :as d]))

(defn- with-env [k v f]
  (let [orig (aget js/process.env k)]
    (if (nil? v) (js-delete js/process.env k) (aset js/process.env k v))
    (try (f)
         (finally
           (if (nil? orig) (js-delete js/process.env k) (aset js/process.env k orig))))))

(describe "debug sink while the TUI is mounted"
          (fn []
            (afterEach (fn [] (d/reset-logger!)))

            (it "the default sink mirrors warn/error to stderr"
                (fn []
                  (with-env "NYMA_QUIET" nil
                    (fn []
                      (with-env "NYMA_DEBUG_STDERR" nil
                        (fn []
                          (d/reset-logger!)
                          (-> (expect (d/stderr-mirror-on?)) (.toBe true))))))))

            (it "silence-stderr-mirror! routes warn/error to the file only"
                (fn []
                  (with-env "NYMA_DEBUG_STDERR" nil
                    (fn []
                      (d/reset-logger!)
                      (d/silence-stderr-mirror!)
                      (-> (expect (d/stderr-mirror-on?)) (.toBe false))
                      (let [orig-write (.-write (.-stderr js/process))
                            writes     (atom 0)]
                        (set! (.-write (.-stderr js/process)) (fn [& _] (swap! writes inc) true))
                        (try
                          (d/error "selftest" "SYNTHETIC selftest line, ignore")
                          (-> (expect @writes) (.toBe 0))
                          (finally
                            (set! (.-write (.-stderr js/process)) orig-write))))))))

            (it "NYMA_DEBUG_STDERR=1 is an explicit request and keeps stderr"
                (fn []
                  (with-env "NYMA_DEBUG_STDERR" "1"
                    (fn []
                      (d/reset-logger!)
                      (d/silence-stderr-mirror!)
                      (let [orig-write (.-write (.-stderr js/process))
                            writes     (atom 0)]
                        (set! (.-write (.-stderr js/process)) (fn [& _] (swap! writes inc) true))
                        (try
                          (d/warn "selftest" "SYNTHETIC selftest line, ignore")
                          (-> (expect (pos? @writes)) (.toBe true))
                          (finally
                            (set! (.-write (.-stderr js/process)) orig-write))))))))

            (it "reset-logger! restores the mirror for the next process"
                (fn []
                  (with-env "NYMA_QUIET" nil
                    (fn []
                      (with-env "NYMA_DEBUG_STDERR" nil
                        (fn []
                          (d/silence-stderr-mirror!)
                          (d/reset-logger!)
                          (-> (expect (d/stderr-mirror-on?)) (.toBe true))))))))))
