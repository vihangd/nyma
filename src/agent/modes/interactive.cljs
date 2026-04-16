(ns agent.modes.interactive
  (:require ["ink" :refer [render]]
            [agent.ui.themes :refer [default-dark]]
            [agent.ui.scrollback :refer [print-header-banner!]]
            ;; Load built-in tool renderers for side effects — each file
            ;; calls register-renderer at module load. Must be required
            ;; before the chat view mounts.
            [agent.ui.renderers.index]
            [agent.ui.autocomplete-builtins :as ac-builtins]))

(defn ^:async start [agent session resources]
  ;; Register built-in completion providers (slash, @file, path). Done
  ;; here so agent state (commands, mention providers) is already set up.
  (ac-builtins/register-all! agent)
  ;; Dynamically import the JSX app component
  (let [app-mod (js-await (js/import "../ui/app.jsx"))
        App     (.-App app-mod)
        theme   default-dark
        model-id (when-let [m (get-in agent [:config :model])]
                   (or (.-modelId m) (str m)))]
    ;; Print the startup banner DIRECTLY to stdout before Ink mounts.
    ;; Matches Claude Code's pattern: the banner is normal terminal
    ;; output and scrolls up with chat content. Keeping it out of the
    ;; Ink tree avoids the "committed content appears above a pinned
    ;; header" UX bug.
    (when (:scrollback-mode ((:get (:settings resources))))
      (print-header-banner! {:model-id model-id :theme theme}))
    (render
     #jsx [App {:agent     agent
                :session   session
                :resources (assoc resources :theme theme)}]
     #js {:exitOnCtrlC true})))
