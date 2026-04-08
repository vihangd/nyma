(ns agent.ui.editor
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useCallback]]
            ["ink" :refer [Box Text]]
            ["ink-text-input$default" :as TextInput]))

(defn editor-prefix [streaming steerAcked]
  (cond steerAcked "↳ queued " streaming "steer❯ " :else "❯ "))

(defn Editor [{:keys [onSubmit streaming steerAcked theme editorValue setEditorValue hidden overlay]}]
  (let [muted-color  (get-in theme [:colors :muted] "#565f89")
        border-color (cond
                       hidden    muted-color
                       streaming (get-in theme [:colors :warning] "#e0af68")
                       :else     (get-in theme [:colors :border] "#3b4261"))]
    #jsx [Box {:borderStyle "round"
               :borderColor border-color
               :paddingX    1
               :flexShrink  0}
          [Text {:color muted-color}
           (if hidden "  thinking..." (editor-prefix streaming steerAcked))]
          (when-not hidden
            #jsx [TextInput {:value     editorValue
                             :onChange  setEditorValue
                             :focus     (and (not overlay) (not hidden))
                             :onSubmit  (fn [text]
                                          (onSubmit text))}])]))
