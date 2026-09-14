(ns agent.extensions.lsp-suite.lsp-tools
  "LLM-facing LSP tools. All line/col inputs are 1-based (matching grep/editor
   output); converted to 0-based before sending to the language server."
  (:require ["node:path" :as node-path]
            [clojure.string :as str]
            [agent.extensions.lsp-suite.lsp_formatters :as fmt]
            [agent.extensions.lsp-suite.lsp_manager :as mgr]
            [agent.extensions.lsp-suite.lsp_client :as lsp-client]
            [agent.extensions.lsp-suite.lsp-edits :as edits]))

;; ── Shared helpers ────────────────────────────────────────────────

(defn- ->0 [n] (dec (js/parseInt n 10)))   ; 1-based → 0-based

(defn- abs-path [file-arg cwd]
  (if (.isAbsolute node-path file-arg)
    file-arg
    (node-path/resolve cwd file-arg)))

(defn- no-server-msg [file]
  (str "No LSP server configured for " file
       ". Add a server entry under the 'lsp' key in .nyma/settings.json."))

(defn- position-params [file line col cwd]
  (let [uri (fmt/path->uri (abs-path file cwd))]
    #js {:textDocument #js {:uri uri}
         :position     #js {:line      (->0 line)
                            :character (->0 col)}}))

;; ── hover ─────────────────────────────────────────────────────────

(defn ^:async hover-execute [manager cwd args]
  (let [file (.-file args)
        line (.-line args)
        col  (.-col  args)
        fabs (abs-path file cwd)]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [result (js-await (lsp-client/request! client "textDocument/hover"
                                                      (position-params file line col cwd)))
                text   (fmt/format-hover result)]
            (or text "No hover information available."))
          (catch :default e
            (str "Hover error: " (.-message e))))))))

(defn make-hover-tool [manager cwd]
  #js {:description
       "Get hover documentation for a symbol at a file position (types, docs, signatures). Line and col are 1-based."
       :parameters
       #js {:type "object"
            :properties
            #js {:file #js {:type "string" :description "Path to the file (absolute or relative to cwd)"}
                 :line #js {:type "integer" :description "Line number (1-based)"}
                 :col  #js {:type "integer" :description "Column number (1-based)"}}
            :required #js ["file" "line" "col"]}
       :display
       #js {:icon "🔍"
            :formatArgs (fn [_n a] (str (.-file a) ":" (.-line a) ":" (.-col a)))
            :formatResult (fn [r] (let [s (str r)] (if (> (count s) 80) (str (.slice s 0 77) "...") s)))}
       :execute
       (fn [args] (hover-execute manager cwd args))})

;; ── goto_definition ───────────────────────────────────────────────

(defn- format-locations [locs]
  (when locs
    (let [items (if (array? locs) locs #js [locs])]
      (if (zero? (.-length items))
        "No definition found."
        (->> (into [] items)
             (map (fn [loc]
                    (let [uri   (or (.-uri loc) (.-targetUri loc))
                          range (or (.-range loc) (.-targetSelectionRange loc))]
                      (when (and uri range)
                        (fmt/format-range-location uri range)))))
             (filter some?)
             (str/join "\n"))))))

(defn ^:async goto-definition-execute [manager cwd args]
  (let [file (.-file args)
        line (.-line args)
        col  (.-col  args)
        fabs (abs-path file cwd)]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [result (js-await (lsp-client/request! client "textDocument/definition"
                                                      (position-params file line col cwd)))]
            (or (format-locations result) "No definition found."))
          (catch :default e
            (str "Definition error: " (.-message e))))))))

(defn make-goto-definition-tool [manager cwd]
  #js {:description
       "Jump to the definition of a symbol at a file position. Returns path:line:col. Line and col are 1-based."
       :parameters
       #js {:type "object"
            :properties
            #js {:file #js {:type "string" :description "File path"}
                 :line #js {:type "integer" :description "Line number (1-based)"}
                 :col  #js {:type "integer" :description "Column number (1-based)"}}
            :required #js ["file" "line" "col"]}
       :display
       #js {:icon "→"
            :formatArgs (fn [_n a] (str (.-file a) ":" (.-line a) ":" (.-col a)))
            :formatResult (fn [r] (str (count (str/split-lines (str r))) " location(s)"))}
       :execute
       (fn [args] (goto-definition-execute manager cwd args))})

;; ── find_references ───────────────────────────────────────────────

