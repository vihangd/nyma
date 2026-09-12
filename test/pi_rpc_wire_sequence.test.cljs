(ns pi-rpc-wire-sequence.test
  "What a consumer actually sees on the wire, in order.

   Three defects a code review found in the first cut of the turn events, all
   of them invisible to a per-field test because each event was individually
   well-formed — it was the *sequence* that was wrong:

   1. nyma emits `turn_start` once per run-loop iteration but `turn_end` once
      per STEP (`loop.cljs:411`, from onStepFinish). A five-tool turn therefore
      sent one start and five ends, and a consumer that finalises its turn
      accounting on `turn_end` finalised after the first tool call.
   2. `agent_settled` was wired to `turn_finalize`, which fires per turn and
      *before* the follow-queue drain (`loop.cljs:707` vs `725`). With a
      follow-up queued — the loop enqueues one itself as the cut-off nudge — a
      frontend that unlocks input on `agent_settled` unlocked mid-run.
   3. On a provider error `agent_end` is skipped (it sits inside the try whose
      catch only stashes the error, `loop.cljs:645-649`) while `turn_finalize`
      still runs, so the wire showed `agent_settled` with no `agent_end` and
      `isStreaming` stayed true until the next run.

   So these tests assert the ORDER of what reaches stdout."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.pi-rpc :refer [subscribe-events!]]))

(defn- capture
  "Run `f` with stdout captured; returns the event objects written."
  [f]
  (let [real (.-log js/console)
        out  (atom [])]
    (set! (.-log js/console) (fn [line] (swap! out conj (js/JSON.parse (str line)))))
    (try (f) (finally (set! (.-log js/console) real)))
    @out))

(defn- fake-agent
  "An agent with just the bus and the follow-queue the translator reads."
  [& [follow]]
  (let [handlers (atom {})]
    {:events {:on  (fn [e h] (swap! handlers update e (fnil conj []) h) nil)
              :off (fn [e h] (swap! handlers update e (fn [hs] (vec (remove #(= % h) hs)))) nil)}
     :follow-queue (atom (or follow []))
     :__handlers handlers}))

(defn- fire! [agent e data]
  (mapv (fn [h] (h data)) (get @(:__handlers agent) e)))

(defn- st-of [] {:acc (atom "") :content-index (atom 0) :streaming? (atom false)
                 :running? (atom false) :compacting? (atom false)
                 :turn-open? (atom false) :pending-ui (atom {})})

(defn- step [& {:keys [text finish]}]
  #js {:text (or text "") :finishReason (or finish "stop") :toolResults #js []})

(defn- types-of [events] (vec (map #(.-type %) events)))

;;; ─── 1. the turn pair ───────────────────────────────────────

(describe "turn_start / turn_end alternate strictly"
          (fn []
            (it "emits a turn_start for every turn_end, not one per run"
                (fn []
                  ;; The five-tool-turn shape: one upstream turn_start, several
                  ;; step-level turn_ends.
                  (let [agent (fake-agent) st (st-of)
                        evs (capture
                             (fn []
                               (let [unsub (subscribe-events! agent st)]
                                 (fire! agent "turn_start" #js {})
                                 (fire! agent "turn_end" (step :finish "tool-calls"))
                                 (fire! agent "turn_end" (step :finish "tool-calls"))
                                 (fire! agent "turn_end" (step :finish "stop"))
                                 (unsub))))
                        ts  (types-of evs)]
                    (-> (expect ts)
                        (.toEqual #js ["turn_start" "turn_end"
                                       "turn_start" "turn_end"
                                       "turn_start" "turn_end"])))))

            (it "does not emit a second turn_start for a repeated upstream one"
                (fn []
                  ;; Idempotent: the stream_filter retry loop sits inside the
                  ;; upstream turn_start, so it must not double up.
                  (let [agent (fake-agent) st (st-of)
                        evs (capture
                             (fn []
                               (let [unsub (subscribe-events! agent st)]
                                 (fire! agent "turn_start" #js {})
                                 (fire! agent "turn_start" #js {})
                                 (fire! agent "turn_end" (step))
                                 (unsub))))]
                    (-> (expect (types-of evs)) (.toEqual #js ["turn_start" "turn_end"])))))))

;;; ─── 2. agent_settled ───────────────────────────────────────

(describe "agent_settled"
          (fn []
            (it "is withheld while a follow-up is queued"
                (fn []
                  ;; The loop will recur into another turn, so the run has not
                  ;; settled — this is the cut-off-nudge path the loop creates
                  ;; for itself.
                  (let [agent (fake-agent [{:role "user" :content "continue"}])
                        st (st-of)
                        evs (capture
                             (fn []
                               (let [unsub (subscribe-events! agent st)]
                                 (fire! agent "turn_finalize" #js {})
                                 (unsub))))]
                    (-> (expect (types-of evs)) (.not.toContain "agent_settled")))))

            (it "is emitted once the queue is empty"
                (fn []
                  (let [agent (fake-agent) st (st-of)
                        evs (capture
                             (fn []
                               (let [unsub (subscribe-events! agent st)]
                                 (fire! agent "turn_finalize" #js {})
                                 (unsub))))]
                    (-> (expect (types-of evs)) (.toEqual #js ["agent_settled"])))))))

;;; ─── 3. the error path ──────────────────────────────────────

(describe "a provider error still closes the turn"
          (fn []
            (it "synthesises agent_end before agent_settled when still streaming"
                (fn []
                  ;; agent_start sets streaming; the provider then errors, so
                  ;; agent_end never arrives from the loop.
                  (let [agent (fake-agent) st (st-of)
                        evs (capture
                             (fn []
                               (let [unsub (subscribe-events! agent st)]
                                 (fire! agent "agent_start" #js {})
                                 (fire! agent "turn_finalize" #js {:error true})
                                 (unsub))))]
                    (-> (expect (types-of evs))
                        (.toEqual #js ["agent_start" "agent_end" "agent_settled"]))
                    ;; And the flag get_state reports is honest again.
                    (-> (expect @(:streaming? st)) (.toBe false)))))

            (it "does not double up agent_end on the normal path"
                (fn []
                  ;; The loop already sent one; a second would look like two
                  ;; runs to a consumer counting them.
                  (let [agent (fake-agent) st (st-of)
                        evs (capture
                             (fn []
                               (let [unsub (subscribe-events! agent st)]
                                 (fire! agent "agent_start" #js {})
                                 (fire! agent "agent_end" #js {})
                                 (fire! agent "turn_finalize" #js {})
                                 (unsub))))]
                    (-> (expect (types-of evs))
                        (.toEqual #js ["agent_start" "agent_end" "agent_settled"])))))))
