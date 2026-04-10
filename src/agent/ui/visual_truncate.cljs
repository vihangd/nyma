(ns agent.ui.visual-truncate
  "Helpers for truncating a sequence of output lines by *visual* row count,
   accounting for long lines that wrap across multiple terminal rows.

   This lets a streaming tool renderer show 'the last N terminal rows of
   output' rather than 'the last N logical lines', which would overflow
   when a single logical line wraps several times."
  (:require [agent.utils.ansi :refer [string-width]]))

(defn count-visual-rows
  "Return the number of terminal rows a single logical line occupies at
   the given terminal width. An empty line still takes one row. Zero or
   negative term-width collapses to 1."
  [line term-width]
  (let [w (max 1 (or term-width 80))
        len (string-width (or line ""))]
    (max 1 (js/Math.ceil (/ len w)))))

(defn truncate-to-visual-lines
  "Return the tail of `lines` that fits within `max-rows` visual rows at
   the given terminal width. Shape of the return value:
     {:visible-lines vector   — the rows to display
      :hidden-count   int     — how many logical lines were dropped}

   Lines are selected from the end (oldest dropped first) so the newest
   output is always shown — matching what a user expects from a live
   streaming view."
  [lines max-rows term-width]
  (let [v    (vec lines)
        cap  (max 1 (or max-rows 1))
        w    (max 1 (or term-width 80))]
    (loop [i (dec (count v))
           acc ()
           rows 0]
      (cond
        (neg? i)
        {:visible-lines (vec acc)
         :hidden-count  0}

        :else
        (let [line      (get v i)
              row-cost  (count-visual-rows line w)
              new-rows  (+ rows row-cost)]
          (if (<= new-rows cap)
            (recur (dec i) (cons line acc) new-rows)
            {:visible-lines (vec acc)
             :hidden-count  (inc i)}))))))
