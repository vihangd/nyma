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
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shell-shared]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]
            [agent.extensions.mcp-client.tool-bridge :as bridge]
            [agent.extensions.mcp-client.tool-override :as override]
            [agent.extensions.mcp-client.status-segments :as segs]))

(defn- reclaim-terminal-title!
  "Some MCP servers (notably lean-ctx, which uses Rust's crossterm)
   emit OSC 0/2 title-escape sequences on startup that leak through
   the SDK's stderr pipe to the user's terminal — making the tab title
   read 'lean-ctx' instead of nyma's cwd. Reassert nyma's title after
   start-all so we win the race."
  []
  (when (and (.-isTTY js/process.stdout)
             (not (.-NYMA_NO_TITLE js/process.env)))
    (try
      (let [base (path/basename (js/process.cwd))]
        (.write js/process.stdout (str "\u001b]0;nyma — " base "\u0007")))
      (catch :default _e nil))))

;; ── Settings ─────────────────────────────────────────────────────

(def ^:private default-shadow-map
  "Native nyma tools made redundant by an MCP server's tools. Map of
   {native-tool-name → mcp-prefixed-name}. When the MCP-prefixed tool
   is present in the registry, the native one is hidden from the active
   set so the LLM only sees the cached/compressed variant.

   `read` and `edit` are NOT in this map — they're handled by
   tool_override (delegating wrappers with native fallback) so a
   transient lean-ctx outage doesn't break those calls. Shadow
   covers tools where there's no compelling fallback path."
  {"ls"   "mcp__lean-ctx__ctx_tree"
   "grep" "mcp__lean-ctx__ctx_search"})

(defn parse-shadow-tools
  "User can:
     - omit `shadow-tools` → use defaults
     - set to `false` or `{}` → disable shadowing entirely
     - set to a partial map → merged on top of defaults, `null` value drops a key"
  [user-val]
  (cond
    (false? user-val) {}
    (nil? user-val)   default-shadow-map
    (object? user-val)
    (let [merged (atom default-shadow-map)]
      (doseq [k (js/Object.keys user-val)]
        (let [v (aget user-val k)]
          (if (nil? v)
            (swap! merged dissoc k)
            (swap! merged assoc k v))))
      @merged)
    :else default-shadow-map))

(def ^:private default-hidden-tools
  "Tool names to hide unconditionally after registration. Unlike shadow-tools
   (which hides A when B is present), these are dropped regardless. Use for
   workflow/memory-management MCP tools the model rarely calls but whose
   schemas still cost upfront context tokens."
  ["mcp__serena__delete_memory"
   "mcp__serena__edit_memory"
   "mcp__serena__rename_memory"
   "mcp__serena__replace_content"])

(defn parse-hidden-tools
  "User can:
     - omit `hidden-tools` → use defaults
     - set to `false` or `[]` → disable hiding entirely
     - set to an array → REPLACES defaults (use empty array to opt out)
     - set to an object → defaults plus/minus per-key:
         { \"my__tool\": true }   adds to hidden set
         { \"mcp__serena__delete_memory\": null } removes from default hidden set"
  [user-val]
  (cond
    (false? user-val) []
    (nil? user-val)   default-hidden-tools
    (.isArray js/Array user-val)
    (vec (js/Array.from user-val))
    (object? user-val)
    (let [merged (atom (set default-hidden-tools))]
      (doseq [k (js/Object.keys user-val)]
        (let [v (aget user-val k)]
          (cond
            (nil? v)            (swap! merged disj k)
            (false? v)          (swap! merged disj k)
            :else               (swap! merged conj k))))
      (vec @merged))
    :else default-hidden-tools))

(defn- load-settings
  "Return MCP client settings or defaults. Self-contained to avoid
   coupling to nyma's settings manager (which uses a shallow merge
   we don't want for nested maps)."
  []
  (let [project-path (path/join (js/process.cwd) ".nyma" "settings.json")
        defaults     {:show-detail-segment false
                      :max-restarts        3
                      :startup-timeout-ms  30000
                      :call-timeout-ms     30000
                      :shadow-tools        default-shadow-map
                      :hidden-tools        default-hidden-tools
                      :tool-overrides      override/default-overrides}]
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
                    :call-timeout-ms     (or (.-call-timeout-ms mcp) (:call-timeout-ms defaults))
                    :shadow-tools        (parse-shadow-tools (aget mcp "shadow-tools"))
                    :hidden-tools        (parse-hidden-tools (aget mcp "hidden-tools"))
                    :tool-overrides      (override/parse-overrides (aget mcp "tool-overrides"))})))
        defaults)
      (catch :default _e defaults))))

