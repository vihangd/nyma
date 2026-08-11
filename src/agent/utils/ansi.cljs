(ns agent.utils.ansi)

(defn terminal-width
  "Returns current terminal width, defaulting to 80 if unavailable."
  []
  (or (.-columns js/process.stdout) 80))

(defn string-width
  "ANSI-aware visual column width of text.

   NOTE: treats a TAB as ZERO columns (Bun.stringWidth does). Nothing else in
   the stack agrees — pi-tui's `visibleWidth` says 1, its overlay compositor
   says 3, a terminal says 4-8 — so any string measured here must already be
   tab-free or the number is fiction. See `expand-tabs`."
  [s]
  (if (nil? s) 0 (js/Bun.stringWidth (str s))))

(def tab-stop
  "Columns per tab stop when expanding. 4 matches how code is usually shown."
  4)

(defn- expand-tabs-line
  "Expand tabs in ONE line, advancing to the next tab stop. Column is measured
   with `string-width` so ANSI escapes before a tab don't shift the stop."
  [line]
  (if-not (.includes line "\t")
    line
    (let [parts (.split line "\t")
          n     (count parts)]
      (loop [i 1, acc (aget parts 0)]
        (if (>= i n)
          acc
          (let [col  (string-width acc)
                pad  (- tab-stop (mod col tab-stop))
                acc' (str acc (.repeat " " pad) (aget parts i))]
            (recur (inc i) acc')))))))

(defn expand-tabs
  "Replace tabs with spaces to the next tab stop, per line.

   A tab is the one character every layer of this stack measures differently,
   so a rendered line containing one has no single true width — which is how a
   tab-indented Go file under a permission-prompt overlay produced a line 3
   columns per tab too wide and crashed the TUI. Expanding to spaces makes all
   the measurements agree, because a space is unambiguous.

   Must run BEFORE wrapping. Expanding an already-wrapped or composited line
   only makes it wider."
  [s]
  (if (or (nil? s) (not (.includes (str s) "\t")))
    s
    (.join (.map (.split (str s) "\n") expand-tabs-line) "\n")))

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
