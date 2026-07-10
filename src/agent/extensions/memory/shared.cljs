(ns agent.extensions.memory.shared
  "Pure helpers for the persistent-memory (MEMORY.md) extension.

   MEMORY.md is the agent's *learned* layer (distinct from AGENTS.md/CLAUDE.md,
   which nyma already loads as the human layer). It is a small, curated markdown
   file of `## key` blocks the agent maintains. Per SOTA (memory rot): the
   injected view is SIZE-CAPPED so the file can't bloat context."
  (:require [clojure.string :as str]))

(def default-config
  {:dir "memory" :max-lines 200})

(defn config [settings]
  (let [m (when settings (aget settings "memory"))]
    (merge default-config
           (when m
             (cond-> {}
               (aget m "dir")       (assoc :dir (aget m "dir"))
               (aget m "max-lines") (assoc :max-lines (aget m "max-lines")))))))

(defn parse-memory
  "Parse '## key' sections into an ordered vector of [key body]. Content before
   the first `## ` (a title) is ignored."
  [content]
  (->> (str/split (or content "") (js/RegExp. "^## " "m"))
       rest
       (map (fn [chunk]
              (let [nl  (.indexOf chunk "\n")]
                (if (neg? nl)
                  [(str/trim chunk) ""]
                  [(str/trim (.slice chunk 0 nl)) (str/trim (.slice chunk (inc nl)))]))))
       (filter #(seq (first %)))
       vec))

(defn render-memory
  "Render [key body] pairs back to a MEMORY.md string."
  [pairs]
  (str "# Memory\n\n"
       (str/join "\n\n" (map (fn [[k v]] (str "## " k "\n" v)) pairs))
       (when (seq pairs) "\n")))

(defn sanitize-body
  "Prevent a value from injecting a spurious `## ` section header when the file
   is re-parsed: prefix any body line starting with `## ` with a space."
  [v]
  (.replace (str v) (js/RegExp. "^(## )" "gm") " $1"))

(defn upsert
  "Insert or replace the block for `key` (value sanitized so it can't forge a
   section header on the next parse)."
  [pairs key value]
  (let [value (sanitize-body value)]
    (if (some #(= (first %) key) pairs)
      (mapv (fn [[k v]] (if (= k key) [k value] [k v])) pairs)
      (conj (vec pairs) [key value]))))

(defn remove-key [pairs key]
  (vec (remove #(= (first %) key) pairs)))

(defn cap-lines
  "Keep the first `n` lines of `s` (rot guard); append a truncation notice."
  [s n]
  (let [lines (str/split (or s "") "\n")]
    (if (<= (count lines) n)
      s
      (str (str/join "\n" (take n lines))
           "\n… [memory truncated at " n " lines — consolidate MEMORY.md]"))))
