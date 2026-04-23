(ns chat-pager.test
  "Unit tests for src/agent/ui/chat_pager.cljs — pure helpers only.

   The React component itself (<ChatPager>) is not tested here; its
   behavior is covered by the pure functions below plus app-level
   integration. Covering:
     - clamp-scroll-offset: bounds and edge cases
     - visible-slice: window math
     - auto-follow-offset: follow-newest vs anchored scroll
     - page-step: the page-size constant"
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/ui/chat_pager.jsx" :refer [clamp_scroll_offset
                                                 visible_slice
                                                 auto_follow_offset
                                                 page_step]]))

(describe "clamp-scroll-offset"
          (fn []

            (it "0 with 0 messages clamps to 0"
                (fn []
                  (-> (expect (clamp_scroll_offset 0 0)) (.toBe 0))))

            (it "negative input clamps to 0"
                (fn []
                  (-> (expect (clamp_scroll_offset -5 10)) (.toBe 0))
                  (-> (expect (clamp_scroll_offset -1 1)) (.toBe 0))))

            (it "offset within range passes through"
                (fn []
                  (-> (expect (clamp_scroll_offset 3 10)) (.toBe 3))
                  (-> (expect (clamp_scroll_offset 0 10)) (.toBe 0))))

            (it "offset at max (count - 1) passes through"
                ;; At max, exactly one message is visible (the oldest).
                (fn []
                  (-> (expect (clamp_scroll_offset 9 10)) (.toBe 9))))

            (it "offset past max clamps to count - 1 (one visible)"
                ;; Never allow a fully empty viewport; always show at
                ;; least one message.
                (fn []
                  (-> (expect (clamp_scroll_offset 15 10)) (.toBe 9))
                  (-> (expect (clamp_scroll_offset 1 1))  (.toBe 0))))

            (it "count = 1 only allows offset 0 (can't hide the only message)"
                (fn []
                  (-> (expect (clamp_scroll_offset 0 1))  (.toBe 0))
                  (-> (expect (clamp_scroll_offset 5 1))  (.toBe 0))))))

(describe "visible-slice"
          (fn []

            (it "offset 0 returns all messages"
                (fn []
                  (let [msgs [{:id "a"} {:id "b"} {:id "c"}]
                        out  (visible_slice msgs 0)]
                    (-> (expect (count out)) (.toBe 3)))))

            (it "offset 1 drops the last message"
                (fn []
                  (let [msgs [{:id "a"} {:id "b"} {:id "c"}]
                        out  (visible_slice msgs 1)]
                    (-> (expect (count out))          (.toBe 2))
                    (-> (expect (:id (last out)))     (.toBe "b")))))

            (it "offset equal to count - 1 returns only the first message"
                (fn []
                  (let [msgs [{:id "a"} {:id "b"} {:id "c"}]
                        out  (visible_slice msgs 2)]
                    (-> (expect (count out))       (.toBe 1))
                    (-> (expect (:id (first out))) (.toBe "a")))))

            (it "offset past max returns only the first message (clamped)"
                (fn []
                  (let [msgs [{:id "a"} {:id "b"} {:id "c"}]
                        out  (visible_slice msgs 99)]
                    (-> (expect (count out))       (.toBe 1))
                    (-> (expect (:id (first out))) (.toBe "a")))))

            (it "empty messages returns empty slice (no crash)"
                (fn []
                  (let [out (visible_slice [] 0)]
                    (-> (expect (count out)) (.toBe 0)))))))

(describe "auto-follow-offset"
          (fn []

            (it "at bottom (offset=0) stays at bottom when new messages arrive"
                (fn []
                  ;; prev=3 msgs, now=5 msgs, was at bottom → stay at 0.
                  (-> (expect (auto_follow_offset 0 3 5)) (.toBe 0))))

            (it "scrolled up advances offset by message delta to stay anchored"
                (fn []
                  ;; prev=3, now=5, offset was 1. Delta=2 → new offset=3.
                  ;; User's visible window was [msg0, msg1] (skipping 1 from end
                  ;; of 3). After 2 new messages, to keep that same window
                  ;; visible, offset must = 3 (skip last 3 of 5 → [msg0, msg1]).
                  (-> (expect (auto_follow_offset 1 3 5)) (.toBe 3))))

            (it "no growth: offset passes through clamped"
                (fn []
                  (-> (expect (auto_follow_offset 0 3 3)) (.toBe 0))
                  (-> (expect (auto_follow_offset 1 3 3)) (.toBe 1))))

            (it "message count shrunk: clamp offset to new max"
                ;; Rare (sessions don't usually shrink) but defensive —
                ;; if offset would exceed the new max-offset, clamp it.
                (fn []
                  (-> (expect (auto_follow_offset 5 10 3)) (.toBe 2))))

            (it "advance-then-clamp: large delta respects new max"
                ;; At bottom but count grew; offset stays 0.
                (fn []
                  (-> (expect (auto_follow_offset 0 0 100)) (.toBe 0))))

            (it "scrolled to oldest stays at oldest after new messages"
                ;; offset=prev-1 (showing oldest). After new msgs, offset
                ;; advances to keep window anchored, then clamps if needed.
                (fn []
                  (-> (expect (auto_follow_offset 2 3 5)) (.toBe 4))))))

(describe "page-step"
          (fn []
            (it "is a positive integer"
                (fn []
                  (-> (expect (pos? (page_step))) (.toBe true))
                  (-> (expect (integer? (page_step))) (.toBe true))))))
