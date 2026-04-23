(ns agent.modes.interactive
  (:require ["ink" :refer [render]]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.scrollback :refer [print-header-banner!]]
            ;; Load built-in tool renderers for side effects — each file
            ;; calls register-renderer at module load. Must be required
            ;; before the chat view mounts.
            [agent.ui.renderers.index]
            [agent.ui.autocomplete-builtins :as ac-builtins]))

(defn alt-screen-enabled?
  "Pure helper: parse the NYMA_ALT_SCREEN env value into a boolean.
   Truthy: any non-empty string except \"0\" and \"false\". Falsy:
   nil, empty string, \"0\", \"false\"."
  [env-value]
  (boolean (and env-value
                (not= env-value "")
                (not= env-value "0")
                (not= env-value "false"))))

(defn pager-mode-enabled?
  "Pure helper: return true when the persisted scrollback-mode setting
   is the string \"pager\". Alt-screen suppresses pager mode — an alt-
   screen has no terminal scrollback, and the pager's in-app scroll is
   orthogonal to writeToStdout commits anyway; forcing one mode at a
   time keeps the interactions predictable."
  [{:keys [alt-screen? scrollback-mode-setting]}]
  (boolean (and (not alt-screen?)
                (= scrollback-mode-setting "pager"))))

(defn effective-scrollback-on?
  "Pure helper: resolve the effective scrollback-mode (the natural-flow
   writeToStdout-commit path) given alt-screen?, pager-mode?, and the
   persisted scrollback-mode setting.

   scrollback-on is only true when:
     - alt-screen is OFF (alt-screen wipes scrollback; commits would
       land in a buffer no one can scroll back to)
     - pager-mode is OFF (pager owns the in-flight slice; there's no
       commit loop to drive)
     - the persisted setting is truthy (true or missing — missing
       defaults to true to match d49e4a4's choice)

   App.cljs and interactive.cljs both call this so the commit-sweep,
   the banner-write, and the layout gates agree on what mode they're in."
  [{:keys [alt-screen? scrollback-mode-setting]
    :as opts}]
  (boolean (and (not alt-screen?)
                (not (pager-mode-enabled? opts))
                (cond
                  (nil? scrollback-mode-setting) true
                  (= scrollback-mode-setting "pager") false
                  :else scrollback-mode-setting))))

(defn ^:async start [agent session resources]
  ;; Register built-in completion providers (slash, @file, path). Done
  ;; here so agent state (commands, mention providers) is already set up.
  (ac-builtins/register-all! agent)
  ;; Dynamically import the JSX app component
  (let [app-mod (js-await (js/import "../ui/app.jsx"))
        App     (.-App app-mod)
        theme   default-dark
        model-id (when-let [m (get-in agent [:config :model])]
                   (or (.-modelId m) (str m)))
        ;; Experimental: NYMA_ALT_SCREEN=1 (or "true") enables Ink 7's
        ;; alternateScreen mode. The conversation runs in the terminal's
        ;; alt-screen buffer (vim/htop/less style); on exit, the terminal
        ;; restores the pre-launch screen. Trade-off: chat history is
        ;; NOT visible in terminal scrollback after the session ends —
        ;; alt-screen has no shared scrollback by design. Off by default.
        ;; Env var (not a setting) so the only way to enable it is
        ;; deliberate per-invocation; no persistence, easy rollback.
        alt-screen? (alt-screen-enabled? (.-NYMA_ALT_SCREEN js/process.env))
        ;; Banner is meaningful only in natural-flow scrollback mode.
        ;; In alt-screen, the screen gets wiped on entry, so writing
        ;; the banner is wasted bytes. The same predicate is applied
        ;; in app.cljs to gate the commit-sweep, so banner-write and
        ;; commit-sweep agree on whether scrollback-mode is in effect.
        scrollback-on?
        (effective-scrollback-on?
         {:alt-screen?             alt-screen?
          :scrollback-mode-setting (:scrollback-mode ((:get (:settings resources))))})]
    (when scrollback-on?
      (print-header-banner! {:model-id model-id :theme theme}))
    (render
     #jsx [App {:agent     agent
                :session   session
                :resources (assoc resources
                                  :theme theme
                                  :alt-screen? alt-screen?)}]
     #js {:exitOnCtrlC    true
          :alternateScreen alt-screen?})))