(defn ^:async find-references-execute [manager cwd args]
  (let [file (.-file args)
        line (.-line args)
        col  (.-col  args)
        fabs (abs-path file cwd)
        incl (not= false (.-includeDeclaration args))]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [params #js {:textDocument #js {:uri (fmt/path->uri fabs)}
                            :position     #js {:line (->0 line) :character (->0 col)}
                            :context      #js {:includeDeclaration incl}}
                result (js-await (lsp-client/request! client "textDocument/references" params))]
            (if (or (nil? result) (zero? (.-length result)))
              "No references found."
              (->> (into [] result)
                   (map (fn [loc]
                          (fmt/format-range-location (.-uri loc) (.-range loc))))
                   (filter some?)
                   (str/join "\n"))))
          (catch :default e
            (str "References error: " (.-message e))))))))

(defn make-find-references-tool [manager cwd]
  #js {:description
       "Find all references to a symbol at a file position. Returns path:line:col list. Line and col are 1-based."
       :parameters
       #js {:type "object"
            :properties
            #js {:file               #js {:type "string"  :description "File path"}
                 :line               #js {:type "integer" :description "Line number (1-based)"}
                 :col                #js {:type "integer" :description "Column number (1-based)"}
                 :includeDeclaration #js {:type "boolean" :description "Include declaration site (default: true)"}}
            :required #js ["file" "line" "col"]}
       :display
       #js {:icon "⋮"
            :formatArgs (fn [_n a] (str (.-file a) ":" (.-line a) ":" (.-col a)))
            :formatResult (fn [r] (str (count (str/split-lines (str r))) " reference(s)"))}
       :execute
       (fn [args] (find-references-execute manager cwd args))})

;; ── document_symbols ──────────────────────────────────────────────

(defn ^:async document-symbols-execute [manager cwd args]
  (let [file (.-file args)
        fabs (abs-path file cwd)]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [params #js {:textDocument #js {:uri (fmt/path->uri fabs)}}
                result (js-await (lsp-client/request! client "textDocument/documentSymbol" params))]
            (if (or (nil? result) (zero? (.-length result)))
              "No symbols found."
              (or (fmt/format-symbol-tree result 0) "No symbols found.")))
          (catch :default e
            (str "Document symbols error: " (.-message e))))))))

(defn make-document-symbols-tool [manager cwd]
  #js {:description
       "List all symbols in a file (classes, functions, variables). Returns a hierarchical tree."
       :parameters
       #js {:type "object"
            :properties
            #js {:file #js {:type "string" :description "File path"}}
            :required #js ["file"]}
       :display
       #js {:icon "⊞"
            :formatArgs (fn [_n a] (.-file a))
            :formatResult (fn [r] (str (count (str/split-lines (str r))) " symbol(s)"))}
       :execute
       (fn [args] (document-symbols-execute manager cwd args))})

;; ── workspace_symbols ─────────────────────────────────────────────

