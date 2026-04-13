(ns agent.ui.skill-picker
  "Fuzzy-searchable skill picker component for ui.custom()."
  (:require [clojure.string :as str]
            [agent.ui.picker-frame :refer [render-frame overlay-max-width]]
            [agent.ui.picker-input :refer [dispatch-input]]))

(defn- filter-skills [query skills]
  (if (empty? query)
    skills
    (filterv #(str/includes?
               (str/lower-case (or (:name %) ""))
               (str/lower-case query))
             skills)))

(defn create-picker
  "Create a pi-mono compatible {render, onInput, dispose} picker component.
   skills: [{:name \"git\" :desc \"first line of SKILL.md\" :active bool}]
   on-resolve: called with skill name string or nil on cancel."
  [skills on-resolve]
  (let [filter-text  (atom "")
        selected-idx (atom 0)
        max-visible  12]
    #js {:render
         (fn [w _h]
           (render-frame
            {:title         "Select skill (type to filter, Enter to activate, Esc to cancel)"
             :prompt-prefix "> "
             :filter-text   @filter-text
             :items         (filter-skills @filter-text skills)
             :selected-idx  @selected-idx
             :max-visible   max-visible
             :max-width     (overlay-max-width w)
             :render-item   (fn [s _focused?]
                              (str (:name s)
                                   (when (:active s) " \u2713")
                                   (when (seq (:desc s)) (str "  \u2014 " (:desc s)))))
             :no-match-text "No matching skills"}))

         :onInput
         (dispatch-input
          {:filter-text-atom  filter-text
           :selected-idx-atom selected-idx
           :filtered-fn       (fn [] (filter-skills @filter-text skills))
           :on-select         (fn [skill] (on-resolve (:name skill)))
           :on-cancel         (fn [] (on-resolve nil))})

         :dispose (fn [] nil)}))
