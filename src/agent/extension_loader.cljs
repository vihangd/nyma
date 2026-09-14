(ns agent.extension-loader
  (:require [agent.debug :as d]
            ["squint-cljs" :refer [compileString]]
            ["node:path" :as path]
            ["node:fs/promises" :as fsp]
            ["node:fs" :as fs]
            [agent.extension-scope :refer [create-scoped-api derive-namespace dispose-scope!]]
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
        (d/info
         (str "[nyma] Installing missing extension deps: "
              (.join (clj->js missing) ", ")))
        (if (fs/existsSync (path/join (js/process.cwd) "package.json"))
          (let [proc (js/Bun.spawn
                      (clj->js (concat ["bun" "add"] missing))
                      #js {:stdout "pipe" :stderr "pipe"
                           :cwd (js/process.cwd)})
                exit-code (js-await (.-exited proc))]
            (when (not= exit-code 0)
              (d/error
               (str "[nyma] Failed to install: "
                    (.join (clj->js missing) ", ")))))
          (d/warn
           "[nyma] No package.json in CWD — cannot auto-install extension deps. "
           (str "Missing: " (.join (clj->js missing) ", "))))))))

(def ^:private cache-dir
  "Squint compilation cache directory."
  (str (.. js/process -env -HOME) "/.nyma/cache"))

;; Bump when the emitted cache format changes (e.g. import rewriting below) so
;; stale cache files with the old format are ignored rather than reused.
(def ^:private cache-version "v2")

(defn- ensure-cache-dir []
  (when-not (fs/existsSync cache-dir)
    (fs/mkdirSync cache-dir #js {:recursive true})))

(defn- builtin-spec? [s]
  (or (.startsWith s "node:") (.startsWith s "bun:")
      (.startsWith s "./") (.startsWith s "../") (.startsWith s "/")))

(defn- resolve-spec
  "Resolve a bare specifier to an absolute path. Tries the loader module's dir
   first (where nyma's own deps like squint-cljs live regardless of cwd), then
   cwd (where per-extension npm deps get installed). nil if unresolvable."
  [spec]
  (loop [bases [(.. js/import -meta -dir) (js/process.cwd)]]
    (when (seq bases)
      (or (try (js/Bun.resolveSync spec (first bases)) (catch :default _ nil))
          (recur (rest bases))))))

(defn absolutize-imports
  "Rewrite bare npm import specifiers in compiled squint output to absolute
   paths. Cache files live in ~/.nyma/cache — outside any node_modules tree — so
   a bare 'squint-cljs/core.js' can't resolve from there; an absolute path can.
   Builtins (node:/bun:) and already-relative/absolute paths are left alone;
   unresolvable specifiers are left as-is (no worse than before)."
  [compiled]
  (.replace compiled
            (js/RegExp. "from\\s+(['\"])([^'\"]+)\\1" "g")
            (fn [match q spec]
              (if (builtin-spec? spec)
                match
                (if-let [abs (resolve-spec spec)]
                  (str "from " q abs q)
                  match)))))

(defn ^:async load-squint-extension
  "Compile a .cljs extension file with squint and evaluate it.
   Uses content-hash caching to avoid recompilation when source unchanged."
  [file-path]
  (let [source     (js-await (.readFile fsp file-path "utf8"))
        hash       (.toString (js/Bun.hash source) 16)
        cache-path (str cache-dir "/" hash "-" cache-version ".mjs")]
    ;; Check cache
    (when-not (fs/existsSync cache-path)
      ;; Compile, rewrite bare imports to absolute (cache dir is outside any
      ;; node_modules tree), and cache.
      (let [compiled (-> (compileString source
                                        #js {:context       "expr"
                                             :elide-imports false})
                         (absolutize-imports))]
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
    :else (d/warn (str "Unknown extension type: " file-path))))

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
        _       (doseq [[ns-str es] (group-by :namespace entries)]
                  (when (> (count es) 1)
                    (d/warn (str "[nyma] Duplicate extension namespace \"" ns-str "\" — "
                                 (.join (clj->js (mapv :path es)) " vs ")
                                 " (only one will load)"))))
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
          (d/warn "[nyma] Extension dependency cycle detected, loading in scan order")
          entries)))))

(def last-load-failures
  "namespace → reason, for the most recent discover-and-load. The loader
   logged failures and then forgot them; /extensions reads this."
  (atom {}))

