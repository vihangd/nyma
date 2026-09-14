(ns agent.extensions.agent-shell.features.effort-switcher
  "Effort level command: /effort <low|medium|high|max|auto>."
  (:require [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.client :as client]))

(def ^:private valid-levels #{"low" "medium" "high" "max" "auto"})

(defn- notify [api msg & [level]]
  (when (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))))

(defn effort-unsupported?
  "Pure: does this agent's pushed config-option list prove it has no `effort`
   option?

   The agent definition's `:thinking` feature is NOT the signal — it means the
   agent streams thought chunks, which is a different capability: claude-agent-acp
   declares `:thinking` and advertises no effort option at all. The live list
   arrives on config_option_update and is stored as :config-options.

   An EMPTY list proves nothing (pre-handshake, or an agent that never pushes
   options), so the send goes ahead and the agent's own error is the answer —
   today's behaviour. Only a populated list without `effort` is a refusal."
  [config-options]
  (let [opts (vec (or config-options []))]
    (boolean (and (seq opts)
                  (not-any? (fn [o] (= (str (:configId o)) "effort"))
                            opts)))))

(defn- set-effort!
  "Send session/set_config_option to set effort level on the active agent."
  [api level]
  (let [agent-key @shared/active-agent
        conn      (shared/find-conn-by-agent agent-key)]
    (cond
      (not agent-key)
      (notify api "No agent connected" "error")

      (not conn)
      (notify api "Agent not connected" "error")

      (effort-unsupported? (shared/get-agent-state agent-key :config-options))
      (notify api (str (shared/kw-name agent-key)
                       " has no effort setting — it advertised "
                       (count (shared/get-agent-state agent-key :config-options))
                       " config option(s), none of them `effort`.")
              "warning")

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
