(ns agent.ui.overlay
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text useInput]]))

(defn Overlay [{:keys [on-close children]}]
  (useInput
    (fn [_input key]
      (when (.-escape key)
        (on-close))))
  #jsx [Box {:position "absolute"
             :flexDirection "column"
             :borderStyle "round"
             :borderColor "#7aa2f7"
             :padding 1
             :width "80%"}
        children])
