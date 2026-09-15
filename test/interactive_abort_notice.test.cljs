(ns interactive-abort-notice.test
  "Esc / Ctrl-C mid-stream is an abort, not an error.

   Observed on the binary: both rendered `✗ unknown error` — the run promise
   rejected with an AbortError whose message is empty, and the transcript's
   error path filled the blank with that text."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.interactive :refer [abort-error? append-error-message]]))

(defn- abort-err []
  (let [e (js/Error. "")]
    (set! (.-name e) "AbortError")
    e))

(def ^:private new-id (let [n (atom 0)] (fn [] (swap! n inc))))

(describe "an aborted run is not an error card"
          (fn []
            (it "recognises AbortError, an aborted flag, and the raw abort reason"
                (fn []
                  (-> (expect (abort-error? (abort-err))) (.toBe true))
                  (-> (expect (abort-error? #js {:aborted true})) (.toBe true))
                  (-> (expect (abort-error? "user-interrupt")) (.toBe true))
                  (-> (expect (abort-error? (js/Error. "boom"))) (.toBe false))
                  (-> (expect (abort-error? nil)) (.toBe false))))

            (it "Esc: renders one info line 'aborted', never 'unknown error'"
                (fn []
                  (let [msgs (append-error-message [{:role "assistant" :content "partial"}] (abort-err) new-id)
                        tail (last msgs)]
                    (-> (expect (count msgs)) (.toBe 2))
                    (-> (expect (:role tail)) (.toBe "info"))
                    (-> (expect (:content tail)) (.toBe "aborted")))))

            (it "Ctrl-C: adds nothing when the interrupt handler already posted its info line"
                (fn []
                  (let [before [{:role "assistant" :content "partial"}
                                {:role "info" :content "interrupted — press Ctrl-C again to exit"}]
                        msgs   (append-error-message before (abort-err) new-id)]
                    (-> (expect (count msgs)) (.toBe 2)))))

            (it "a real failure still becomes an error card with its message"
                (fn []
                  (let [tail (last (append-error-message [] (js/Error. "rate limit exceeded") new-id))]
                    (-> (expect (:role tail)) (.toBe "error"))
                    (-> (expect (:content tail)) (.toContain "rate limit exceeded")))))

            (it "an error with no message still says so instead of crashing"
                (fn []
                  (let [tail (last (append-error-message [] (js/Error. "") new-id))]
                    (-> (expect (:role tail)) (.toBe "error"))
                    (-> (expect (:content tail)) (.toContain "unknown error")))))))
