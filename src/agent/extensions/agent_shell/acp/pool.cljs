(ns agent.extensions.agent-shell.acp.pool
  "ACP connection pool with promise sentinel pattern.
   Manages lifecycle: spawn → handshake → reuse → graceful shutdown."
  (:require [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.acp.handlers :as handlers]
            [agent.extensions.agent-shell.acp.notifications :as notifications]
            [agent.extensions.agent-shell.shared :as shared]
            [clojure.string :as str]))

;;; ─── Spawn + handshake ─────────────────────────────────────

(defn- create-connection
  "Spawn an ACP agent process and perform the full handshake.
   Returns a promise resolving to a connection map."
  [agent-key agent-def api]
  (let [command      (:command agent-def)
        args         (:args agent-def)
        project-root (js/process.cwd)
        ;; Emit function that routes ACP events onto the global agent event bus.
        ;; Uses api.emitGlobal (added in extensions.cljs) so subscribers using
        ;; api.on can observe acp_connect, acp_disconnect, acp_message, etc.
        emit-fn  (fn [event data]
                   (try
                     (when (.-emitGlobal api)
                       (.emitGlobal api event data))
                     (catch :default _ nil)))]
    (js/Promise.
     (fn [resolve reject]
       (try
         (let [handle (.spawn api command (clj->js args)
                              #js {:cwd project-root
                                   :env js/process.env})
               conn   {:proc         handle
                       :stdin        (.-stdin handle)
                       :stdout       (.-stdout handle)
                       :stderr       (.-stderr handle)
                       :project-root project-root
                       :agent-key    agent-key
                       :state        (atom {:pending {} :terminals {}})
                       :prompt-state (atom {:text "" :tool-calls []})
                       :id-counter   (atom 0)
                       :session-id   (atom nil)
                       :on-reverse-request (fn [conn parsed]
                                             (handlers/dispatch-reverse-request conn parsed api))
                       :on-notification    (fn [conn parsed]
                                             (notifications/dispatch-notification conn parsed api))
                       :emit         emit-fn}]
            ;; Wire up stream handlers
           (client/setup-stdout-handler conn)
           (client/setup-stderr-handler conn)

            ;; Handle process exit
           (-> (.-exited handle)
               (.then (fn [exit-code]
                         ;; Don't console.log — it corrupts Ink's cursor tracking
                         ;; Emit disconnect event before cleaning up
                        (emit-fn "acp_disconnect"
                                 #js {:agent-key agent-key :exit-code exit-code})
                         ;; Reject all pending requests
                        (doseq [[rid {:keys [reject]}] (get @(:state conn) :pending)]
                          (reject (js/Error. (str "ACP process exited (code " exit-code ")"))))
                         ;; Clean up pool
                        (swap! shared/connections dissoc agent-key)
                         ;; Notify if this was the active agent
                        (when (= @shared/active-agent agent-key)
                          (when (and (.-ui api) (.-available (.-ui api)))
                            (.notify (.-ui api)
                                     (str (:name agent-def) " disconnected (exit " exit-code ")") "error"))))))

            ;; Handshake sequence (with delay for cold start)
           (-> (js/Promise.
                (fn [res _] (js/setTimeout #(res nil) 500)))
               (.then
                (fn [_]
                    ;; Phase 1: Initialize
                  (client/send-request conn (client/next-id conn) "initialize"
                                       {:protocolVersion    1
                                        :clientInfo         {:name "nyma" :title "nyma" :version "0.1.0"}
                                        :clientCapabilities {:fs          {:readTextFile true :writeTextFile true}
                                                             :terminal    true
                                                             :elicitation {:form {} :url {}}}})))
               (.then
                (fn [init-result]
                    ;; Store agent capabilities for reference
                  (shared/update-agent-state! agent-key :capabilities
                                              (shared/js->clj* (.-agentCapabilities init-result)))
                    ;; Phase 2: Create session (pass discovered MCP servers)
                  (client/send-request conn (client/next-id conn) "session/new"
                                       {:cwd project-root
                                        :mcpServers (let [servers @shared/mcp-servers]
                                                      (if (seq servers)
                                                        (clj->js servers)
                                                        []))})))
               (.then
                (fn [session-result]
                  (let [sid (.-sessionId session-result)]
                    (reset! (:session-id conn) sid)
                      ;; Store agent name for UI
                    (shared/update-agent-state! agent-key :name (:name agent-def))
                      ;; Set default mode label (agents may override via notification)
                    (shared/update-agent-state! agent-key :mode
                                                (or (:init-mode agent-def) "default"))
                      ;; Phase 3a: Set initial mode if configured
                    (when-let [init-mode (:init-mode agent-def)]
                      (-> (client/send-request conn (client/next-id conn) "session/set_mode"
                                               {:sessionId sid :modeId init-mode})
                          (.catch (fn [_] nil))))
                      ;; Phase 3b: Set model if configured in settings
                    (let [config  (shared/load-config)
                          model   (get-in config [:agents agent-key :model])
                          method  (or (:model-method agent-def) :set_config_option)]
                      (when model
                        (shared/update-agent-state! agent-key :model model)
                        (-> (case method
                              :set_model
                              (client/send-request conn (client/next-id conn) "session/set_model"
                                                   {:sessionId sid :modelId model})
                                ;; default: session/set_config_option
                              (client/send-request conn (client/next-id conn) "session/set_config_option"
                                                   {:sessionId sid
                                                    :configId  (or (:model-config-id agent-def) "model")
                                                    :value     model}))
                            (.catch (fn [_] nil)))))  ;; non-fatal
                      ;; Done — connection is ready
                      ;; Connection logged via agent_switcher notify, not console.log
                    (emit-fn "acp_connect"
                             #js {:agent-key  agent-key
                                  :session-id sid})
                    conn)))
               (.then (fn [conn] (resolve conn)))
               (.catch (fn [e] (reject e)))))
         (catch :default e
           (reject e)))))))

;;; ─── Pool operations ───────────────────────────────────────

(defn get-or-create
  "Get or create an ACP connection for an agent.
   Uses promise sentinel to prevent duplicate spawns."
  [agent-key agent-def api]
  (let [existing (get @shared/connections agent-key)]
    (cond
      ;; Already have a resolved connection with a session
      (and (map? existing) (:session-id existing))
      (js/Promise.resolve existing)

      ;; Pending promise — await it
      (some? existing)
      existing

      ;; No entry — create
      :else
      (let [promise (-> (create-connection agent-key agent-def api)
                        (.then (fn [conn]
                                 (swap! shared/connections assoc agent-key conn)
                                 conn))
                        (.catch (fn [e]
                                  ;; Remove poisoned entry
                                  (swap! shared/connections dissoc agent-key)
                                  (throw e))))]
        ;; Insert promise as sentinel
        (swap! shared/connections assoc agent-key promise)
        promise))))

(defn disconnect
  "Gracefully shut down an ACP agent connection.
   Cascade: close stdin → 100ms → SIGTERM → 1500ms → SIGKILL → 1s → resolve."
  [agent-key]
  (let [entry (get @shared/connections agent-key)]
    (swap! shared/connections dissoc agent-key)
    (when (= @shared/active-agent agent-key)
      (reset! shared/active-agent nil))
    (cond
      ;; Resolved connection
      (and (map? entry) (:proc entry))
      (js/Promise.
       (fn [resolve _]
         (let [handle   (:proc entry)
               resolved? (atom false)
               do-resolve (fn []
                            (when (compare-and-set! resolved? false true)
                              (resolve)))]
            ;; Resolve when process exits
           (-> (.-exited handle) (.then (fn [_] (do-resolve))))
            ;; Close stdin
           (when-let [stdin (:stdin entry)]
             (try (.end stdin) (catch :default _ nil)))
            ;; SIGTERM after 100ms
           (js/setTimeout
            (fn []
              (try (.kill handle "SIGTERM") (catch :default _ nil))
                ;; SIGKILL after 1500ms
              (js/setTimeout
               (fn []
                 (try (.kill handle "SIGKILL") (catch :default _ nil))
                    ;; Force resolve after 1s
                 (js/setTimeout do-resolve 1000))
               1500))
            100))))

      ;; Pending promise — just remove from pool
      (some? entry)
      (-> entry
          (.then (fn [conn]
                   (when (map? conn)
                     (try (.kill (:proc conn) "SIGTERM") (catch :default _ nil)))))
          (.catch (fn [_] nil)))

      :else
      (js/Promise.resolve nil))))

(defn disconnect-all
  "Disconnect all ACP agents."
  []
  (let [keys (keys @shared/connections)]
    (js/Promise.all
     (clj->js (mapv disconnect keys)))))
