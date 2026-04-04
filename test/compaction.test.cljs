(ns compaction.test
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/sessions/manager.mjs" :refer [create-session-manager]]
            ["./agent/events.mjs" :refer [create-event-bus]]
            ["./agent/sessions/compaction.mjs" :refer [compact format-messages
                                                        extract-files-read
                                                        extract-files-modified]]))

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

(describe "agent.sessions.compaction - format-messages"
  (fn []
    (it "formats single message as role: content"
      (fn []
        (let [result (format-messages [{:role "user" :content "hello"}])]
          (-> (expect result) (.toBe "user: hello")))))

    (it "joins multiple messages with double newline"
      (fn []
        (let [result (format-messages [{:role "user" :content "hi"}
                                       {:role "assistant" :content "hey"}])]
          (-> (expect result) (.toBe "user: hi\n\nassistant: hey")))))

    (it "returns empty string for empty messages"
      (fn []
        (let [result (format-messages [])]
          (-> (expect result) (.toBe "")))))))

(describe "extract-files-read" (fn []
  (it "extracts paths from read tool_call entries"
    (fn []
      (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/foo.cljs"}}}
                  {:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/bar.cljs"}}}
                  {:role "tool_call" :content "..." :metadata {:tool-name "write" :args {:path "/out.txt"}}}
                  {:role "user" :content "hello"}]
            result (extract-files-read msgs)]
        (-> (expect (count result)) (.toBe 2))
        (-> (expect (first result)) (.toBe "/src/foo.cljs")))))

  (it "deduplicates paths"
    (fn []
      (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/foo.cljs"}}}
                  {:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/foo.cljs"}}}]
            result (extract-files-read msgs)]
        (-> (expect (count result)) (.toBe 1)))))

  (it "returns empty for no read entries"
    (fn []
      (let [msgs [{:role "user" :content "hi"}]
            result (extract-files-read msgs)]
        (-> (expect (count result)) (.toBe 0)))))))

(describe "extract-files-modified" (fn []
  (it "extracts paths from write and edit tool_call entries"
    (fn []
      (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "write" :args {:path "/out.txt"}}}
                  {:role "tool_call" :content "..." :metadata {:tool-name "edit" :args {:path "/src/foo.cljs"}}}
                  {:role "tool_call" :content "..." :metadata {:tool-name "read" :args {:path "/src/bar.cljs"}}}]
            result (extract-files-modified msgs)]
        (-> (expect (count result)) (.toBe 2)))))

  (it "returns empty for no write/edit entries"
    (fn []
      (let [msgs [{:role "tool_call" :content "..." :metadata {:tool-name "bash" :args {:command "ls"}}}]
            result (extract-files-modified msgs)]
        (-> (expect (count result)) (.toBe 0)))))))
