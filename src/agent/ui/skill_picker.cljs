(ns agent.ui.skill-picker
  "Fuzzy-searchable skill picker component for ui.custom()."
  (:require [clojure.string :as str]))

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
         (fn [_w _h]
           (let [filtered (filter-skills @filter-text skills)
                 total    (count filtered)
                 idx      (min @selected-idx (max 0 (dec total)))
                 visible  (min max-visible total)
                 ;; Scroll window centred on selection
                 scroll-start (max 0 (- idx (quot visible 2)))
                 scroll-end   (min total (+ scroll-start visible))
                 scroll-start (max 0 (- scroll-end visible))
                 window   (subvec (vec filtered) scroll-start scroll-end)]
             (str "Select skill (type to filter, Enter to activate, Esc to cancel)\n"
                  "> " @filter-text "\n"
                  (when (> scroll-start 0)
                    (str "  ... " scroll-start " more above\n"))
                  (str/join "\n"
                    (map-indexed
                      (fn [i s]
                        (let [abs-idx (+ scroll-start i)
                              prefix  (if (= abs-idx idx) "  \u25b6 " "    ")]
                          (str prefix (:name s)
                               (when (:active s) " \u2713")
                               (when (seq (:desc s)) (str "  \u2014 " (:desc s))))))
                      window))
                  (when (< scroll-end total)
                    (str "\n  ... " (- total scroll-end) " more below"))
                  (when (zero? total)
                    "\n  No matching skills"))))

         :onInput
         (fn [input key]
           (cond
             ;; Escape → cancel
             (.-escape key)
             (on-resolve nil)

             ;; Enter → activate selected
             (.-return key)
             (let [filtered (filter-skills @filter-text skills)
                   idx      (min @selected-idx (max 0 (dec (count filtered))))]
               (when (seq filtered)
                 (on-resolve (:name (nth filtered idx)))))

             ;; Up arrow / Ctrl+P
             (or (.-upArrow key) (and (.-ctrl key) (= input "p")))
             (swap! selected-idx #(max 0 (dec %)))

             ;; Down arrow / Ctrl+N
             (or (.-downArrow key) (and (.-ctrl key) (= input "n")))
             (let [filtered (filter-skills @filter-text skills)]
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
