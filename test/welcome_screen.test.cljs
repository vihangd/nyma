(ns welcome-screen.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/welcome.jsx" :refer [compute-layout]]))

(describe "compute-layout" (fn []
  (it "uses dual-column layout when width is >= 70"
    (fn []
      (let [r (compute-layout 80)]
        (-> (expect (:dual-column? r)) (.toBe true))
        (-> (expect (pos? (:left-col r))) (.toBe true))
        (-> (expect (pos? (:right-col r))) (.toBe true)))))

  (it "caps left column at 26 columns"
    (fn []
      (let [r (compute-layout 200)]
        (-> (expect (:left-col r)) (.toBe 26)))))

  (it "collapses to single-column below 70 columns"
    (fn []
      (let [r (compute-layout 60)]
        (-> (expect (:dual-column? r)) (.toBe false))
        (-> (expect (:right-col r)) (.toBe 0)))))

  (it "handles width 0 without crashing"
    (fn []
      (let [r (compute-layout 0)]
        (-> (expect (:dual-column? r)) (.toBe false))
        (-> (expect (:left-col r)) (.toBe 0)))))

  (it "handles nil width"
    (fn []
      (let [r (compute-layout nil)]
        (-> (expect (:dual-column? r)) (.toBe false)))))

  (it "left column has a minimum of 20 when dual-column"
    (fn []
      (let [r (compute-layout 70)]
        (-> (expect (:dual-column? r)) (.toBe true))
        (-> (expect (>= (:left-col r) 20)) (.toBe true)))))))
