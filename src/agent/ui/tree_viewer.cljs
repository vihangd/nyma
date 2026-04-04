(ns agent.ui.tree-viewer
  (:require [agent.utils.ansi :refer [terminal-width string-width]]))

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
        display (if (> (count content) max-len)
                  (str (subs content 0 max-len) "…")
                  content)]
    (str prefix display)))

(defn create-tree-viewer
  "Create a pi-mono render object for interactive tree browsing.
   Returns an object with .render(w,h), .onInput(key), .dispose()."
  [session]
  (let [tree      ((:get-tree session))
        selected  (atom 0)
        collapsed (atom #{})
        component #js {}]
    (set! (.-render component)
      (fn [w _h]
        (let [visible (compute-visible tree @collapsed)
              header  "Session Tree (up/down navigate, Enter fold/unfold, Esc close)"
              lines   (into [header ""]
                        (map-indexed
                          (fn [i {:keys [entry depth]}]
                            (let [has-kids (has-children? tree (:id entry))
                                  is-collapsed (contains? @collapsed (:id entry))]
                              (render-entry entry depth (= i @selected)
                                            has-kids is-collapsed (or w 80))))
                          visible))]
          (.join (clj->js lines) "\n"))))

    (set! (.-onInput component)
      (fn [key]
        (let [visible (compute-visible tree @collapsed)
              max-idx (max 0 (dec (count visible)))]
          (case key
            "up"     (do (swap! selected (fn [s] (max 0 (dec s)))) nil)
            "down"   (do (swap! selected (fn [s] (min max-idx (inc s)))) nil)
            "enter"  (do (let [entry (:entry (nth visible @selected nil))]
                           (when (and entry (has-children? tree (:id entry)))
                             (swap! collapsed
                               (fn [c] (if (contains? c (:id entry))
                                          (disj c (:id entry))
                                          (conj c (:id entry)))))))
                         nil)
            "escape" #js {:close true}
            nil))))

    component))
