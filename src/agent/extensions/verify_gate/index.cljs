(ns agent.extensions.verify-gate.index
  "Verify-before-done gate: after a turn that edited files, run the
   configured quality command; on failure, inject the output as a follow-up
   so the agent fixes it before stopping. Off unless settings#verify.cmd is
   set. Attempts are capped so a stubbornly red suite can't loop forever.
   With settings#verify.critic naming a role, a green run also gets one
   read-only review in a fresh context."
  (:require [agent.debug :as d]
            [agent.tool-metadata :as tool-metadata]
            [agent.utils.content :as content]
            [agent.extensions.model-roles.features.review :as review]
            [agent.extensions.verify-gate.shared :as shared]))

(defn ^:async run-cmd [cmd timeout-ms]
  (let [proc   (js/Bun.spawn #js ["sh" "-c" cmd]
                             #js {:timeout timeout-ms :stdout "pipe" :stderr "pipe"})
        stdout (js-await (.text (js/Response. (.-stdout proc))))
        stderr (js-await (.text (js/Response. (.-stderr proc))))
        code   (js-await (.-exited proc))]
    {:exit-code code :output (str stdout (when (seq stderr) (str "\n" stderr)))}))

;; The last verify outcome, for the status line: {:status :pass|:fail :cmd s}.
;;
;; Recorded where the bus events are already emitted, so this costs nothing — the
;; command has run either way. Module-level rather than episode-scoped because
;; every other piece of verify_gate state is reset by `end-episode!`, and the
;; point of this one is to survive the episode and still be readable next turn.
;;
;; NB a comment, not a docstring: Squint's `defonce` takes no docstring, so
;; `(defonce x "doc" (atom nil))` binds x to the STRING and drops the atom —
;; every later `reset!` then fails inside squint core with a nil error.
(defonce last-result (atom nil))

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
        ;; All per prompt, unlike the episode above: a fix loop ends one episode
        ;; and the critic still owes the whole run one look.
        run-paths    (atom #{})
        run-task     (atom nil)
        red?         (atom false)
        critic-done? (atom false)

        ;; The debug log alone reached nobody, and a human is who this is for.
        warn!
        (fn [msg]
          (d/warn "verify-gate" msg)
          (let [ui (.-ui api)]
            (when (and ui (.-available ui) (.-notify ui))
              (.notify ui msg "warning"))))

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
              (swap! run-paths conj (str path))
              (swap! (if (pos? @attempts) fix-paths pre-paths) conj (str path)))))

        on-start
        (fn [_ _]
          (reset! run-paths #{})
          (reset! red? false)
          (reset! critic-done? false)
          (reset! run-task (when (.-getState api)
                             (some->> (:messages (.getState api))
                                      (filter #(= "user" (:role %)))
                                      last :content content/->text)))
          nil)

        ;; Awaited inside turn_finalize, because a follow-up queued any later
        ;; lands after the drain and sits there. Never blocks: a critic that
        ;; failed has said nothing about the change.
        critic!
        (^:async fn [event ctx]
          (when (and (:critic cfg)
                     (not @critic-done?)
                     (not @red?)
                     (seq @run-paths)
                     (not (.-stepCapped event))
                     (not (.-nudged event))
                     (not (some-> ctx .hasPendingMessages))
                     (not (aget js/process.env "NYMA_SUBAGENT_DEPTH")))
            (let [change (shared/run-change @run-paths (js/process.cwd))]
              (when (or (seq (:diff change)) (seq (:new change)))
                (reset! critic-done? true)
                (let [ui     (.-ui api)
                      _      (when (and ui (.-available ui) (.-notify ui))
                               (.notify ui (str "Reviewing this change with " (:critic cfg) "…") "info"))
                      result (try
                               (js-await (review/ask-child (.getTool api "subagent__subagent") (:critic cfg)
                                                           (shared/critic-task @run-task change)
                                                           (.-abortSignal event)))
                               (catch :default e {:ok false :text (.-message e)}))]
                  (cond
                    (nil? result)
                    (warn! "verify.critic needs the subagent extension")

                    (= "cancelled" (:status result)) nil

                    (not (:ok result))
                    (warn! (str "critic did not finish: " (:text result)))

                    (review/findings? (:text result))
                    (let [findings (shared/critic-findings (:text result))]
                      (reset! last-result {:status :critic :count (count findings)})
                      ((.-sendUserMessage api) (shared/critic-message findings)
                                               #js {:deliverAs "followUp"}))))))))

        on-finalize
        (^:async fn [event ctx]
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
                  (reset! red? false)
                  ;; "Building to the Test" (2026): in-loop verifiers get
                  ;; satisfied instead of the request. Green after a failure:
                  ;; strong warning for graded-check edits made mid-fix-loop,
                  ;; soft mention for ones made earlier in the same episode.
                  (when (pos? @attempts)
                    (when-let [tampered (seq (shared/tampered-paths @fix-paths))]
                      (warn! (str "gate passed after edits to graded checks — review: "
                                 (.join (clj->js (vec tampered)) ", "))))
                    (when-let [earlier (seq (shared/tampered-paths @pre-paths))]
                      (warn! (str "gate also saw pre-failure test edits this episode — worth a look: "
                                 (.join (clj->js (vec earlier)) ", ")))))
                  ;; Green. Published so a driver above this gate can tell
                  ;; "verify is settled" from "verify is mid-fix-loop" — the
                  ;; fail/exhausted pair alone only ever says it is red, so a
                  ;; subscriber had no edge on which to resume.
                  (reset! last-result {:status :pass :cmd (:cmd cfg)})
                  (when (.-emitGlobal api)
                    (.emitGlobal api "small-model/verify-pass"
                                 #js {:cmd (:cmd cfg) :attempts @attempts}))
                  (end-episode!))
                ;; Publish a failure signal for small-model/self-tune (additive;
                ;; no-op if nothing subscribes). Mirrors quality_monitor's bus use.
                (do
                  (reset! red? true)
                  (reset! last-result {:status :fail :cmd (:cmd cfg)})
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
                         #js {:deliverAs "followUp"})))))))
          (when-not (.-error event)
            (js-await (critic! event ctx))))]

    ;; Registered OUTSIDE the branch below. It sat in the `else` — the
    ;; verify-is-OFF arm — while `last-result` is only ever set in the `:cmd`
    ;; arm, so the two were mutually exclusive: with verify.cmd set the segment
    ;; never appeared, and without it the segment existed and could never have
    ;; data. Exactly inverted.
    (when (.-registerStatusSegment api)
      (.registerStatusSegment
       api "verify.last"
       #js {:category "verify"
            :autoAppend true
            :position "right"
            :render
            (fn [ctx]
              (let [{:keys [status]} @last-result
                    theme (aget ctx "theme")
                    col   (fn [k d] (or (get-in theme [:colors k]) d))]
                (case status
                  :pass #js {:visible? true :content " verify ✓"
                             :color (col :success "#9ece6a")}
                  :fail #js {:visible? true :content " verify ✗"
                             :color (col :error "#f7768e")}
                  :critic #js {:visible? true :content (str " critic " (:count @last-result))
                               :color (col :warning "#e0af68")}
                  ;; Nothing has run yet — and "no result" is not a pass.
                  #js {:visible? false})))}))

    (if (or (:cmd cfg) (:critic cfg))
      (do
        (.on api "agent_start" on-start)
        (.on api "tool_complete" on-complete)
        (.on api "turn_finalize" on-finalize)
        (fn []
          (.off api "agent_start" on-start)
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