(defn ^:async discover-and-load
  "Scan directories for extension files, load them, and wire them up.
   Extensions may return a deactivate function for cleanup.
   If an extension.json manifest exists, it provides namespace, capabilities, and dependsOn.
   Extensions are loaded in dependency order (topological sort)."
  [dirs api & [builtins]]
  (reset! last-load-failures {})
  ;; Pass 1: collect metadata — the statically compiled builtins first, then
  ;; whatever the user directories hold.
  ;;
  ;; Builtins arrive already resolved (see agent.builtin-extensions) because a
  ;; single-file binary has no directory to scan: the old scan-only path found
  ;; nothing there and shipped a nyma with 2 of 40 extensions. They still go
  ;; through the same topological sort, capability parsing and activation as a
  ;; scanned file — one pipeline, so a builtin cannot drift from a user
  ;; extension in how it is loaded.
  (let [scan-results (atom (vec (for [{:keys [namespace module manifest]} (or builtins [])]
                                  {:path      (str "builtin:" namespace)
                                   :entry     "index.cljs"
                                   :namespace namespace
                                   :manifest  manifest
                                   :module    module
                                   :deps      (when (and manifest (.-dependsOn manifest))
                                                (vec (js/Array.from (.-dependsOn manifest))))})))]
    (doseq [dir dirs]
      (when (js-await (-> (.stat fsp dir) (.then (constantly true)) (.catch (constantly false))))
        (let [entries (js-await (.readdir fsp dir #js {:recursive true}))]
          (doseq [entry entries]
            (let [full-path (path/join dir entry)]
              (when (or (cljs-extension? entry) (ts-js-extension? entry))
                ;; Multi-file extension filtering:
                ;; If the file's directory — or ANY ancestor up to the scan
                ;; root — contains extension.json, only load that extension's
                ;; entry point (index.*). Without the ancestor walk, helper
                ;; files in subdirs (e.g. agent_shell/features/handoff.mjs)
                ;; are scanned as single-file extensions whose derived
                ;; namespace can silently collide with a real extension.
                (let [dir-of-file    (path/dirname full-path)
                      has-manifest?  (loop [d dir-of-file]
                                       (cond
                                         (fs/existsSync (path/join d "extension.json")) true
                                         (or (= d dir) (= d (path/dirname d))) false
                                         :else (recur (path/dirname d))))]
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
                        (d/error
                         (str "[nyma] Failed to scan extension (" full-path "):") e)))))))))))
    ;; Pass 2: Topological sort
    (let [sorted     (topo-sort @scan-results)
          extensions (atom [])
          failed     (atom #{})]
      ;; Pass 3: Load in sorted order; skip anything whose dependency failed
      ;; (loading against a half-initialized dependency is worse than not
      ;; loading at all).
      (doseq [{:keys [path entry namespace manifest deps module]} sorted]
        (if-let [bad (first (filter @failed (or deps [])))]
          (do (swap! failed conj namespace)
              (swap! last-load-failures assoc namespace (str "dependency " bad " failed to load"))
              (d/error
               (str "[nyma] Skipping extension " namespace
                    " — its dependency " bad " failed to load")))
          ;; Held outside the try so a throw mid-activation can sweep what the
          ;; extension registered before it died. Without this a half-activated
          ;; extension left its handlers and commands live under a namespace
          ;; marked failed — and nothing would ever deactivate them.
          (let [scoped-box (atom nil)]
          (try
            ;; Resolve npm dependencies before loading. A builtin's deps are
            ;; nyma's own and already installed, so this only has work to do for
            ;; something found on disk.
            (when (and manifest (not module))
              (js-await (resolve-dependencies manifest)))
            (let [ext-fn (if module
                           (.-default module)
                           (js-await (load-extension path)))
                  caps   (parse-capabilities
                          (when manifest (.-capabilities manifest))
                          namespace)
                  scoped (create-scoped-api api namespace caps)
                  _      (reset! scoped-box scoped)]
              (when ext-fn
                (let [result (js-await (ext-fn scoped))]
                  (swap! extensions conj
                         {:path       path
                          :namespace  namespace
                          :type       (cond module :builtin
                                            (cljs-extension? entry) :squint
                                            :else :ts)
                          :manifest   manifest
                          :scope      scoped
                          :deactivate (when (fn? result) result)}))))
            (catch :default e
              (swap! failed conj namespace)
              (swap! last-load-failures assoc namespace (str (.-message e)))
              (dispose-scope! @scoped-box)
              (d/error
               (str "[nyma] Failed to load extension (" path "):") e))))))
      @extensions)))

(defn ^:async deactivate-all
  "Call deactivate on every loaded extension, then sweep what its scope
   recorded (`dispose-scope!`) — the extension's own cleanup first, the
   namespace's safety net second.

   ONE synchronous pass, then a single Promise.all. A per-item await would
   suspend after the first extension, and cli.cljs' `process.on \"exit\"`
   handler is synchronous — node never runs the continuation, so cleanup
   would stop at the first promise-returning deactivate. This way every
   sync deactivate and every sweep behind one has already run by the time
   the promise is handed back."
  [extensions]
  (js-await
   (js/Promise.all
    (mapv (fn [{:keys [deactivate scope path]}]
            (let [log   (fn [e] (d/error (str "[nyma] Extension deactivate error (" path "):") e))
                  sweep (fn [] (dispose-scope! scope) nil)
                  r     (try (when deactivate (deactivate))
                             (catch :default e (log e) nil))]
              (if (and r (fn? (.-then r)))
                (.then r (fn [_] (sweep)) (fn [e] (log e) (sweep)))
                (sweep))))
          extensions))))

