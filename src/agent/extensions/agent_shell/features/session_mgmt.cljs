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


(defn method-not-found?
  "Does this rejection mean the agent has no such method?

   Checks the JSON-RPC code first. It used to test only the message string for
   \"-32601\", which could never match: handle-response formatted the error and
   dropped the code entirely, so the session/load fallback was dead."
  [e]
  (boolean
   (or (= -32601 (.-code e))
       (let [m (str (or (.-message e) ""))]
         (or (.includes m "-32601") (.includes m "Method not found"))))))

(defn in-process-refusal
  "Why /sessions cannot work on this connection, or nil.

   In-process runners have `:stdin nil`, and send-request's safe-write swallows
   the failure — so the request registered a promise that never settled and the
   command hung with no error at all."
  [conn agent-key]
  (when (:in-process? conn)
    (str "`" (shared/kw-name agent-key) "` runs in-process, not over ACP stdio — "
         "it has no session/list or session/resume. Its history is resumed by "
         "the SDK on connect instead.")))

(defn- list-sessions
  "Call session/list on the active agent and display results."
  [api]
  (let [agent-key @shared/active-agent
        conn      (shared/find-conn-by-agent agent-key)]
    (cond
      (not conn)
      (notify api "No agent connected" "error")

      (not (shared/agent-supports-resume? agent-key))
      (notify api
              (str "Agent `" (name (or agent-key "?"))
                   "` does not declare session resume capability — "
                   "session list/resume isn't available.")
              "warning")

      :else
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

(defn replay-renderer
  "A `shared/replay-callback` that appends replayed turns to the transcript
   pane, or nil when there is no pane to append to.

   Takes the command's ctx rather than the api: `append-message` is handed to
   command handlers by the interactive mode (interactive.cljs), and a replay
   happens in response to a command, not during a prompt — so none of the
   per-turn streaming callbacks are in flight to reuse."
  [ctx]
  (when-let [append (and ctx (aget ctx "append-message"))]
    (let [last-role (atom nil)]
      (fn [{:keys [role text]}]
        ;; One message per role RUN, not per chunk: replay arrives fragmented
        ;; exactly like live output, and a message per fragment would render
        ;; one conversation as hundreds of lines.
        (append #js {:role (if (= "user" (str role)) "user" "assistant")
                     :content (str text)})
        (reset! last-role (str role))))))

(defn begin-replay!
  "Mark the connection as replaying and clear the transcript.

   `session/load` streams the entire prior conversation back as session/update
   notifications and only THEN answers the request, so everything arriving in
   between is history. Clearing first means a resume replaces the transcript
   rather than concatenating onto whatever was there."
  [conn]
  (when (:replaying? conn) (reset! (:replaying? conn) true))
  (shared/clear-transcript! (:pool-key conn)))

(defn end-replay!
  "Stop treating notifications as history. Called on both settle paths — an
   agent that fails midway must not leave the connection stuck in replay,
   which would silently swallow the next live turn."
  [conn]
  (when (:replaying? conn) (reset! (:replaying? conn) false)))

(defn- do-session-load-fallback
  "Fallback to legacy session/load when the agent has no session/resume.
   Unlike resume, load REPLAYS the conversation, so the transcript is rebuilt."
  [conn api session-id & [ctx]]
  (begin-replay! conn)
  (reset! shared/replay-callback (replay-renderer ctx))
  (-> (client/send-request conn (client/next-id conn) "session/load"
                           {:sessionId  session-id
                            :cwd        (js/process.cwd)
                            :mcpServers (let [servers @shared/mcp-servers]
                                          (if (seq servers)
                                            (clj->js servers)
                                            []))})
      (.then (fn [_]
               (end-replay! conn)
               (reset! shared/replay-callback nil)
               (reset! (:session-id conn) session-id)
               (shared/remember-session! api (:agent-key conn) session-id)
               (let [n (count (shared/get-transcript (:pool-key conn)))]
                 (notify api (str "Resumed session: " session-id
                                  (when (pos? n) (str "  (" n " turns restored)")))))))
      (.catch (fn [e]
                (end-replay! conn)
                (reset! shared/replay-callback nil)
                (notify api (str "session/load failed: " (.-message e)) "error")))))

(defn- resume-session
  "Resume a specific session by ID.
   Tries session/resume (ACP spec Apr 2026) first; falls back to session/load
   if the agent returns -32601 (method not found)."
  [api session-id & [ctx]]
  (let [agent-key @shared/active-agent
        conn      (shared/find-conn-by-agent agent-key)]
    (cond
      (not conn)
      (notify api "No agent connected" "error")

      (in-process-refusal conn agent-key)
      (notify api (in-process-refusal conn agent-key) "warning")

      (not (shared/agent-supports-resume? agent-key))
      (notify api
              (str "Agent `" (shared/kw-name agent-key)
                   "` does not declare session resume capability — "
                   "cannot resume.")
              "warning")

      :else
      (do
        (notify api (str "Resuming session " session-id "..."))
        (-> (client/send-request conn (client/next-id conn) "session/resume"
                                 {:sessionId  session-id
                                  :cwd        (js/process.cwd)
                                  :mcpServers (let [servers @shared/mcp-servers]
                                                (if (seq servers)
                                                  (clj->js servers)
                                                  []))})
            (.then (fn [_]
                     (reset! (:session-id conn) session-id)
                     (shared/remember-session! api agent-key session-id)
                     ;; session/resume restores context WITHOUT replaying, by
                     ;; design. So the agent remembers the conversation and
                     ;; nyma does not — say so, because /plan-capture reads
                     ;; nyma's transcript and would refuse.
                     (notify api (str "Resumed session: " session-id
                                      "\n  context restored on the agent; no history replayed"))))
            (.catch (fn [e]
                      (if (method-not-found? e)
                        ;; Agent predates session/resume — session/load both
                        ;; restores AND replays.
                        (do-session-load-fallback conn api session-id ctx)
                        (notify api (str "session/resume failed: " (.-message e)) "error")))))))))

(defn activate
  "Register the /sessions command."
  [api]
  (.registerCommand api "sessions"
                    #js {:description "List, resume, or create agent sessions"
                         :handler (fn [args ctx]
                                    (let [subcmd (first args)]
                                      (cond
                                        (or (nil? subcmd) (= subcmd "list"))
                                        (list-sessions api)

                                        (= subcmd "resume")
                                        (if-let [sid (second args)]
                                          (resume-session api sid ctx)
                                          ;; No id: the remembered one for this
                                          ;; project. Retyping an opaque id is
                                          ;; the whole reason nothing was ever
                                          ;; resumed across a restart.
                                          (if-let [saved (shared/recall-session api @shared/active-agent)]
                                            (resume-session api (aget saved "sessionId") ctx)
                                            (notify api
                                                    (str "No remembered session for this project.\n"
                                                         "  Usage: /agent-shell__sessions resume <session-id>"
                                                         "  (or `list` to pick one)")
                                                    "error")))

                                        :else
                                        (notify api "Usage: /agent-shell__sessions [list|resume <id>]" "error"))))})

  (fn []
    (.unregisterCommand api "sessions")))
