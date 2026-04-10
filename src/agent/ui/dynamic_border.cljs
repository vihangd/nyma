(ns agent.ui.dynamic-border
  "Full-width horizontal rule. Width is read from the current terminal
   (via useStdout) and updates on resize."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text useStdout]]))

(defn DynamicBorder
  "Render a single-row horizontal rule spanning the terminal width.
   Props:
     :color     ANSI hex or named color (default muted gray)
     :char      the fill character (default '─')
     :max-width optional integer cap — useful for nested boxes"
  [{:keys [color char max-width]}]
  (let [{:keys [stdout]} (useStdout)
        cols (or (.-columns stdout) 80)
        width (if max-width (min max-width cols) cols)
        fill  (or char "─")
        line  (.repeat fill (max 0 width))]
    #jsx [Box {:flexShrink 0}
          [Text {:color (or color "#565f89")} line]]))
