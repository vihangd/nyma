(ns agent.modes.sdk
  (:require [agent.core :refer [create-agent]]
            [agent.loop :refer [run steer follow-up]]
            [agent.resources.loader :refer [discover]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load]]
            [clojure.string :as str]))

(defn- temp-session-path []
  (str "/tmp/nyma-sdk-session-" (js/Date.now) ".jsonl"))

(defn- extract-last-assistant-text
  "Pull the final assistant message text out of agent state after a run."
  [agent]
  (let [msgs      (:messages @(:state agent))
        last-asst (last (filter #(= (:role %) "assistant") msgs))
        content   (when last-asst (:content last-asst))]
    (cond
      (string? content)  content
      (vector? content)  (str/join "\n"
                                   (keep #(when (= (:type %) "text") (:text %))
                                         content))
      :else              nil)))

(defn ^:async create-session
  "Programmatic API for embedding the agent in other applications.

   Opts may include all create-agent keys plus:
     :session-path          — path to JSONL session file (default: temp file)
     :require-capabilities  — #{kw} filter builtin tools by capability
     :exclude-capabilities  — #{kw} exclude tools with any of these capabilities
     :modes                 — #{kw} filter tools by allowed runtime mode"
  [opts]
  (let [settings  (create-settings-manager)
        resources (js-await (discover))
        session   (create-session-manager (or (:session-path opts)
                                              (temp-session-path)))
        agent     (create-agent
                   (merge ((:get settings))
                          (select-keys opts [:model :tools :system-prompt
                                             :require-capabilities
                                             :exclude-capabilities
                                             :modes])))]

    ;; Load extensions
    (js-await (discover-and-load
               (:extension-dirs resources)
               (create-extension-api agent)))

    {:agent     agent
     :session   session
     :send      (partial run agent)
     :steer     (partial steer agent)
     :follow-up (partial follow-up agent)

     ;; Wait for the run to finish and return the final assistant text.
     :send-and-wait
     (fn [text & args]
       (.. (apply run agent text args)
           (then (fn [_] (extract-last-assistant-text agent)))))

     ;; Subscribe to multiple events at once. Returns an unsubscribe-all fn.
     :on-many
     (fn [handlers-map]
       (let [unsub-fns (mapv (fn [[event handler]]
                               ((:on (:events agent)) (name event) handler))
                             handlers-map)]
         (fn [] (doseq [f unsub-fns] (when (fn? f) (f))))))

     ;; Abort the current run. reason is informational only.
     :interrupt!
     (fn [& [_reason]]
       (.abort @(:abort-controller agent))
       nil)

     ;; Derive a coarse agent state keyword from live state.
     ;; :tool-running — one or more tool calls in flight
     ;; :idle         — nothing running
     ;; Phase 1 will add :thinking :streaming :awaiting-approval :error etc.
     :agent-state
     (fn []
       (let [s @(:state agent)]
         (if (seq (:active-executions s)) :tool-running :idle)))

     :on    (fn [event handler] ((:on (:events agent)) event handler))
     :state (fn [] @(:state agent))}))
