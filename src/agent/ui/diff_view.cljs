(ns agent.ui.diff-view
  "Ink renderer that drops a parsed diff into <Text> elements.
   Consumes render-diff-block from diff_renderer."
  {:squint/extension "jsx"}
  (:require ["react" :refer [useMemo]]
            ["ink" :refer [Box Text]]
            [agent.ui.diff-renderer :refer [render-diff-block]]))

(defn- row-color
  [type theme]
  (case type
    :added    (get-in theme [:colors :success] "#9ece6a")
    :removed  (get-in theme [:colors :error]   "#f7768e")
    :context  (get-in theme [:colors :muted]   "#565f89")
    (get-in theme [:colors :muted] "#565f89")))

(defn- row-prefix
  [type]
  (case type
    :added    "+"
    :removed  "-"
    :context  " "
    " "))

(defn DiffView
  "Render a parsed diff as coloured, left-padded rows with a 4-column
   line-number gutter."
  [{:keys [diff-text theme]}]
  (let [rows (useMemo (fn [] (render-diff-block diff-text))
                      #js [diff-text])]
    #jsx [Box {:flexDirection "column"}
          (for [[i row] (map-indexed vector rows)]
            (let [color (row-color (:type row) theme)
                  prefix (row-prefix (:type row))
                  num-str (if (:line-num row)
                            (.padStart (str (:line-num row)) 4 " ")
                            "    ")]
              #jsx [Box {:key i :flexDirection "row"}
                    [Text {:color color} (str prefix " ")]
                    [Text {:color (get-in theme [:colors :muted] "#565f89")}
                     (str num-str " │ ")]
                    [Text {:color color} (str (:content row))]]))]))
