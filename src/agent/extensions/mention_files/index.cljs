(ns agent.extensions.mention-files.index
  "Built-in @-mention provider for project files.
   Registers as a mention provider that searches files via Bun.Glob."
  (:require [clojure.string :as str]
            ["node:path" :as path]
            ["node:fs" :as fs]))

(defn- read-gitignore-patterns
  "Read .gitignore patterns from the project root. Returns array of patterns."
  []
  (let [gitignore-path (path/join (js/process.cwd) ".gitignore")]
    (if (fs/existsSync gitignore-path)
      (let [content (fs/readFileSync gitignore-path "utf8")
            lines   (.split content "\n")]
        (vec (remove #(or (empty? %) (str/starts-with? % "#")) lines)))
      [])))

(defn- search-files
  "Search project files matching query. Returns Promise<[{:label :value :description}]>."
  [query]
  (js/Promise.
    (fn [resolve _reject]
      (try
        (let [pattern    (if (empty? query) "**/*" (str "**/*" query "*"))
              gitignore  (read-gitignore-patterns)
              ;; Always ignore common non-project dirs
              ignore     (into ["node_modules/**" ".git/**" "dist/**" ".nyma/**"
                                "*.pyc" "__pycache__/**"]
                               gitignore)
              glob       (js/Bun.Glob. pattern)
              cwd        (js/process.cwd)
              results    (atom [])
              max-results 50]
          ;; Use scanSync for simplicity (Bun.Glob supports sync iteration)
          (doseq [file (.scanSync glob #js {:cwd cwd :dot false})]
            (when (< (count @results) max-results)
              ;; Check against ignore patterns (simple prefix/suffix matching)
              (let [ignored (some (fn [pat]
                                    (or (str/includes? file (str/replace pat "**" ""))
                                        (str/ends-with? file (str/replace pat "*" ""))))
                                  ignore)]
                (when-not ignored
                  (let [dir (path/dirname file)]
                    (swap! results conj
                      {:label       (path/basename file)
                       :value       file
                       :description (when (not= dir ".") dir)}))))))
          (resolve (clj->js @results)))
        (catch :default e
          (resolve #js []))))))

(defn- resolve-file
  "Resolve a selected file mention. Returns {:text :context}."
  [item]
  {:text    (.-value item)
   :context {:type "file" :path (.-value item)}})

(defn ^:export default
  "Extension activation function."
  [api]
  (when (and api (.-registerMentionProvider api))
    (.registerMentionProvider api "files"
      #js {:trigger "@"
           :label   "Files"
           :search  search-files
           :resolve resolve-file}))
  ;; Return deactivator
  (fn []
    (when (and api (.-unregisterMentionProvider api))
      (.unregisterMentionProvider api "files"))))
