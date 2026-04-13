(ns agent.ui.mention-picker
  "Fuzzy-searchable mention picker for @-mention system.
   Uses the same pi-mono {render, onInput, dispose} protocol as model_picker."
  (:require [clojure.string :as str]
            [agent.ui.picker-frame :refer [render-frame overlay-max-width]]
            [agent.ui.picker-input :refer [dispatch-input]]))

(defn- fuzzy-match
  "Simple case-insensitive substring match on label + value."
  [query item]
  (let [q   (str/lower-case (or query ""))
        lbl (str/lower-case (or (:label item) ""))
        val (str/lower-case (or (:value item) ""))]
    (or (empty? q)
        (str/includes? lbl q)
        (str/includes? val q))))

(defn- filter-items [query items]
  (if (empty? query)
    items
    (filterv #(fuzzy-match query %) items)))

(defn create-picker
  "Create a pi-mono compatible picker for mention results.
   items: [{:label \"README.md\" :value \"README.md\" :description \"root\"} ...]
   initial-query: initial filter text (text after @)
   on-resolve: called with selected item map or nil on cancel."
  [items initial-query on-resolve]
  (let [filter-text  (atom (or initial-query ""))
        selected-idx (atom 0)
        max-visible  15]
    #js {:render
         (fn [w h]
           (render-frame
            {:title         "Select file (type to filter, Enter to select, Esc to cancel)"
             :prompt-prefix "@ "
             :filter-text   @filter-text
             :items         (filter-items @filter-text items)
             :selected-idx  @selected-idx
             :max-visible   (min max-visible (or h 20))
             :max-width     (overlay-max-width w)
             :render-item   (fn [item _focused?]
                              (if (:description item)
                                (str (:label item) "  (" (:description item) ")")
                                (str (:label item))))
             :no-match-text "No matches"}))

         :onInput
         (dispatch-input
          {:filter-text-atom  filter-text
           :selected-idx-atom selected-idx
           :filtered-fn       (fn [] (filter-items @filter-text items))
           :on-select         on-resolve
           :on-cancel         (fn [] (on-resolve nil))})

         :dispose (fn [] nil)}))
