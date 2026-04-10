(ns time-util.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.time :refer [relative-time]]))

(describe "relative-time" (fn []
  (it "returns 'just now' for timestamps within the last minute"
    (fn []
      (let [now  (js/Date.now)
            recent (- now 5000)]
        (-> (expect (relative-time recent)) (.toBe "just now")))))

  (it "returns Nm ago for timestamps within the last hour"
    (fn []
      (let [now  (js/Date.now)
            past (- now (* 5 60 1000))]
        (-> (expect (relative-time past)) (.toBe "5m ago")))))

  (it "returns Nh ago for timestamps within the last day"
    (fn []
      (let [now  (js/Date.now)
            past (- now (* 3 60 60 1000))]
        (-> (expect (relative-time past)) (.toBe "3h ago")))))

  (it "returns Nd ago for timestamps within the last week"
    (fn []
      (let [now  (js/Date.now)
            past (- now (* 2 24 60 60 1000))]
        (-> (expect (relative-time past)) (.toBe "2d ago")))))

  (it "returns Nw ago for timestamps older than a week"
    (fn []
      (let [now  (js/Date.now)
            past (- now (* 3 7 24 60 60 1000))]
        (-> (expect (relative-time past)) (.toBe "3w ago")))))

  (it "handles exactly 59s as 'just now'"
    (fn []
      (let [now  (js/Date.now)
            past (- now (* 59 1000))]
        (-> (expect (relative-time past)) (.toBe "just now")))))

  (it "handles exactly 60s as 1m ago"
    (fn []
      (let [now  (js/Date.now)
            past (- now (* 60 1000))]
        (-> (expect (relative-time past)) (.toBe "1m ago")))))))
