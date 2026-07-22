(ns agent.extensions.verify-gate.index
  "Verify-before-done gate: after a turn that edited files, run the
   configured quality command; on failure, inject the output as a follow-up
   so the agent fixes it before stopping. Off unless settings#verify.cmd is
   set. Attempts are capped so a stubbornly red suite can't loop forever."
  (:require [agent.debug :as d]
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
        ;; Paths edited during the fix loop — for the graded-check tamper warning.
        edited-paths (atom #{})
        attempts (atom 0)

        on-complete
        (fn [event _ctx]
          (when (and (shared/edit-tool? (.-toolName event))
                     (not (.-isError event)))
            (reset! edited? true)
            ;; Track paths only DURING the fix loop (after a gate failure) —
            ;; pre-failure edits are the user's requested work; flagging them
            ;; would accuse e.g. "write tests for X" of tampering.
            (when (pos? @attempts)
              (when-let [path (some-> (.-args event) (aget "path"))]
                (swap! edited-paths conj (str path))))))

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
                  ;; satisfied instead of the request. If the gate turned
                  ;; green after the agent edited test files mid-fix-loop,
                  ;; tell the user to review those edits.
                  (when (pos? @attempts)
                    (when-let [tampered (seq (shared/tampered-paths @edited-paths))]
                      (d/warn "verify-gate"
                              (str "gate passed after edits to graded checks — review: "
                                   (.join (clj->js (vec tampered)) ", ")))))
                  (reset! edited-paths #{})
                  (reset! attempts 0))
                (if (< @attempts (:max-attempts cfg))
                  (do (swap! attempts inc)
                      ((.-sendUserMessage api)
                       (shared/failure-message (:cmd cfg) exit-code output
                                               @attempts (:max-attempts cfg))
                       #js {:deliverAs "followUp"}))
                  ;; Cap reached and still red: stop the loop, but don't lie
                  ;; by staying silent — report without asking for edits.
                  ;; Episode over: clear the fix-loop path ledger too, or its
                  ;; stale paths leak into the NEXT episode's tamper warning.
                  (do (reset! attempts 0)
                      (reset! edited-paths #{})
                      ((.-sendUserMessage api)
                       (str "Verification still failing after the attempt cap (`" (:cmd cfg)
                            "` exited " exit-code "). Do NOT edit further — summarize the "
                            "remaining failures for the user:\n\n```\n"
                            (shared/tail-lines output 40) "\n```")
                       #js {:deliverAs "followUp"})))))))]

    (when (:cmd cfg)
      (.on api "tool_complete" on-complete)
      (.on api "turn_finalize" on-finalize))

    (fn []
      (when (:cmd cfg)
        (.off api "tool_complete" on-complete)
        (.off api "turn_finalize" on-finalize)))))

(def ^:export default activate)
