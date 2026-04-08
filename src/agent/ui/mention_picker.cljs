(ns agent.ui.mention-picker
  "Fuzzy-searchable mention picker for @-mention system.
   Uses the same pi-mono {render, onInput, dispose} protocol as model_picker."
  (:require [clojure.string :as str]))

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
           (let [filtered (filter-items @filter-text items)
                 total    (count filtered)
                 idx      (min @selected-idx (max 0 (dec total)))
                 visible  (min max-visible (or h 20) total)
                 scroll-start (max 0 (- idx (quot visible 2)))
                 scroll-end   (min total (+ scroll-start visible))
                 scroll-start (max 0 (- scroll-end visible))
                 window   (subvec (vec filtered) scroll-start scroll-end)]
             (str "Select file (type to filter, Enter to select, Esc to cancel)\n"
                  "@ " @filter-text "\n"
                  (when (> scroll-start 0)
                    (str "  ... " scroll-start " more above\n"))
                  (str/join "\n"
                    (map-indexed
                      (fn [i item]
                        (let [abs-idx (+ scroll-start i)
                              prefix  (if (= abs-idx idx) "  \u25b6 " "    ")]
                          (if (:description item)
                            (str prefix (:label item) "  (" (:description item) ")")
                            (str prefix (:label item)))))
                      window))
                  (when (< scroll-end total)
                    (str "\n  ... " (- total scroll-end) " more below"))
                  (when (zero? total)
                    "\n  No matches"))))

         :onInput
         (fn [input key]
           (cond
             (.-escape key)
             (on-resolve nil)

             (.-return key)
             (let [filtered (filter-items @filter-text items)
                   idx      (min @selected-idx (max 0 (dec (count filtered))))]
               (when (seq filtered)
                 (on-resolve (nth filtered idx))))

             (or (.-upArrow key) (and (.-ctrl key) (= input "p")))
             (swap! selected-idx #(max 0 (dec %)))

             (or (.-downArrow key) (and (.-ctrl key) (= input "n")))
             (let [filtered (filter-items @filter-text items)]
               (swap! selected-idx #(min (dec (count filtered)) (inc %))))

             (.-backspace key)
             (do (swap! filter-text #(if (empty? %) "" (subs % 0 (dec (count %)))))
                 (reset! selected-idx 0))

             (.-tab key) nil

             (and input (= (count input) 1))
             (do (swap! filter-text str input)
                 (reset! selected-idx 0))))

         :dispose (fn [] nil)}))
