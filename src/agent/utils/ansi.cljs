(ns agent.utils.ansi)

(defn terminal-width
  "Returns current terminal width, defaulting to 80 if unavailable."
  []
  (or (.-columns js/process.stdout) 80))

(defn string-width
  "ANSI-aware visual column width of text."
  [s]
  (if (nil? s) 0 (js/Bun.stringWidth (str s))))

(defn wrap-ansi
  "Wrap text to fit within `cols` columns, preserving ANSI codes.
   Options: :hard (break mid-word), :trim (trim whitespace), :word-wrap."
  ([s cols] (wrap-ansi s cols {}))
  ([s cols opts]
   (if (nil? s) ""
     (js/Bun.wrapAnsi (str s) cols
       #js {:hard     (get opts :hard true)
            :trim     (get opts :trim true)
            :wordWrap (get opts :word-wrap true)}))))

(defn truncate-text
  "Wrap long lines to cols width, then truncate by line count.
   First wraps lines to fit terminal, then caps at max-lines."
  ([s max-lines] (truncate-text s max-lines (terminal-width)))
  ([s max-lines cols]
   (if (nil? s) ""
     (let [wrapped (wrap-ansi s cols)
           lines   (.split wrapped "\n")]
       (if (<= (count lines) max-lines)
         wrapped
         (let [kept (.slice lines 0 max-lines)
               remaining (- (count lines) max-lines)]
           (str (.join kept "\n") "\n[..." remaining " more lines]")))))))
