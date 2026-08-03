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
  (let [cfg      (shared/config (try (.getSettings api) (catch :default _ nil)))
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
                  (end-episode!))
                ;; Publish a failure signal for small-model/self-tune (additive;
                ;; no-op if nothing subscribes). Mirrors quality_monitor's bus use.
                (do
                  (when (and (.-emitGlobal api) (not @signalled?))
                    (reset! signalled? true)
                    (.emitGlobal api "small-model/verify-fail"
                                 #js {:reason (str "verify command failed (exit " exit-code ")")
                                      :cmd    (:cmd cfg)}))
                  (if (< @attempts (:max-attempts cfg))
                    (do (swap! attempts inc)
                        ((.-sendUserMessage api)
                         (shared/failure-message (:cmd cfg) exit-code output
                                                 @attempts (:max-attempts cfg))
                         #js {:deliverAs "followUp"}))
                    ;; Cap reached and still red: stop the loop, but don't lie
                    ;; by staying silent — report without asking for edits.
                    (do (end-episode!)
                        ((.-sendUserMessage api)
                         (str "Verification still failing after the attempt cap (`" (:cmd cfg)
                              "` exited " exit-code "). Do NOT edit further — summarize the "
                              "remaining failures for the user:\n\n```\n"
                              (shared/tail-lines output 40) "\n```")
                         #js {:deliverAs "followUp"}))))))))]

    (when (:cmd cfg)
      (.on api "tool_complete" on-complete)
      (.on api "turn_finalize" on-finalize))

    (fn []
      (when (:cmd cfg)
        (.off api "tool_complete" on-complete)
        (.off api "turn_finalize" on-finalize)))))

(def ^:export default activate)
