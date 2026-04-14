(ns cli-lifecycle.test
  "Tests for the CLI session lifecycle — the events fired by cli.cljs at
   startup and shutdown:

     session_ready        — after extensions loaded + model resolved
     session_shutdown     — first signal that the agent is going away
     session_end_summary  — stats snapshot
     session_end          — final cleanup hook

   Extensions like desktop-notify subscribe to session_end_summary; if the
   ordering or payload shape ever drifts, those handlers silently break.
   These tests pin the contract."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.cli :refer [agent-stats
                               emit-session-ready!
                               emit-session-shutdown!
                               emit-session-shutdown-async!]]))

(defn- make-agent []
  (create-agent {:model "test" :system-prompt "test"}))

;;; ─── agent-stats ─────────────────────────────────────────────────────

(describe "cli/agent-stats"
          (fn []
            (it "snapshots the standard fields with sensible defaults"
                (fn []
                  (let [agent (make-agent)
                        s     (agent-stats agent)]
                    ;; Fresh agent: zero counts, zero cost
                    (-> (expect (.-totalCost s)) (.toBe 0))
                    (-> (expect (.-turnCount s)) (.toBe 0))
                    (-> (expect (.-inputTokens s)) (.toBe 0))
                    (-> (expect (.-outputTokens s)) (.toBe 0))
                    (-> (expect (.-messageCount s)) (.toBe 0)))))

            (it "reflects current state values"
                (fn []
                  (let [agent (make-agent)]
                    (swap! (:state agent) assoc
                           :total-cost         0.42
                           :turn-count         3
                           :total-input-tokens 1500
                           :total-output-tokens 700
                           :messages           [{:role "user" :content "a"}
                                                {:role "assistant" :content "b"}])
                    (let [s (agent-stats agent)]
                      (-> (expect (.-totalCost s)) (.toBe 0.42))
                      (-> (expect (.-turnCount s)) (.toBe 3))
                      (-> (expect (.-inputTokens s)) (.toBe 1500))
                      (-> (expect (.-outputTokens s)) (.toBe 700))
                      (-> (expect (.-messageCount s)) (.toBe 2))))))))

;;; ─── session_ready ──────────────────────────────────────────────────

