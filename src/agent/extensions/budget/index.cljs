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

        on-start
        (fn [_data _ctx] (swap! totals assoc :turn 0) nil)

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
      (fn []
        (.off api "before_agent_start" on-start)
        (.off api "turn_end" on-step)))))

(def ^:export default activate)
