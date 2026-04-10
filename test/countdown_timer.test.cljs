(ns countdown-timer.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.countdown-timer :refer [seconds-remaining]]))

(describe "seconds-remaining" (fn []
  (it "returns full seconds when deadline is N seconds in the future"
    (fn []
      (-> (expect (seconds-remaining 10000 0)) (.toBe 10))))

  (it "rounds partial seconds up (ceiling)"
    (fn []
      ;; 10.5 seconds left should show as 11 (ceiling), not 10 (floor).
      (-> (expect (seconds-remaining 10500 0)) (.toBe 11))))

  (it "returns 0 at the deadline"
    (fn []
      (-> (expect (seconds-remaining 5000 5000)) (.toBe 0))))

  (it "clamps negative remainders to 0"
    (fn []
      ;; Deadline has already passed by 2 seconds.
      (-> (expect (seconds-remaining 3000 5000)) (.toBe 0))))

  (it "handles 0 deadline / 0 now"
    (fn []
      (-> (expect (seconds-remaining 0 0)) (.toBe 0))))

  (it "returns 0 when deadline is nil"
    (fn []
      (-> (expect (seconds-remaining nil 1000)) (.toBe 0))))

  (it "returns 0 when now is nil"
    (fn []
      (-> (expect (seconds-remaining 1000 nil)) (.toBe 0))))

  (it "computes a 1-second deadline"
    (fn []
      (-> (expect (seconds-remaining 1000 0)) (.toBe 1))))

  (it "handles sub-second remainder correctly"
    (fn []
      ;; 499 ms left → should round up to 1 second.
      (-> (expect (seconds-remaining 5499 5000)) (.toBe 1))))))