(defn ^:async test-session-ready-payload-shape []
  (let [agent (make-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "session_ready"
                           (fn [data] (reset! seen data)))
    (js-await (emit-session-ready! agent "claude-test" 7))
    (-> (expect (some? @seen)) (.toBe true))
    (-> (expect (.-cwd @seen)) (.toBe (js/process.cwd)))
    (-> (expect (.-model @seen)) (.toBe "claude-test"))
    (-> (expect (.-extensions @seen)) (.toBe 7))))

(defn ^:async test-session-ready-with-nil-model []
  ;; If the user has no API key, model is nil — the helper still has to
  ;; emit a payload, with model="unknown".
  (let [agent (make-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "session_ready"
                           (fn [data] (reset! seen data)))
    (js-await (emit-session-ready! agent nil 0))
    (-> (expect (.-model @seen)) (.toBe "unknown"))
    (-> (expect (.-extensions @seen)) (.toBe 0))))

;;; ─── synchronous shutdown sequence (the "exit" handler path) ─────────

(defn ^:async test-shutdown-event-order-sync []
  (let [agent (make-agent)
        order (atom [])]
    ((:on (:events agent)) "session_shutdown"
                           (fn [_] (swap! order conj :shutdown)))
    ((:on (:events agent)) "session_end_summary"
                           (fn [_] (swap! order conj :summary)))
    ((:on (:events agent)) "session_end"
                           (fn [_] (swap! order conj :end)))
    (emit-session-shutdown! agent "exit")
    (-> (expect (nth @order 0)) (.toBe :shutdown))
    (-> (expect (nth @order 1)) (.toBe :summary))
    (-> (expect (nth @order 2)) (.toBe :end))
    (-> (expect (count @order)) (.toBe 3))))

(defn ^:async test-shutdown-payload-shapes-sync []
  (let [agent      (make-agent)
        shutdown   (atom nil)
        summary    (atom nil)
        end        (atom nil)]
    (swap! (:state agent) assoc
           :total-cost   1.5
           :turn-count   4
           :messages     [{:role "user" :content "x"}])
    ((:on (:events agent)) "session_shutdown" (fn [d] (reset! shutdown d)))
    ((:on (:events agent)) "session_end_summary" (fn [d] (reset! summary d)))
    ((:on (:events agent)) "session_end" (fn [d] (reset! end d)))
    (emit-session-shutdown! agent "exit")
    ;; session_shutdown carries {:reason "exit"}
    (-> (expect (:reason @shutdown)) (.toBe "exit"))
    ;; session_end_summary + session_end both carry the stats JS obj
    (-> (expect (.-totalCost @summary)) (.toBe 1.5))
    (-> (expect (.-turnCount @summary)) (.toBe 4))
    (-> (expect (.-messageCount @summary)) (.toBe 1))
    (-> (expect (.-totalCost @end)) (.toBe 1.5))))

;;; ─── async shutdown sequence (the "SIGINT" handler path) ─────────────

(defn ^:async test-shutdown-event-order-async []
  (let [agent (make-agent)
        order (atom [])]
    ((:on (:events agent)) "session_shutdown"
                           (fn [_] (swap! order conj :shutdown)))
    ((:on (:events agent)) "session_end_summary"
                           (fn [_] (swap! order conj :summary)))
    ((:on (:events agent)) "session_end"
                           (fn [_] (swap! order conj :end)))
    (js-await (emit-session-shutdown-async! agent "sigint"))
    (-> (expect (nth @order 0)) (.toBe :shutdown))
    (-> (expect (nth @order 1)) (.toBe :summary))
    (-> (expect (nth @order 2)) (.toBe :end))))

(defn ^:async test-shutdown-async-awaits-handlers []
  ;; The async shutdown should wait for emit-async handlers to settle. Use
  ;; a deliberately-slow handler to make the contract observable.
  (let [agent     (make-agent)
        completed (atom false)]
    ((:on (:events agent)) "session_end_summary"
                           (fn [_]
                             (js/Promise. (fn [resolve]
                                            (js/setTimeout
                                             (fn []
                                               (reset! completed true)
                                               (resolve nil))
                                             10)))))
    (js-await (emit-session-shutdown-async! agent "sigint"))
    ;; After awaiting, the slow handler must have run
    (-> (expect @completed) (.toBe true))))

(defn ^:async test-shutdown-async-reason-payload []
  (let [agent (make-agent)
        seen  (atom nil)]
    ((:on (:events agent)) "session_shutdown" (fn [d] (reset! seen d)))
    (js-await (emit-session-shutdown-async! agent "sigint"))
    (-> (expect (:reason @seen)) (.toBe "sigint"))))

(describe "cli/emit-session-ready!"
          (fn []
            (it "emits session_ready with cwd / model / extensions count"
                test-session-ready-payload-shape)
            (it "stringifies a nil model to 'unknown'"
                test-session-ready-with-nil-model)))

(describe "cli/emit-session-shutdown! — synchronous (exit handler)"
          (fn []
            (it "emits session_shutdown → session_end_summary → session_end in order"
                test-shutdown-event-order-sync)
            (it "carries the right payload shapes on each event"
                test-shutdown-payload-shapes-sync)))

(describe "cli/emit-session-shutdown-async! — async (SIGINT handler)"
          (fn []
            (it "emits the same three events in order"
                test-shutdown-event-order-async)
            (it "awaits async handlers attached to session_end_summary"
                test-shutdown-async-awaits-handlers)
            (it "session_shutdown carries {:reason 'sigint'}"
                test-shutdown-async-reason-payload)))
