(ns agent.ui.header
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn Header [{:keys [agent resources theme]}]
  (let [model (or (when-let [m (get-in agent [:config :model])] (.-modelId m)) "unknown")]
    #jsx [Box {:borderStyle "single"
               :borderColor (get-in theme [:colors :border] "#3b4261")
               :paddingX 1
               :flexShrink 0
               :justifyContent "space-between"}
          [Text {:bold true :color (get-in theme [:colors :primary] "#7aa2f7")}
           "nyma"]
          [Text {:color (get-in theme [:colors :muted] "#565f89")}
           model]]))
