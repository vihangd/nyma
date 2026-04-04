(ns agent.ui.tool-status
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["ink-spinner$default" :as Spinner]
            [agent.utils.ansi :refer [truncate-text terminal-width]]))

;;; ─── Pure formatting functions (exported for testing) ───

(defn truncate-lines
  "Returns s if ≤ max-lines lines, else first max-lines lines + summary.
   Delegates to ANSI-aware truncate-text for proper handling of escape codes."
  [s max-lines]
  (truncate-text s max-lines (terminal-width)))

(defn format-args
  "Format tool args for display based on verbosity level."
  [args verbosity]
  (case verbosity
    "collapsed" ""
    "summary"   (truncate-lines (js/JSON.stringify (clj->js args) nil 2) 3)
    "full"      (js/JSON.stringify (clj->js args) nil 2)
    ""))

(defn format-result
  "Format tool result for display based on verbosity level."
  [result verbosity max-lines]
  (let [max-l (or max-lines 500)]
    (case verbosity
      "collapsed" ""
      "summary"   (truncate-lines result 5)
      "full"      (truncate-lines result max-l)
      "")))

(defn format-duration
  "Format milliseconds as human-readable duration."
  [ms]
  (cond
    (nil? ms) ""
    (< ms 1000) (str ms "ms")
    :else (let [secs (/ ms 1000)]
            (str (.toFixed secs 1) "s"))))

;;; ─── Components ─────────────────────────────────────────

(defn ToolStartStatus [{:keys [tool-name args verbosity theme]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        args-text (format-args args (or verbosity "collapsed"))]
    #jsx [Box {:flexDirection "column" :marginBottom 0}
          [Box {:flexDirection "row"}
           [Text {:color muted} [Spinner {:type "dots"}]]
           [Text {:color muted} (str " " tool-name)]]
          (when (and (seq args-text) (not= args-text ""))
            #jsx [Text {:color muted} args-text])]))

(defn ToolEndStatus [{:keys [tool-name duration result verbosity max-lines theme]}]
  (let [success (get-in theme [:colors :success] "#9ece6a")
        muted   (get-in theme [:colors :muted] "#565f89")
        dur-text (format-duration duration)
        result-text (format-result result (or verbosity "collapsed") max-lines)]
    #jsx [Box {:flexDirection "column" :marginBottom 0}
          [Box {:flexDirection "row"}
           [Text {:color success} (str "✓ " tool-name)]
           (when (seq dur-text)
             #jsx [Text {:color muted} (str " " dur-text)])]
          (when (and (seq result-text) (not= result-text ""))
            #jsx [Text {:color muted} result-text])]))
