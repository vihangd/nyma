(ns agent.ui.picker-frame
  "Shared string-rendering for filter-picker components.

   Each picker we ship renders the same visual frame — title line,
   prompt-prefixed filter text with a live (idx/total) counter, and
   a windowed list of items with a `▶` focus pointer on the selected
   row. When the visible window is clipped above/below, the first or
   last visible row shows a subtle `↑` / `↓` scroll indicator in the
   same 1-char slot as the focus pointer (borrowed from cc-kit's
   ListItem pattern at packages/ui/src/design-system/ListItem.tsx:
   109-128). Focus always wins over a scroll hint on the same row.

   Uses the TRAILING window strategy (cc-kit's FuzzyPicker.tsx:154
   — `windowStart = focusedIndex - visibleCount + 1`) so every
   arrow-down past the last visible row immediately scrolls. The
   older centred strategy hides scrolling for the first ~half of
   the visible count and makes the list feel stuck."
  (:require ["@mariozechner/pi-tui" :refer [visibleWidth truncateToWidth]]
            [clojure.string :as str]
            [agent.ui.picker-math :refer [safe-index window-trailing]]))

;; Widths here are DISPLAY COLUMNS, not characters. `count` was used
;; throughout, which is the same number only for ASCII: one CJK glyph is two
;; columns and an emoji can be four, so a row padded to 20 "chars" of 漢 is 40
;; columns — and pi-tui kills the session over any line wider than the
;; terminal. Measured before this: a picker with CJK/emoji items emitted 81
;; columns at width 80, at every width tested.
(defn- width-of [s] (visibleWidth (str s)))

(def ^:private focus-prefix "  \u25b6 ")
(def ^:private blank-prefix "    ")
(def ^:private scroll-up-prefix "  \u2191 ")
(def ^:private scroll-dn-prefix "  \u2193 ")

(defn- pad-to
  "Right-pad `line` with spaces until it's `w` display COLUMNS wide. Lines longer
   than `w` are returned unchanged — caller should have truncated them
   already if that matters."
  [line w]
  (let [n (width-of line)]
    (if (>= n w)
      line
      (str line (.repeat " " (- w n))))))

(defn truncate-to
  "Truncate `line` to at most `w` display COLUMNS, appending a single-char
   ellipsis when truncation actually happens. Returns the line unchanged when
   it already fits. Guards against nil / non-positive widths by returning the
   input as-is.

   Columns, not characters: one CJK glyph is two columns, so the previous
   `count`-based version let a 20-column budget emit 40 actual columns."
  [line w]
  (cond
    (or (nil? line) (nil? w) (not (pos? w))) line
    (<= (width-of line) w) line
    ;; Truncate to (w-1) COLUMNS with pi-tui's indicator suppressed, then add
    ;; our own. Letting pi-tui append it instead leaves a reset escape AFTER
    ;; the ellipsis, so the row no longer ends in the character callers (and
    ;; tests) look for.
    :else (str (truncateToWidth (str line) (max 0 (dec w)) "" false) "…")))

(defn truncate-tail
  "Truncate to `w` display COLUMNS keeping the END of the string, with a
   leading `…`.

   For identifiers the tail is the distinguishing part: head-truncating
   `openrouter/nvidia/nemotron-3-super-120b-a12b:free` yields
   `openrouter/nvidia/nemotron-3-…`, which is indistinguishable from its
   siblings, whereas the tail keeps `…nemotron-3-super-120b-a12b:free`."
  [s w]
  (let [s (str s)]
    (cond
      (or (nil? w) (not (pos? w))) s
      (<= (width-of s) w) s
      (= w 1) "…"
      ;; Column-accurate tail: drop leading glyphs until the remainder fits
      ;; in (w-1) columns. Character arithmetic would overshoot on wide
      ;; glyphs, which is the bug this file had throughout.
      ;;
      ;; Steps by CODE POINT (Array.from), not code unit: `subs` can stop
      ;; between the halves of an emoji's surrogate pair and emit a lone
      ;; surrogate, which was measurable at w=10/20/30. Width stays correct
      ;; either way, so this is legibility, not crash-safety. A ZWJ sequence
      ;; can still be cut at a joint.
      :else (let [budget (dec w)
                  glyphs (js/Array.from s)
                  n      (.-length glyphs)]
              (loop [i 0]
                (let [tail (.join (.slice glyphs i) "")]
                  (if (or (>= i n) (<= (width-of tail) budget))
                    (str "…" tail)
                    (recur (inc i)))))))))

(defn two-col-row
  "Lay out `left` and `right` on one row exactly `width` wide: `left` padded
   out, `right` flush to the end, at least one space between.

   Without per-field budgets a single `left + \"  — \" + right` string gets
   truncated as a unit, so the right-hand metadata is always the part that
   disappears — which is the information the row exists to convey."
  [left right width]
  (let [width (max 1 (or width 1))
        right (str right)
        gap   2
        ;; Metadata is short and fixed-shape, so it may take up to half the row
        ;; — but only while the identifier keeps a legible budget. On a narrow
        ;; terminal both cannot fit, and knowing WHICH model a row is beats
        ;; knowing its price, so the metadata is what gets dropped.
        left-budget (- width (width-of right) gap)
        show-right? (and (seq right)
                         (<= (width-of right) (js/Math.floor (/ width 2)))
                         (>= left-budget 24))
        right   (if show-right? right "")
        gap     (if show-right? gap 0)
        left-w  (max 1 (- width (width-of right) gap))
        left    (truncate-tail left left-w)]
    (if (seq right)
      (str (pad-to left left-w) (.repeat " " gap) right)
      (pad-to left width))))

