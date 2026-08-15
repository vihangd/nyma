(ns agent.extensions.todos.shared
  "Pure helpers for the todo-ledger extension. Per SOTA (HIPIF), completed items
   are COLLAPSED in the injected view to cut long-context interference — open
   items shown in full, done ones as a single count."
  (:require [clojure.string :as str]))

(defn normalize-status [s]
  (let [s (str/lower-case (str (or s "pending")))]
    (cond
      (or (= s "completed") (= s "done") (= s "complete")) :done
      (or (= s "in_progress") (= s "in-progress") (= s "doing") (= s "active")) :in-progress
      :else :pending)))

(defn parse-todos
  "Normalize a JS array of {content, status} into [{:content :status}]."
  [js-arr]
  (->> (into [] (or js-arr []))
       (keep (fn [t]
               (let [c (str/trim (str (.-content t)))]
                 (when (seq c)
                   {:content c :status (normalize-status (.-status t))}))))
       vec))

(defn counts [todos]
  {:open  (count (remove #(= (:status %) :done) todos))
   :total (count todos)})

(defn- marker [status]
  (case status :done "x" :in-progress "~" " "))

(defn render-ledger
  "Render the ledger for injection: open/in-progress items in full, completed
   collapsed to a count. Returns nil when empty."
  [todos]
  (when (seq todos)
    (let [live (remove #(= (:status %) :done) todos)
          done (count (filter #(= (:status %) :done) todos))]
      (str "## Todo list\n"
           (str/join "\n" (map (fn [t] (str "- [" (marker (:status t)) "] " (:content t))) live))
           (when (pos? done)
             (str (when (seq live) "\n") "_(" done " completed)_"))
           ;; The list alone does not keep itself current. Across 26 real
           ;; sessions only 4 ever called todo_write, and the longest went 634
           ;; lines past its final write — the status counter was frozen for the
           ;; back half of the session because nothing wrote to it. The only
           ;; standing instruction to update lived in the tool description,
           ;; which the model sees once at tool-listing time rather than per
           ;; turn. This costs one line and no tool call, which matters for
           ;; small models where an invalid call is expensive.
           (when (seq live)
             "\nKeep this current: mark items completed as you finish them, and keep exactly one in_progress.")))))
