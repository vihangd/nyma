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
  (:require [agent.debug :as d]
            ["node:os" :as os]
            [agent.extensions.claude-hook-bridge.config :as config]
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

(defn notify-parse-errors!
  "Surface every parse failure from a load result through `notify`.
   A malformed hooks file used to fail into total silence — not even
   debug.log. Pure over the injected callback, so the test needs no UI."
  [errors notify]
  (doseq [{:keys [path message]} errors]
    (let [m (config/malformed-config-message path message)]
      (d/warn "[hook-bridge]" m)
      (when notify (notify m "error")))))

(defn ^:export default [api]
  (let [cwd        (js/process.cwd)
        compat     (config/load-compat-flags cwd)
        loaded     (config/load-merged-hooks cwd compat)
        ;; The WHOLE load result lives in an atom, not just the hooks map:
        ;; /hooks prints the source list and the parse errors, and after a
        ;; hot reload a report built from the activation-time value would
        ;; still be showing the typo the user just fixed.
        loaded-atom (atom loaded)
        hooks-atom (atom (:hooks loaded))
        notify     (fn [msg level]
                     (let [ui (.-ui api)]
                       (when (and ui (.-available ui) (.-notify ui))
                         (.notify ui msg (or level "info")))))
        cleanups   (atom [])]

    (notify-parse-errors! (:errors loaded) notify)

    ;; Expose mode + a query for the loaded hooks for diagnostic UIs.
    (try
      (set! (.-mode api) (detect-mode api))
      (set! (.-getHookConfig api) (fn [] (clj->js @hooks-atom)))
      (catch :default _e nil))

    ;; Always register handlers — even when no hooks are loaded at
    ;; activation time. This matters because hot-reload mutates the
    ;; hooks-atom in place (the per-event closures dereference it on
    ;; every fire), so if the user starts nyma with no hooks and then
    ;; adds some, only handlers that are already subscribed will see
    ;; the new config. Conditional registration was a bug:
    ;; subscriptions can't be added retroactively from inside the
    ;; reload closure.
    ;;
    ;; The cost when the atom is empty is one nyma event-bus hash
    ;; lookup per tool call (handlers no-op because the matched-block
    ;; list is always empty). Negligible.
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
      (swap! cleanups conj (perm/register! shared)))

    ;; /hooks — what got resolved, out of which files, and what broke.
    (.registerCommand api "hooks"
                      #js {:description "Show resolved hooks, the source files read, and any that failed to parse"
                           :handler
                           (fn [_args ctx]
                             (let [text (config/format-report @loaded-atom)]
                               (when (and ctx (.-ui ctx) (.-notify (.-ui ctx)))
                                 (.notify (.-ui ctx) text "info"))
                               text))})
    (swap! cleanups conj (fn [] (.unregisterCommand api "hooks")))

    ;; Visible startup line — only when NYMA_DEBUG=1, otherwise silent.
    (when (and (.-NYMA_DEBUG js/process.env)
               (or (seq @hooks-atom) (:disable-all-source loaded)))
      (d/info
       (str "[hook-bridge] active — "
            (count (:sources-loaded loaded)) " source(s), "
            (count @hooks-atom) " event(s) configured"
            (when (seq (:sources-loaded loaded))
              (str ": "
                   (.join (clj->js
                           (mapv (fn [p]
                                   (let [h (or (.-NYMA_HOME js/process.env)
                                               (.homedir os))]
                                     (if (and h (.startsWith p h))
                                       (str "~" (.slice p (count h)))
                                       p)))
                                 (:sources-loaded loaded)))
                          ", "))))))

    ;; Hot-reload watcher — fires the reload closure on any change to
    ;; any watched source path. The reload swaps the hooks-atom; per-
    ;; event closures pick up the new config on their next call.
    ;; NYMA_HOME is an undocumented test-isolation override. When unset
    ;; (the common case) fall back to os.homedir() so path/join doesn't
    ;; receive undefined and throw ERR_INVALID_ARG_TYPE on extension load.
    (let [home        (or (.-NYMA_HOME js/process.env) (.homedir os))
          watch-paths (watch/watched-paths cwd compat home)
          on-reload   (fn []
                        (try
                          (let [fresh (config/load-merged-hooks
                                       cwd (config/load-compat-flags cwd))]
                            (reset! loaded-atom fresh)
                            (reset! hooks-atom (:hooks fresh))
                            (notify-parse-errors! (:errors fresh) notify)
                            (audit/reset-seen!)  ;; re-audit edited scripts
                            (diag/reset!)        ;; old unseen matchers are no longer config
                            (when (seq (:sources-loaded fresh))
                              (d/info
                               (str "[hook-bridge] reloaded "
                                    (count (:sources-loaded fresh))
                                    " source(s); "
                                    (count @hooks-atom) " event(s) configured"))))
                          (catch :default e
                            (d/warn
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
