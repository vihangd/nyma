(ns agent.ui.tool-status
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
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

(defn format-one-line-result-for-tool
  "Tool-specific one-line result summary. Returns a compact human-readable
   string: '42 lines' for read, '7 matches' for grep, 'exit 1' for failed
   bash, etc. Falls back to generic format-one-line-result for unknown tools."
  [tool-name result _args]
  (let [lines-of (fn [s] (let [ls (.split (or s "") "\n")]
                           (count (filterv seq ls))))]
    (case tool-name
      "read"  (let [n (lines-of result)]
                (if (= n 1) "1 line" (str n " lines")))
      "grep"  (let [n (lines-of result)]
                (if (= n 1) "1 match" (str n " matches")))
      "glob"  (let [n (lines-of result)]
                (if (= n 1) "1 file" (str n " files")))
      "ls"    (let [n (lines-of result)]
                (if (= n 1) "1 item" (str n " items")))
      "edit"  "applied"
      "write" "written"
      "bash"  (let [n (lines-of result)] (str n " lines"))
      (format-one-line-result result))))

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

;;; ─── Grouping ───────────────────────────────────────────

(def ^:private groupable-tools
  "Tools that can be grouped when consecutive. Read-only tools only."
  #{"read" "glob" "grep" "ls"})

(defn- corrected-suffix
  "When a tool's args carry :corrected-path (set by the path resolver when it
   fixes a typo), append a ' (was: original)' suffix to the label."
  [args]
  (if-let [orig (or (get args :corrected-path) (get args "corrected-path"))]
    (str " (was: " orig ")")
    ""))

(defn tool-group-label
  "Extract the meaningful display label from a tool call's args.
   If the path resolver corrected a typo, appends ' (was: original)'."
  [tool-name args]
  (let [base (case tool-name
               "read" (or (get args :path) (get args "path") "?")
               "glob" (or (get args :pattern) (get args "pattern") "?")
               "grep" (let [p (or (get args :pattern) (get args "pattern") "?")]
                        (str "\"" p "\""))
               "ls"   (or (get args :path) (get args "path") ".")
               "?")]
    (str base (corrected-suffix args))))

(defn- item-status-icon
  "Return the status icon for a grouped tool item:
   ✓ when the result is non-empty, ✖ when nil/empty."
  [item]
  (if (and (:result item) (seq (str (:result item))))
    "✓"
    "✖"))

(defn group-messages
  "Collapse consecutive same-tool tool-end messages into :tool-group entries.
   Only groups tools in groupable-tools set. Minimum 2 to trigger grouping."
  [messages]
  (loop [msgs messages
         acc  []
         group nil]  ;; {:tool-name :items [{:args :duration :result}]}
    (if (empty? msgs)
      ;; Flush remaining group
      (if (and group (>= (count (:items group)) 2))
        (conj acc {:role      "tool-group"
                   :id        (:id (:original (first (:items group))))
                   :tool-name (:tool-name group)
                   :items     (:items group)})
        (into acc (when group
                    (mapv (fn [item] (:original item)) (:items group)))))
      (let [msg  (first msgs)
            rest-msgs (rest msgs)]
        (if (and (= (:role msg) "tool-end")
                 (contains? groupable-tools (:tool-name msg)))
          ;; This is a groupable tool-end
          (if (and group (= (:tool-name group) (:tool-name msg)))
            ;; Same tool as current group — extend
            (recur rest-msgs acc
                   (update group :items conj {:args     (:args msg)
                                              :duration (:duration msg)
                                              :result   (:result msg)
                                              :original msg}))
            ;; Different tool or no group — flush old, start new
            (let [flushed (if (and group (>= (count (:items group)) 2))
                            (conj acc {:role      "tool-group"
                                       :id        (:id (:original (first (:items group))))
                                       :tool-name (:tool-name group)
                                       :items     (:items group)})
                            (into acc (when group
                                        (mapv (fn [item] (:original item)) (:items group)))))]
              (recur rest-msgs flushed
                     {:tool-name (:tool-name msg)
                      :items [{:args     (:args msg)
                               :duration (:duration msg)
                               :result   (:result msg)
                               :original msg}]})))
          ;; Non-groupable message — flush group, emit message
          (let [flushed (if (and group (>= (count (:items group)) 2))
                          (conj acc {:role      "tool-group"
                                     :id        (:id (:original (first (:items group))))
                                     :tool-name (:tool-name group)
                                     :items     (:items group)})
                          (into acc (when group
                                      (mapv (fn [item] (:original item)) (:items group)))))]
            (recur rest-msgs (conj flushed msg) nil)))))))

;;; ─── Components ─────────────────────────────────────────

;; Static glyph shown in place of a ticking spinner. The ToolStartStatus
;; row is the top of a potentially tall dynamic region (it stacks with
;; reasoning blocks, tool args, editor, status, footer), so any 80-ms
;; Ink tick in here scrolls the top line into permanent terminal
;; scrollback — see the ReasoningBlock fix for the full mechanism.
;; Visual liveness is provided by the status-line activity segment,
;; which lives in a fixed 1-row region that can't overflow.
(def ^:private TOOL-RUNNING-GLYPH "◌")

(defn ToolStartStatus [{:keys [tool-name args verbosity theme
                               custom-one-line-args custom-status-text custom-icon]}]
  (let [muted (get-in theme [:colors :muted] "#565f89")
        v     (or verbosity "collapsed")
        icon  (or custom-icon TOOL-RUNNING-GLYPH)]
    (if (= v "one-line")
      (let [one-line (or custom-status-text
                         custom-one-line-args
                         (format-one-line-args tool-name args))]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color muted} (str icon " " tool-name)]
               (when (seq one-line)
                 #jsx [Text {:color muted} (str " — " one-line)])]])
      (let [args-text (format-args args v)]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color muted} (str icon " " tool-name)]]
              (when (and (seq args-text) (not= args-text ""))
                #jsx [Text {:color muted} args-text])]))))

(defn ToolEndStatus [{:keys [tool-name args duration result verbosity max-lines theme
                             custom-one-line-result custom-icon]}]
  (let [success  (get-in theme [:colors :success] "#9ece6a")
        muted    (get-in theme [:colors :muted] "#565f89")
        dur-text (format-duration duration)
        v        (or verbosity "collapsed")
        icon     (or custom-icon "✓")]
    (if (= v "one-line")
      (let [arg-text (format-one-line-args tool-name args)
            one-line (or custom-one-line-result (format-one-line-result-for-tool tool-name result args))]
        #jsx [Box {:flexDirection "column" :marginBottom 0}
              [Box {:flexDirection "row"}
               [Text {:color success} (str icon " " tool-name)]
               (when (seq arg-text)
                 #jsx [Text {:color muted} (str " " arg-text)])
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

(defn ToolGroupStatus
  "Render a group of consecutive same-tool calls as a compact tree.
   Each child row shows a per-item status icon (✓ success, ✖ empty result)
   and, when the path resolver corrected a typo, the original path."
  [{:keys [tool-name items theme]}]
  (let [success    (get-in theme [:colors :success] "#9ece6a")
        muted      (get-in theme [:colors :muted] "#565f89")
        error-c    (get-in theme [:colors :error] "#f7768e")
        n          (count items)
        total-dur  (reduce + 0 (keep :duration items))
        dur-text   (format-duration total-dur)]
    #jsx [Box {:flexDirection "column" :marginBottom 0}
          [Box {:flexDirection "row"}
           [Text {:color success} (str "✓ " tool-name " ×" n)]
           (when (seq dur-text)
             #jsx [Text {:color muted} (str " (" dur-text ")")])]
          [Box {:flexDirection "column" :marginLeft 2}
           (map-indexed
            (fn [i item]
              (let [last? (= i (dec n))
                    connector (if last? "└─ " "├─ ")
                    icon (item-status-icon item)
                    ok?  (= icon "✓")
                    label (tool-group-label tool-name (:args item))]
                #jsx [Box {:key i :flexDirection "row"}
                      [Text {:color muted} connector]
                      [Text {:color (if ok? success error-c)} (str icon " ")]
                      [Text {:color muted} label]]))
            items)]]))
