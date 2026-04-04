(ns agent.ui.widget-container
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn WidgetContainer
  "Renders extension widgets filtered by position (above or below the chat).
   widgets is a map of {id → {:lines [strings], :position \"above\"|\"below\"}}."
  [{:keys [widgets position]}]
  (let [filtered (filter (fn [[_ w]] (= (:position w) position)) widgets)]
    (when (seq filtered)
      #jsx [Box {:flexDirection "column"}
            (map (fn [[id w]]
                   #jsx [Box {:key id :flexDirection "column"
                              :borderStyle "single" :borderColor "#3b4261"
                              :paddingLeft 1 :paddingRight 1}
                         (map-indexed
                           (fn [i line]
                             #jsx [Text {:key (str id "-" i)} (str line)])
                           (:lines w))])
                 filtered)])))