(defn ^:async workspace-symbols-execute [manager cwd args]
  (let [query (.-query args)
        limit (or (.-limit args) 50)]
    (when-let [client (mgr/get-any-running-client manager)]
      (try
        (let [result (js-await (lsp-client/request! client "workspace/symbol"
                                                    #js {:query query}))]
          (if (or (nil? result) (zero? (.-length result)))
            "No symbols found."
            (->> (into [] (if (> (.-length result) limit)
                            (.slice result 0 limit)
                            result))
                 (map (fn [sym]
                        (let [kind (fmt/symbol-kind-name (.-kind sym))
                              loc  (.-location sym)]
                          (str kind " " (.-name sym)
                               (when loc (str " — " (fmt/format-range-location (.-uri loc) (.-range loc))))))))
                 (str/join "\n"))))
        (catch :default e
          (str "Workspace symbols error: " (.-message e)))))))

(defn make-workspace-symbols-tool [manager cwd]
  #js {:description
       "Search for symbols across the workspace by name query. Returns list of name, kind, and location."
       :parameters
       #js {:type "object"
            :properties
            #js {:query #js {:type "string"  :description "Symbol name or partial name to search"}
                 :limit #js {:type "integer" :description "Max results (default: 50)"}}
            :required #js ["query"]}
       :display
       #js {:icon "🔎"
            :formatArgs (fn [_n a] (.-query a))
            :formatResult (fn [r] (str (count (str/split-lines (str r))) " symbol(s)"))}
       :execute
       (fn [args] (workspace-symbols-execute manager cwd args))})

;; ── get_diagnostics ───────────────────────────────────────────────

(defn ^:async get-diagnostics-execute [manager cwd args diag-registry]
  (let [file     (.-file args)
        all-diags (if diag-registry (diag-registry) #js [])]
    (if (or (nil? all-diags) (zero? (.-length all-diags)))
      "No diagnostics."
      (let [filtered (if file
                       (let [fabs (abs-path file cwd)
                             uri  (fmt/path->uri fabs)]
                         (filter #(= (.-uri %) uri) (into [] all-diags)))
                       (into [] all-diags))]
        (if (empty? filtered)
          "No diagnostics for this file."
          (->> filtered
               (map (fn [d]
                      (fmt/format-diagnostic d (fmt/uri->path (.-uri d)))))
               (str/join "\n")))))))

(defn make-get-diagnostics-tool [manager cwd diag-registry]
  #js {:description
       "Get current LSP diagnostics (type errors, warnings, hints). Optionally filter by file."
       :parameters
       #js {:type "object"
            :properties
            #js {:file #js {:type "string" :description "Filter by file path (optional — omit for all files)"}}
            :required #js []}
       :display
       #js {:icon "⚠"
            :formatArgs (fn [_n a] (or (.-file a) "all files"))
            :formatResult (fn [r] (str (count (str/split-lines (str r))) " diagnostic(s)"))}
       :execute
       (fn [args] (get-diagnostics-execute manager cwd args diag-registry))})

;; ── rename_symbol ─────────────────────────────────────────────────

(defn- rel [cwd p] (node-path/relative cwd p))

(defn- summarize-apply [cwd verb result]
  (let [files   (:files result)
        skipped (:skipped result)]
    (str (if (seq files)
           (str "✅ " verb " — edited " (count files) " file(s):\n"
                (str/join "\n" (map #(str "  " (rel cwd %)) files)))
           (str verb ": no changes applied."))
         (when (seq skipped)
           (str "\n(skipped: " (str/join ", " skipped) ")")))))

(defn ^:async rename-execute [manager cwd args]
  (let [file (.-file args) line (.-line args) col (.-col args)
        new-name (.-newName args)
        fabs (abs-path file cwd)]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [params #js {:textDocument #js {:uri (fmt/path->uri fabs)}
                            :position     #js {:line (->0 line) :character (->0 col)}
                            :newName      new-name}
                wsedit (js-await (lsp-client/request! client "textDocument/rename" params))]
            (if-not wsedit
              "No rename edits returned — the symbol may not be renameable here."
              (summarize-apply cwd (str "Renamed to '" new-name "'")
                               (js-await (edits/apply-workspace-edit manager wsedit)))))
          (catch :default e
            (str "Rename error: " (.-message e))))))))

(defn make-rename-tool [manager cwd]
  #js {:safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
       :description
       "Rename the symbol at a file position across the whole project (LSP rename). Applies the edits to disk. Line and col are 1-based."
       :parameters
       #js {:type "object"
            :properties
            #js {:file    #js {:type "string"  :description "File path"}
                 :line    #js {:type "integer" :description "Line number (1-based)"}
                 :col     #js {:type "integer" :description "Column number (1-based)"}
                 :newName #js {:type "string"  :description "New symbol name"}}
            :required #js ["file" "line" "col" "newName"]}
       :display
       #js {:icon "✎"
            :formatArgs (fn [_n a] (str (.-file a) ":" (.-line a) " → " (.-newName a)))}
       :execute
       (fn [args] (rename-execute manager cwd args))})

;; ── code_action ───────────────────────────────────────────────────

(defn- action-kind [a] (or (.-kind a) ""))

(defn- list-actions [actions]
  (->> (into [] actions)
       (map-indexed (fn [i a]
                      (str (inc i) ". " (.-title a)
                           (let [k (action-kind a)] (when (seq k) (str "  [" k "]"))))))
       (str/join "\n")))

(defn- title-match? [action want]
  (let [t (str/lower-case (str (.-title action)))
        w (str/lower-case (str want))]
    (or (= t w) (.includes t w))))

