(ns agent.extensions.mcp-client.manager
  "Multi-server orchestration. Holds a `{server-name → client}`
   registry and runs starts/stops in parallel via Promise.all so a
   slow server doesn't gate fast ones, and a failure on one doesn't
   abort the rest of the pool.

   Mirrors `lsp_manager.cljs` but eager (start-all at session_start)
   instead of lazy-on-extension-match. MCP doesn't have an analog
   to LSP's file-extension routing — the LLM picks tools by name,
   so all configured servers must be connected and their tools
   registered before the first turn.

   Public API:
     (create)
     (start-all! manager configs)  ; configs is a CLJS seq or JS array
     (stop-all! manager)
     (get-client manager server-name)
     (all-clients manager)         ; vec of {:name :client}
     (server-names manager)
     (summary manager)             ; {:total :running :starting :error}"
  (:require [agent.extensions.mcp-client.client :as client]))

;; ── State ────────────────────────────────────────────────────────

(defn create []
  {:clients (atom {})})

(defn server-names [manager]
  (vec (sort (keys @(:clients manager)))))

(defn get-client [manager server-name]
  (get @(:clients manager) server-name))

(defn all-clients
  "Return a vec of {:name :client} pairs in deterministic order."
  [manager]
  (mapv (fn [n] {:name n :client (get @(:clients manager) n)})
        (server-names manager)))

(defn- normalize-config
  "Accept either CLJS map or JS object form. mcp_discovery's
   `shared/mcp-servers` atom stores CLJS-shaped maps with stdio
   (:command :args :env :cwd) and/or remote (:url :type :headers)
   keys. Both branches are carried through so the per-client
   transport selector can dispatch."
  [cfg]
  (cond
    (map? cfg)    cfg
    (object? cfg) {:name    (.-name cfg)
                   :command (.-command cfg)
                   :args    (when-let [a (.-args cfg)] (vec (js/Array.from a)))
                   :env     (.-env cfg)
                   :cwd     (.-cwd cfg)
                   :url     (.-url cfg)
                   :type    (or (.-type cfg) (.-transportType cfg))
                   :headers (.-headers cfg)}
    :else nil))

;; ── Lifecycle ───────────────────────────────────────────────────

(defn ^:async start-all!
  "Spawn every configured server in parallel. A failure on any one
   server is logged but never rejects the overall promise — failed
   servers parked at :stopped-error stay visible to the manager so
   the status segment can show them and `/mcp status` can list them.

   Returns a promise that resolves to a {:ok [names] :failed [names]}
   summary once every server has reached a terminal state
   (:running or :stopped-error)."
  [manager configs]
  (let [normalized (vec (keep normalize-config configs))
        ;; Build clients first so tests can observe them mid-start.
        named-clients (mapv (fn [cfg]
                              {:name   (:name cfg)
                               :client (client/create cfg)})
                            normalized)
        _ (reset! (:clients manager)
                  (into {} (map (fn [{:keys [name client]}] [name client]))
                        named-clients))
        starts (mapv (fn [{:keys [name client]}]
                       (-> (client/start! client)
                           (.then (fn [_] {:name name :ok true}))
                           (.catch (fn [e]
                                     {:name name
                                      :ok   false
                                      :error (or (.-message e) (str e))}))))
                     named-clients)
        results (js-await (js/Promise.all (clj->js starts)))]
    (let [results-vec (vec (for [i (range (.-length results))]
                             (let [r (aget results i)]
                               {:name (.-name r)
                                :ok   (.-ok r)
                                :error (when (not (.-ok r)) (.-error r))})))]
      {:ok     (mapv :name (filter :ok results-vec))
       :failed (mapv :name (remove :ok results-vec))
       :results results-vec})))

(defn ^:async stop-all!
  "Stop every server in parallel. Always returns successfully; an
   exception in any client/stop! is swallowed so cleanup is
   best-effort."
  [manager]
  (let [pairs (all-clients manager)
        stops (mapv (fn [{:keys [client]}]
                      (-> (client/stop! client)
                          (.catch (fn [_] nil))))
                    pairs)]
    (js-await (js/Promise.all (clj->js stops)))
    (reset! (:clients manager) {})
    nil))

;; ── Diagnostics ─────────────────────────────────────────────────

(defn summary
  "Snapshot {:total :running :starting :error} for the status line."
  [manager]
  (let [pairs (all-clients manager)
        states (mapv (fn [{:keys [client]}] (client/state client)) pairs)
        n      (count states)
        cnt    (fn [s] (count (filter #(= % s) states)))]
    {:total     n
     :running   (cnt :running)
     :starting  (+ (cnt :starting) (cnt :restarting))
     :error     (cnt :stopped-error)
     :stopped   (cnt :stopped)}))
