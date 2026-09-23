(ns agent.ui.tree-viewer
  "Session tree browser for `ctx.ui.custom()`."
  (:require [agent.ui.picker-frame :refer [truncate-to]]
            [agent.utils.ansi :refer [terminal-width string-width]]))

;; Pi-mono render object for interactive session tree browsing.
;; Used with ctx.ui.custom() via the CustomComponentAdapter in overlay.cljs.

(defn- has-children?
  "Check if a node has children (entries whose parent-id matches this node's id)."
  [tree id]
  (some #(= (:parent-id %) id) tree))

(defn- compute-visible
  "Walk tree entries, skip children of collapsed nodes.
   Returns vector of {:entry :depth} maps."
  [tree collapsed]
  (let [result (atom [])]
    (doseq [entry tree]
      (let [depth (or (:depth entry) 0)
            ;; Check if any ancestor is collapsed
            ancestors-collapsed
            (loop [pid (:parent-id entry)]
              (cond
                (nil? pid) false
                (contains? collapsed pid) true
                :else (let [parent (some #(when (= (:id %) pid) %) tree)]
                        (recur (:parent-id parent)))))]
        (when-not ancestors-collapsed
          (swap! result conj {:entry entry :depth depth}))))
    @result))

(defn- render-entry
  "Render a single tree entry as a text line."
  [entry depth selected? has-kids? collapsed? w]
  (let [indent  (apply str (repeat (* 2 depth) " "))
        fold    (if has-kids? (if collapsed? "▶ " "▼ ") "  ")
        cursor  (if selected? "> " "  ")
        role    (str "[" (:role entry) "] ")
        content (or (:content entry) "")
        prefix  (str cursor indent fold role)
        max-len (max 10 (- w (string-width prefix) 2))
        ;; COLUMNS, not characters. max-len is a column budget (it comes
        ;; from string-width), so comparing it against `count` mixes units:
        ;; a message of CJK or emoji rendered at two to four columns per
        ;; budgeted character and blew past `w`, which pi-tui kills the
        ;; session over.
        display (truncate-to content max-len)]
    (str prefix display)))

(defn create-tree-viewer
  "Create a pi-mono render object for interactive tree browsing.
   Returns an object with .render(w,h), .onInput(input, key), .dispose().
   onInput receives Ink's (input, key) pair — we branch on key properties
   (.-upArrow, .-return, .-escape) for consistency with the other pickers
   in the codebase and with CustomComponentAdapter's calling convention."
  [session]
  (let [tree      ((:get-tree session))
        selected  (atom 0)
        collapsed (atom #{})
        ;; Multi-step: Enter folds/unfolds rather than selecting-and-closing,
        ;; so the overlay host must not treat it as a dismissal. Escape still
        ;; closes (onInput returns {close: true}).
        component #js {:keepOpen true}]
    (set! (.-render component)
          (fn [w _h]
            (let [visible (compute-visible tree @collapsed)
                  cols    (or w 80)
                  ;; The header is a fixed 61 columns; below that terminal
                  ;; width it overflowed no matter what the entries held,
                  ;; which pi-tui turns into a dead session.
                  header  (truncate-to
                           "Session Tree (↑/↓ PgUp/PgDn Home/End, Enter fold/unfold, Esc close)"
                           cols)
                  lines   (into [header ""]
                                (map-indexed
                                 (fn [i {:keys [entry depth]}]
                                   (let [has-kids (has-children? tree (:id entry))
                                         is-collapsed (contains? @collapsed (:id entry))]
                                     (render-entry entry depth (= i @selected)
                                                   has-kids is-collapsed cols)))
                                 visible))]
              (.join (clj->js lines) "\n"))))

    (set! (.-onInput component)
          (fn [_input key]
            (let [visible (compute-visible tree @collapsed)
                  max-idx (max 0 (dec (count visible)))]
              (cond
                (.-upArrow key)   (do (swap! selected (fn [s] (max 0 (dec s)))) nil)
                (.-downArrow key) (do (swap! selected (fn [s] (min max-idx (inc s)))) nil)
                (.-pageUp key)    (do (swap! selected (fn [s] (max 0 (- s 10)))) nil)
                (.-pageDown key)  (do (swap! selected (fn [s] (min max-idx (+ s 10)))) nil)
                (.-home key)      (do (reset! selected 0) nil)
                (.-end key)       (do (reset! selected max-idx) nil)
                (.-return key)    (do (let [entry (:entry (nth visible @selected nil))]
                                        (when (and entry (has-children? tree (:id entry)))
                                          (swap! collapsed
                                                 (fn [c] (if (contains? c (:id entry))
                                                           (disj c (:id entry))
                                                           (conj c (:id entry)))))))
                                      nil)
                (.-escape key)    #js {:close true}
                :else             nil))))

    component))
