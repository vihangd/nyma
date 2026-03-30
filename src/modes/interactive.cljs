(ns agent.modes.interactive
  (:require ["ink" :refer [render]]
            [agent.ui.themes :refer [default-dark]]))

(defn ^:async start [agent session resources]
  ;; Dynamically import the JSX app component
  (let [app-mod (js-await (js/import "../ui/app.jsx"))
        App     (.-App app-mod)]
    (render
      #jsx [App {:agent     agent
                 :session   session
                 :resources (assoc resources :theme default-dark)}]
      #js {:exitOnCtrlC true})))
