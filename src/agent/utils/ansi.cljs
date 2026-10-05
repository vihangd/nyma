(ns agent.utils.ansi
  "ANSI-aware text utilities (`truncate-text`, `terminal-width`).")

(defn terminal-width
  "Returns current terminal width, defaulting to 80 if unavailable."
  []
  (or (.-columns js/process.stdout) 80))

(defn string-width
  "ANSI-aware visual column width of text.

   NOTE: treats a TAB as ZERO columns (Bun.stringWidth does). Nothing else in
   the stack agrees — pi-tui's `visibleWidth` counts it as 3 (measured: `a\tb`
   is 5 there, 2 here), a terminal says 4-8 — so any string measured here must
   already be tab-free or the number is fiction. See `expand-tabs`.

   Bun and pi-tui also disagree on a lone regional indicator (1 vs 2) and on
   Devanagari conjuncts (`क्षि`, 2 vs 3) — always with Bun LOWER, so a line Bun
   says fits can still exceed pi-tui's crash check. That is why the width guard
   clamps with pi-tui's own functions, never these. Pinned by
   `test/width_parity.test.cljs`."
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
       ;; No TERM at all (CI, a pipe, a test runner) is not a 16-colour
       ;; terminal — it is no terminal. NO_COLOR is the opt-out.
       :else                                        16777216))))

(defn- hex->rgb
  "`#rrggbb` → [r g b], or nil.

   The digits are checked, not just the shape. Without that, `#gggggg` passed
   the length-and-`#` test and `parseInt` returned NaN for each channel — which
   the old luminance branch quietly turned into plain white, and which any
   arithmetic downstream turns into nonsense. `fg` promises the empty string
   for a colour it cannot read; this is what makes that true."
  [hex]
  (let [h (str (or hex ""))]
    (when (re-matches #"#[0-9a-fA-F]{6}" h)
      [(js/parseInt (.slice h 1 3) 16)
       (js/parseInt (.slice h 3 5) 16)
       (js/parseInt (.slice h 5 7) 16)])))

(defn- rgb->256 [[r g b]]
  (let [q (fn [v] (js/Math.round (* 5 (/ v 255))))]
    (+ 16 (* 36 (q r)) (* 6 (q g)) (q b))))

(defn rgb->16
  "Nearest of the 16 ANSI colours, as an SGR foreground code.

   This replaced a luminance test that returned 97 or 37 — bright white or
   white — for every colour. At depth 16 that made error, warning and success
   byte-identical, so the one thing colour carries in this UI (which of these
   lines is a problem) was gone exactly where there is no other signal.

   Hue sector, not nearest-RGB. Nearest-RGB was tried first and is wrong for
   precisely the colours themes are built from: `#f7768e`, this theme's error
   red, is 34,152 away from pure red and 14,706 from mid-grey, so a distance
   metric calls a salmon-pink error message grey — and then success and muted
   land on the same grey. Every desaturated colour collapses to the centre,
   which is the bug again in a new coat.

   So: decide chromatic-or-not by saturation, then pick by hue. Pure.

   pi-tui cannot help here. Its `TerminalColorMode` is only
   `\"256color\" | \"truecolor\"`, so `foregroundAnsi` has no 16-colour mode to
   borrow — passing one falls through to 256."
  [[r g b]]
  (let [mx    (max r g b)
        mn    (min r g b)
        delta (- mx mn)
        ;; HSV value and saturation, 0–1.
        v     (/ mx 255)
        s     (if (zero? mx) 0 (/ delta mx))
        bright? (> v 0.6)]
    (if (< s 0.2)
      ;; Greyscale: three steps is all 16 colours offer.
      (cond (< v 0.25) 30                        ; black
            (< v 0.55) 90                        ; bright black (grey)
            (< v 0.85) 37                        ; white
            :else      97)                       ; bright white
      ;; Chromatic: six 60° sectors, bright or normal by value.
      (let [h (* 60 (cond
                      (= mx r) (mod (/ (- g b) delta) 6)
                      (= mx g) (+ 2 (/ (- b r) delta))
                      :else    (+ 4 (/ (- r g) delta))))
            h (if (neg? h) (+ h 360) h)
            ;; Boundaries are the midpoints between the six primaries (30, 90,
            ;; 150, …) EXCEPT yellow/green, which sits at 75 rather than 90.
            ;; Yellow is a narrow perceptual band: `#9ece6a`, an unmistakably
            ;; green "success" colour, computes to hue 88.8° and a midpoint
            ;; boundary would print it as yellow — the same shade as "warning",
            ;; which is the one pair that most needs to stay distinguishable.
            base (cond
                   (< h 30)  31                  ; red
                   (< h 75)  33                  ; yellow
                   (< h 150) 32                  ; green
                   (< h 210) 36                  ; cyan
                   (< h 270) 34                  ; blue
                   (< h 330) 35                  ; magenta
                   :else     31)]                ; back to red
        (if bright? (+ base 60) base)))))

(defn fg
  "Foreground SGR for a #rrggbb string at the terminal's depth; empty string
   under NO_COLOR, so callers can concatenate unconditionally."
  ([hex] (fg hex (color-depth)))
  ([hex depth]
   (if-let [[r g b] (hex->rgb hex)]
     (cond
       (= depth 0)  ""
       (< depth 256) (str ESC "[" (rgb->16 [r g b]) "m")
       (= depth 256) (str ESC "[38;5;" (rgb->256 [r g b]) "m")
       :else         (str ESC "[38;2;" r ";" g ";" b "m"))
     "")))

(defn- xterm-256->rgb
  "RGB of an xterm-256 palette index: 0-15 the basic colours (approximated),
   16-231 the 6x6x6 cube, 232-255 the grey ramp."
  [n]
  (cond
    (< n 16)  (let [base [[0 0 0] [205 0 0] [0 205 0] [205 205 0]
                          [0 0 238] [205 0 205] [0 205 205] [229 229 229]
                          [127 127 127] [255 0 0] [0 255 0] [255 255 0]
                          [92 92 255] [255 0 255] [0 255 255] [255 255 255]]]
                (nth base n))
    (< n 232) (let [i (- n 16)
                    s (fn [v] (if (zero? v) 0 (+ 55 (* 40 v))))]
                [(s (js/Math.floor (/ i 36)))
                 (s (mod (js/Math.floor (/ i 6)) 6))
                 (s (mod i 6))])
    :else     (let [v (+ 8 (* 10 (- n 232)))] [v v v])))

(defn downgrade-256
  "Rewrite xterm-256 SGRs (`38;5;N` foreground, `48;5;N` background) to the
   nearest of the 16 ANSI colours, for a terminal that cannot show 256.

   For output nyma does not colour itself — Bun.markdown.ansi emits 256-colour
   codes and has no depth option, where marked-terminal went through chalk,
   which downgraded."
  [s]
  (.replace (str s) (js/RegExp. (str ESC "\\[(38|48);5;(\\d{1,3})m") "g")
            (fn [_m kind n]
              (let [code (rgb->16 (xterm-256->rgb (js/parseInt n 10)))]
                (str ESC "[" (if (= kind "48") (+ code 10) code) "m")))))
