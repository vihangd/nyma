(ns agent.extensions.token-suite.repo-map
  (:require [agent.extensions.token-suite.shared :as shared]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

;; ── Regex-based symbol extraction (no tree-sitter dependency) ──

(def ^:private lang-patterns
  "Regex patterns for extracting definitions per language family."
  {"js"   {:def #"(?m)^(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\("
           :class #"(?m)^(?:export\s+)?class\s+(\w+)"
           :import #"(?m)(?:import|require)\s*\(?['\"]([^'\"]+)['\"]"}
   "ts"   {:def #"(?m)^(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*[\(<]"
           :class #"(?m)^(?:export\s+)?(?:class|interface|type)\s+(\w+)"
           :import #"(?m)(?:import|require)\s*\(?['\"]([^'\"]+)['\"]"}
   "cljs" {:def #"(?m)\(defn-?\s+\^?:?\w*\s*(\S+)"
           :class #"(?m)\(defprotocol\s+(\S+)"
           :import #"(?m):require\s+\[\"([^\"]+)\""}
   "py"   {:def #"(?m)^(?:async\s+)?def\s+(\w+)\s*\("
           :class #"(?m)^class\s+(\w+)"
           :import #"(?m)(?:from\s+(\S+)\s+import|import\s+(\S+))"}
   "go"   {:def #"(?m)^func\s+(?:\(\w+\s+\*?\w+\)\s+)?(\w+)\s*\("
           :class #"(?m)^type\s+(\w+)\s+struct"
           :import #"(?m)\"([^\"]+)\""}
   "rs"   {:def #"(?m)^(?:pub\s+)?(?:async\s+)?fn\s+(\w+)"
           :class #"(?m)^(?:pub\s+)?(?:struct|enum|trait)\s+(\w+)"
           :import #"(?m)use\s+(\S+)"}
   "java" {:def #"(?m)(?:public|private|protected)?\s*(?:static\s+)?(?:\w+\s+)+(\w+)\s*\("
           :class #"(?m)(?:public\s+)?(?:class|interface|enum)\s+(\w+)"
           :import #"(?m)import\s+(\S+);"}
   "rb"   {:def #"(?m)^\s*def\s+(\w+)"
           :class #"(?m)^\s*class\s+(\w+)"
           :import #"(?m)require[_\s]+['\"]([^'\"]+)['\"]"}})

(defn- detect-lang [file-path]
  (let [ext (last (.split file-path "."))]
    (case ext
      ("js" "mjs" "jsx") "js"
      ("ts" "tsx")        "ts"
      "cljs"              "cljs"
      "py"                "py"
      "go"                "go"
      ("rs")              "rs"
      "java"              "java"
      "rb"                "rb"
      nil)))

(defn- extract-symbols
  "Extract function/class definitions and imports from a file."
  [file-path content]
  (when-let [lang (detect-lang file-path)]
    (when-let [patterns (get lang-patterns lang)]
      (let [defs    (map second (re-seq (:def patterns) content))
            classes (map second (re-seq (:class patterns) content))
            imports (->> (re-seq (:import patterns) content)
                         (mapcat rest)
                         (filter some?))]
        {:path    file-path
         :defs    (vec defs)
         :classes (vec classes)
         :imports (vec imports)}))))

(defn- glob-source-files
  "Find all source files in the project directory."
  [cwd extensions]
  (let [results (atom [])]
    (letfn [(walk [dir depth]
              (when (< depth 5)  ;; max depth
                (try
                  (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
                    (doseq [entry entries]
                      (let [name (.-name entry)
                            full (path/join dir name)]
                        (cond
                          ;; Skip hidden dirs, node_modules, dist, .git
                          (and (.isDirectory entry)
                               (not (.startsWith name "."))
                               (not= name "node_modules")
                               (not= name "dist")
                               (not= name ".git"))
                          (walk full (inc depth))

                          ;; Include source files
                          (.isFile entry)
                          (let [ext (last (.split name "."))]
                            (when (contains? extensions ext)
                              (swap! results conj full)))))))
                  (catch :default _e nil))))]
      (walk cwd 0))
    @results))

(defn- build-index
  "Index the codebase: extract symbols and build dependency graph."
  [cwd extensions]
  (let [start    (js/Date.now)
        files    (glob-source-files cwd extensions)
        symbols  (atom [])
        ;; Build reference counts for ranking
        ref-count (atom {})]  ;; path → number of files that import it

    (doseq [f files]
      (try
        (let [content (fs/readFileSync f "utf8")
              rel     (.replace f (str cwd "/") "")
              syms    (extract-symbols rel content)]
          (when syms
            (swap! symbols conj syms)
            ;; Count references: each import increments the target's ref count
            (doseq [imp (:imports syms)]
              (swap! ref-count update imp (fnil inc 0)))))
        (catch :default _e nil)))

    (let [elapsed (- (js/Date.now) start)
          total-symbols (reduce + 0 (map #(+ (count (or (:defs %) [])) (count (or (:classes %) []))) @symbols))]

      ;; Update stats
      (swap! shared/suite-stats update :repo-map
        (fn [s] (assoc s
                  :files (count files)
                  :symbols total-symbols
                  :last-index-ms elapsed)))

      {:symbols @symbols
       :ref-count @ref-count
       :file-count (count files)
       :symbol-count total-symbols})))

(defn- render-repo-map
  "Render the repo map as a compact text representation."
  [index max-files]
  (let [;; Sort by reference count (most referenced first)
        sorted (->> (:symbols index)
                    (sort-by (fn [s]
                               (- (reduce + 0 (map #(get (:ref-count index) % 0)
                                                    (or (:imports s) []))))))
                    (take max-files))
        lines  (atom [(str "## Repository Map (" (:file-count index) " files, "
                           (:symbol-count index) " symbols)")])]
    (doseq [{:keys [path defs classes]} sorted]
      (let [d (or defs [])
            c (or classes [])]
        (when (or (seq d) (seq c))
          (swap! lines conj (str "### " path))
          (doseq [cls c]
            (swap! lines conj (str "  class " cls)))
          (doseq [def-name d]
            (swap! lines conj (str "  fn " def-name))))))
    (str/join "\n" @lines)))

(defn activate [api]
  (let [config   (shared/load-config)
        rm-cfg   (:repo-map config)
        cwd      (js/process.cwd)
        index    (atom nil)
        dirty    (atom true)]

    ;; Build initial index
    (reset! index (build-index cwd (:extensions rm-cfg)))
    (reset! dirty false)

    ;; Mark dirty on file edits
    (when (:reindex-on-edit rm-cfg)
      (.on api "tool_execution_end"
        (fn [event _ctx]
          (let [tool (or (.-toolName event) "")]
            (when (contains? #{"edit" "write" "multi_edit"} tool)
              (reset! dirty true))))))

    ;; before_agent_start — inject repo map as prompt-section
    (.on api "before_agent_start"
      (fn [_ _ctx]
        ;; Re-index if dirty
        (when @dirty
          (reset! index (build-index cwd (:extensions rm-cfg)))
          (reset! dirty false))

        (when (and @index (pos? (:file-count @index)))
          (let [max-tokens (:max-tokens rm-cfg)
                ;; Binary search for max files that fit
                total (count (:symbols @index))
                best  (loop [lo 1 hi (min total 200)]
                        (if (>= lo hi) lo
                          (let [mid (js/Math.ceil (/ (+ lo hi) 2))
                                text (render-repo-map @index mid)
                                est  (.estimateTokens api text)]
                            (if (<= est max-tokens)
                              (recur mid hi)
                              (recur lo (dec mid))))))
                map-text (render-repo-map @index best)]
            #js {:prompt-sections
                 #js [#js {:content map-text :priority 30}]})))
      50)

    ;; Return deactivate
    (fn [] (reset! index nil))))
