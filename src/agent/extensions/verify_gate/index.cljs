(ns agent.extensions.verify-gate.index
  "Verify-before-done gate: after a turn that edited files, run the
   configured quality command; on failure, inject the output as a follow-up
   so the agent fixes it before stopping. Off unless settings#verify.cmd is
   set. Attempts are capped so a stubbornly red suite can't loop forever."
  (:require [agent.debug :as d]
            [agent.tool-metadata :as tool-metadata]
            [agent.extensions.verify-gate.shared :as shared]))

(defn ^:async run-cmd [cmd timeout-ms]
  (let [proc   (js/Bun.spawn #js ["sh" "-c" cmd]
                             #js {:timeout timeout-ms :stdout "pipe" :stderr "pipe"})
        stdout (js-await (.text (js/Response. (.-stdout proc))))
        stderr (js-await (.text (js/Response. (.-stderr proc))))
        code   (js-await (.-exited proc))]
    {:exit-code code :output (str stdout (when (seq stderr) (str "\n" stderr)))}))

(defn ^:export activate [api]
  (let [cfg      (shared/config (.settings api))
        edited?  (atom false)
        ;; Two ledgers, two tones: paths edited DURING the fix loop (after a
        ;; failure) warrant the strong tamper warning; paths edited BEFORE the
        ;; first failure are usually the user's requested work ("write tests
        ;; for X") but still deserve a soft mention when the gate then failed
        ;; and recovered — a turn-1 test-weakening is in that window.
        fix-paths (atom #{})
        pre-paths (atom #{})
        attempts (atom 0)
        ;; One bus signal per red episode, not per failed turn: a subscriber
        ;; (small-model/self-tune) budgets its reflections, and 4 identical
        ;; signals for one logical failure would burn that budget on dupes.
        signalled? (atom false)

        end-episode!
        (fn []
          (reset! fix-paths #{})
          (reset! pre-paths #{})
          (reset! signalled? false)
          (reset! attempts 0))

        on-complete
        (fn [event _ctx]
          (when (and (shared/edit-tool? (.-toolName event))
                     (not (.-isError event)))
            (reset! edited? true)
            (when-let [path (tool-metadata/tool-path (.-args event))]
              (swap! (if (pos? @attempts) fix-paths pre-paths) conj (str path)))))

        on-finalize
        (^:async fn [event _ctx]
          (when (and (:cmd cfg)
                     @edited?
                     (not (.-error event)))
            (reset! edited? false)
            ;; ALWAYS run the gate when files changed — the attempt cap only
            ;; gates whether another fix follow-up is SENT, so the fix made
            ;; in response to the final follow-up is still verified.
            (let [{:keys [exit-code output]}
                  (js-await (run-cmd (:cmd cfg) (:timeout-ms cfg)))]
              (if (zero? exit-code)
                (do
                  ;; "Building to the Test" (2026): in-loop verifiers get
                  ;; satisfied instead of the request. Green after a failure:
                  ;; strong warning for graded-check edits made mid-fix-loop,
                  ;; soft mention for ones made earlier in the same episode.
                  (when (pos? @attempts)
                    (when-let [tampered (seq (shared/tampered-paths @fix-paths))]
                      (d/warn "verify-gate"
                              (str "gate passed after edits to graded checks — review: "
                                   (.join (clj->js (vec tampered)) ", "))))
                    (when-let [earlier (seq (shared/tampered-paths @pre-paths))]
                      (d/warn "verify-gate"
                              (str "gate also saw pre-failure test edits this episode — worth a look: "
                                   (.join (clj->js (vec earlier)) ", ")))))
                  ;; Green. Published so a driver above this gate can tell
                  ;; "verify is settled" from "verify is mid-fix-loop" — the
                  ;; fail/exhausted pair alone only ever says it is red, so a
                  ;; subscriber had no edge on which to resume.
                  (when (.-emitGlobal api)
                    (.emitGlobal api "small-model/verify-pass"
                                 #js {:cmd (:cmd cfg) :attempts @attempts}))
                  (end-episode!))
                ;; Publish a failure signal for small-model/self-tune (additive;
                ;; no-op if nothing subscribes). Mirrors quality_monitor's bus use.
                (do
                  (when (and (.-emitGlobal api) (not @signalled?))
                    (reset! signalled? true)
                    (.emitGlobal api "small-model/verify-fail"
                                 #js {:reason (str "verify command failed (exit " exit-code ")")
                                      :cmd    (:cmd cfg)
                                      ;; The failure OUTPUT, not just the exit
                                      ;; number. self-tune asks the advisor to
                                      ;; write a rule preventing this class of
                                      ;; mistake; with only "exit 1" to go on it
                                      ;; was reasoning about a failure it could
                                      ;; not see. Same tail the model gets.
                                      :output (shared/tail-lines output 40)}))
                  (if (< @attempts (:max-attempts cfg))
                    (do (swap! attempts inc)
                        ((.-sendUserMessage api)
                         (shared/failure-message (:cmd cfg) exit-code output
                                                 @attempts (:max-attempts cfg))
                         #js {:deliverAs "followUp"}))
                    ;; Cap reached and still red: stop the loop, but don't lie
                    ;; by staying silent — report without asking for edits.
                    ;; The exhausted signal is separate from `verify-fail` above:
                    ;; that one fires on the FIRST red, while this gate is still
                    ;; fixing. Only here is the worker actually out of road.
                    (do (when (.-emitGlobal api)
                          (.emitGlobal api "small-model/verify-exhausted"
                                       #js {:reason "cmd" :output (shared/tail-lines output 40)}))
                        (end-episode!)
                        ((.-sendUserMessage api)
                         (str "Verification still failing after the attempt cap (`" (:cmd cfg)
                              "` exited " exit-code "). Do NOT edit further — summarize the "
                              "remaining failures for the user:\n\n```\n"
                              (shared/tail-lines output 40) "\n```")
                         #js {:deliverAs "followUp"}))))))))]

    (if (:cmd cfg)
      (do
        (.on api "tool_complete" on-complete)
        (.on api "turn_finalize" on-finalize)
        (fn []
          (.off api "tool_complete" on-complete)
          (.off api "turn_finalize" on-finalize)))

      ;; Off. Silent for the many users who never asked for the gate — but
      ;; NOT for the ones who wrote a `verify` section and misspelled the
      ;; one key that switches it on. Said once, at session_ready, when the
      ;; UI is guaranteed to be up.
      (let [unknown (shared/unknown-keys (.settings api "verify"))
            on-ready (fn [_data _ctx]
                       (when (seq unknown)
                         (let [msg (shared/off-hint unknown)
                               ui  (.-ui api)]
                           (d/warn "verify-gate" msg)
                           (when (and ui (.-available ui) (.-notify ui))
                             (.notify ui msg "warning"))))
                       nil)]
        (when (seq unknown) (.on api "session_ready" on-ready))
        (fn []
          (when (seq unknown) (.off api "session_ready" on-ready)))))))

(def ^:export default activate)
