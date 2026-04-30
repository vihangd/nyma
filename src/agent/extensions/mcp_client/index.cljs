(ns agent.extensions.mcp-client.index
  "Activation entry point for the MCP client extension.

   On extension load:
     - Read settings under .nyma/settings.json#mcp for opts.
     - Pull the configured MCP server list from agent-shell's
       shared/mcp-servers atom (already populated by mcp_discovery).
     - Create a manager (no spawning yet — defer to session_start).
     - Register the two status segments + /mcp-status command.
     - Subscribe session_start (eager start-all + tool registration)
       and session_shutdown (clean teardown) on the MAIN event bus
       (api.on, NOT api.events.on — the bus distinction that bit
      the hook bridge).

   Returns a deactivator that unregisters everything."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.agent-shell.shared :as shell-shared]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]
            [agent.extensions.mcp-client.tool-bridge :as bridge]
            [agent.extensions.mcp-client.status-segments :as segs]))

;; ── Settings ─────────────────────────────────────────────────────

(defn- load-settings
  "Return MCP client settings or defaults. Self-contained to avoid
   coupling to nyma's settings manager (which uses a shallow merge
   we don't want for nested maps)."
  []
  (let [project-path (path/join (js/process.cwd) ".nyma" "settings.json")
        defaults     {:show-detail-segment false
                      :max-restarts        3
                      :startup-timeout-ms  30000
                      :call-timeout-ms     30000}]
    (try
      (if (fs/existsSync project-path)
        (let [raw    (fs/readFileSync project-path "utf8")
              parsed (js/JSON.parse raw)
              mcp    (aget parsed "mcp")]
          (merge defaults
                 (when mcp
                   {:show-detail-segment (boolean (.-show-detail-segment mcp))
                    :max-restarts        (or (.-max-restarts mcp) (:max-restarts defaults))
                    :startup-timeout-ms  (or (.-startup-timeout-ms mcp) (:startup-timeout-ms defaults))
                    :call-timeout-ms     (or (.-call-timeout-ms mcp) (:call-timeout-ms defaults))})))
        defaults)
      (catch :default _e defaults))))

(defn- enriched-configs
  "Take the raw [{:name :command :args :env}] entries from
   shared/mcp-servers and add the per-client tuning values from
   settings."
  [raw-list settings]
  (mapv (fn [cfg]
          (assoc cfg
                 :max-restarts       (:max-restarts settings)
                 :startup-timeout-ms (:startup-timeout-ms settings)
                 :call-timeout-ms    (:call-timeout-ms settings)))
        raw-list))

;; ── Helpers ──────────────────────────────────────────────────────

(defn- format-status-table
  "Build a human-readable status table for /mcp-status."
  [manager]
  (let [pairs (mgr/all-clients manager)]
    (if (empty? pairs)
      "No MCP servers configured.\nAdd a .mcp.json to your project root."
      (let [rows (mapv (fn [{:keys [name client]}]
                         (let [st     (client/state client)
                               tools  (count (client/list-tools client))
                               err    (client/last-error client)]
                           (str "  " name " — " (str st)
                                "  (" tools " tools)"
                                (when err (str "\n      " err)))))
                       pairs)
            s    (mgr/summary manager)]
        (str "MCP servers: " (:running s) "/" (:total s) " connected\n"
             (clojure.string/join "\n" rows))))))

(defn- notify [api msg]
  (when (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg "info")))

;; ── Activation ───────────────────────────────────────────────────

(defn ^:export default [api]
  (let [settings           (load-settings)
        manager            (mgr/create)
        manager-ref        (atom manager)
        show-detail?-ref   (atom (:show-detail-segment settings))
        registered-tools   (atom [])
        ;; Probe configured server count up-front so the startup log
        ;; surfaces whether mcp-client even sees what mcp_discovery
        ;; loaded. Mirrors the hook-bridge convention — a visible
        ;; activation line beats silent failure.
        configured-count  (count (or @shell-shared/mcp-servers []))
        _ (js/console.log
           (str "[mcp-client] active — " configured-count
                " server(s) configured"
                (when (pos? configured-count)
                  (str ": "
                       (.join (clj->js
                               (mapv :name @shell-shared/mcp-servers))
                              ", ")))))

        ;; status segments
        seg-cleanup (segs/register-all! api manager-ref show-detail?-ref)

        ;; /mcp-status command — separate from agent-shell's /mcp
        ;; (which owns list/refresh). Distinct name avoids
        ;; cross-extension command collision.
        _ (.registerCommand api "mcp-status"
                            #js {:description "Show MCP server connection state and tool counts"
                                 :handler (fn [_args _ctx]
                                            (notify api (format-status-table @manager-ref)))})

        ;; session_start: spawn all, register tools.
        on-session-start
        (^:async fn [_data]
          (let [raw (or @shell-shared/mcp-servers [])
                configs (enriched-configs raw settings)]
            (when (seq configs)
              (try
                (js-await (mgr/start-all! @manager-ref configs))
                (let [names (bridge/register-all! api @manager-ref)]
                  (reset! registered-tools names))
                (catch :default e
                  (js/console.warn "[mcp-client] start-all error:"
                                   (or (.-message e) (str e))))))))

        ;; session_shutdown: tools off, then stop all.
        on-session-shutdown
        (^:async fn [_data]
          (try
            (bridge/unregister-all! api @registered-tools)
            (reset! registered-tools [])
            (js-await (mgr/stop-all! @manager-ref))
            (catch :default e
              (js/console.warn "[mcp-client] shutdown error:"
                               (or (.-message e) (str e))))))]

    ;; Subscribe on the MAIN event bus (api.on, not api.events.on).
    (.on api "session_start" on-session-start 50)
    (.on api "session_shutdown" on-session-shutdown 50)
    (.on api "session_end" on-session-shutdown 50)

    ;; Deactivator
    (fn []
      (try (.off api "session_start" on-session-start) (catch :default _ nil))
      (try (.off api "session_shutdown" on-session-shutdown) (catch :default _ nil))
      (try (.off api "session_end" on-session-shutdown) (catch :default _ nil))
      (try (.unregisterCommand api "mcp-status") (catch :default _ nil))
      (try (when (fn? seg-cleanup) (seg-cleanup)) (catch :default _ nil))
      ;; Best-effort teardown of any still-running clients.
      (when @manager-ref
        (-> (mgr/stop-all! @manager-ref)
            (.catch (fn [_] nil)))))))