(defn ^:async apply-one-action [manager client cwd actions want]
  (let [match (first (filter #(title-match? % want) (into [] actions)))]
    (if-not match
      (str "No code action matching \"" want "\". Available:\n" (list-actions actions))
      ;; Resolve the edit if the server deferred it (edit absent).
      (let [resolved (if (.-edit match)
                       match
                       (try (js-await (lsp-client/request! client "codeAction/resolve" match))
                            (catch :default _ match)))
            edit (.-edit resolved)
            cmd  (.-command resolved)
            base (if edit
                   (summarize-apply cwd (str "Applied '" (.-title match) "'")
                                    (js-await (edits/apply-workspace-edit manager edit)))
                   (str "Applied '" (.-title match) "' (no text edit)."))]
        ;; Some fixes are command-driven (e.g. add-missing-import); execute it.
        (if cmd
          (do (try (js-await (lsp-client/request! client "workspace/executeCommand"
                                                  #js {:command (.-command cmd)
                                                       :arguments (or (.-arguments cmd) #js [])}))
                   (catch :default _ nil))
              (str base "\n(ran command: " (.-title match) ")"))
          base)))))

(defn ^:async code-action-execute [manager cwd args diag-registry]
  (let [file (.-file args) line (.-line args)
        end-line (or (.-endLine args) line)
        want (.-apply args)
        fabs (abs-path file cwd)]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [uri (fmt/path->uri fabs)
                all-diags (if diag-registry (diag-registry) #js [])
                ctx-diags (vec (filter #(= (.-uri %) uri) (into [] all-diags)))
                params #js {:textDocument #js {:uri uri}
                            :range   #js {:start #js {:line (->0 line) :character 0}
                                          :end   #js {:line (->0 end-line) :character 100000}}
                            :context #js {:diagnostics (clj->js ctx-diags)}}
                actions (js-await (lsp-client/request! client "textDocument/codeAction" params))]
            (if (or (nil? actions) (zero? (.-length actions)))
              "No code actions available at this location."
              (if want
                (js-await (apply-one-action manager client cwd actions want))
                (str "Available code actions (call again with apply=\"<title>\"):\n"
                     (list-actions actions)))))
          (catch :default e
            (str "Code action error: " (.-message e))))))))

(defn make-code-action-tool [manager cwd diag-registry]
  #js {:safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
       :description
       "List or apply LSP code actions (quick-fixes, refactors) at a file location. Omit `apply` to LIST available actions; pass apply=\"<title substring>\" to apply one (edits written to disk). Lines are 1-based."
       :parameters
       #js {:type "object"
            :properties
            #js {:file    #js {:type "string"  :description "File path"}
                 :line    #js {:type "integer" :description "Line number (1-based)"}
                 :endLine #js {:type "integer" :description "End line for a range (optional; defaults to line)"}
                 :apply   #js {:type "string"  :description "Title (or substring) of the action to apply. Omit to list."}}
            :required #js ["file" "line"]}
       :display
       #js {:icon "🛠"
            :formatArgs (fn [_n a] (str (.-file a) ":" (.-line a)
                                        (when (.-apply a) (str " apply=" (.-apply a)))))}
       :execute
       (fn [args] (code-action-execute manager cwd args diag-registry))})

;; ── organize_imports ──────────────────────────────────────────────

(defn ^:async organize-imports-execute [manager cwd args]
  (let [file (.-file args)
        fabs (abs-path file cwd)]
    (js-await (mgr/ensure-open! manager fabs))
    (let [client (js-await (mgr/get-client-for! manager fabs))]
      (if-not client
        (no-server-msg file)
        (try
          (let [uri (fmt/path->uri fabs)
                params #js {:textDocument #js {:uri uri}
                            :range   #js {:start #js {:line 0 :character 0}
                                          :end   #js {:line 0 :character 0}}
                            :context #js {:diagnostics #js []
                                          :only #js ["source.organizeImports"]}}
                actions (js-await (lsp-client/request! client "textDocument/codeAction" params))]
            (if (or (nil? actions) (zero? (.-length actions)))
              "This server does not offer organize-imports for this file."
              (js-await (apply-one-action manager client cwd actions
                                          (.-title (aget actions 0))))))
          (catch :default e
            (str "Organize imports error: " (.-message e))))))))

(defn make-organize-imports-tool [manager cwd]
  #js {:safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
       :description
       "Organize/sort imports in a file via the LSP source.organizeImports action. Applies the edit to disk."
       :parameters
       #js {:type "object"
            :properties #js {:file #js {:type "string" :description "File path"}}
            :required #js ["file"]}
       :display
       #js {:icon "🧹"
            :formatArgs (fn [_n a] (.-file a))}
       :execute
       (fn [args] (organize-imports-execute manager cwd args))})
