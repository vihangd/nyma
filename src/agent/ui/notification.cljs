(ns agent.ui.notification
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn Notification [{:keys [message level theme]}]
  (let [color (case level
                "error"   (get-in theme [:colors :error] "#f7768e")
                "warning" (get-in theme [:colors :warning] "#e0af68")
                (get-in theme [:colors :success] "#9ece6a"))
        icon  (case level
                "error"   "✗ "
                "warning" "! "
                "✓ ")]
    #jsx [Box {:paddingX 1}
          [Text {:color color} (str icon message)]]))
