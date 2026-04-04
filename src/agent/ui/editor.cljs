(ns agent.ui.editor
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useCallback]]
            ["ink" :refer [Box Text]]
            ["ink-text-input$default" :as TextInput]))

(defn editor-prefix [streaming steerAcked]
  (cond steerAcked "↳ queued " streaming "steer❯ " :else "❯ "))

(defn Editor [{:keys [onSubmit streaming steerAcked theme]}]
  (let [[value set-value] (useState "")
        border-color      (if streaming
                            (get-in theme [:colors :warning] "#e0af68")
                            (get-in theme [:colors :border] "#3b4261"))]
    #jsx [Box {:borderStyle "round"
               :borderColor border-color
               :paddingX 1}
          [Text {:color (get-in theme [:colors :muted] "#565f89")}
           (editor-prefix streaming steerAcked)]
          [TextInput {:value     value
                      :onChange  set-value
                      :onSubmit  (fn [text]
                                  (set-value "")
                                  (onSubmit text))}]]))
