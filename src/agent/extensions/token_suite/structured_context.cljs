(ns agent.extensions.token-suite.structured-context
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.token-suite.shared :as shared]
            [clojure.string :as str]))

;; ── Context File Discovery ────────────────────────────────────

(defn- safe-read [fpath]
  (try
    (fs/readFileSync fpath "utf8")
    (catch :default _e nil)))

(defn- estimate-tokens-fn
  "Use api.estimateTokens if available, fallback to char/4."
  [api text]
  (let [est-fn (when api (.-estimateTokens api))]
    (if (and est-fn (fn? est-fn))
      (est-fn text)
      (js/Math.ceil (/ (count text) 4)))))

(defn- scan-root-files
  "Scan project root for context files matching patterns."
  [cwd patterns]
  (->> patterns
       (map (fn [p] (path/join cwd p)))
       (filter #(fs/existsSync %))
       (map (fn [fpath]
              (let [content (safe-read fpath)]
                (when content
                  {:path (path/relative cwd fpath)
                   :abs-path fpath
                   :content content
                   :format (cond
                             (.endsWith fpath ".md") "md"
                             (.endsWith fpath ".mdc") "mdc"
                             :else "text")}))))
       (remove nil?)))

(defn- scan-mdc-dir
  "Scan .cursor/rules/ for *.mdc files."
  [cwd mdc-dir]
  (let [dir-path (path/join cwd mdc-dir)]
    (if (fs/existsSync dir-path)
      (let [entries (fs/readdirSync dir-path)]
        (->> entries
             (filter #(.endsWith % ".mdc"))
             (map (fn [file]
                    (let [fpath (path/join dir-path file)
                          content (safe-read fpath)]
                      (when content
                        {:path (path/relative cwd fpath)
                         :abs-path fpath
                         :content content
                         :format "mdc"}))))
             (remove nil?)))
      [])))

(defn- scan-subdirs
  "Walk subdirectories up to max-depth looking for context files."
  [cwd patterns max-depth]
  (let [results (atom [])
        skip-dirs #{"node_modules" ".git" ".nyma" "dist" "build" ".next" "target" "vendor"}]
    (letfn [(walk [dir depth]
              (when (< depth max-depth)
                (try
                  (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
                    (doseq [entry entries]
                      (let [name (.-name entry)]
                        (when (and (.isDirectory entry)
                                   (not (contains? skip-dirs name))
                                   (not (.startsWith name ".")))
                          (let [subdir (path/join dir name)]
                            ;; Check for context files in this subdir
                            (doseq [pattern patterns]
                              (let [fpath (path/join subdir pattern)]
                                (when (fs/existsSync fpath)
                                  (let [content (safe-read fpath)]
                                    (when content
                                      (swap! results conj
                                        {:path (path/relative cwd fpath)
                                         :abs-path fpath
                                         :dir (path/relative cwd subdir)
                                         :content content
                                         :format (if (.endsWith fpath ".md") "md" "text")}))))))
                            ;; Recurse
                            (walk subdir (inc depth)))))))
                  (catch :default _e nil))))]
      (walk cwd 0))
    @results))

(defn- discover-context-files
  "Discover all context files and classify into hot/warm/cold tiers."
  [cwd config api]
  (let [patterns (or (:file-patterns config)
                     ["CLAUDE.md" "CONTEXT.md" ".cursorrules"])
        mdc-dir (or (:mdc-dir config) ".cursor/rules")
        scan-depth (or (:scan-depth config) 3)
        hot-budget (or (:hot-budget config) 2000)

        ;; Discover root files + mdc files (candidates for hot tier)
        root-files (scan-root-files cwd patterns)
        mdc-files (scan-mdc-dir cwd mdc-dir)
        all-root (concat root-files mdc-files)

        ;; Skip AGENTS.md and SYSTEM.md — handled by loader.cljs
        all-root (remove #(or (= (:path %) "AGENTS.md")
                              (= (:path %) "SYSTEM.md")
                              (= (:path %) ".nyma/AGENTS.md")
                              (= (:path %) ".nyma/SYSTEM.md"))
                         all-root)

        ;; Add token estimates
        all-root (map (fn [f]
                        (assoc f :tokens (estimate-tokens-fn api (:content f))))
                      all-root)

        ;; Classify root files: hot if they fit in budget
        hot-files (atom [])
        hot-tokens (atom 0)
        cold-root (atom [])]
    (doseq [f all-root]
      (if (<= (+ @hot-tokens (:tokens f)) hot-budget)
        (do (swap! hot-files conj f)
            (swap! hot-tokens + (:tokens f)))
        (swap! cold-root conj f)))

    ;; Discover subdirectory files (warm tier)
    (let [subdir-files (scan-subdirs cwd patterns scan-depth)
          subdir-files (map (fn [f]
                              (assoc f :tokens (estimate-tokens-fn api (:content f))))
                            subdir-files)
          ;; Group warm files by directory
          warm-by-dir (reduce (fn [acc f]
                                (update acc (:dir f) (fnil conj []) f))
                              {} subdir-files)]
      {:hot @hot-files
       :warm warm-by-dir
       :cold (vec (concat @cold-root
                          ;; Files from very deep dirs go to cold
                          ))})))

;; ── Cold Context Tool ─────────────────────────────────────────

(defn ^:async context-files-list [discovered]
  (let [hot (:hot @discovered)
        warm (:warm @discovered)
        cold (:cold @discovered)
        lines (atom ["Project Context Files:"])]
    ;; Hot tier
    (when (seq hot)
      (swap! lines conj "\nHot (always loaded):")
      (doseq [f hot]
        (swap! lines conj (str "  " (:path f) " (" (:tokens f) " tokens, " (:format f) ")"))))
    ;; Warm tier
    (when (seq warm)
      (swap! lines conj "\nWarm (loaded on directory access):")
      (doseq [[dir files] warm]
        (doseq [f files]
          (swap! lines conj (str "  " (:path f) " [dir: " dir "] (" (:tokens f) " tokens)")))))
    ;; Cold tier
    (when (seq cold)
      (swap! lines conj "\nCold (available on demand):")
      (doseq [f cold]
        (swap! lines conj (str "  " (:path f) " (" (:tokens f) " tokens)"))))
    (when (and (empty? hot) (empty? warm) (empty? cold))
      (swap! lines conj "  No context files found (CLAUDE.md, CONTEXT.md, .cursorrules)"))
    (str/join "\n" @lines)))

(defn ^:async context-files-read [discovered fpath cwd]
  (let [abs-path (if (path/isAbsolute fpath) fpath (path/join cwd fpath))
        all-files (concat (:hot @discovered)
                          (mapcat val (:warm @discovered))
                          (:cold @discovered))
        found (first (filter #(or (= (:path %) fpath)
                                  (= (:abs-path %) abs-path))
                             all-files))]
    (if found
      (do (swap! shared/suite-stats update-in [:structured-context :cache-hits] inc)
          (:content found))
      ;; Try reading directly
      (if (fs/existsSync abs-path)
        (safe-read abs-path)
        (str "File not found: " fpath)))))

(defn ^:async context-files-execute-fn [discovered cwd args]
  (let [action (.-action args)
        fpath (.-path args)]
    (cond
      (= action "list")
      (context-files-list discovered)

      (= action "read")
      (if fpath
        (context-files-read discovered fpath cwd)
        "Error: path is required for 'read' action")

      :else
      "Error: action must be 'list' or 'read'")))

;; ── Activate / Deactivate ──────────────────────────────────────

(defn activate [api]
  (let [config (shared/load-config)
        sc-cfg (:structured-context config)
        cwd (js/process.cwd)
        discovered (atom (discover-context-files cwd sc-cfg api))
        accessed-dirs (atom #{})
        injected-dirs (atom #{})
        warm-tokens-used (atom 0)
        warm-budget (or (:warm-budget sc-cfg) 4000)]

    ;; Update discovery stats
    (let [hot-count (count (:hot @discovered))
          warm-count (reduce + 0 (map count (vals (:warm @discovered))))
          cold-count (count (:cold @discovered))]
      (swap! shared/suite-stats update-in [:structured-context :files-discovered]
             + (+ hot-count warm-count cold-count)))

    ;; Hot context injection via prompt-sections (before_agent_start, priority 45)
    (.on api "before_agent_start"
      (fn [_event _ctx]
        (let [hot-files (:hot @discovered)]
          (when (seq hot-files)
            (let [hot-text (str/join "\n\n---\n\n"
                             (map (fn [f]
                                    (str "### " (:path f) "\n\n" (:content f)))
                                  hot-files))
                  total-tokens (reduce + 0 (map :tokens hot-files))]
              (swap! shared/suite-stats assoc-in [:structured-context :hot-tokens] total-tokens)
              #js {:prompt-sections
                   #js [#js {:content (str "## Project Context\n\n" hot-text)
                             :priority 60}]}))))
      45)

    ;; Track accessed directories via tool_execution_end
    (.on api "tool_execution_end"
      (fn [event _ctx]
        (let [tool (or (.-toolName event) "")
              args (.-args event)
              fpath (when args (or (.-path args) ""))]
          (when (and (seq fpath)
                     (contains? #{"read" "edit" "write" "multi_edit" "glob" "grep"} tool))
            (swap! accessed-dirs conj (path/dirname fpath)))))
      0)

    ;; Warm context injection (before_agent_start, priority 40)
    (.on api "before_agent_start"
      (fn [_event _ctx]
        (let [warm-map (:warm @discovered)
              new-dirs (remove @injected-dirs (keys warm-map))
              ;; Find dirs that match any accessed directory
              matching (filter
                         (fn [dir]
                           (some (fn [accessed]
                                   (or (= accessed dir)
                                       (str/starts-with? accessed dir)))
                                 @accessed-dirs))
                         new-dirs)]
          (when (seq matching)
            (let [files (mapcat warm-map matching)
                  total-tokens (reduce + 0 (map :tokens files))]
              (when (<= (+ @warm-tokens-used total-tokens) warm-budget)
                (swap! warm-tokens-used + total-tokens)
                (swap! injected-dirs into matching)
                (swap! shared/suite-stats update-in [:structured-context :warm-tokens]
                       + total-tokens)
                #js {:inject-messages
                     (to-array
                       (map (fn [f]
                              #js {:role "system"
                                   :content (str "[Context: " (:path f) "]\n" (:content f))})
                            files))})))))
      40)

    ;; Register context_files tool
    (.registerTool api "context_files"
      (tool
        #js {:description "List or read project context files (CLAUDE.md, CONTEXT.md, .cursorrules, etc.). Use 'list' to see all discovered files with their tier and token count, or 'read' to get a specific file's content."
             :inputSchema (.object z
                            #js {:action (-> (.enum z #js ["list" "read"])
                                             (.describe "list = show all files; read = get content"))
                                 :path   (-> (.string z) (.optional)
                                             (.describe "File path (required for read action)"))})
             :execute (fn [args] (context-files-execute-fn discovered cwd args))}))

    ;; Return deactivator
    (fn []
      (.unregisterTool api "context_files")
      (reset! discovered nil)
      (reset! accessed-dirs #{})
      (reset! injected-dirs #{}))))
