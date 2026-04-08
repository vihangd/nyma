(ns agent.extensions.agent-shell.ui.model-picker
  "Fuzzy-searchable model picker component for ui.custom()."
  (:require [clojure.string :as str]))

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
           (let [filtered (filter-models @filter-text models)
                 total    (count filtered)
                 idx      (min @selected-idx (max 0 (dec total)))
                 visible  (min max-visible (or h 20) total)
                 ;; Scroll window
                 scroll-start (max 0 (- idx (quot visible 2)))
                 scroll-end   (min total (+ scroll-start visible))
                 scroll-start (max 0 (- scroll-end visible))
                 window   (subvec (vec filtered) scroll-start scroll-end)]
             (str "Select model (type to filter, Enter to select, Esc to cancel)\n"
                  "> " @filter-text "\n"
                  (when (> scroll-start 0)
                    (str "  ... " scroll-start " more above\n"))
                  (str/join "\n"
                    (map-indexed
                      (fn [i m]
                        (let [abs-idx (+ scroll-start i)]
                          (if (= abs-idx idx)
                            (str "  \u25b6 " (:display m) "  (" (:id m) ")")
                            (str "    " (:display m) "  (" (:id m) ")"))))
                      window))
                  (when (< scroll-end total)
                    (str "\n  ... " (- total scroll-end) " more below"))
                  (when (zero? total)
                    "\n  No matching models"))))

         :onInput
         (fn [input key]
           (cond
             ;; Escape → cancel
             (.-escape key)
             (on-resolve nil)

             ;; Enter → select
             (.-return key)
             (let [filtered (filter-models @filter-text models)
                   idx      (min @selected-idx (max 0 (dec (count filtered))))]
               (when (seq filtered)
                 (on-resolve (:id (nth filtered idx)))))

             ;; Up arrow / Ctrl+P
             (or (.-upArrow key) (and (.-ctrl key) (= input "p")))
             (swap! selected-idx #(max 0 (dec %)))

             ;; Down arrow / Ctrl+N
             (or (.-downArrow key) (and (.-ctrl key) (= input "n")))
             (let [filtered (filter-models @filter-text models)]
               (swap! selected-idx #(min (dec (count filtered)) (inc %))))

             ;; Backspace
             (.-backspace key)
             (do (swap! filter-text #(if (empty? %) "" (subs % 0 (dec (count %)))))
                 (reset! selected-idx 0))

             ;; Tab → skip
             (.-tab key) nil

             ;; Character input → filter
             (and input (= (count input) 1))
             (do (swap! filter-text str input)
                 (reset! selected-idx 0))))

         :dispose (fn [] nil)}))
