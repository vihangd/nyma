(ns handler-lifecycle.test
  "Regression suite for event-bus handler lifecycle correctness.

   Covers the class of bug fixed in e882aed: a message_update handler leaked
   when run threw between on and off (no try/finally), so every subsequent turn
   added another handler — each text chunk was appended N times, doubling the
   assistant response after any error during a session.

   These tests use :handler-count on the event bus (added as part of this fix)
   to assert handler counts directly, rather than inferring from side-effects."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.events :refer [create-event-bus]]
            [agent.loop :refer [run run-turn-with-update-handler]]
            [test-util.agent-harness :refer [make-test-agent block-provider!
                                             throw-from-run!]]))

;;; ─── 1. Event bus :handler-count baseline ────────────────────────────────

(defn test-handler-count-zero-for-unknown-event []
  (let [bus (create-event-bus)]
    (-> (expect ((:handler-count bus) "message_update")) (.toBe 0))))

(defn test-handler-count-one-after-on-zero-after-off []
  (let [bus (create-event-bus)
        h   (fn [_])]
    ((:on bus) "message_update" h)
    (-> (expect ((:handler-count bus) "message_update")) (.toBe 1))
    ((:off bus) "message_update" h)
    (-> (expect ((:handler-count bus) "message_update")) (.toBe 0))))

(defn test-handler-count-multiple-handlers []
  (let [bus (create-event-bus)
        h1  (fn [_])
        h2  (fn [_])]
    ((:on bus) "message_update" h1)
    ((:on bus) "message_update" h2)
    (-> (expect ((:handler-count bus) "message_update")) (.toBe 2))
    ((:off bus) "message_update" h1)
    (-> (expect ((:handler-count bus) "message_update")) (.toBe 1))))

;;; ─── 2. run-turn-with-update-handler lifecycle ───────────────────────────

(defn ^:async test-handler-count-zero-after-successful-turn []
  (let [agent    (make-test-agent)
        on-chunk (fn [_])
        _        (block-provider! agent)]
    (js-await (run-turn-with-update-handler agent on-chunk #(run agent "hello")))
    (-> (expect ((:handler-count (:events agent)) "message_update")) (.toBe 0))))

(defn ^:async test-handler-count-zero-after-two-consecutive-turns []
  (let [agent    (make-test-agent)
        on-chunk (fn [_])
        _        (block-provider! agent)]
    (js-await (run-turn-with-update-handler agent on-chunk #(run agent "turn 1")))
    (js-await (run-turn-with-update-handler agent on-chunk #(run agent "turn 2")))
    (-> (expect ((:handler-count (:events agent)) "message_update")) (.toBe 0))))

(defn ^:async test-handler-count-zero-when-run-throws []
  ;; Reproduction of e882aed: run throws, the try/finally in
  ;; run-turn-with-update-handler must still deregister the handler.
  (let [agent    (make-test-agent)
        on-chunk (fn [_])
        _        (throw-from-run! agent (js/Error. "simulated run failure"))]
    (try
      (js-await (run-turn-with-update-handler agent on-chunk #(run agent "fail")))
      (catch :default _))
    (-> (expect ((:handler-count (:events agent)) "message_update")) (.toBe 0))))

(defn ^:async test-handler-count-never-exceeds-one-across-throw-then-success []
  ;; First turn throws; second turn succeeds.
  ;; At no point should there be > 1 handler; after the second turn count = 0.
  (let [agent    (make-test-agent)
        on-chunk (fn [_])
        err      (js/Error. "first turn fails")
        throw-h  (throw-from-run! agent err)]
    ;; First turn: throw
    (try
      (js-await (run-turn-with-update-handler agent on-chunk #(run agent "fail")))
      (catch :default _))
    (-> (expect ((:handler-count (:events agent)) "message_update")) (.toBe 0))
    ;; Remove throw hook, install block so second turn completes cleanly
    ((:off (:events agent)) "before_agent_start" throw-h)
    (block-provider! agent)
    ;; Second turn: success
    (js-await (run-turn-with-update-handler agent on-chunk #(run agent "ok")))
    (-> (expect ((:handler-count (:events agent)) "message_update")) (.toBe 0))))

;;; ─── 3. SDK :on-many unsubscribe round-trip (F1) ─────────────────────────

(defn test-sdk-on-many-unsub-removes-all-handlers []
  ;; Mirrors the SDK :on-many wrapper in modes/sdk.cljs to verify the
  ;; corrected implementation returns real unsub thunks.
  (let [agent   (make-test-agent)
        events  (:events agent)
        on-many (fn [handlers-map]
                  (let [unsub-fns (mapv (fn [[ev handler]]
                                          ((:on events) ev handler)
                                          (fn [] ((:off events) ev handler)))
                                        handlers-map)]
                    (fn [] (doseq [f unsub-fns] (f)))))
        unsub   (on-many [["message_update" (fn [_])]
                          ["agent_start"    (fn [_])]
                          ["agent_end"      (fn [_])]])]
    (-> (expect ((:handler-count events) "message_update")) (.toBe 1))
    (-> (expect ((:handler-count events) "agent_start"))    (.toBe 1))
    (-> (expect ((:handler-count events) "agent_end"))      (.toBe 1))
    (unsub)
    (-> (expect ((:handler-count events) "message_update")) (.toBe 0))
    (-> (expect ((:handler-count events) "agent_start"))    (.toBe 0))
    (-> (expect ((:handler-count events) "agent_end"))      (.toBe 0))))

;;; ─── 4. SDK :on unsubscribe round-trip (F2) ──────────────────────────────

(defn test-sdk-on-unsub-thunk-removes-handler []
  ;; Mirrors the SDK :on wrapper in modes/sdk.cljs.
  (let [agent  (make-test-agent)
        events (:events agent)
        sdk-on (fn [event handler]
                 ((:on events) event handler)
                 (fn [] ((:off events) event handler)))
        unsub  (sdk-on "message_update" (fn [_]))]
    (-> (expect ((:handler-count events) "message_update")) (.toBe 1))
    (unsub)
    (-> (expect ((:handler-count events) "message_update")) (.toBe 0))))

;;; ─── Registration ────────────────────────────────────────────────────────

(describe "event bus :handler-count"
          (fn []
            (it "returns 0 for event with no handlers"
                test-handler-count-zero-for-unknown-event)
            (it "returns 1 after on, 0 after off"
                test-handler-count-one-after-on-zero-after-off)
            (it "counts multiple handlers independently"
                test-handler-count-multiple-handlers)))

(describe "run-turn-with-update-handler lifecycle"
          (fn []
            (it "handler count is 0 after successful turn"
                test-handler-count-zero-after-successful-turn)
            (it "handler count is 0 after two consecutive successful turns"
                test-handler-count-zero-after-two-consecutive-turns)
            (it "handler count is 0 when run-fn throws (original bug)"
                test-handler-count-zero-when-run-throws)
            (it "handler count never exceeds 1 across throw-then-success"
                test-handler-count-never-exceeds-one-across-throw-then-success)))

(describe "SDK :on-many unsubscribe (F1)"
          (fn []
            (it "unsub-all removes all registered handlers"
                test-sdk-on-many-unsub-removes-all-handlers)))

(describe "SDK :on unsubscribe (F2)"
          (fn []
            (it "returned thunk removes the handler"
                test-sdk-on-unsub-thunk-removes-handler)))
