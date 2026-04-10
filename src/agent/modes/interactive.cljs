(ns agent.modes.interactive
  (:require ["ink" :refer [render]]
            [agent.ui.themes :refer [default-dark]]
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
        App     (.-App app-mod)]
    (render
      #jsx [App {:agent     agent
                 :session   session
                 :resources (assoc resources :theme default-dark)}]
      #js {:exitOnCtrlC true})))
