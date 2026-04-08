(ns agent.extensions.agent-shell.features.effort-switcher
  "Effort level command: /effort <low|medium|high|max|auto>."
  (:require [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.client :as client]))

(def ^:private valid-levels #{"low" "medium" "high" "max" "auto"})

(defn- notify [api msg & [level]]
  (when (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))))

(defn- set-effort!
  "Send session/set_config_option to set effort level on the active agent."
  [api level]
  (let [agent-key @shared/active-agent
        conn      (get @shared/connections agent-key)]
    (cond
      (not agent-key)
      (notify api "No agent connected" "error")

      (not conn)
      (notify api "Agent not connected" "error")

      :else
      (let [sid @(:session-id conn)]
        (-> (client/send-request conn (client/next-id conn) "session/set_config_option"
              {:sessionId sid :configId "effort" :value level})
            (.then (fn [_]
                     (shared/update-agent-state! agent-key :effort level)
                     (notify api (str "Effort set to " level))))
            (.catch (fn [e]
                      (notify api (str "Effort switch failed: " (.-message e)) "error"))))))))

(defn activate
  "Register /effort command. Returns deactivator."
  [api]
  (.registerCommand api "effort"
    #js {:description "Set thinking effort level (low/medium/high/max/auto)"
         :handler (fn [args _ctx]
                    (let [level (some-> (first args) str/lower-case)]
                      (cond
                        (nil? level)
                        (notify api "Usage: /effort <low|medium|high|max|auto>")

                        (contains? valid-levels level)
                        (set-effort! api level)

                        :else
                        (notify api (str "Invalid effort level: " level ". Valid: low, medium, high, max, auto") "error"))))})

  (fn []
    (.unregisterCommand api "effort")))
