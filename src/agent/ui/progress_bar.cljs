(ns agent.ui.progress-bar
  "Unicode sub-cell progress bar. Borrowed from cc-kit's
   packages/ui/src/ProgressBar.tsx — same eight-slot sub-block
   character set, same clamp + remainder-cell arithmetic, rewritten
   for Squint + ink v6.

   Why this module exists:
     `agent_shell/ui/status_line.cljs` has a private `progress-bar`
     helper that only draws full blocks (`█` / `░`) — no sub-cell
     resolution. We'd also like a reusable component for streaming
     token counters, download progress, and anything else that wants
     a smooth bar without flicker. Rather than duplicate the math,
     there's one blessed renderer.

   Two entry points:
     `render-string` — pure function, returns the bar glyphs as a
                       plain string. Safe in status line contexts
                       that compose strings, not JSX.
     `ProgressBar`   — JSX component (Text) with optional fill/empty
                       colors. Use inside ink trees."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Text]]))

;;; ─── Glyphs ────────────────────────────────────────────

(def ^:private blocks
  "Sub-cell block glyphs from empty (index 0) to full (index 8).
   Borrowed verbatim from cc-kit's ProgressBar.tsx:11."
  [" " "\u258f" "\u258e" "\u258d" "\u258c" "\u258b" "\u258a" "\u2589" "\u2588"])

(def ^:private full-block (nth blocks 8))
(def ^:private empty-block (nth blocks 0))

;;; ─── Pure rendering ────────────────────────────────────

(defn- clamp-ratio [r]
  (cond
    (or (nil? r) (js/Number.isNaN r)) 0
    (< r 0) 0
    (> r 1) 1
    :else r))

(defn render-string
  "Render a progress bar as a plain string of `width` cells, with
   `ratio` (0–1) filled. Clamps out-of-range ratios (including nil
   and NaN → 0, >1 → 1). Returns the empty string when `width` is
   nil, zero, or negative.

   The last partially-filled cell uses one of 8 sub-block glyphs so
   the bar advances smoothly as the ratio grows — a 0.73 ratio on a
   10-cell bar shows 7 full blocks, a ~⅗ block, and 2 empty cells.
   This is the same math as cc-kit's ProgressBar.tsx:19-31."
  [ratio width]
  (let [w (or width 0)]
    (if (or (not (number? w)) (<= w 0))
      ""
      (let [r      (clamp-ratio ratio)
            w      (js/Math.floor w)
            whole  (js/Math.floor (* r w))
            head   (.repeat full-block whole)]
        (if (>= whole w)
          head
          (let [remainder (- (* r w) whole)
                mid-idx   (js/Math.min (dec (count blocks))
                                       (js/Math.floor (* remainder (count blocks))))
                mid       (nth blocks mid-idx)
                empty     (js/Math.max 0 (- w whole 1))
                tail      (.repeat empty-block empty)]
            (str head mid tail)))))))

;;; ─── JSX wrapper ───────────────────────────────────────

(defn ProgressBar
  "Ink component wrapper. Props:
     :ratio        current progress, 0–1 (clamped)
     :width        total cells the bar occupies
     :fillColor    foreground color for the filled segment
     :emptyColor   background color for the empty segment"
  [{:keys [ratio width fillColor emptyColor]}]
  #jsx [Text {:color           fillColor
              :backgroundColor emptyColor}
        (render-string ratio width)])
