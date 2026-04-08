(ns agent.extensions.agent-shell.features.cost-tracker
  "Accumulates usage/cost data from ACP notifications."
  (:require [agent.extensions.agent-shell.shared :as shared]))

(defn activate
  "Listen for acp_usage events to accumulate cost data."
  [api]
  (let [handler (fn [data _ctx]
                  ;; usage_update data is already stored by notifications.cljs
                  ;; This handler can do additional processing if needed
                  nil)]
    (.on api "acp_usage" handler)
    (fn [] (.off api "acp_usage" handler))))
