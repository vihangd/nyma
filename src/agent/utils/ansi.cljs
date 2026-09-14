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


;;; ─── Colour ─────────────────────────────────────────────────────────
;;; One `fg` for every renderer. Three files each carried their own
;;; truecolor-only copy, and none of them knew about NO_COLOR or a terminal
;;; that cannot show 24-bit colour.

(def ^:private ESC (js/String.fromCharCode 27))

(defn color-depth
  "0 (NO_COLOR set, or TERM=dumb), 16, 256 or 16777216, from the environment.
   Pure over `env` so it is testable; defaults to process.env."
  ([] (color-depth js/process.env))
  ([env]
   (let [g (fn [k] (str (or (aget env k) "")))]
     (cond
       (seq (g "NO_COLOR"))                         0
       (= (g "TERM") "dumb")                        0
       (seq (g "FORCE_COLOR"))                      16777216
       (contains? #{"truecolor" "24bit"} (g "COLORTERM")) 16777216
       (.includes (g "TERM") "256color")            256
       (.includes (g "TERM") "color")               16
       (seq (g "TERM"))                             16777216
       :else                                        16))))

(defn- hex->rgb [hex]
  (let [h (str (or hex ""))]
    (when (and (= (count h) 7) (.startsWith h "#"))
      [(js/parseInt (.slice h 1 3) 16)
       (js/parseInt (.slice h 3 5) 16)
       (js/parseInt (.slice h 5 7) 16)])))

(defn- rgb->256 [[r g b]]
  (let [q (fn [v] (js/Math.round (* 5 (/ v 255))))]
    (+ 16 (* 36 (q r)) (* 6 (q g)) (q b))))

(defn fg
  "Foreground SGR for a #rrggbb string at the terminal's depth; empty string
   under NO_COLOR, so callers can concatenate unconditionally."
  ([hex] (fg hex (color-depth)))
  ([hex depth]
   (if-let [[r g b] (hex->rgb hex)]
     (cond
       (= depth 0)  ""
       (< depth 256) (let [lum (+ (* 0.299 r) (* 0.587 g) (* 0.114 b))]
                       (str ESC "[" (if (> lum 128) "97" "37") "m"))
       (= depth 256) (str ESC "[38;5;" (rgb->256 [r g b]) "m")
       :else         (str ESC "[38;2;" r ";" g ";" b "m"))
     "")))
