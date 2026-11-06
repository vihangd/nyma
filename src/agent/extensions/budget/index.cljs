(ns agent.extensions.budget.index
  "Token budgets: accumulate usage per turn and per session; when a
   configured cap is exceeded, abort the in-flight run so a runaway loop
   can't burn tokens unattended. Off unless settings#budget sets a cap.

   Enforcement rides `turn_end`, which fires per STEP (streamText
   onStepFinish) — aborting there stops the run between tool steps.
   `after_provider_request` only fires after the whole run has finished,
   which is too late to save any tokens.

   Subagents and extensions' own model calls count too, and are checked as they
   land: a child running inside one parent step reports each of its steps, and
   an abort reaches it through the tool's signal. A worktree child reports only
   when it exits, so parallel ones can overshoot by what each spends."
  (:require [agent.debug :as d]
            [agent.extensions.budget.shared :as shared]))

(defn ^:export activate [api]
  (let [cfg    (shared/config (.settings api))
        totals (atom {:turn 0 :session 0})
        ;; :extension-tokens already seen, so each is counted once.
        seen   (atom 0)
        new-extension-tokens!
        (fn []
          (let [now (or (when (.-getState api) (:extension-tokens (.getState api))) 0)
                new (- now @seen)]
            (reset! seen now)
            new))
        ;; Wall clock is a TIMER, not a turn_end check: a hung provider call
        ;; finishes no step, so a check that rides turn_end never runs in the
        ;; case the cap is for. Borrowed from mini-swe-agent's
        ;; wall_time_limit_seconds, which sits beside its step and cost limits.
        timer  (atom nil)
        ;; The running run's ctx, nil between runs: what lands then is the
        ;; session's alone, which `on-start` counts.
        run-ctx (atom nil)
        cancel-timer!
        (fn [] (when-let [t @timer] (js/clearTimeout t) (reset! timer nil)) nil)

        ;; Armed ONCE per run and never re-armed per step: this caps the run's
        ;; total wall time, which is what the name says. Re-arming on each step
        ;; would quietly turn it into an inactivity timeout that a long but
        ;; healthy run never trips.
        arm!
        (fn [ctx]
          (cancel-timer!)
          (when-let [secs (:wall-seconds cfg)]
            (reset! timer
                    (js/setTimeout
                     (fn []
                       (reset! timer nil)
                       (let [msg (str "wall-clock budget exceeded (" secs "s)")]
                         (d/warn "budget" (str msg " — aborting run"))
                         (when (and ctx (.-abort ctx))
                           ((.-abort ctx) (shared/stop-reason "error_max_wall_time" msg)))))
                     (* 1000 secs))))
          nil)

        on-start
        (fn [_data ctx]
          ;; Spent between runs: the session's, and no run's.
          (let [between (new-extension-tokens!)]
            (swap! totals (fn [t] (-> t (assoc :turn 0) (update :session + between)))))
          (reset! run-ctx ctx)
          (arm! ctx)
          nil)

        ;; agent_end fires once per run, unlike turn_end (per step), so this is
        ;; the only hook that can disarm without disarming after step one.
        on-end (fn [_data _ctx] (reset! run-ctx nil) (cancel-timer!) nil)

        add-spent!
        (fn [spent ctx]
          (swap! totals (fn [t] (-> t (update :turn + spent) (update :session + spent))))
          (when-let [which (shared/over-budget cfg @totals)]
            (let [msg (str (name which) " token budget exceeded ("
                           (get @totals which) " > "
                           (if (= which :turn) (:turn-tokens cfg) (:session-tokens cfg))
                           ")")]
              (d/warn "budget" (str msg " — aborting run"))
              (when (and ctx (.-abort ctx))
                ((.-abort ctx) (shared/stop-reason "error_max_budget_tokens" msg)))))
          nil)

        on-step
        (fn [step ctx]
          (let [usage (.-usage step)]
            (add-spent! (+ (or (some-> usage .-inputTokens) 0)
                           (or (some-> usage .-outputTokens) 0)
                           (new-extension-tokens!))
                        ctx)))

        ;; Fires on every state change, so it only looks unless tokens moved.
        on-state
        (fn [st]
          (when (and @run-ctx (> (or (:extension-tokens st) 0) @seen))
            (add-spent! (new-extension-tokens!) @run-ctx)))]

    ;; Single gate: registration and the returned deactivator can't disagree.
    ;; nil (not an empty fn) is the loader's "nothing to clean up" convention.
    (when (shared/enabled? cfg)
      (.on api "before_agent_start" on-start)
      (.on api "turn_end" on-step)
      (.on api "agent_end" on-end)
      (let [unwatch (when (.-onStateChange api) (.onStateChange api on-state))]
        (fn []
          (cancel-timer!)
          (when unwatch (unwatch))
          (.off api "before_agent_start" on-start)
          (.off api "turn_end" on-step)
          (.off api "agent_end" on-end))))))

(def ^:export default activate)
