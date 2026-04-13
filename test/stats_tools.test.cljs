(ns stats-tools.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]))

(describe "stats-dashboard:tool-metrics" (fn []
  (it "tool_complete event fires and can be subscribed to"
    (fn []
      (let [agent    (create-agent {:model "test" :system-prompt "test"})
            events   (:events agent)
            received (atom nil)]
        ((:on events) "tool_complete"
          (fn [data] (reset! received data)))
        ((:emit events) "tool_complete"
          #js {:toolName "read" :duration 150 :isError false})
        (-> (expect (.-toolName @received)) (.toBe "read"))
        (-> (expect (.-duration @received)) (.toBe 150)))))

  (it "tool metrics can accumulate across multiple calls"
    (fn []
      (let [metrics (atom {})]
        ;; Simulate the tracking logic from stats_dashboard
        (doseq [call [{:tool "read" :dur 100} {:tool "read" :dur 200} {:tool "bash" :dur 50}]]
          (swap! metrics update (:tool call)
            (fn [m]
              (let [m (or m {:calls 0 :total-ms 0})]
                (-> m (update :calls inc) (update :total-ms + (:dur call)))))))
        (-> (expect (:calls (get @metrics "read"))) (.toBe 2))
        (-> (expect (:total-ms (get @metrics "read"))) (.toBe 300))
        (-> (expect (:calls (get @metrics "bash"))) (.toBe 1)))))

  (it "average duration calculation is correct"
    (fn []
      (let [m {:calls 4 :total-ms 1000}
            avg (js/Math.round (/ (:total-ms m) (max 1 (:calls m))))]
        (-> (expect avg) (.toBe 250)))))))
