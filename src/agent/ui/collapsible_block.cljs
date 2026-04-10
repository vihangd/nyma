(ns agent.ui.collapsible-block
  "Generic container that renders only its header when collapsed, and
   header+content when expanded. Used by ToolExecution and other blocks
   that have a one-line summary plus a body the user can reveal."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box]]))

(defn CollapsibleBlock
  "Props:
     :header   Ink element — always rendered
     :content  Ink element — rendered when expanded is true
     :expanded bool
     :padded   optional bool — when true, content gets marginLeft 2"
  [{:keys [header content expanded padded]}]
  #jsx [Box {:flexDirection "column"}
        header
        (when (and expanded content)
          #jsx [Box {:flexDirection "column"
                     :marginLeft (if padded 2 0)}
                content])])
