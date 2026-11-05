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

(defn- step [tool input output & [text]]
  #js {:text text
       :toolResults #js [#js {:toolName tool :input input :output output}]})

(describe "stall:digest" (fn []
  (it "shows each call with its result, and the files edited"
      (fn []
        (let [d (-> {} (stall/add-step (step "bash" #js {:command "bun test"} "2 failed" "running tests"))
                    (stall/add-step (step "token-suite__multi_edit" #js {:path "src/a.cljs"} "ok")))
              t (stall/digest-text d)]
          (-> (expect t) (.toContain "Files edited: src/a.cljs"))
          (-> (expect t) (.toContain "said: running tests"))
          (-> (expect t) (.toContain "2 failed")))))
  (it "keeps the latest entries and says how many went"
      (fn []
        (let [d (reduce (fn [acc i] (stall/add-step acc (step "read" #js {:path (str "f" i)} "x")))
                        {} (range 40))]
          (-> (expect (count (:lines d))) (.toBe 30))
          (-> (expect (stall/digest-text d)) (.toContain "(10 earlier entries not shown)"))
          (-> (expect (stall/digest-text d)) (.toContain "f39")))))
  (it "a pass that did nothing has no digest"
      (fn [] (-> (expect (stall/digest-text {})) (.toBe ""))))))

