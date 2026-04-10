(ns agent.ui.key-hints
  "Helpers that format keybinding hint strings from a registry.
   Pure logic — callers render the returned strings with their own color/style."
  (:require [agent.keybinding-registry :as kbr]))

(defn format-hint
  "Format 'combo label' for a single action. If the action has no effective
   binding, returns '[?] label' so the label is still visible.

   Example: (format-hint reg \"app.history.search\" \"history\") → \"^R history\""
  [registry action-id label]
  (let [combo (kbr/get-binding registry action-id)]
    (if combo
      (str (kbr/format-key-combo combo) " " label)
      (str "[?] " label))))

(defn hint-row
  "Concatenate multiple hints into a single footer-row string.
   pairs is a seq of [action-id label]. Hints are separated by two spaces."
  [registry pairs]
  (->> pairs
       (map (fn [[action-id label]] (format-hint registry action-id label)))
       (interpose "  ")
       (apply str)))

(defn all-actions-grouped
  "Return actions from the registry grouped by category for display in the
   help overlay. Shape: [{:category :actions [{:id :description :binding}]}]
   Categories are emitted in a stable order."
  [registry]
  (let [actions (:actions registry)
        entries (for [[action-id info] actions]
                  {:id          action-id
                   :description (:description info)
                   :category    (:category info)
                   :binding     (kbr/get-binding registry action-id)})
        by-cat  (group-by :category entries)
        ;; Stable category ordering
        order   [:navigation :agent :editor :tools :session]]
    (vec
      (for [cat order
            :when (contains? by-cat cat)]
        {:category cat
         :actions  (vec (sort-by :id (get by-cat cat)))}))))
