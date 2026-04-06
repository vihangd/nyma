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

(defn- truncate-to
  "Truncate string s to max-len, appending '…' if truncated."
  [s max-len]
  (if (> (count s) max-len)
    (str (.slice s 0 max-len) "…")
    s))

(defn format-one-line-args
  "Format tool args as a human-readable one-liner based on tool type."
  [tool-name args]
  (let [max-w (max 20 (- (terminal-width) 30))]
    (truncate-to
      (case tool-name
        "bash"       (or (first (.split (or (get args :command) "") "\n")) "")
        "read"       (let [p (or (get args :path) "")]
                       (if-let [r (get args :range)]
                         (str p ":" (first r) "-" (second r))
                         p))
        "write"      (or (get args :path) "")
        "edit"       (or (get args :path) "")
        "ls"         (or (get args :path) ".")
        "glob"       (let [pat (or (get args :pattern) "")
                           p   (get args :path)]
                       (if (seq p) (str pat " in " p) pat))
        "grep"       (let [pat (or (get args :pattern) "")
                           p   (get args :path)]
                       (if (seq p)
                         (str "\"" pat "\" in " p)
                         (str "\"" pat "\"")))
        "web_fetch"  (or (get args :url) "")
        "web_search" (str "\"" (or (get args :query) "") "\"")
        "think"      (or (first (.split (or (get args :thought) "") "\n")) "")
        ;; fallback: compact key=value pairs
        (let [pairs (map (fn [[k v]] (str k "=" v)) args)]
          (.join (clj->js pairs) " ")))
      max-w)))

(defn format-one-line-result
  "Summarize tool result as a human-readable one-liner."
  [result]
  (if (or (nil? result) (= result ""))
    ""
    (let [lines (.split result "\n")
          n     (count lines)
          max-w (max 20 (- (terminal-width) 30))]
      (if (= n 1)
        (truncate-to (first lines) max-w)
        (str n " lines")))))

(defn format-args
  "Format tool args for display based on verbosity level."
  [args verbosity]
  (case verbosity
    "collapsed" ""
    "one-line"  ""  ;; handled inline by component
    "summary"   (truncate-lines (js/JSON.stringify (clj->js args) nil 2) 3)
    "full"      (js/JSON.stringify (clj->js args) nil 2)
    ""))

(defn format-result
  "Format tool result for display based on verbosity level."
  [result verbosity max-lines]
  (let [max-l (or max-lines 500)]
    (case verbosity
      "collapsed" ""
      "one-line"  ""  ;; handled inline by component
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

(defn ToolStartStatus [{:keys [tool-name args verbosity theme
                               custom-one-line-args custom-status-text custom-icon]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        v     (or verbosity "collapsed")]
    (if (= v "one-line")
      (let [one-line (or custom-status-text
                         custom-one-line-args
                         (format-one-line-args tool-name args))]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color muted}
                (if custom-icon
                  (str custom-icon " ")
                  #jsx [Spinner {:type "dots"}])]
               [Text {:color muted} (str (when-not custom-icon " ") tool-name)]
               (when (seq one-line)
                 #jsx [Text {:color muted} (str " — " one-line)])]])
      (let [args-text (format-args args v)]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color muted}
                (if custom-icon
                  (str custom-icon " ")
                  #jsx [Spinner {:type "dots"}])]
               [Text {:color muted} (str (when-not custom-icon " ") tool-name)]]
              (when (and (seq args-text) (not= args-text ""))
                #jsx [Text {:color muted} args-text])]))))

(defn ToolEndStatus [{:keys [tool-name duration result verbosity max-lines theme
                            custom-one-line-result custom-icon]}]
  (let [success  (get-in theme [:colors :success] "#9ece6a")
        muted    (get-in theme [:colors :muted] "#565f89")
        dur-text (format-duration duration)
        v        (or verbosity "collapsed")
        icon     (or custom-icon "✓")]
    (if (= v "one-line")
      (let [one-line (or custom-one-line-result (format-one-line-result result))]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color success} (str icon " " tool-name)]
               (when (seq dur-text)
                 #jsx [Text {:color muted} (str " " dur-text)])
               (when (seq one-line)
                 #jsx [Text {:color muted} (str " — " one-line)])]])
      (let [result-text (format-result result v max-lines)]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color success} (str icon " " tool-name)]
               (when (seq dur-text)
                 #jsx [Text {:color muted} (str " " dur-text)])]
              (when (and (seq result-text) (not= result-text ""))
                #jsx [Text {:color muted} result-text])]))))
