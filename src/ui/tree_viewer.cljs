(ns agent.ui.tree-viewer
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text useInput]]))

(defn TreeViewer [{:keys [session on-close theme]}]
  (let [tree ((:get-tree session))
        entries (js->clj tree :keywordize-keys true)]
    (useInput
      (fn [_input key]
        (when (.-escape key)
          (on-close))))
    #jsx [Box {:flexDirection "column" :padding 1}
          [Text {:bold true :color (get-in theme [:colors :primary])}
           "Session Tree"]
          (map-indexed
            (fn [i entry]
              #jsx [Box {:key i :marginLeft (* 2 (or (:depth entry) 0))}
                    [Text {:color (get-in theme [:colors :muted])}
                     (str "├─ " (:role entry) ": "
                          (subs (str (:content entry)) 0 (min 60 (count (str (:content entry))))) "...")]])
            (take 20 entries))]))
