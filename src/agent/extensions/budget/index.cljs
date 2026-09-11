(ns agent.extensions.budget.index
  "Token budgets: accumulate usage per turn and per session; when a
   configured cap is exceeded, abort the in-flight run so a runaway loop
   can't burn tokens unattended. Off unless settings#budget sets a cap.

   Enforcement rides `turn_end`, which fires per STEP (streamText
   onStepFinish) — aborting there stops the run between tool steps.
   `after_provider_request` only fires after the whole run has finished,
   which is too late to save any tokens."
  (:require [agent.debug :as d]
            [agent.extensions.budget.shared :as shared]))

(defn ^:export activate [api]
  (let [cfg    (shared/config (try (.getSettings api) (catch :default _ nil)))
        totals (atom {:turn 0 :session 0})
        ;; Wall clock is a TIMER, not a turn_end check: a hung provider call
        ;; finishes no step, so a check that rides turn_end never runs in the
        ;; case the cap is for. Borrowed from mini-swe-agent's
        ;; wall_time_limit_seconds, which sits beside its step and cost limits.
        timer  (atom nil)
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
                       (d/warn "budget" (str "wall-clock budget exceeded (" secs "s) — aborting run"))
                       (when (and ctx (.-abort ctx)) ((.-abort ctx))))
                     (* 1000 secs))))
          nil)

        on-start
        (fn [_data ctx]
          (swap! totals assoc :turn 0)
          (arm! ctx)
          nil)

        ;; agent_end fires once per run, unlike turn_end (per step), so this is
        ;; the only hook that can disarm without disarming after step one.
        on-end (fn [_data _ctx] (cancel-timer!) nil)

        on-step
        (fn [step ctx]
          (let [usage (.-usage step)
                spent (+ (or (some-> usage .-inputTokens) 0)
                         (or (some-> usage .-outputTokens) 0))]
            (swap! totals (fn [t] (-> t (update :turn + spent) (update :session + spent))))
            (when-let [which (shared/over-budget cfg @totals)]
              (d/warn "budget" (str (name which) " token budget exceeded ("
                                    (get @totals which) " > "
                                    (if (= which :turn) (:turn-tokens cfg) (:session-tokens cfg))
                                    ") — aborting run"))
              (when (and ctx (.-abort ctx)) ((.-abort ctx))))
            nil))]

    ;; Single gate: registration and the returned deactivator can't disagree.
    ;; nil (not an empty fn) is the loader's "nothing to clean up" convention.
    (when (shared/enabled? cfg)
      (.on api "before_agent_start" on-start)
      (.on api "turn_end" on-step)
      (.on api "agent_end" on-end)
      (fn []
        (cancel-timer!)
        (.off api "before_agent_start" on-start)
        (.off api "turn_end" on-step)
        (.off api "agent_end" on-end)))))

(def ^:export default activate)
