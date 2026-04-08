(ns agent.extensions.agent-shell.features.session-mgmt
  "Session management: /sessions list, resume, new."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.client :as client]
            [clojure.string :as str]))

(defn- notify [api msg & [level]]
  (if (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))
    nil))

(defn- list-sessions
  "Call session/list on the active agent and display results."
  [api]
  (let [agent-key @shared/active-agent
        conn      (get @shared/connections agent-key)]
    (if-not conn
      (notify api "No agent connected" "error")
      (-> (client/send-request conn (client/next-id conn) "session/list"
            {:cwd (js/process.cwd)})
          (.then (fn [result]
                   (let [sessions (when-let [s (.-sessions result)] (seq s))]
                     (if (empty? sessions)
                       (notify api "No sessions found")
                       ;; Interactive picker when UI select is available
                       (if (and (.-ui api) (.-available (.-ui api)) (.-select (.-ui api)))
                         (-> (.select (.-ui api) "Pick a session to resume"
                               (clj->js (mapv (fn [s]
                                                {:label (str (or (.-title s) "untitled")
                                                             " (" (.-sessionId s) ")")
                                                 :value (.-sessionId s)})
                                              (take 20 sessions))))
                             (.then (fn [choice]
                                      (when (some? choice)
                                        (resume-session api (.-value choice))))))
                         ;; Fallback: text list
                         (let [lines (mapv (fn [s]
                                             (str "  " (or (.-title s) (.-sessionId s))
                                                  " (" (.-sessionId s) ")"))
                                           (take 20 sessions))]
                           (notify api (str "Sessions:\n" (str/join "\n" lines)))))))))
          (.catch (fn [e]
                    (notify api (str "session/list failed: " (.-message e)) "error")))))))

(defn- resume-session
  "Load a specific session by ID."
  [api session-id]
  (let [agent-key @shared/active-agent
        conn      (get @shared/connections agent-key)]
    (if-not conn
      (notify api "No agent connected" "error")
      (do
        (notify api (str "Loading session " session-id "..."))
        (-> (client/send-request conn (client/next-id conn) "session/load"
              {:sessionId session-id
               :cwd       (js/process.cwd)})
            (.then (fn [_]
                     (reset! (:session-id conn) session-id)
                     (notify api (str "Resumed session: " session-id))))
            (.catch (fn [e]
                      (notify api (str "session/load failed: " (.-message e)) "error"))))))))

(defn activate
  "Register the /sessions command."
  [api]
  (.registerCommand api "sessions"
    #js {:description "List, resume, or create agent sessions"
         :handler (fn [args _ctx]
                    (let [subcmd (first args)]
                      (cond
                        (or (nil? subcmd) (= subcmd "list"))
                        (list-sessions api)

                        (= subcmd "resume")
                        (if-let [sid (second args)]
                          (resume-session api sid)
                          (notify api "Usage: /agent-shell__sessions resume <session-id>" "error"))

                        :else
                        (notify api "Usage: /agent-shell__sessions [list|resume <id>]" "error"))))})

  (fn []
    (.unregisterCommand api "sessions")))