(defn compute-shadow-set
  "Given the shadow map and the current set of active tool names,
   return the set of native names to hide because their MCP
   replacement is present and active."
  [shadow-map active-names]
  (let [present (set active-names)]
    (set (for [[native mcp-name] shadow-map
               :when (contains? present mcp-name)]
           native))))

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

(defn format-status-table
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
             (str/join "\n" rows))))))

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
        ;; Track which native tool names we hid so we can re-enable
        ;; them on shutdown (or if the user reloads/disables an MCP
        ;; server mid-session). Keeps the toggle reversible.
        shadowed-natives   (atom #{})
        ;; Track explicit-hide tools (from :hidden-tools setting) we
        ;; removed from active. Restored on shutdown. Separate from
        ;; shadowed-natives because the semantic differs — hidden are
        ;; unconditional, shadows are conditional on MCP-equivalent
        ;; presence.
        hidden-tools-set   (atom #{})
        ;; Native tool names whose execute we delegated to MCP via
        ;; tool_override. Restored on shutdown via __original chain.
        applied-overrides  (atom [])
        ;; Probe configured server count up-front so the startup log
        ;; surfaces whether mcp-client even sees what mcp_discovery
        ;; loaded. Mirrors the hook-bridge convention — a visible
        ;; activation line beats silent failure.
        configured-count  (count (or @shell-shared/mcp-servers []))
        _ (when (.-NYMA_DEBUG js/process.env)
            (js/console.log
             (str "[mcp-client] active — " configured-count
                  " server(s) configured"
                  (when (pos? configured-count)
                    (str ": "
                         (.join (clj->js
                                 (mapv :name @shell-shared/mcp-servers))
                                ", "))))))

        ;; status segments
        seg-cleanup (segs/register-all! api manager-ref show-detail?-ref)

        ;; /mcp-status command — separate from agent-shell's /mcp
        ;; (which owns list/refresh). Distinct name avoids
        ;; cross-extension command collision.
        _ (.registerCommand api "mcp-status"
                            #js {:description "Show MCP server connection state and tool counts"
                                 :handler (fn [_args _ctx]
                                            (notify api (format-status-table @manager-ref)))})

        ;; Bring up: spawn every configured server, register tools.
        ;; Idempotent — if the manager already has clients, no-op.
        ;; Critical detail discovered the hard way: nyma fires
        ;;   session_ready  on every normal CLI launch,
        ;;   session_start  ONLY on /new / /fork / /clear.
        ;; So we subscribe to session_ready (always) and also
        ;; session_start (so explicit new-session actions get the
        ;; same wiring), and the idempotency check prevents
        ;; double-spawn when both fire.
        on-bring-up
        (^:async fn [_data]
          (when (zero? (count (mgr/server-names @manager-ref)))
            (let [raw (or @shell-shared/mcp-servers [])
                  configs (enriched-configs raw settings)]
              (when (seq configs)
                (try
                  (js-await (mgr/start-all! @manager-ref configs))
                  (let [names (bridge/register-all! api @manager-ref)]
                    (reset! registered-tools names))
                  ;; Subprocess may have stomped the terminal title.
                  (reclaim-terminal-title!)
                  ;; Tool overrides (read/edit) — install delegators
                  ;; with native fallback. Order matters: must run
                  ;; AFTER bridge/register-all! so the ctx_* tools
                  ;; exist in the manager when override probes
                  ;; healthiness, but BEFORE shadow-set application
                  ;; so the override targets aren't pre-removed.
                  (when-let [overrides (:tool-overrides settings)]
                    (when (seq overrides)
                      (let [applied (override/register! api manager-ref overrides)]
                        (reset! applied-overrides applied))))
                  ;; Tool shadowing: hide native tools whose MCP
                  ;; replacement is now present in the registry.
                  ;; Reversible — recorded on shadowed-natives.
                  (let [active     (vec (.getActiveTools api))
                        to-hide    (compute-shadow-set
                                    (:shadow-tools settings) active)
                        new-active (vec (remove to-hide active))]
                    (when (seq to-hide)
                      (.setActiveTools api (clj->js new-active))
                      (reset! shadowed-natives to-hide)))
                  ;; Unconditional hides: drop tools listed in
                  ;; :hidden-tools (workflow/memory-mgmt MCP tools
                  ;; that cost schema tokens but are rarely called).
                  ;; Filtered against the post-shadow active set so
                  ;; we only record tools that were actually present.
                  (let [active     (set (.getActiveTools api))
                        to-hide    (set (filter active (:hidden-tools settings)))
                        new-active (vec (remove to-hide active))]
                    (when (seq to-hide)
                      (.setActiveTools api (clj->js new-active))
                      (reset! hidden-tools-set to-hide)))
                  (catch :default e
                    (js/console.warn "[mcp-client] start-all error:"
                                     (or (.-message e) (str e)))))))))

        ;; session_shutdown: tools off, then stop all.
        ;;
        ;; CRITICAL: subprocess teardown (mgr/stop-all!) MUST run even if
        ;; an earlier step throws — otherwise stdio MCP children leak as
        ;; orphan processes. Each step is wrapped in its own try/catch
        ;; so a failure in tool-state restoration can't skip the kill.
        on-session-shutdown
        (^:async fn [_data]
          ;; Step 1: best-effort restore shadowed-natives.
          (try
            (when (seq @shadowed-natives)
              (let [active   (set (.getActiveTools api))
                    restored (vec (into active @shadowed-natives))]
                (.setActiveTools api (clj->js restored))
                (reset! shadowed-natives #{})))
            (catch :default e
              (js/console.warn "[mcp-client] shutdown(shadow-restore):"
                               (or (.-message e) (str e)))))
          ;; Step 2: best-effort restore hidden-tools.
          (try
            (when (seq @hidden-tools-set)
              (let [active   (set (.getActiveTools api))
                    restored (vec (into active @hidden-tools-set))]
                (.setActiveTools api (clj->js restored))
                (reset! hidden-tools-set #{})))
            (catch :default e
              (js/console.warn "[mcp-client] shutdown(hidden-restore):"
                               (or (.-message e) (str e)))))
          ;; Step 3: best-effort tool overrides + bridge unregister.
          (try
            (let [a @applied-overrides]
              (when (or (and (map? a) (or (seq (:overrides a)) (seq (:hidden a))))
                        (and (sequential? a) (seq a)))
                (override/unregister! api a)
                (reset! applied-overrides [])))
            (bridge/unregister-all! api @registered-tools)
            (reset! registered-tools [])
            (catch :default e
              (js/console.warn "[mcp-client] shutdown(unregister):"
                               (or (.-message e) (str e)))))
          ;; Step 4: ALWAYS stop the subprocesses — this is the
          ;; non-skippable kill that prevents orphans.
          (try
            (js-await (mgr/stop-all! @manager-ref))
            (catch :default e
              (js/console.warn "[mcp-client] shutdown(stop-all):"
                               (or (.-message e) (str e))))))]

    ;; Subscribe on the MAIN event bus (api.on, not api.events.on).
    ;; session_ready: vanilla CLI launch — primary entry point.
    ;; session_start: explicit /new / /fork / /clear.
    ;; session_shutdown / session_end: cleanup on exit.
    (.on api "session_ready" on-bring-up 50)
    (.on api "session_start" on-bring-up 50)
    (.on api "session_shutdown" on-session-shutdown 50)
    (.on api "session_end" on-session-shutdown 50)

    ;; Deactivator
    (fn []
      (try (.off api "session_ready" on-bring-up) (catch :default _ nil))
      (try (.off api "session_start" on-bring-up) (catch :default _ nil))
      (try (.off api "session_shutdown" on-session-shutdown) (catch :default _ nil))
      (try (.off api "session_end" on-session-shutdown) (catch :default _ nil))
      (try (.unregisterCommand api "mcp-status") (catch :default _ nil))
      (try (when (fn? seg-cleanup) (seg-cleanup)) (catch :default _ nil))
      ;; Best-effort teardown of any still-running clients.
      (when @manager-ref
        (-> (mgr/stop-all! @manager-ref)
            (.catch (fn [_] nil)))))))
