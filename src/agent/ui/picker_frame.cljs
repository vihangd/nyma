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
  (:require [clojure.string :as str]
            [agent.ui.picker-math :refer [safe-index window-trailing]]))

(def ^:private focus-prefix "  \u25b6 ")
(def ^:private blank-prefix "    ")
(def ^:private scroll-up-prefix "  \u2191 ")
(def ^:private scroll-dn-prefix "  \u2193 ")

(defn- pad-to
  "Right-pad `line` with spaces until it's `w` chars wide. Lines longer
   than `w` are returned unchanged — caller should have truncated them
   already if that matters."
  [line w]
  (let [n (count line)]
    (if (>= n w)
      line
      (str line (.repeat " " (- w n))))))

(defn truncate-to
  "Truncate `line` to at most `w` visible characters, appending a
   single-char ellipsis `…` when truncation actually happens.
   Returns the line unchanged when it already fits. Guards against
   nil / non-positive widths by returning the input as-is."
  [line w]
  (cond
    (or (nil? line) (nil? w) (not (pos? w))) line
    (<= (count line) w) line
    ;; Reserve one char for the ellipsis so the total is exactly w.
    :else (str (subs line 0 (max 0 (dec w))) "\u2026")))

(defn pad-lines
  "Pad every line in a multi-line string to the width of the widest
   line. Used by the overlay so the picker frame is a solid rectangle
   when the surrounding Text is given a backgroundColor — otherwise
   the chat view underneath leaks through the ragged right edge."
  [s]
  (let [lines (.split (or s "") "\n")
        width (reduce (fn [w l] (max w (count l))) 0 lines)]
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
  "Truncate every line to at most `w` characters, then right-pad any
   shorter lines back up to exactly `w`. Every returned line is
   guaranteed to be exactly `w` visible characters.

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
