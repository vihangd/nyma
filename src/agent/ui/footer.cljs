(ns agent.ui.footer
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn Footer [{:keys [agent theme statuses]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        status-str (when (and statuses (seq statuses))
                     (str " " (->> (vals statuses)
                                   (interpose " | ")
                                   (apply str))))]
    #jsx [Box {:paddingX 1
               :flexShrink 0}
          [Box {:flexGrow 1 :flexShrink 1 :overflow "hidden"}
           [Text {:color muted :wrap "truncate"}
            "ctrl+c exit  /help commands  ctrl+l model"]]
          [Text {:color muted}
           (str (or status-str "") " | nyma v0.1.0")]]))