(defn pad-lines
  "Pad every line in a multi-line string to the width of the widest
   line. Used by the overlay so the picker frame is a solid rectangle
   when the surrounding Text is given a backgroundColor — otherwise
   the chat view underneath leaks through the ragged right edge."
  [s]
  (let [lines (.split (or s "") "\n")
        width (reduce (fn [w l] (max w (width-of l))) 0 lines)]
    (->> lines
         (map (fn [l] (pad-to l width)))
         (str/join "\n"))))

(defn overlay-max-width
  "Compute the max character width a picker row should render at,
   given the terminal's column count. Accounts for the Overlay Box's
   60% width constraint (`:width \"60%\"` in overlay.cljs) plus its
   border (1 char each side) + paddingX of 2 (total 6 chars of
   chrome). Clamped to a minimum of 30 so narrow terminals still
   produce a readable picker rather than a sliver.

   This is the cap callers pass as `:max-width` to `render-frame`.
   Change here if Overlay ever switches away from 60%."
  [terminal-cols]
  (let [cols (or terminal-cols 80)
        sixty (js/Math.floor (* cols 0.6))
        ;; Subtract border (2) + paddingX (4 — 2 each side) = 6.
        avail (- sixty 6)]
    (max 30 avail)))

(defn fit-lines
  "Truncate every line to at most `w` display COLUMNS, then right-pad any
   shorter lines back up to `w`, so every returned line is exactly `w`
   columns \u2014 truncation may stop a column short when a wide glyph
   straddles the boundary, and the padding makes that up.

   This is the overlay's fix for long rows: without the truncation
   step, a single long line (e.g. `/agent-shell__agent` plus a huge
   description) forces `pad-lines` to pad every other row up to the
   long line's width. Those now-wide rows overflow the overlay's
   60% width constraint, ink wraps them, the picker doubles in
   height, and chat content leaks through the gaps. `fit-lines`
   clamps each row so no row ever exceeds the overlay box, so no
   wrapping happens at all."
  [s w]
  (if (or (nil? w) (not (pos? w)))
    (pad-lines s)
    (->> (.split (or s "") "\n")
         (map (fn [l] (pad-to (truncate-to l w) w)))
         (str/join "\n"))))

(defn- row-prefix
  "Pick the 1-char indicator slot for a row.
     - focused items ALWAYS show the `▶` pointer (focus wins over
       scroll hints on the same row)
     - the first visible row shows `↑` when there are items above
     - the last visible row shows `↓` when there are items below
     - everything else gets a blank space
   Mirrors cc-kit's ListItem indicator-selection at
   packages/ui/src/design-system/ListItem.tsx:109-128."
  [focused? first-visible? last-visible? clipped-above? clipped-below?]
  (cond
    focused?                          focus-prefix
    (and first-visible? clipped-above?) scroll-up-prefix
    (and last-visible?  clipped-below?) scroll-dn-prefix
    :else                             blank-prefix))

(defn render-frame
  "Render a filter picker to a single string.

   Options map:
     :title         Header line shown above the filter input.
     :prompt-prefix String prepended to the filter text (e.g. \"@ \"
                    for mentions, \"> \" for most others).
     :filter-text   Current query the user has typed.
     :items         The filtered list to render.
     :selected-idx  Current selection index (will be clamp-safed).
     :max-visible   Max rows to show. The caller may cap on terminal
                    rows by passing (min max-visible rows) if desired.
     :render-item   (fn [item focused?]) → string for one row.
                    Must NOT include the focus prefix — render-frame
                    prepends it for you.
     :no-match-text String shown when items is empty (e.g. \"No matches\").

     :max-width     OPTIONAL. Cap every row at this many visible
                    characters — longer rows are truncated with `…`
                    and shorter rows are padded with spaces to the
                    same width. Prevents overflow-wrapping when a
                    single row (e.g. a command with a long
                    description) would otherwise exceed the overlay
                    box and push every other row into a second
                    visual line. When omitted, falls back to
                    pad-to-widest-line behaviour (and may cause
                    the overflow-wrap bug on long content).

   Returns the assembled string. Uses the trailing-window strategy
   and inline scroll arrows so users can tell the list is scrolling.
   Shows an (idx/total) counter on the prompt line when the list is
   non-empty so users know the scale at a glance."
  [{:keys [title prompt-prefix filter-text items selected-idx
           max-visible render-item no-match-text max-width]}]
  (let [total       (count items)
        idx         (safe-index selected-idx total)
        {:keys [start end]} (window-trailing idx (or max-visible 12) total)
        window      (subvec (vec items) start end)
        clipped-above? (pos? start)
        clipped-below? (< end total)
        counter     (when (pos? total)
                      (str " (" (inc idx) "/" total ")"))
        header      (str title "\n"
                         (or prompt-prefix "> ") (or filter-text "")
                         (or counter "") "\n")
        last-i      (dec (count window))
        rows        (str/join "\n"
                              (map-indexed
                               (fn [i item]
                                 (let [abs (+ start i)
                                       focused? (= abs idx)
                                       prefix   (row-prefix focused?
                                                            (zero? i)
                                                            (= i last-i)
                                                            clipped-above?
                                                            clipped-below?)]
                                   (str prefix (render-item item focused?))))
                               window))
        empty-msg   (when (zero? total)
                      (str "\n  " (or no-match-text "No matches")))
        raw         (str header rows empty-msg)]
    ;; Truncate then pad so every row is exactly `max-width` wide
    ;; (when the caller supplied a cap). Without the truncation step,
    ;; a single long row would push pad-lines's widest-line target
    ;; beyond the overlay's 60% width, and ink would wrap every
    ;; padded row onto a second visual line — making the picker look
    ;; twice as tall as it should.
    (if max-width
      (fit-lines raw max-width)
      (pad-lines raw))))
