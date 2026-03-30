(ns agent.ui.footer
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn Footer [{:keys [agent theme]}]
  #jsx [Box {:paddingX 1
             :justifyContent "space-between"}
        [Text {:color (get-in theme [:colors :muted] "#565f89") :dimColor true}
         "ctrl+c exit  /help commands  ctrl+l model"]
        [Text {:color (get-in theme [:colors :muted] "#565f89") :dimColor true}
         "nyma v0.1.0"]])
