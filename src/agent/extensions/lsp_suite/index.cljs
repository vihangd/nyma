(ns agent.extensions.lsp-suite.index
  "LSP Suite — code intelligence for nyma via Language Server Protocol.
   Adds hover, goto-definition, find-references, document-symbols,
   workspace-symbols, and get-diagnostics tools. Syncs LSP servers'
   view of the workspace with read/write/edit tool activity."
  (:require [agent.tool-metadata :as tm]
            ["node:fs" :as fs]
            ["node:path" :as node-path]
            [clojure.string :as str]
            [agent.extensions.lsp-suite.lsp_config :as config]
            [agent.extensions.lsp-suite.lsp_manager :as mgr]
            [agent.extensions.lsp-suite.lsp_diagnostics :as diags]
            [agent.extensions.lsp-suite.lsp_tools :as tools]
            [agent.extensions.lsp-suite.lsp_formatters :as fmt]
            [agent.extensions.lsp-suite.lsp_client :as lsp-client]))

;; ── Diagnostics prompt-section builder ───────────────────────────

(defn- diagnostics-section []
  (let [pending (diags/drain-pending!)]
    (when (seq pending)
      (let [lines (map (fn [{:keys [diag path]}]
                         (let [sev  (case (.-severity diag)
                                      1 "Error" 2 "Warning" 3 "Info" "Hint")
                               line (when (.. diag -range -start)
                                      (inc (.. diag -range -start -line)))
                               msg  (.-message diag)]
                           (str sev " " path ":" line " — " msg)))
                       pending)]
        (str "## LSP Diagnostics\n\n" (str/join "\n" lines))))))

;; ── File-sync helpers ─────────────────────────────────────────────

(defn- tool-name-from-data [data]
  (or (and data (.-name data)) (and data (.-toolName data))))

