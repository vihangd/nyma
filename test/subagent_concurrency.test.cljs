(ns subagent-concurrency.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.subagent.concurrency :as conc]))

(describe "subagent:concurrency" (fn []
                                   (it "preserves input order"
                                       (fn []
                                         (-> (conc/map-with-limit [1 2 3 4 5] 2
                                                                  (fn [x _i] (js/Promise.resolve (* x 10))))
                                             (.then (fn [r] (-> (expect (vec r)) (.toEqual [10 20 30 40 50])))))))

                                   (it "a failing item becomes nil, others survive"
                                       (fn []
                                         (-> (conc/map-with-limit [1 2 3] 3
                                                                  (fn [x _i]
                                                                    (if (= x 2)
                                                                      (js/Promise.reject (js/Error. "boom"))
                                                                      (js/Promise.resolve x))))
                                             (.then (fn [r] (-> (expect (vec r)) (.toEqual [1 nil 3])))))))

                                   (it "empty input returns empty"
                                       (fn []
                                         (-> (conc/map-with-limit [] 4 (fn [x _i] (js/Promise.resolve x)))
                                             (.then (fn [r] (-> (expect (count r)) (.toBe 0)))))))

                                   (it "passes the index to f"
                                       (fn []
                                         (-> (conc/map-with-limit [:a :b :c] 2 (fn [_x i] (js/Promise.resolve i)))
                                             (.then (fn [r] (-> (expect (vec r)) (.toEqual [0 1 2])))))))))
