(ns agent.ui.message
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(defn ToolCallMessage [{:keys [name args theme]}]
  #jsx [Box {:flexDirection "row" :marginBottom 0}
        [Text {:color (get-in theme [:colors :muted] "#565f89")}
         (str "⚙ " name " ")]
        [Text {:color (get-in theme [:colors :muted] "#565f89") :dimColor true}
         (js/JSON.stringify (clj->js args) nil 2)]])

(defn ToolResultMessage [{:keys [name result theme]}]
  #jsx [Box {:flexDirection "column" :marginBottom 1}
        [Text {:color (get-in theme [:colors :success] "#9ece6a")}
         (str "✓ " name)]
        [Text {:dimColor true}
         (str result)]])