(defn- file-write-tool? [name]
  (tm/matches-any-tool-name? name #{"write" "edit" "multi_edit"}))

(defn- file-read-tool? [name]
  (= name "read"))

;; ── Activator ─────────────────────────────────────────────────────

(defn ^:export default [api]
  (let [cwd     (js/process.cwd)
        cfg     (config/load-config)
        manager (mgr/create-manager)]

    (mgr/init! manager cfg cwd)

    ;; Wire diagnostic handler: incoming publishDiagnostics → registry
    (mgr/set-diagnostic-handler! manager
                                 (fn [uri diag-array]
                                   (diags/register! uri diag-array (fmt/uri->path uri))))

    ;; ── before_agent_start: inject pending diagnostics ────────────

    (.on api "before_agent_start"
         (fn [_event _ctx]
           (let [section (diagnostics-section)]
             (when section
               #js {:prompt-sections
                    #js [#js {:content section :priority 60}]})))
         50)

    ;; ── tool_complete: sync reads and writes to LSP ───────────────

    (.on api "tool_complete"
         (fn [data]
           (let [name    (tool-name-from-data data)
                 args    (and data (.-args data))
                 file    (and args (or (.-path args) (.-file args)))
                 fabs    (when file
                           (if (.isAbsolute node-path file)
                             file
                             (node-path/resolve cwd file)))]
             (when (and fabs (fs/existsSync fabs))
               (cond
                 (file-read-tool? name)
                 ;; Only sync with already-running LSP clients — never spawn.
                 ;; Spawning (e.g. rust-analyzer) runs spawnSync + heavy workspace
                 ;; indexing as a side-effect of every file read, which competes
                 ;; with the provider HTTP stream and causes "Divining" hangs.
                 ;; LSP tools (hover, goto_definition) spawn lazily on first use.
                 (-> (mgr/ensure-open-if-running! manager fabs) (.catch (fn [_] nil)))

                 (file-write-tool? name)
                 (let [content (try (.readFileSync fs fabs "utf8") (catch :default _ nil))]
                   (when content
                     (-> (mgr/did-change-if-running! manager fabs content) (.catch (fn [_] nil))))))))
           nil)
         50)

    ;; ── Register LLM tools ────────────────────────────────────────

    (let [live-diags diags/get-all-current]

      (.registerTool api "hover"
                     (tools/make-hover-tool manager cwd))

      (.registerTool api "goto_definition"
                     (tools/make-goto-definition-tool manager cwd))

      (.registerTool api "find_references"
                     (tools/make-find-references-tool manager cwd))

      (.registerTool api "document_symbols"
                     (tools/make-document-symbols-tool manager cwd))

      (.registerTool api "workspace_symbols"
                     (tools/make-workspace-symbols-tool manager cwd))

      (.registerTool api "get_diagnostics"
                     (tools/make-get-diagnostics-tool manager cwd live-diags))

      (.registerTool api "rename_symbol"
                     (tools/make-rename-tool manager cwd))

      (.registerTool api "code_action"
                     (tools/make-code-action-tool manager cwd live-diags))

      (.registerTool api "organize_imports"
                     (tools/make-organize-imports-tool manager cwd)))

    ;; ── Diagnostics on the status line ────────────────────────────
    ;; Pure over the live view — no I/O, which the render cadence requires. The
    ;; count is partial by construction (servers spawn lazily, the store is
    ;; capped) so it is a signal, not an audit, and it hides at zero rather than
    ;; claiming a clean workspace it cannot verify.
    (when (.-registerStatusSegment api)
      (.registerStatusSegment
       api "lsp.diagnostics"
       #js {:category "lsp"
            :autoAppend true
            :position "left"
            :render
            (fn [ctx]
              (let [{:keys [errors warnings]} (diags/severity-counts)
                    theme (aget ctx "theme")
                    col   (fn [k d] (or (get-in theme [:colors k]) d))]
                (cond
                  (pos? (or errors 0))
                  #js {:visible? true
                       :content (str " ✗" errors (when (pos? (or warnings 0)) (str " ⚠" warnings)))
                       :color (col :error "#f7768e")}
                  (pos? (or warnings 0))
                  #js {:visible? true :content (str " ⚠" warnings)
                       :color (col :warning "#e0af68")}
                  :else #js {:visible? false})))}))

    ;; ── Withhold the tools when nothing could serve them ──────────
    ;; Nine schemas of near-duplicate path/line/character parameters ride on
    ;; every request. On a machine with no language server installed, and in any
    ;; headless run, they are pure weight.
    ;;
    ;; The workspace match matters as much as the PATH check. Measured here:
    ;; rust-analyzer and clangd are installed while the JavaScript server is not,
    ;; so gating on `anything on PATH` answers yes for a pure-JavaScript project
    ;; and never fires.
    ;;
    ;; Computed ONCE: this shells out to `which` per configured server and walks
    ;; the tree, so asking per loop iteration would spawn processes every turn.
    ;; A server installed mid-session is rare enough to be worth a restart.
    (let [lsp-set   (when (.-settings api) (.settings api :lsp))
          gate?     (not (false? (:gate-tools lsp-set)))
          usable?   (and gate?
                         (config/any-server-available?
                          cfg lsp-client/command-on-path?
                          (config/workspace-extensions cwd)))
          bare      ["hover" "goto_definition" "find_references" "document_symbols"
                     "workspace_symbols" "get_diagnostics" "rename_symbol"
                     "code_action" "organize_imports"]]
      (when (and gate? (not usable?))
        (.on api "tool_access_check"
             (fn [data _ctx]
               (when-let [kept (config/without-lsp-tools
                                (vec (or (.-tools data) [])) bare)]
                 #js {:allowed (clj->js kept)})))))

    ;; ── Shutdown cleanup ──────────────────────────────────────────

    (.on api "session_shutdown"
         (fn [_ _]
           (mgr/stop-all! manager))
         10)

    ;; Return deactivator
    (fn []
      (.unregisterTool api "hover")
      (.unregisterTool api "goto_definition")
      (.unregisterTool api "find_references")
      (.unregisterTool api "document_symbols")
      (.unregisterTool api "workspace_symbols")
      (.unregisterTool api "get_diagnostics")
      (.unregisterTool api "rename_symbol")
      (.unregisterTool api "code_action")
      (.unregisterTool api "organize_imports")
      (mgr/stop-all! manager)
      (diags/clear!))))
