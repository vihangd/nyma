(ns agent.extensions.lsp-suite.lsp-manager
  "Routes LSP requests to the appropriate server by file extension.
   Lazy-spawns servers on first use and tracks open files."
  (:require ["node:path" :as path]
            [agent.extensions.lsp-suite.lsp-servers-catalog :as catalog]
            [agent.extensions.lsp-suite.lsp_client :as lsp-client]
            [agent.extensions.lsp-suite.lsp_formatters :as fmt]))

;; Extension → language-id mapping (used in textDocument/didOpen)
(def ^:private ext->lang-id
  {".ts"   "typescript" ".tsx" "typescriptreact"
   ".js"   "javascript" ".jsx" "javascriptreact"
   ".mjs"  "javascript" ".cjs" "javascript"
   ".py"   "python"     ".pyi" "python"
   ".rs"   "rust"
   ".go"   "go"
   ".clj"  "clojure"    ".cljs" "clojure"
   ".cljc" "clojure"    ".edn" "clojure"
   ".rb"   "ruby"})

;; ── Manager state ─────────────────────────────────────────────────

(defn create-manager
  "Create a manager. Call init! before use."
  []
  {:clients      (atom {})    ; server-id -> lsp-client map
   :config       (atom {})    ; server-id -> config map (from lsp_config/load-config)
   :cwd          (atom nil)
   :diag-handler (atom nil)}) ; fn(uri, diags-array) registered by index.cljs

;; ── Init / shutdown ───────────────────────────────────────────────

(defn init!
  "Set the config. Does not start any servers — they are lazy-spawned."
  [manager config cwd]
  (reset! (:config manager) config)
  (reset! (:cwd manager) cwd))

(defn ^:async stop-all!
  "Stop all running clients in parallel."
  [manager]
  (let [clients @(:clients manager)
        stops   (mapv (fn [[_ c]] (lsp-client/stop! c)) (seq clients))]
    (when (seq stops)
      (js-await (js/Promise.all (clj->js stops))))
    (reset! (:clients manager) {})))

;; ── Lazy server resolution ────────────────────────────────────────

(defn ^:async get-client-for!
  "Return a running lsp-client for the given file path, spawning lazily.
   Returns nil if no server is configured or the server is disabled."
  [manager file-path]
  (let [ext      (.toLowerCase (path/extname file-path))
        [id _]   (catalog/server-for-extension ext)
        cfg-map  @(:config manager)
        cfg      (and id (get cfg-map id))
        cwd      @(:cwd manager)]
    (when (and cfg (not (:disabled? cfg)))
      (let [clients @(:clients manager)]
        (if-let [existing (get clients id)]
          (when (lsp-client/running? existing) existing)
          ;; Spawn new client and wire diagnostic handler
          (try
            (let [client (lsp-client/create cfg)
                  _      (js-await (lsp-client/start! client cwd))]
              ;; Wire diagnostic handler if registered
              (when-let [h @(:diag-handler manager)]
                (lsp-client/set-diagnostic-handler! client h))
              (swap! (:clients manager) assoc id client)
              client)
            (catch :default _ nil)))))))

(defn get-any-running-client
  "Return the first running client (for workspace-wide operations like workspace/symbol)."
  [manager]
  (some (fn [[_ c]] (when (lsp-client/running? c) c)) @(:clients manager)))

;; ── File sync entry points ────────────────────────────────────────

(defn ^:async ensure-open!
  "Ensure the file is open on its language server before querying."
  [manager file-path]
  (when-let [client (js-await (get-client-for! manager file-path))]
    (let [uri     (fmt/path->uri file-path)
          ext     (.toLowerCase (path/extname file-path))
          lang-id (get ext->lang-id ext "plaintext")]
      (js-await (lsp-client/ensure-open! client uri lang-id)))))

(defn ^:async did-change!
  "Notify the language server that a file was written."
  [manager file-path new-content]
  (when-let [client (js-await (get-client-for! manager file-path))]
    (lsp-client/did-change! client (fmt/path->uri file-path) new-content)))

(defn set-diagnostic-handler!
  "Register a fn(uri, diags-array) that is called whenever diagnostics arrive.
   Also wires handler to any already-running clients."
  [manager handler]
  (reset! (:diag-handler manager) handler)
  (doseq [[_ c] @(:clients manager)]
    (lsp-client/set-diagnostic-handler! c handler)))
