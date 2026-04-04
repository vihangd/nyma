(ns agent.ui.footer
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn Footer [{:keys [agent theme statuses]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")]
    #jsx [Box {:paddingX 1
               :justifyContent "space-between"}
          [Text {:color muted}
           "ctrl+c exit  /help commands  ctrl+l model"]
          [Box {:gap 2}
           (when (and statuses (seq statuses))
             (map (fn [[id text]]
                    #jsx [Text {:key id} text])
                  statuses))
           [Text {:color muted} "nyma v0.1.0"]]]))
