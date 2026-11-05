(ns stall.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.stall :as stall]))

(describe "stall:repeat-cycle" (fn []
  (it "finds a single call repeated"
      (fn [] (-> (expect (stall/repeat-cycle ["x" "a" "a" "a"] 3)) (.toBe 1))))
  (it "finds every cycle length up to five"
      (fn []
        (doseq [len [2 3 4 5]]
          (let [round (mapv str (range len))]
            (-> (expect (stall/repeat-cycle (vec (concat round round round)) 3)) (.toBe len))))))
  (it "ignores a cycle longer than five"
      (fn [] (let [round (mapv str (range 6))]
               (-> (expect (stall/repeat-cycle (vec (concat round round round)) 3)) (.toBeUndefined)))))
  (it "needs the repeat at the tail"
      (fn [] (-> (expect (stall/repeat-cycle ["a" "a" "a" "b"] 3)) (.toBeUndefined))))
  (it "too few repeats is not a cycle"
      (fn [] (-> (expect (stall/repeat-cycle ["a" "b" "a" "b"] 3)) (.toBeUndefined))))))
