(ns agent.ui.picker-input
  "Shared keyboard-dispatch for filter-picker-style components.

   Every picker we ship — mention_picker, skill_picker, model_picker,
   prompt_history — had its own 25-line `cond` reimplementing the
   same branch structure with small variations. Each copy was its own
   regression risk: the macOS backspace bug and the Ctrl+N/P gap both
   had to be fixed in four places.

   This module replaces those copies with a single `dispatch-input`
   function that handles the common branches (esc/enter/arrow nav/
   Ctrl+PN/backspace+delete/tab/single-char filter) and calls back
   into the picker via injected callbacks. Each picker reduces to
     - a `filter-text-atom`
     - a `selected-idx-atom`
     - a `filtered-fn` thunk
     - an `on-select` and `on-cancel`
   with no branch logic of its own.

   Branch structure borrowed from cc-kit's FuzzyPicker onInput at
   packages/ui/src/design-system/FuzzyPicker.tsx:84-138."
  (:require [agent.ui.picker-math :refer [safe-index step-up step-down]]))

(defn dispatch-input
  "Return a `(fn [input key])` suitable as the :onInput slot of a
   pi-mono picker component.

   Required callbacks:
     :filter-text-atom  — atom holding the current query string
     :selected-idx-atom — atom holding the current selection index
     :filtered-fn       — 0-arg fn returning the currently-visible vector
     :on-select         — 1-arg fn called with the chosen item on Enter
     :on-cancel         — 0-arg fn called on Escape

   Behaviour:
     Escape        → on-cancel
     Enter         → on-select (nth items safe-index) if list non-empty
     Up / Ctrl+P   → step-up selected-idx
     Down / Ctrl+N → step-down selected-idx against the current list
     Backspace /
       key.delete  → strip last char of filter-text, reset idx to 0
                     (key.delete is the macOS quirk — see
                      mention_picker comment from phase 'picker backspace')
     Tab           → nil (swallowed so a Tab press doesn't leak
                     through to the parent overlay)
     Single char   → append to filter-text, reset idx to 0
     Anything else → nil"
  [{:keys [filter-text-atom selected-idx-atom filtered-fn on-select on-cancel]}]
  (fn [input key]
    (cond
      (.-escape key)
      (on-cancel)

      (.-return key)
      (let [items (filtered-fn)
            idx   (safe-index @selected-idx-atom (count items))]
        (when (seq items)
          (on-select (nth items idx))))

      ;; Up / Ctrl+P — vi-style alternative to arrow keys
      (or (.-upArrow key) (and (.-ctrl key) (= input "p")))
      (swap! selected-idx-atom step-up)

      ;; Down / Ctrl+N
      (or (.-downArrow key) (and (.-ctrl key) (= input "n")))
      (let [items (filtered-fn)]
        (swap! selected-idx-atom step-down (count items)))

      ;; Backspace (macOS sends \u007f which ink reports as key.delete)
      (or (.-backspace key) (.-delete key))
      (do (swap! filter-text-atom
                 (fn [s] (if (empty? s) "" (subs s 0 (dec (count s))))))
          (reset! selected-idx-atom 0))

      ;; Tab is deliberately swallowed — prevents leaking to overlay.
      (.-tab key) nil

      ;; Printable character → grow filter, reset selection.
      (and input (= (count input) 1))
      (do (swap! filter-text-atom str input)
          (reset! selected-idx-atom 0)))))
