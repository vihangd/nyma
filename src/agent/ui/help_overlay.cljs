(ns agent.ui.help-overlay
  {:squint/extension "jsx"}
  (:require ["react" :refer [useEffect]]
            ["ink" :refer [Box Text useInput]]
            [agent.ui.key-hints :refer [all-actions-grouped]]
            [agent.keybinding-registry :refer [format-key-combo]]))

(def ^:private category-labels
  {:navigation "Navigation"
   :agent      "Agent"
   :editor     "Editor"
   :tools      "Tools"
   :session    "Session"})

(defn HelpOverlay
  "Modal overlay listing every action + its current binding, grouped by
   category. Press Esc to close. Also shows extension-contributed shortcuts
   from (:shortcuts agent) as a separate section."
  [{:keys [registry shortcuts onClose theme]}]
  (let [primary (get-in theme [:colors :primary] "#7aa2f7")
        muted   (get-in theme [:colors :muted]   "#565f89")
        groups  (all-actions-grouped registry)
        ext-entries (when (and shortcuts (seq shortcuts))
                      (sort-by first shortcuts))]
    (useInput
      (fn [_input key]
        (when (.-escape key)
          (when onClose (onClose)))))

    #jsx [Box {:flexDirection "column" :paddingX 2 :paddingY 1
               :borderStyle "round" :borderColor primary}
          [Box {:marginBottom 1}
           [Text {:bold true :color primary} "Keyboard Shortcuts"]]
          (for [group groups]
            #jsx [Box {:key (str (:category group))
                       :flexDirection "column" :marginBottom 1}
                  [Text {:bold true :color muted}
                   (get category-labels (:category group) (str (:category group)))]
                  (for [action (:actions group)]
                    #jsx [Box {:key (:id action) :flexDirection "row"}
                          [Box {:width 14}
                           [Text {:color primary}
                            (format-key-combo (:binding action))]]
                          [Text (:description action)]])])
          (when ext-entries
            #jsx [Box {:flexDirection "column" :marginBottom 1}
                  [Text {:bold true :color muted} "Extension Shortcuts"]
                  (for [[combo entry] ext-entries]
                    #jsx [Box {:key combo :flexDirection "row"}
                          [Box {:width 14}
                           [Text {:color primary} (format-key-combo combo)]]
                          [Text (or (:action entry) combo)]])])
          [Box {:marginTop 1}
           [Text {:color muted} "Press Esc to close"]]]))
