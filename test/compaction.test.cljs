(ns compaction.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager]]
            ["./agent/events.mjs" :refer [create-event-bus]]
            ["./agent/sessions/compaction.mjs" :refer [compact]]))

(defn ^:async test-under-threshold []
  (let [sm     (create-session-manager nil)
        events (create-event-bus)]
    ;; Add a few small messages (well under 85% of 100k tokens)
    ((:append sm) {:role "user" :content "hello"})
    ((:append sm) {:role "assistant" :content "hi there"})
    (let [tree-before ((:get-tree sm))]
      (js-await (compact sm "mock-model" events))
      ;; No compaction entry should have been added
      (let [tree-after ((:get-tree sm))]
        (-> (expect (count tree-after)) (.toBe (count tree-before)))))))

(describe "agent.sessions.compaction"
  (fn []
    (it "does nothing when messages are under threshold" test-under-threshold)))
