(ns agent.extensions.lsp-suite.lsp-config
  "Load and merge LSP server configuration from .nyma/settings.json."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.lsp-suite.lsp-servers-catalog :as catalog]))

(defn- expand-env [s]
  (when (string? s)
    (.replace s (js/RegExp. "\\$\\{([^}]+)\\}" "g")
              (fn [_ k] (or (aget js/process.env k) (str "${" k "}"))))))

(defn- resolve-command [cmd-arr]
  (when (and cmd-arr (pos? (.-length cmd-arr)))
    (into [] (map (fn [s] (or (expand-env s) s)) cmd-arr))))

(defn load-config
  "Returns a map of server-id → config map, merging catalog defaults
   with user overrides from .nyma/settings.json#lsp."
  []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")
        user-lsp      (when (fs/existsSync settings-path)
                        (try
                          (let [p (js/JSON.parse (fs/readFileSync settings-path "utf8"))]
                            (.-lsp p))
                          (catch :default _ nil)))]
    ;; Merge catalog entries with user overrides
    (reduce
     (fn [acc [id recipe]]
       (let [ue      (and user-lsp (aget user-lsp id))
             cmd-raw (or (and ue (.-command ue)) (clj->js (:command recipe)))
             ext-raw (or (and ue (.-extensions ue)) (clj->js (:extensions recipe)))]
         (assoc acc id
                {:id          id
                 :name        (:name recipe)
                 :command     (resolve-command cmd-raw)
                 :extensions  (into [] ext-raw)
                 :disabled?   (boolean (and ue (.-disabled ue)))
                 :env         (and ue (.-env ue))
                 :initOptions (and ue (.-initializationOptions ue))
                 :startupTimeout (or (and ue (.-startupTimeout ue)) 15000)
                 :maxRestarts    (or (and ue (.-maxRestarts ue)) 3)})))
     {}
     catalog/catalog)))

;; ── Tool gating ──────────────────────────────────────────────────
;;
;; Nine LSP tools ride on every request — roughly 1,650 tokens of near-duplicate
;; path/line/character schemas — whether or not a language server could ever
;; answer one. On a machine with no server binaries, and in every headless
;; benchmark run, that is pure weight.
;;
;; The runtime predicates cannot gate it. `get-any-running-client` and
;; `lsp-client/running?` are only true BECAUSE a tool was called: servers spawn
;; lazily and these tools are what spawn them, so gating on "a server is running"
;; means one never can be. This is the config-level question instead, which is
;; answerable before any call.

(defn workspace-extensions
  "File extensions actually present under `cwd`, dotted to match the catalog —
   `.rb`, not `rb`. Depth-limited and skips the usual dead weight, because this
   runs once at activation and the answer only has to be good enough to tell
   `there is Ruby here` from `there is not`."
  [cwd & [{:keys [max-depth]}]]
  (let [limit (or max-depth 4)
        found (atom #{})]
    (letfn [(walk [dir depth]
              (when (< depth limit)
                (try
                  (doseq [entry (fs/readdirSync dir #js {:withFileTypes true})]
                    (let [nm (.-name entry)]
                      (cond
                        (and (.isDirectory entry)
                             (not (.startsWith nm "."))
                             (not (contains? #{"node_modules" "dist" "target"
                                               "build" "vendor" "__pycache__"} nm)))
                        (walk (path/join dir nm) (inc depth))

                        (.isFile entry)
                        (let [i (.lastIndexOf nm ".")]
                          (when (pos? i)
                            (swap! found conj (.slice nm i)))))))
                  (catch :default _e nil))))]
      (walk cwd 0))
    @found))

(defn any-server-available?
  "Could any configured server start here AND handle a file type this workspace
   actually contains?

   The extension match is load-bearing, not belt-and-braces. Measured on this
   machine: rust-analyzer and clangd are installed while the JavaScript language
   server is not, so a plain `is any server on PATH` test answers yes for a
   pure-JavaScript workspace and the nine tool schemas ride along for nothing.

   `on-path?` and `present-exts` are passed in so this stays pure and cheap to
   test — shelling out to `which` per server is the expensive half."
  [config on-path? present-exts]
  (let [present (set present-exts)]
    (boolean
     (some (fn [[_id c]]
             (let [cmd (first (:command c))]
               (and (not (:disabled? c))
                    (string? cmd)
                    (seq cmd)
                    (some present (:extensions c))
                    (on-path? cmd))))
           config))))

(defn without-lsp-tools
  "The `tool_access_check` answer when no server can serve these: everything on
   offer MINUS the LSP tools, or nil when there is nothing to remove.

   Subtractive on purpose. This event merges by set intersection, so an answer
   built by removing names keeps everything it does not mention — including the
   gateway tools that are the only route to withheld content. Building an
   allowlist additively would have to know about those; this does not.

   nil rather than the unchanged list because nil is how a handler says 'no
   opinion'; an empty vector would hide every tool in the session."
  [candidates bare-names]
  (let [bare  (set bare-names)
        lsp?  (fn [c]
                (let [s (str c)]
                  (boolean (some (fn [b]
                                   (or (= s b) (.endsWith s (str "__" b))))
                                 bare))))
        kept  (vec (remove lsp? candidates))]
    (when (not= (count kept) (count candidates))
      kept)))
