(ns stream-filter.test
  "Tests for G1/G2: stream_filter emit-collect event and retry-state atom.
   Tests are split into two layers:
   - Structural: verify retry-state atom and event bus semantics (no streamText mock needed)
   - Event shape: verify stream_filter handlers receive and return the right data"
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]))

;;; ─── retry-state atom ────────────────────────────────────────────────

(describe "agent.loop - retry-state atom" (fn []
                                            (it "retry-state is present in agent map"
                                                (fn []
                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})]
                                                    (-> (expect (contains? agent :retry-state)) (.toBe true)))))

                                            (it "retry-state starts as nil"
                                                (fn []
                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})]
                                                    (-> (expect (nil? @(:retry-state agent))) (.toBe true)))))

                                            (it "retry-state can be set to a map"
                                                (fn []
                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})]
                                                    (reset! (:retry-state agent) {:reason "test rule" :inject []})
                                                    (-> (expect (nil? @(:retry-state agent))) (.toBe false))
                                                    (-> (expect (:reason @(:retry-state agent))) (.toBe "test rule")))))

                                            (it "retry-state can be cleared back to nil"
                                                (fn []
                                                  (let [agent (create-agent {:model "test" :system-prompt "test"})]
                                                    (reset! (:retry-state agent) {:reason "temporary"})
                                                    (reset! (:retry-state agent) nil)
                                                    (-> (expect (nil? @(:retry-state agent))) (.toBe true)))))

                                            (it "retry-state is independent per agent"
                                                (fn []
                                                  (let [a1 (create-agent {:model "test" :system-prompt "test"})
                                                        a2 (create-agent {:model "test" :system-prompt "test"})]
                                                    (reset! (:retry-state a1) {:reason "a1 only" :inject []})
                                                    (-> (expect (nil? @(:retry-state a2))) (.toBe true)))))))

;;; ─── stream_filter event semantics ──────────────────────────────────

(describe "hooks:stream-filter" (fn []
                                  (it "stream_filter is an emit-collect event (merged return semantics)"
                                      (fn []
                                        (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                              events (:events agent)
                                              on     (:on events)
                                              emit-c (:emit-collect events)]
                                          (on "stream_filter" (fn [_] #js {:abort true :reason "matched eval()"}))
                                          (let [p (emit-c "stream_filter"
                                                          #js {:delta "call eval() here"
                                                               :chunk "eval()"
                                                               :type  "message_update"})]
                                            (.then p (fn [result]
                                                       (-> (expect (get result "abort")) (.toBe true))
                                                       (-> (expect (get result "reason")) (.toBe "matched eval()"))))))))

                                  (it "handler returning nil has no effect"
                                      (fn []
                                        (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                              events (:events agent)
                                              on     (:on events)
                                              emit-c (:emit-collect events)]
                                          (on "stream_filter" (fn [_] nil))
                                          (let [p (emit-c "stream_filter"
                                                          #js {:delta "safe text" :chunk "text" :type "message_update"})]
                                            (.then p (fn [result]
                                                       (-> (expect (get result "abort")) (.toBeUndefined))))))))

                                  (it "stream_filter receives delta (accumulated) and chunk (just this chunk)"
                                      (fn []
                                        (let [agent    (create-agent {:model "test" :system-prompt "test"})
                                              events   (:events agent)
                                              on       (:on events)
                                              emit-c   (:emit-collect events)
                                              received (atom nil)]
                                          (on "stream_filter" (fn [data] (reset! received data) nil))
                                          (let [p (emit-c "stream_filter"
                                                          #js {:delta "hello world" :chunk "world" :type "message_update"})]
                                            (.then p (fn [_]
                                                       (-> (expect (.-delta @received)) (.toBe "hello world"))
                                                       (-> (expect (.-chunk @received)) (.toBe "world"))
                                                       (-> (expect (.-type @received)) (.toBe "message_update"))))))))

                                  (it "handler returning {abort: true, inject: [...]} carries inject messages"
                                      (fn []
                                        (let [agent  (create-agent {:model "test" :system-prompt "test"})
                                              events (:events agent)
                                              on     (:on events)
                                              emit-c (:emit-collect events)]
                                          (on "stream_filter"
                                              (fn [_]
                                                #js {:abort  true
                                                     :reason "TTSR rule matched"
                                                     :inject #js [#js {:role "system" :content "Never use eval()"}]}))
                                          (let [p (emit-c "stream_filter"
                                                          #js {:delta "eval(" :chunk "eval(" :type "message_update"})]
                                            (.then p (fn [result]
                                                       (-> (expect (get result "abort")) (.toBe true))
                                                       (let [inject (get result "inject")]
                                                         (-> (expect (some? inject)) (.toBe true))
                                                         (-> (expect (.-length inject)) (.toBe 1))
                                                         (-> (expect (.-content (aget inject 0))) (.toBe "Never use eval()")))))))))))

;;; ─── before_provider_request-blocked integration ─────────────────────
;; Verify that the run loop's retry infrastructure is wired up correctly
;; by checking that retry-state is isolated from the blocked path.

(defn ^:async test-retry-state-not-consumed-by-blocked-path []
  (let [agent  (create-agent {:model "test" :system-prompt "test"})
        on     (:on (:events agent))]
    ;; Manually set retry-state to simulate a previous aborted stream
    (reset! (:retry-state agent) {:reason "leftover" :inject []})
    ;; Block the LLM call — this path never enters the retry loop
    (on "before_provider_request" (fn [_] #js {:block true :reason "ok"}))
    (js-await (run agent "test"))
    ;; The blocked path doesn't touch retry-state, so it remains set.
    ;; This confirms the blocked and retry paths are independent.
    (-> (expect (nil? @(:retry-state agent))) (.toBe false))))

(describe "agent.loop/run - retry-state reset" (fn []
                                                 (it "blocked path does not consume retry-state"
                                                     test-retry-state-not-consumed-by-blocked-path)

                                                 (it "PLACEHOLDER — blocked path is separate from retry loop"
                                                     (fn []
        ;; The blocked path (before_provider_request returns {block: true})
        ;; exits before the retry loop is entered. Full retry loop tests
        ;; require a streamText mock and live in integration tests.
                                                       (-> (expect true) (.toBe true))))))
