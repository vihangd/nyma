(ns agent.ui.editor
  {:squint/extension "jsx"}
  (:require ["react" :refer [useState useCallback]]
            ["ink" :refer [Box Text]]
            ["ink-text-input$default" :as TextInput]
            [agent.ui.editor-mode :refer [detect-mode border-color-for-mode mode-label]]))

(defn editor-prefix [streaming steerAcked]
  (cond steerAcked "↳ queued " streaming "steer❯ " :else "❯ "))

(defn Editor [{:keys [onSubmit streaming steerAcked theme editorValue setEditorValue hidden overlay]}]
  (let [muted-color  (get-in theme [:colors :muted] "#565f89")
        mode         (detect-mode editorValue)
        mode-text    (mode-label mode)
        border-color (cond
                       hidden    muted-color
                       streaming (get-in theme [:colors :warning] "#e0af68")
                       :else     (border-color-for-mode mode theme))]
    #jsx [Box {:borderStyle "round"
               :borderColor border-color
               :paddingX    1
               :flexShrink  0}
          [Text {:color muted-color}
           (if hidden "  thinking..." (editor-prefix streaming steerAcked))]
          (when mode-text
            #jsx [Text {:color border-color :bold true}
                  (str mode-text " ")])
          (when-not hidden
            #jsx [TextInput {:value     editorValue
                             :onChange  setEditorValue
                             :focus     (and (not overlay) (not hidden))
                             :onSubmit  (fn [text]
                                          (onSubmit text))}])]))
