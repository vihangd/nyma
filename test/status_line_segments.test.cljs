(ns status-line-segments.test
  "Tests for segment registry and context-usage-level."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.ui.status-line-segments :refer [register-segment
                                                   unregister-segment
                                                   get-segment
                                                   segment-registry
                                                   auto-append-ids
                                                   context-usage-level
                                                   token-rate-per-sec
                                                   reset-registry!]]))

(beforeEach (fn [] (reset-registry!)))

;;; ─── registry lifecycle ───────────────────────────────────────────────────

(describe "segment-registry/register" (fn []
                                        (it "registers a segment and retrieves it by id"
                                            (fn []
                                              (register-segment "my-seg" {:category :info :render identity})
                                              (-> (expect (some? (get-segment "my-seg"))) (.toBe true))))

                                        (it "coerces keyword id to string"
                                            (fn []
                                              (register-segment :kw-seg {:category :info :render identity})
                                              (-> (expect (some? (get-segment "kw-seg"))) (.toBe true))))

                                        (it "stores :id in the segment map"
                                            (fn []
                                              (register-segment "seg1" {:category :info :render identity})
                                              (-> (expect (:id (get-segment "seg1"))) (.toBe "seg1"))))

                                        (it "returns nil for unknown id"
                                            (fn []
                                              (-> (expect (get-segment "missing")) (.toBeNil))))

                                        (it "segment-registry returns all registered segments"
                                            (fn []
                                              (register-segment "a" {:category :x :render identity})
                                              (register-segment "b" {:category :y :render identity})
                                              (-> (expect (count (segment-registry))) (.toBe 2))))))

(describe "segment-registry/unregister" (fn []
                                          (it "removes a registered segment"
                                              (fn []
                                                (register-segment "to-remove" {:category :x :render identity})
                                                (unregister-segment "to-remove")
                                                (-> (expect (get-segment "to-remove")) (.toBeNil))))

                                          (it "is a no-op for unknown id"
                                              (fn []
                                                (unregister-segment "never-existed")
                                                (-> (expect (count (segment-registry))) (.toBe 0))))))

;;; ─── auto-append-ids ──────────────────────────────────────────────────────

(describe "segment-registry/auto-append-ids" (fn []
                                               (it "returns ids marked auto-append? for the given position"
                                                   (fn []
                                                     (register-segment "left-a" {:auto-append? true :position :left :render identity})
                                                     (register-segment "right-b" {:auto-append? true :position :right :render identity})
                                                     (let [ids (auto-append-ids :left [])]
                                                       (-> (expect (.includes (to-array ids) "left-a")) (.toBe true))
                                                       (-> (expect (.includes (to-array ids) "right-b")) (.toBe false)))))

                                               (it "excludes ids already present in preset-ids"
                                                   (fn []
                                                     (register-segment "auto-x" {:auto-append? true :position :right :render identity})
                                                     (-> (expect (count (auto-append-ids :right ["auto-x"]))) (.toBe 0))))

                                               (it "returns empty when nothing is auto-append"
                                                   (fn []
                                                     (register-segment "manual" {:auto-append? false :position :left :render identity})
                                                     (-> (expect (count (auto-append-ids :left []))) (.toBe 0))))))

;;; ─── context-usage-level ──────────────────────────────────────────────────

(describe "context-usage-level" (fn []
                                  (it "returns :ok for zero tokens"
                                      (fn []
                                        (-> (expect (context-usage-level 0 200000)) (.toBe :ok))))

                                  (it "returns :ok below warning thresholds"
                                      (fn []
                                        (-> (expect (context-usage-level 10000 200000)) (.toBe :ok))))

                                  (it "returns :warning at 50% of window"
                                      (fn []
        ;; 100k / 200k = 50% → :warning
                                        (-> (expect (context-usage-level 100000 200000)) (.toBe :warning))))

                                  (it "returns :warning at absolute 150k tokens even in large window"
                                      (fn []
                                        (-> (expect (context-usage-level 150000 2000000)) (.toBe :warning))))

                                  (it "returns :purple at 70% of window"
                                      (fn []
        ;; 140k / 200k = 70% → :purple
                                        (-> (expect (context-usage-level 140000 200000)) (.toBe :purple))))

                                  (it "returns :purple at absolute 270k tokens"
                                      (fn []
                                        (-> (expect (context-usage-level 270000 2000000)) (.toBe :purple))))

                                  (it "returns :error at 90% of window"
                                      (fn []
        ;; 180k / 200k = 90% → :error
                                        (-> (expect (context-usage-level 180000 200000)) (.toBe :error))))

                                  (it "returns :error at absolute 500k tokens"
                                      (fn []
                                        (-> (expect (context-usage-level 500000 2000000)) (.toBe :error))))

                                  (it "returns :ok when ctx-window is nil"
                                      (fn []
                                        (-> (expect (context-usage-level 0 nil)) (.toBe :ok))))

                                  (it "returns :ok when ctx-used is nil"
                                      (fn []
                                        (-> (expect (context-usage-level nil 200000)) (.toBe :ok))))))

;;; ─── token-rate-per-sec ───────────────────────────────────────────────────

(describe "token-rate-per-sec" (fn []
                                 (it "returns 0 for empty samples"
                                     (fn []
                                       (-> (expect (token-rate-per-sec [] 60000 1000)) (.toBe 0))))

                                 (it "returns 0 for a single sample"
                                     (fn []
                                       (-> (expect (token-rate-per-sec [{:ts 1000 :delta-tokens 100}] 60000 1000)) (.toBe 0))))

                                 (it "computes rate for two samples"
                                     (fn []
        ;; 100 tokens over 1000ms = 100 tok/s
                                       (let [samples [{:ts 0 :delta-tokens 0} {:ts 1000 :delta-tokens 100}]
                                             rate    (token-rate-per-sec samples 60000 1000)]
                                         (-> (expect rate) (.toBe 100.0)))))

                                 (it "excludes samples outside the window"
                                     (fn []
        ;; old sample at ts=0 is outside a 500ms window ending at now=1000
                                       (let [samples [{:ts 0 :delta-tokens 999} {:ts 600 :delta-tokens 0} {:ts 1000 :delta-tokens 50}]
                                             rate    (token-rate-per-sec samples 500 1000)]
          ;; only ts=600 and ts=1000 are in window; 50 tokens / 400ms = 125 tok/s
                                         (-> (expect rate) (.toBe 125.0)))))

                                 (it "returns 0 when span is zero"
                                     (fn []
                                       (let [samples [{:ts 1000 :delta-tokens 10} {:ts 1000 :delta-tokens 20}]]
                                         (-> (expect (token-rate-per-sec samples 60000 1000)) (.toBe 0)))))))
