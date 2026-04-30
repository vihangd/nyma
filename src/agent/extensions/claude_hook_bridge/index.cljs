(ns agent.extensions.claude-hook-bridge.index
  "Claude-Code-shape hooks for nyma.

   Reads the `hooks` key from .nyma/settings.json (and ~/.nyma's), plus
   optional .claude/ + .agents/ compat sources, and registers nyma
   event handlers that translate inbound events into CC's hook stdin
   schema, dispatch to the configured handlers, and translate the
   response back into mutations on nyma's middleware ctx or event flow.

   Hot-reload: the parsed hooks live in a single atom that the per-
   event handler closures dereference on every fire. When any watched
   settings file changes, the atom is atomically updated. No
   subscriber churn — old handler closures keep working with the new
   config on their next call."
  (:require [agent.extensions.claude-hook-bridge.config :as config]
            [agent.extensions.claude-hook-bridge.watch :as watch]
            [agent.extensions.claude-hook-bridge.audit :as audit]
            [agent.extensions.claude-hook-bridge.diagnostics :as diag]
            [agent.extensions.claude-hook-bridge.events.pre-tool-use :as pre-tool-use]
            [agent.extensions.claude-hook-bridge.events.post-tool-use :as post-tool-use]
            [agent.extensions.claude-hook-bridge.events.session :as session]
            [agent.extensions.claude-hook-bridge.events.user-prompt-submit :as ups]
            [agent.extensions.claude-hook-bridge.events.stop :as stop]
            [agent.extensions.claude-hook-bridge.events.compact :as compact]
            [agent.extensions.claude-hook-bridge.events.permission-request :as perm]))

(defn- detect-mode
  "Best-effort: read the agent's mode hint from the api.

   nyma's interactive/sdk/rpc/print modes don't expose an explicit
   accessor today, so we approximate from `api.ui.available`:
     - has UI         → \"interactive\"
     - no UI          → \"sdk\" (caller can override via NYMA_MODE)"
  [api]
  (or (when-let [m (and js/process.env (.-NYMA_MODE js/process.env))]
        (str m))
      (try
        (let [ui (.-ui api)]
          (if (and ui (.-available ui))
            "interactive"
            "sdk"))
        (catch :default _e "sdk"))))

(defn ^:export default [api]
  (let [cwd        (js/process.cwd)
        compat     (config/load-compat-flags cwd)
        loaded     (config/load-merged-hooks cwd compat)
        hooks-atom (atom (:hooks loaded))
        cleanups   (atom [])]

    ;; Expose mode + a query for the loaded hooks for diagnostic UIs.
    (try
      (set! (.-mode api) (detect-mode api))
      (set! (.-getHookConfig api) (fn [] (clj->js @hooks-atom)))
      (catch :default _e nil))

    (when (or (seq @hooks-atom) (:disable-all-source loaded))
      (let [shared {:api          api
                    :hooks-atom   hooks-atom
                    :cwd          cwd
                    :sources      (:sources-loaded loaded)}]
        (swap! cleanups conj (pre-tool-use/register! shared))
        (swap! cleanups conj (post-tool-use/register! shared))
        (swap! cleanups conj (session/register! shared))
        (swap! cleanups conj (ups/register! shared))
        (swap! cleanups conj (stop/register! shared))
        (swap! cleanups conj (compact/register! shared))
        (swap! cleanups conj (perm/register! shared))))

    ;; Hot-reload watcher — fires the reload closure on any change to
    ;; any watched source path. The reload swaps the hooks-atom; per-
    ;; event closures pick up the new config on their next call.
    (let [watch-paths (watch/watched-paths cwd compat (.-NYMA_HOME js/process.env))
          on-reload   (fn []
                        (try
                          (let [fresh (config/load-merged-hooks
                                       cwd (config/load-compat-flags cwd))]
                            (reset! hooks-atom (:hooks fresh))
                            (audit/reset-seen!)  ;; re-audit edited scripts
                            (diag/reset!)        ;; old unseen matchers are no longer config
                            (when (seq (:sources-loaded fresh))
                              (js/console.log
                               (str "[hook-bridge] reloaded "
                                    (count (:sources-loaded fresh))
                                    " source(s); "
                                    (count @hooks-atom) " event(s) configured"))))
                          (catch :default e
                            (js/console.warn
                             "[hook-bridge] reload error:"
                             (or (.-message e) (str e))))))
          stop-watch  (watch/start-watcher
                       {:paths     watch-paths
                        :on-reload on-reload})]
      (swap! cleanups conj stop-watch))

    ;; Deactivate
    (fn []
      (doseq [c @cleanups]
        (when (fn? c) (try (c) (catch :default _e nil)))))))
