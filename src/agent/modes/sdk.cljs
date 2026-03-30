(ns agent.modes.sdk
  (:require [agent.core :refer [create-agent]]
            [agent.loop :refer [run steer follow-up]]
            [agent.resources.loader :refer [discover]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load]]))

(defn- temp-session-path []
  (str "/tmp/nyma-sdk-session-" (js/Date.now) ".jsonl"))

(defn ^:async create-session
  "Programmatic API for embedding the agent in other applications."
  [opts]
  (let [settings  (create-settings-manager)
        resources (js-await (discover))
        session   (create-session-manager (or (:session-path opts)
                                              (temp-session-path)))
        agent     (create-agent
                    (merge ((:get settings))
                           (select-keys opts [:model :tools :system-prompt])))]

    ;; Load extensions
    (js-await (discover-and-load
                (:extension-dirs resources)
                (create-extension-api agent)))

    {:agent     agent
     :session   session
     :send      (partial run agent)
     :steer     (partial steer agent)
     :follow-up (partial follow-up agent)
     :on        (fn [event handler] ((:on (:events agent)) event handler))
     :state     (fn [] @(:state agent))}))
