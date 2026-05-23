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
   Returns a promise resolving to a connection map.

   `cwd` is the working directory passed to the subprocess and to `session/new`.
   Callers that don't specify one get `(js/process.cwd)` — matches the historical
   UI behaviour. Gateway flows pass an explicit project root."
  [agent-key agent-def api cwd]
  (let [command      (:command agent-def)
        args         (:args agent-def)
        project-root cwd
        p-key        (shared/pool-key agent-key cwd)
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
                       :pool-key     p-key
                       :agent-key    agent-key
                       :state        (atom {:pending {} :terminals {}})
                       :prompt-state (atom {:text "" :tool-calls []})
                       ;; Per-connection notification callbacks. The interactive UI
                       ;; uses the global shared/*-callback atoms; the gateway's
                       ;; programmatic api/run-prompt sets these per-conn so parallel
                       ;; cross-project calls don't clobber each other's stream.
                       :callbacks    (atom nil)
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
                         ;; Clean up pool — dissoc the composite (agent, cwd) key
                        (swap! shared/connections dissoc p-key)
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
                    ;; Detect auth requirements and surface to user
                  (let [auth-methods (when-let [am (.-authMethods init-result)] (seq am))]
                    (when auth-methods
                      (shared/update-agent-state! agent-key :auth-methods
                                                  (mapv shared/js->clj* auth-methods))
                      (when (and (.-ui api) (.-available (.-ui api)))
                        (.notify (.-ui api)
                                 (str "Agent requires authentication ("
                                      (str/join ", " (map #(or (.-id %) (.-type %)) auth-methods))
                                      "). Log in via the agent's own CLI (e.g. `claude /login`, `gemini auth`) and reconnect.")
                                 "warning"))))
                    ;; Phase 2: Create session (pass discovered MCP servers + optional extra dirs)
                  (let [base-params {:cwd        project-root
                                     :mcpServers (let [servers @shared/mcp-servers]
                                                   (if (seq servers)
                                                     (clj->js servers)
                                                     []))}
                        extra-dirs  (:additional-directories agent-def)
                        params      (if (seq extra-dirs)
                                      (assoc base-params :additionalDirectories (vec extra-dirs))
                                      base-params)]
                    (client/send-request conn (client/next-id conn) "session/new" params))))
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
  "Get or create an ACP connection for an agent in a given working directory.

   The pool is keyed by `[agent-key, cwd]` (composed via `shared/pool-key`) so
   the same agent can be running against multiple projects in parallel — the
   gateway uses this to fan out `claude` across vyom / nyma / scratch from one
   inbox. Callers in the interactive UI omit `cwd`; it defaults to
   `(js/process.cwd)` and the pool key is stable for the process lifetime.

   Promise sentinel prevents duplicate spawns when concurrent requests race."
  [agent-key agent-def api & [cwd]]
  (let [resolved-cwd (or cwd (js/process.cwd))
        p-key        (shared/pool-key agent-key resolved-cwd)
        existing     (get @shared/connections p-key)]
    (cond
      ;; Already have a resolved connection with a session
      (and (map? existing) (:session-id existing))
      (js/Promise.resolve existing)

      ;; Pending promise — await it
      (some? existing)
      existing

      ;; No entry — create (in-process or subprocess)
      :else
      (let [factory (or (:create-fn agent-def) create-connection)
            promise (-> (js/Promise.resolve (factory agent-key agent-def api resolved-cwd))
                        (.then (fn [conn]
                                 (swap! shared/connections assoc p-key conn)
                                 conn))
                        (.catch (fn [e]
                                  ;; Remove poisoned entry
                                  (swap! shared/connections dissoc p-key)
                                  (throw e))))]
        ;; Insert promise as sentinel
        (swap! shared/connections assoc p-key promise)
        promise))))

(defn- disconnect-by-pool-key
  "Internal: shut down a single connection identified by its composite pool-key."
  [p-key]
  (let [entry (get @shared/connections p-key)]
    (swap! shared/connections dissoc p-key)
    (cond
      ;; In-process agent — no subprocess to kill, just clean up
      (and (map? entry) (:in-process? entry))
      (do
        (when-let [close-fn (:close entry)]
          (try (close-fn) (catch :default _ nil)))
        (js/Promise.resolve nil))

      ;; Resolved subprocess connection
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
            ;; Send session/close before closing stdin (fire-and-forget)
           (when-let [sid (and (:session-id entry) @(:session-id entry))]
             (try
               (client/send-request entry (client/next-id entry) "session/close"
                                    {:sessionId sid})
               (catch :default _ nil)))
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

(defn disconnect
  "Gracefully shut down ACP agent connection(s).

   - `(disconnect agent-key)`        — disconnect every live connection for that
     agent across any cwd. Used by the interactive UI's `/agent disconnect` and
     by shutdown hooks: the user never tracks individual project workers.
   - `(disconnect agent-key cwd)`    — disconnect a specific (agent, cwd) entry.
     Used by gateway-side cleanup when a project session goes idle.

   Cascade per connection: close stdin → 100ms → SIGTERM → 1500ms → SIGKILL → 1s → resolve."
  [agent-key & [cwd]]
  (when (= @shared/active-agent agent-key)
    (reset! shared/active-agent nil))
  (if cwd
    (disconnect-by-pool-key (shared/pool-key agent-key cwd))
    (let [prefix  (str (shared/kw-name agent-key) "@")
          matches (filter (fn [k] (and (string? k) (.startsWith k prefix)))
                          (keys @shared/connections))]
      (if (seq matches)
        (js/Promise.all (clj->js (mapv disconnect-by-pool-key matches)))
        (js/Promise.resolve nil)))))

(defn disconnect-all
  "Disconnect every connection in the pool. Used by gateway shutdown so ACP
   workers don't outlive the gateway process. Bypasses the agent-key splitting
   in `disconnect` because the atom keys are already the composite pool-keys."
  []
  (let [ks (keys @shared/connections)]
    (js/Promise.all
     (clj->js (mapv disconnect-by-pool-key ks)))))
