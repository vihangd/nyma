(ns agent.extensions.budget.index
  "Token budgets: accumulate usage per turn and per session; when a
   configured cap is exceeded, abort the in-flight run so a runaway loop
   can't burn tokens unattended. Off unless settings#budget sets a cap."
  (:require [agent.debug :as d]
            [agent.extensions.budget.shared :as shared]))

(defn ^:export activate [api]
  (let [cfg    (shared/config (try (.getSettings api) (catch :default _ nil)))
        totals (atom {:turn 0 :session 0})

        on-start
        (fn [_data _ctx] (swap! totals assoc :turn 0) nil)

        on-usage
        (fn [event ctx]
          (let [usage (.-usage event)
                step  (+ (or (some-> usage .-inputTokens) 0)
                         (or (some-> usage .-outputTokens) 0))]
            (swap! totals (fn [t] (-> t (update :turn + step) (update :session + step))))
            (when-let [which (shared/over-budget cfg @totals)]
              (d/warn "budget" (str (name which) " token budget exceeded ("
                                    (get @totals which) " > "
                                    (if (= which :turn) (:turn-tokens cfg) (:session-tokens cfg))
                                    ") — aborting run"))
              (when (and ctx (.-abort ctx)) ((.-abort ctx))))
            nil))]

    (when (shared/enabled? cfg)
      (.on api "before_agent_start" on-start)
      (.on api "after_provider_request" on-usage))

    (fn []
      (when (shared/enabled? cfg)
        (.off api "before_agent_start" on-start)
        (.off api "after_provider_request" on-usage)))))

(def ^:export default activate)
