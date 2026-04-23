(ns agent.ui.widget-container
  "Renders pinned extension widgets above or below the chat view.

   Widgets are STATUS UI — they don't scroll into terminal scrollback
   when the conversation moves on; they stay visible until the producing
   extension clears them. Because of that, a widget with a lot of lines
   permanently occupies dynamic-region height, which pushes the editor
   off-screen and creates the same Ink log-update overflow-leak vector
   as the ChatView bug (overflow → log-update can't clear lines that
   scrolled off → top lines leak into real scrollback on every frame).

   Guard: each widget renders at most `:max-lines` lines (default 20).
   When truncated, we show a dim `… +N more lines` indicator so the
   extension's author knows their widget is being clipped and can
   either shorten it or switch to an overlay (overlays scroll; widgets
   don't)."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(def ^:private DEFAULT-MAX-LINES 20)

(defn truncate-widget-lines
  "Return {:shown [lines…] :extra N} where shown is the prefix of `lines`
   under `cap` and extra is 0 or the count of dropped lines. Pure —
   tested directly."
  [lines cap]
  (let [n   (count lines)
        cap (max 0 cap)]
    (if (<= n cap)
      {:shown (vec lines) :extra 0}
      {:shown (vec (take cap lines))
       :extra (- n cap)})))

(defn WidgetContainer
  "Renders extension widgets filtered by position (above or below the chat).
   widgets is a map of {id → {:lines [strings] :position \"above\"|\"below\"
                              :priority N? :max-lines N?}}.

   Each widget is capped at `:max-lines` lines (default 20). Overflow
   appears as `… +N more lines` so producers see their widget is being
   clipped."
  [{:keys [widgets position]}]
  (let [filtered (->> widgets
                      (filter (fn [[_ w]] (= (:position w) position)))
                      (sort-by (fn [[_ w]] (- (or (:priority w) 0)))))]
    (when (seq filtered)
      #jsx [Box {:flexDirection "column"}
            (map (fn [[id w]]
                   (let [cap              (or (:max-lines w) DEFAULT-MAX-LINES)
                         {:keys [shown extra]} (truncate-widget-lines (:lines w) cap)]
                     #jsx [Box {:key id :flexDirection "column"
                                :borderStyle "single" :borderColor "#3b4261"
                                :paddingLeft 1 :paddingRight 1}
                           (map-indexed
                            (fn [i line]
                              #jsx [Text {:key (str id "-" i)} (str line)])
                            shown)
                           (when (pos? extra)
                             #jsx [Text {:key (str id "-overflow") :dimColor true}
                                   (str "… +" extra " more lines")])]))
                 filtered)])))
