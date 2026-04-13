(ns agent.extensions.prompt-history
  "Persistent prompt history with Ctrl+R fuzzy search.
   Stores every submitted prompt in SQLite, provides search overlay."
  (:require [clojure.string :as str]
            [agent.utils.time :refer [relative-time]]
            [agent.ui.picker-frame :refer [render-frame overlay-max-width]]
            [agent.ui.picker-input :refer [dispatch-input]]))

(defn- truncate-line [text max-len]
  (let [one-line (first (str/split-lines (or text "")))]
    (if (> (count one-line) max-len)
      (str (subs one-line 0 (- max-len 1)) "…")
      one-line)))

(defn create-history-picker
  "Create a fuzzy-searchable history picker component.
   prompts: [{:text :timestamp :session-file}]
   on-resolve: called with text string or nil on cancel."
  [prompts on-resolve]
  (let [filter-text  (atom "")
        selected-idx (atom 0)
        max-visible  12]

    (letfn [(filtered []
              (if (empty? @filter-text)
                prompts
                (filterv #(str/includes?
                           (str/lower-case (or (:text %) ""))
                           (str/lower-case @filter-text))
                         prompts)))]

      #js {:render
           (fn [w _h]
             (render-frame
              {:title         "Search history (Ctrl+R, Enter to select, Esc to cancel)"
               :prompt-prefix "> "
               :filter-text   @filter-text
               :items         (filtered)
               :selected-idx  @selected-idx
               :max-visible   max-visible
               :max-width     (overlay-max-width w)
               :render-item   (fn [p _focused?]
                                (str (truncate-line (:text p) 60)
                                     "  (" (relative-time (:timestamp p)) ")"))
               :no-match-text "No matching prompts"}))

           :onInput
           (dispatch-input
            {:filter-text-atom  filter-text
             :selected-idx-atom selected-idx
             :filtered-fn       filtered
             :on-select         (fn [prompt] (on-resolve (:text prompt)))
             :on-cancel         (fn [] (on-resolve nil))})

           :dispose (fn [] nil)})))

(defn ^:export default [api]
  (let [handlers     (atom [])
        last-prompt  (atom nil) ;; dedup consecutive identical prompts

        ;; Capture submitted prompts
        on-submit
        (fn [data]
          (let [text (.-text data)]
            (when (and (string? text) (> (count (str/trim text)) 0)
                       (not= text @last-prompt))
              (reset! last-prompt text)
              ;; Insert into SQLite if storage available
              (when-let [store (.-__sqlite-store api)]
                ((:insert-prompt store) text nil)))))]

    (.on api "input_submit" on-submit)
    (swap! handlers conj ["input_submit" on-submit])

    ;; Ctrl+R shortcut — open history search
    (.registerShortcut api "ctrl+r"
                       (fn []
                         (when-let [store (.-__sqlite-store api)]
                           (let [prompts ((:recent-prompts store) 200)
                                 picker  (create-history-picker prompts
                                                                (fn [selected]
                                                                  (when selected
                              ;; Paste into editor via ui.setEditorValue (G6)
                                                                    (when (and (.-ui api) (.-setEditorValue (.-ui api)))
                                                                      (.setEditorValue (.-ui api) selected)))))]
                             (when (.-ui api)
                               (.custom (.-ui api) picker))))))

    ;; /history command — search prompts
    (.registerCommand api "history"
                      #js {:description "Search prompt history. Usage: /history [query]"
                           :handler
                           (fn [args ctx]
                             (if-let [store (.-__sqlite-store api)]
                               (let [query   (str/join " " args)
                                     results (if (empty? query)
                                               ((:recent-prompts store) 20)
                                               ((:search-prompts store) query 20))]
                                 (if (empty? results)
                                   (.notify (.-ui ctx) "No prompts found" "info")
                                   (.notify (.-ui ctx)
                                            (str "Recent prompts:\n"
                                                 (str/join "\n"
                                                           (map-indexed
                                                            (fn [i p]
                                                              (str "  " (inc i) ". " (truncate-line (:text p) 70)
                                                                   "  (" (relative-time (:timestamp p)) ")"))
                                                            results))))))
                               (.notify (.-ui ctx) "Prompt history requires SQLite storage" "error")))})

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterShortcut api "ctrl+r")
      (.unregisterCommand api "history"))))
