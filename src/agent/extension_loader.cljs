(ns agent.extension-loader
  (:require ["squint-cljs" :refer [compileString]]
            ["node:path" :as path]
            ["node:fs/promises" :as fsp]
            ["node:fs" :as fs]
            [agent.extension-scope :refer [create-scoped-api derive-namespace]]
            [agent.permissions :refer [parse-capabilities]]))

(defn- cljs-extension?  [p] (or (.endsWith p ".cljs") (.endsWith p ".cljc")))
(defn- ts-js-extension? [p] (or (.endsWith p ".ts") (.endsWith p ".js")
                                (.endsWith p ".mjs")))

(defn- entry-point?
  "Check if a filename is an extension entry point (index.*)."
  [filename]
  (let [base (path/basename filename)]
    (or (= base "index.cljs") (= base "index.cljc")
        (= base "index.mjs")  (= base "index.js")
        (= base "index.ts"))))

(defn- dep-resolvable?
  "Check if an npm package is resolvable from CWD using Bun's resolver."
  [pkg-name]
  (try
    (js/Bun.resolveSync pkg-name (js/process.cwd))
    true
    (catch :default _e false)))

(defn ^:async resolve-dependencies
  "Check declared dependencies in a manifest and auto-install any missing ones.
   Requires a package.json in CWD. Skips silently if no dependencies declared."
  [manifest]
  (when-let [deps (and manifest (.-dependencies manifest))]
    (let [pkg-names (js/Object.keys deps)
          missing   (vec (filter #(not (dep-resolvable? %)) pkg-names))]
      (when (seq missing)
        (js/console.log
         (str "[nyma] Installing missing extension deps: "
              (.join (clj->js missing) ", ")))
        (if (fs/existsSync (path/join (js/process.cwd) "package.json"))
          (let [proc (js/Bun.spawn
                      (clj->js (concat ["bun" "add"] missing))
                      #js {:stdout "pipe" :stderr "pipe"
                           :cwd (js/process.cwd)})
                exit-code (js-await (.-exited proc))]
            (when (not= exit-code 0)
              (js/console.error
               (str "[nyma] Failed to install: "
                    (.join (clj->js missing) ", ")))))
          (js/console.warn
           "[nyma] No package.json in CWD — cannot auto-install extension deps. "
           (str "Missing: " (.join (clj->js missing) ", "))))))))

(def ^:private cache-dir
  "Squint compilation cache directory."
  (str (.. js/process -env -HOME) "/.nyma/cache"))

(defn- ensure-cache-dir []
  (when-not (fs/existsSync cache-dir)
    (fs/mkdirSync cache-dir #js {:recursive true})))

(defn ^:async load-squint-extension
  "Compile a .cljs extension file with squint and evaluate it.
   Uses content-hash caching to avoid recompilation when source unchanged."
  [file-path]
  (let [source     (js-await (.readFile fsp file-path "utf8"))
        hash       (.toString (js/Bun.hash source) 16)
        cache-path (str cache-dir "/" hash ".mjs")]
    ;; Check cache
    (when-not (fs/existsSync cache-path)
      ;; Compile and cache
      (let [compiled (compileString source
                                    #js {:context       "expr"
                                         :elide-imports false})]
        (ensure-cache-dir)
        (js-await (js/Bun.write cache-path compiled))))
    ;; Import from cache (or freshly written)
    (let [mod (js-await (js/import cache-path))]
      (.-default mod))))

(defn ^:async load-ts-extension
  "Load a .ts/.js extension file directly via Bun's native TS loader."
  [file-path]
  (let [mod (js-await (js/import (path/resolve file-path)))]
    (.-default mod)))

(defn ^:async load-extension
  "Load a single extension file. Dispatches on file extension."
  [file-path]
  (cond
    (cljs-extension? file-path)  (js-await (load-squint-extension file-path))
    (ts-js-extension? file-path) (js-await (load-ts-extension file-path))
    :else (js/console.warn (str "Unknown extension type: " file-path))))

(defn ^:async load-manifest
  "Load an optional extension.json manifest from the same directory."
  [ext-path]
  (let [dir          (path/dirname ext-path)
        manifest-path (path/join dir "extension.json")]
    (when (fs/existsSync manifest-path)
      (try
        (js/JSON.parse (js-await (.readFile fsp manifest-path "utf8")))
        (catch :default _e nil)))))

(defn topo-sort
  "Topological sort of extension entries by dependsOn.
   Falls back to original order on cycles. Public for direct testing."
  [entries]
  (let [ns-set  (set (map :namespace entries))
        by-ns   (into {} (map (fn [e] [(:namespace e) e]) entries))
        ;; Filter deps to only known namespaces
        deps-of (fn [e] (filterv #(contains? ns-set %) (or (:deps e) [])))
        in-deg  (atom (into {} (map (fn [e] [(:namespace e) 0]) entries)))
        adj     (atom {})]
    ;; Build adjacency: dep → [dependents]
    (doseq [e entries]
      (doseq [dep (deps-of e)]
        (swap! adj update dep (fnil conj []) (:namespace e))
        (swap! in-deg update (:namespace e) inc)))
    ;; BFS from nodes with 0 in-degree
    (let [queue  (atom (vec (filter #(= 0 (get @in-deg %)) (map :namespace entries))))
          result (atom [])]
      (loop []
        (when (seq @queue)
          (let [n (first @queue)]
            (swap! queue #(vec (rest %)))
            (swap! result conj n)
            (doseq [neighbor (get @adj n [])]
              (swap! in-deg update neighbor dec)
              (when (= 0 (get @in-deg neighbor))
                (swap! queue conj neighbor)))
            (recur))))
      (if (= (count @result) (count entries))
        (mapv #(get by-ns %) @result)
        (do
          (js/console.warn "[nyma] Extension dependency cycle detected, loading in scan order")
          entries)))))

(defn ^:async discover-and-load
  "Scan directories for extension files, load them, and wire them up.
   Extensions may return a deactivate function for cleanup.
   If an extension.json manifest exists, it provides namespace, capabilities, and dependsOn.
   Extensions are loaded in dependency order (topological sort)."
  [dirs api]
  ;; Pass 1: Scan and collect metadata
  (let [scan-results (atom [])]
    (doseq [dir dirs]
      (when (js-await (-> (.stat fsp dir) (.then (constantly true)) (.catch (constantly false))))
        (let [entries (js-await (.readdir fsp dir #js {:recursive true}))]
          (doseq [entry entries]
            (let [full-path (path/join dir entry)]
              (when (or (cljs-extension? entry) (ts-js-extension? entry))
                ;; Multi-file extension filtering:
                ;; If the file's directory contains extension.json, only load the
                ;; entry point (index.*). Skip helper/support files like shared.mjs.
                (let [dir-of-file    (path/dirname full-path)
                      has-manifest?  (fs/existsSync
                                      (path/join dir-of-file "extension.json"))]
                  (when (or (not has-manifest?)    ;; single-file ext: always load
                            (entry-point? entry))  ;; multi-file ext: only load index
                    (try
                      (let [manifest (js-await (load-manifest full-path))
                            ns-str   (or (and manifest (.-namespace manifest))
                                         (derive-namespace full-path))
                            deps     (when (and manifest (.-dependsOn manifest))
                                       (vec (js/Array.from (.-dependsOn manifest))))]
                        (swap! scan-results conj
                               {:path full-path :entry entry :namespace ns-str
                                :manifest manifest :deps deps}))
                      (catch :default e
                        (js/console.error
                         (str "[nyma] Failed to scan extension (" full-path "):") e)))))))))))
    ;; Pass 2: Topological sort
    (let [sorted     (topo-sort @scan-results)
          extensions (atom [])]
      ;; Pass 3: Load in sorted order
      (doseq [{:keys [path entry namespace manifest]} sorted]
        (try
          ;; Resolve npm dependencies before loading
          (when manifest
            (js-await (resolve-dependencies manifest)))
          (let [ext-fn (js-await (load-extension path))
                caps   (parse-capabilities
                        (when manifest (.-capabilities manifest)))
                scoped (create-scoped-api api namespace caps)]
            (when ext-fn
              (let [result (js-await (ext-fn scoped))]
                (swap! extensions conj
                       {:path       path
                        :namespace  namespace
                        :type       (if (cljs-extension? entry) :squint :ts)
                        :deactivate (when (fn? result) result)}))))
          (catch :default e
            (js/console.error
             (str "[nyma] Failed to load extension (" path "):") e))))
      @extensions)))

(defn filter-by-mode
  "Return only those loaded-extension entries whose manifest allows `current-mode`.

   Manifest format (extension.json): {\"modes\": [\"tui\", \"gateway\"]}
   If a manifest has no `modes` field (or the extension has no manifest),
   the extension is allowed in every mode — this preserves backward compatibility
   with all existing extensions written before mode declarations were introduced.

   `current-mode` is a keyword such as :tui or :gateway.

   Example:
     (filter-by-mode loaded-extensions :gateway)"
  [extensions current-mode]
  (filter (fn [{:keys [manifest]}]
            (let [modes (when manifest (.-modes manifest))]
              (or (nil? modes)
                  (let [mode-strs (js/Array.from modes)
                        mode-name (name current-mode)]
                    (.includes mode-strs mode-name)))))
          extensions))

(defn deactivate-all
  "Call deactivate on all loaded extensions that returned a cleanup function."
  [extensions]
  (doseq [{:keys [deactivate path]} extensions]
    (when deactivate
      (try
        (deactivate)
        (catch :default e
          (js/console.error
           (str "[nyma] Extension deactivate error (" path "):") e))))))

(defn ^:async reload-extension
  "Deactivate and re-load a single extension."
  [ext-info api]
  (when (:deactivate ext-info)
    (try ((:deactivate ext-info))
         (catch :default e
           (js/console.error (str "[nyma] Extension deactivate error during reload:") e))))
  (try
    (let [manifest (js-await (load-manifest (:path ext-info)))
          _        (when manifest (js-await (resolve-dependencies manifest)))
          ext-fn   (js-await (load-extension (:path ext-info)))
          ns-str   (or (and manifest (.-namespace manifest))
                       (:namespace ext-info))
          caps     (parse-capabilities
                    (when manifest (.-capabilities manifest)))
          scoped   (create-scoped-api api ns-str caps)]
      (when ext-fn
        (let [result (js-await (ext-fn scoped))]
          (assoc ext-info :deactivate (when (fn? result) result)))))
    (catch :default e
      (js/console.error (str "[nyma] Extension reload error (" (:path ext-info) "):") e)
      ext-info)))

(defn ^:async reload-all
  "Reload all extensions. Returns updated extension info vector."
  [extensions api]
  (let [results (atom [])]
    (doseq [ext extensions]
      (let [reloaded (js-await (reload-extension ext api))]
        (swap! results conj reloaded)))
    @results))
