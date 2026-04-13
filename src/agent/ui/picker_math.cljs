(ns agent.ui.picker-math
  "Pure boundary math for pickers. Every picker we ship reimplemented the
   same three pieces of logic — step-the-index, clamp-at-render-time,
   window-the-visible-slice — with slightly different bugs in each copy.
   This module centralises them.

   Borrowed from cc-kit:
     - packages/ink-renderer/src/layout/geometry.ts:83-87   (`clamp`)
     - packages/ui/src/design-system/FuzzyPicker.tsx:77-82  (`step`)
     - packages/ui/src/design-system/FuzzyPicker.tsx:145-147 (`setFocusedIndex(clamp(...))`)

   We keep nyma's centered-window scrolling strategy rather than
   cc-kit's 'scroll only when focus hits edge' — the existing pickers
   rely on it and changing it would be a UX shift, not a bug fix.")

;;; ─── clamp ──────────────────────────────────────────────

(defn clamp
  "Clamp `v` into `[lo, hi]`. Both bounds are optional — pass `nil`
   (or omit `hi`) for 'unbounded on that side'. Returns `v` unchanged
   when already in range. Mirrors cc-kit's clamp in geometry.ts:83."
  ([v lo] (clamp v lo nil))
  ([v lo hi]
   (cond
     (and (some? lo) (< v lo)) lo
     (and (some? hi) (> v hi)) hi
     :else v)))

;;; ─── Index stepping (navigation) ────────────────────────

(defn step-up
  "Move a picker index up one row. Clamped at 0. Safe on empty lists
   and on an already-at-zero index — always returns a non-negative int.

   Replaces the `(max 0 (dec %))` incantation scattered across every
   picker's upArrow/Ctrl+P branch."
  [idx]
  (clamp (dec (or idx 0)) 0 nil))

(defn step-down
  "Move a picker index down one row. Clamped at `(max 0 (dec count))`
   — which is 0 for an empty list, not -1. The old code did
   `(min (dec count) (inc %))` which returns -1 when count is 0,
   quietly poisoning later `nth` calls. step-down short-circuits
   that case.

   Replaces the `(min (dec (count items)) (inc %))` incantation in
   every picker's downArrow/Ctrl+N branch."
  [idx count]
  (if (pos? count)
    (clamp (inc (or idx 0)) 0 (dec count))
    0))

;;; ─── Defensive clamp at render time ─────────────────────

(defn safe-index
  "Return `idx` clamped into `[0, count-1]`. Used at render time to
   guard against a stored index that's gone stale because the filtered
   list shrank. For empty lists, always returns 0 (the caller decides
   whether to render anything at all).

   Replaces the `(min @selected-idx (max 0 (dec total)))` pattern."
  [idx count]
  (if (pos? count)
    (clamp (or idx 0) 0 (dec count))
    0))

;;; ─── Scroll window for a centered view ─────────────────

(defn window-centered
  "Compute the visible slice `[start end)` of a list of `total` items
   showing at most `visible` items, with the focused `idx` centered in
   the window where possible.

   Returns `{:start :end :visible-count}`. `:visible-count` is
   `(min visible total)` — smaller than the request if the list is
   shorter than the window. `:start` and `:end` satisfy
   `(- end start) = :visible-count` except when `total` is 0 (both 0).

   Useful when the picker shows many items at once and the user wants
   to see context around the focused item. Downside: when the window
   is larger than twice the current focus, early arrow-downs don't
   visibly scroll — the focus moves but the window stays put."
  [idx visible total]
  (if (or (zero? (or total 0)) (zero? (or visible 0)))
    {:start 0 :end 0 :visible-count 0}
    (let [v       (min visible total)
          i       (safe-index idx total)
          half    (quot v 2)
          raw     (max 0 (- i half))
          end     (min total (+ raw v))
          ;; Re-align start so the window stays full at the bottom
          start   (max 0 (- end v))]
      {:start         start
       :end           end
       :visible-count v})))

(defn window-trailing
  "Compute the visible slice `[start end)` keeping the focused `idx`
   near the BOTTOM of the window, not the centre. Borrowed from
   cc-kit's FuzzyPicker strategy
   (packages/ui/src/design-system/FuzzyPicker.tsx:154):

     windowStart = clamp(idx - visible + 1, 0, total - visible)

   Why this is better for discovery-style pickers: every arrow-down
   past the last visible row immediately scrolls, so the user sees
   new content as soon as they leave the initial view. The centered
   strategy hides scrolling behaviour until idx passes visible/2,
   which feels like 'the list is stuck' for the first few presses.

   Returns the same `{:start :end :visible-count}` shape as
   window-centered so callers can swap them freely."
  [idx visible total]
  (if (or (zero? (or total 0)) (zero? (or visible 0)))
    {:start 0 :end 0 :visible-count 0}
    (let [v     (min visible total)
          i     (safe-index idx total)
          ;; Keep focus at the bottom of the window: start is idx -
          ;; (visible-1), clamped into [0, total - visible].
          raw   (- i (dec v))
          max-s (max 0 (- total v))
          start (clamp raw 0 max-s)
          end   (+ start v)]
      {:start         start
       :end           end
       :visible-count v})))
