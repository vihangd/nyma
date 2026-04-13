(ns agent.extensions.agent-shell.ui.model-picker
  "Fuzzy-searchable model picker component for ui.custom()."
  (:require [clojure.string :as str]
            [agent.ui.picker-frame :refer [render-frame overlay-max-width]]
            [agent.ui.picker-input :refer [dispatch-input]]))

(defn- fuzzy-match
  "Simple case-insensitive substring match on id + display."
  [query model]
  (let [q     (str/lower-case (or query ""))
        id    (str/lower-case (or (:id model) ""))
        disp  (str/lower-case (or (:display model) ""))]
    (or (empty? q)
        (str/includes? id q)
        (str/includes? disp q))))

(defn- filter-models [query models]
  (if (empty? query)
    models
    (filterv #(fuzzy-match query %) models)))

(defn create-picker
  "Create a pi-mono compatible {render, onInput, dispose} picker component.
   models: [{:id \"opus\" :display \"Claude Opus 4.6\"} ...]
   on-resolve: called with selected model id or nil on cancel."
  [models on-resolve]
  (let [filter-text  (atom "")
        selected-idx (atom 0)
        max-visible  15]
    #js {:render
         (fn [w h]
           (render-frame
            {:title         "Select model (type to filter, Enter to select, Esc to cancel)"
             :prompt-prefix "> "
             :filter-text   @filter-text
             :items         (filter-models @filter-text models)
             :selected-idx  @selected-idx
             :max-visible   (min max-visible (or h 20))
             :max-width     (overlay-max-width w)
             :render-item   (fn [m _focused?]
                              (str (:display m) "  (" (:id m) ")"))
             :no-match-text "No matching models"}))

         :onInput
         (dispatch-input
          {:filter-text-atom  filter-text
           :selected-idx-atom selected-idx
           :filtered-fn       (fn [] (filter-models @filter-text models))
           :on-select         (fn [model] (on-resolve (:id model)))
           :on-cancel         (fn [] (on-resolve nil))})

         :dispose (fn [] nil)}))
