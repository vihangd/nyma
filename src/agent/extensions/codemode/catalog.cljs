(ns agent.extensions.codemode.catalog
  "The functions a script can call, as the model reads them. Pure.

   One line per tool — `(github/list-issues {repo: str, state?: \"open\"|\"closed\"}) — List issues`
   — with real parameter names (`agent.schema/signature`), inside a budget.
   The budget is filled round-robin across namespaces, shortest line first, so
   one server with 40 tools cannot crowd out the other four; every namespace is
   always named with how many of its tools are shown."
  (:require [clojure.string :as str]
            [agent.schema :as schema]
            [agent.extensions.codemode.analyze :as an]))

(def default-budget-chars
  "About 2k tokens — opencode's inline catalog budget."
  8000)

(defn- one-line [s n]
  (let [t (first (str/split-lines (str s)))]
    (if (> (count t) n) (str (subs t 0 (dec n)) "…") t)))

(defn line
  "`(ns/fn {sig}) → ret — description` for one entry
   `{:name :description :schema :output :observed?}`. `→ ~{…}` is a shape seen
   in an earlier result, not one the tool declared."
  [{:keys [name description schema output observed?]}]
  (str "(" (an/display-name name) " " (if schema (schema/signature schema) "{}") ")"
       (when output (str " → " (when observed? "~") (schema/signature output)))
       (when (seq (str description)) (str " — " (one-line description 90)))))

(defn- round-robin
  "Interleave groups: first of each, then second of each, …"
  [groups]
  (loop [gs (vec (remove empty? groups)) out []]
    (if (empty? gs)
      out
      (recur (vec (remove empty? (map rest gs)))
             (into out (map first gs))))))

(defn render
  "Catalog text for `entries` within `budget` chars."
  [entries budget]
  (let [by-ns   (group-by (fn [e] (first (an/script-name (:name e)))) entries)
        groups  (map (fn [[_ es]] (sort-by count (map line es))) (sort-by first by-ns))
        picked  (loop [ls (round-robin groups) used 0 acc #{}]
                  (if-let [l (first ls)]
                    (if (> (+ used (count l) 1) budget)
                      (recur (rest ls) used acc)
                      (recur (rest ls) (+ used (count l) 1) (conj acc l)))
                    acc))]
    (str/join "\n"
              (mapcat (fn [[n es]]
                        (let [ls    (sort (map line es))
                              shown (filter #(contains? picked %) ls)]
                          (cons (str n " (" (count ls) " tools"
                                     (when (< (count shown) (count ls)) (str ", " (count shown) " shown"))
                                     "):")
                                (map #(str "  " %) shown))))
                      (sort-by first by-ns)))))
