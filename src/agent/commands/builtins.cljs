(ns agent.commands.builtins
  (:require [agent.sessions.compaction :refer [compact]]
            [agent.sessions.listing :refer [list-sessions]]
            [agent.commands.share :refer [messages->html messages->markdown]]
            [agent.extension-loader :refer [deactivate-all discover-and-load]]
            [agent.resources.loader :refer [discover]]
            [agent.ui.tree-viewer :refer [create-tree-viewer]]
            [clojure.string :as str]
            ["node:fs" :as fs]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- notify
  "Show a notification via UI if available, else log to console."
  [ctx msg & [level]]
  (if (and ctx (.-ui ctx) (.-notify (.-ui ctx)))
    (.notify (.-ui ctx) msg (or level "info"))
    (js/console.log (str "\n" msg "\n"))))

(defn- show-info
  "Show info text via overlay if available, else log to console."
  [ctx text]
  (if (and ctx (.-ui ctx) (.-showOverlay (.-ui ctx)))
    (.showOverlay (.-ui ctx) text)
    (js/console.log (str "\n" text "\n"))))

(defn scaffold-extension
  "Generate a skeleton ClojureScript extension file."
  [ext-name]
  (str "(ns " ext-name ")\n\n"
       ";; Extension: " ext-name "\n"
       ";; Registered tools, events, and commands go here.\n\n"
       "(defn ^:export default [api]\n"
       "  (js/console.log \"[" ext-name "] loaded\")\n"
       "  ;; Return deactivate function for cleanup\n"
       "  (fn [] (js/console.log \"[" ext-name "] deactivated\")))\n"))

;;; ─── Async handlers (fn ^:async doesn't work in Squint) ──

(defn ^:async handle-reload
  "Orchestrate full reload: deactivate → settings → resources → extensions → flags."
  [agent resources extensions-atom resolve-flags-fn ctx]
  ;; 1. Deactivate loaded extensions
  (when extensions-atom
    (deactivate-all @extensions-atom))
  ;; 2. Reload settings from disk
  (when-let [settings (:settings resources)]
    (when-let [reload-fn (:reload settings)]
      (reload-fn)))
  ;; 3. Rediscover resources
  (let [new-resources (js-await (discover {:events (:events agent)
                                            :reason "reload"}))]
    ;; 4. Rebuild system prompt
    (when-let [build-fn (:build-system-prompt new-resources)]
      (set! (.-system-prompt (:config agent)) (build-fn)))
    ;; 5. Reload extensions
    (when (and extensions-atom (.-extension-api agent))
      (let [loaded (js-await (discover-and-load
                                (:extension-dirs new-resources)
                                (.-extension-api agent)))]
        (reset! extensions-atom loaded)))
    ;; 6. Re-resolve CLI flags
    (when resolve-flags-fn
      (resolve-flags-fn agent)))
  ;; 7. Emit event and notify
  ((:emit (:events agent)) "reload" {})
  (notify ctx "Extensions reloaded"))

(defn ^:async handle-settings
  "Interactive settings viewer/editor."
  [resources ctx]
  (let [settings (:settings resources)
        current  (when settings ((:get settings)))
        entries  (when current
                   (mapv (fn [[k v]] (str (name k) " = " (pr-str v)))
                         (sort-by first current)))]
    (if-not (and ctx (.-ui ctx) (.-select (.-ui ctx)))
      (show-info ctx (str "Settings:\n" (str/join "\n" (map #(str "  " %) entries))))
      (let [choice (js-await (.select (.-ui ctx) "Settings (select to edit):" (clj->js entries)))]
        (when choice
          (let [key-name (first (.split choice " = "))
                new-val  (js-await (.input (.-ui ctx) (str "New value for " key-name ":") ""))]
            (when (and new-val (seq (.trim new-val)))
              ((:set-override settings) (keyword key-name) (.trim new-val))
              (notify ctx (str key-name " = " (.trim new-val))))))))))

(defn ^:async handle-login
  "Prompt for API key and save to credentials file."
  [args ctx]
  (let [provider  (or (first args) "anthropic")
        cred-path (str (.. js/process -env -HOME) "/.nyma/credentials.json")]
    (if-not (and ctx (.-ui ctx) (.-input (.-ui ctx)))
      (notify ctx "Login requires interactive mode" "error")
      (let [key (js-await (.input (.-ui ctx) (str "API key for " provider ":") "sk-..."))]
        (when (and key (seq (.trim key)))
          (let [existing (if (fs/existsSync cred-path)
                           (js/JSON.parse (fs/readFileSync cred-path "utf8"))
                           #js {})
                _ (aset existing provider (.trim key))
                dir (str (.. js/process -env -HOME) "/.nyma")]
            (when-not (fs/existsSync dir)
              (fs/mkdirSync dir #js {:recursive true}))
            (fs/writeFileSync cred-path (js/JSON.stringify existing nil 2))
            (notify ctx (str "Saved " provider " API key"))))))))

;;; ─── Registration ───────────────────────────────────────────

(defn register-builtins
  "Register all built-in slash commands on the agent.
   Pi-mono-compatible: help, model, clear, exit, new, fork, tree,
   compact, debug, reload, name, session, copy, hotkeys, export,
   resume, import, settings, changelog, login, logout, scoped-models."
  [agent session resources & [extensions-atom resolve-flags-fn]]
  (swap! (:commands agent) merge
    {"help"
     {:description "Show available commands"
      :handler (fn [_args ctx]
                 (let [cmds  @(:commands agent)
                       lines (mapv (fn [[name cmd]]
                                     (str "  /" name " - " (or (:description cmd) "")))
                                   (sort-by first cmds))]
                   (show-info ctx (str "Available commands:\n" (str/join "\n" lines)))))}

     "model"
     {:description "Show or change current model"
      :handler (fn [args ctx]
                 (if (seq args)
                   (let [model-spec (str/join " " args)]
                     (when-let [api (.-extension-api agent)]
                       (.setModel api model-spec))
                     (notify ctx (str "Model changed to: " model-spec)))
                   (let [model-id (or (.-modelId (:model (:config agent))) "unknown")]
                     (notify ctx (str "Model: " model-id)))))}

     "clear"
     {:description "Clear messages"
      :handler (fn [_args ctx]
                 ((:dispatch! (:store agent)) :messages-cleared {})
                 (notify ctx "Messages cleared"))}

     "exit"
     {:description "Exit the agent"
      :handler (fn [_args _ctx]
                 ((:emit (:events agent)) "session_shutdown" {:reason "exit"})
                 (js/process.exit 0))}

     "new"
     {:description "Start a new session"
      :handler (fn [_args ctx]
                 ((:dispatch! (:store agent)) :messages-cleared {})
                 ((:emit (:events agent)) "session_start" {:reason "new"})
                 (notify ctx "New session started"))}

     "fork"
     {:description "Fork conversation at current point"
      :handler (fn [_args ctx]
                 (when-let [s @(:session agent)]
                   (let [leaf ((:leaf-id s))]
                     ((:branch s) leaf)
                     ((:emit (:events agent)) "session_start" {:reason "fork"})
                     (notify ctx "Session forked"))))}

     "tree"
     {:description "Show session tree"
      :handler (fn [_args ctx]
                 (when-let [s @(:session agent)]
                   (if (and ctx (.-ui ctx) (.-custom (.-ui ctx)))
                     ;; Interactive tree viewer via custom overlay
                     (.custom (.-ui ctx) (create-tree-viewer s))
                     ;; Fallback: text dump
                     (let [tree ((:get-tree s))
                           lines (->> (take 20 tree)
                                      (map (fn [e]
                                             (str "  " (:id e) " [" (:role e) "] "
                                                  (subs (or (:content e) "") 0
                                                        (min 60 (count (or (:content e) ""))))))))]
                       (show-info ctx
                         (str "Session tree (" (count tree) " entries):\n"
                              (str/join "\n" lines)
                              (when (> (count tree) 20) "\n  ... more entries")))))))}

     "compact"
     {:description "Compact conversation context"
      :handler (fn [_args ctx]
                 (when-let [s @(:session agent)]
                   (compact s (:model (:config agent)) (:events agent))
                   (notify ctx "Compaction complete")))}

     "debug"
     {:description "Show debug information"
      :handler (fn [_args ctx]
                 (let [s @(:state agent)]
                   (show-info ctx
                     (str "--- Debug Info ---\n"
                          "Messages: " (count (:messages s)) "\n"
                          "Input tokens: " (or (:total-input-tokens s) 0) "\n"
                          "Output tokens: " (or (:total-output-tokens s) 0) "\n"
                          "Cost: $" (.toFixed (or (:total-cost s) 0) 4) "\n"
                          "Turns: " (or (:turn-count s) 0) "\n"
                          "Active tools: " (count (:active-tools s)) "\n"
                          "Active executions: " (count (:active-executions s)) "\n"
                          "Thinking level: " @(:thinking-level agent) "\n"
                          "------------------"))))}

     "reload"
     {:description "Reload extensions and configuration"
      :handler (fn [_args ctx]
                 (handle-reload agent resources extensions-atom resolve-flags-fn ctx))}

     ;; ── New pi-mono-compatible commands ─────────────────────

     "name"
     {:description "Set or show session display name"
      :handler (fn [args ctx]
                 (if (seq args)
                   (let [name (str/join " " args)]
                     (when-let [s @(:session agent)]
                       ((:set-session-name s) name))
                     (notify ctx (str "Session named: " name)))
                   (let [current (when-let [s @(:session agent)]
                                   ((:get-session-name s)))]
                     (notify ctx (str "Session: " (or current "(unnamed)"))))))}

     "session"
     {:description "Show session info and stats"
      :handler (fn [_args ctx]
                 (let [s    @(:state agent)
                       sess @(:session agent)
                       fp   (when sess ((:get-file-path sess)))
                       tree (when sess ((:get-tree sess)))
                       name (when sess ((:get-session-name sess)))]
                   (show-info ctx
                     (str "--- Session Info ---\n"
                          "Name: " (or name "(unnamed)") "\n"
                          "Path: " (or fp "(ephemeral)") "\n"
                          "Entries: " (count (or tree [])) "\n"
                          "Messages: " (count (:messages s)) "\n"
                          "Input tokens: " (or (:total-input-tokens s) 0) "\n"
                          "Output tokens: " (or (:total-output-tokens s) 0) "\n"
                          "Cost: $" (.toFixed (or (:total-cost s) 0) 4) "\n"
                          "Turns: " (or (:turn-count s) 0) "\n"
                          "--------------------"))))}

     "copy"
     {:description "Copy last assistant message to clipboard"
      :handler (fn [_args ctx]
                 (let [msgs     (:messages @(:state agent))
                       last-asst (last (filter #(= (:role %) "assistant") msgs))]
                   (if-not last-asst
                     (notify ctx "No assistant message to copy" "error")
                     (let [text     (:content last-asst)
                           platform (.-platform js/process)
                           cmd      (case platform
                                      "darwin" "pbcopy"
                                      "win32"  "clip"
                                      "xclip -selection clipboard")
                           proc     (js/Bun.spawn #js ["sh" "-c" cmd]
                                      #js {:stdin "pipe" :stdout "pipe" :stderr "pipe"})]
                       (.write (.-stdin proc) text)
                       (.end (.-stdin proc))
                       (.then (.-exited proc)
                         (fn [_] (notify ctx "Copied to clipboard")))))))}

     "hotkeys"
     {:description "Show keyboard shortcuts"
      :handler (fn [_args ctx]
                 (let [ext-shortcuts @(:shortcuts agent)
                       builtins [["Escape"  "Abort current generation"]
                                 ["Ctrl+L"  "Show current model"]
                                 ["Ctrl+P"  "Reserved"]]
                       lines (concat
                               ["Built-in:"]
                               (map (fn [[k d]] (str "  " k " - " d)) builtins)
                               (when (seq ext-shortcuts)
                                 (concat ["" "Extensions:"]
                                   (map (fn [[k _]] (str "  " k)) ext-shortcuts))))]
                   (show-info ctx (str/join "\n" lines))))}

     "export"
     {:description "Export session to file (html, md, jsonl)"
      :handler (fn [args ctx]
                 (let [format  (or (first args) "html")
                       sess    @(:session agent)
                       msgs    (or (when sess ((:build-context sess)))
                                   (:messages @(:state agent)))
                       name    (or (when sess ((:get-session-name sess))) "session")
                       ts      (js/Date.now)
                       ext     (case format "md" "md" "jsonl" "jsonl" "html")
                       out-dir (str (js/process.cwd) "/.nyma/exports")
                       out-path (str out-dir "/" name "-" ts "." ext)
                       content (case format
                                 "md"    (messages->markdown msgs name)
                                 "jsonl" (str/join "\n"
                                           (map #(js/JSON.stringify (clj->js %)) msgs))
                                 (messages->html msgs name))]
                   (when-not (fs/existsSync out-dir)
                     (fs/mkdirSync out-dir #js {:recursive true}))
                   (.then (js/Bun.write out-path content)
                     (fn [_] (notify ctx (str "Exported to " out-path))))))}

     "resume"
     {:description "Resume a previous session"
      :handler (fn [_args ctx]
                 (let [dir      (str (.. js/process -env -HOME) "/.nyma/sessions")
                       sessions (list-sessions dir)]
                   (if (empty? sessions)
                     (notify ctx "No sessions found" "error")
                     (if-not (and ctx (.-ui ctx) (.-select (.-ui ctx)))
                       ;; Non-interactive: just list sessions
                       (show-info ctx
                         (str "Available sessions:\n"
                              (str/join "\n"
                                (map-indexed (fn [i s]
                                               (str "  " (inc i) ". " (:name s)
                                                    " (" (:entry-count s) " entries)"))
                                             sessions))))
                       ;; Interactive: show selector
                       (let [options (mapv (fn [s]
                                            (str (:name s) " (" (:entry-count s) " entries)"))
                                          sessions)]
                         (.then (.select (.-ui ctx) "Resume session:" (clj->js options))
                           (fn [choice]
                             (when choice
                               (let [idx  (.indexOf options choice)
                                     sess (nth sessions idx)
                                     sm   @(:session agent)]
                                 ((:switch-file sm) (:path sess))
                                 ((:dispatch! (:store agent)) :messages-cleared {})
                                 (doseq [msg ((:build-context sm))]
                                   ((:dispatch! (:store agent)) :message-added {:message msg}))
                                 ((:emit (:events agent)) "session_start"
                                   {:reason "resume" :previousSessionFile (:path sess)})
                                 (notify ctx (str "Resumed: " (:name sess))))))))))))}

     "import"
     {:description "Import and resume a session from a JSONL file"
      :handler (fn [args ctx]
                 (let [file-path (first args)]
                   (cond
                     (or (nil? file-path) (empty? (str file-path)))
                     (notify ctx "Usage: /import <path.jsonl>" "error")

                     (not (fs/existsSync file-path))
                     (notify ctx (str "File not found: " file-path) "error")

                     :else
                     (let [sm @(:session agent)]
                       ((:switch-file sm) file-path)
                       ((:dispatch! (:store agent)) :messages-cleared {})
                       (doseq [msg ((:build-context sm))]
                         ((:dispatch! (:store agent)) :message-added {:message msg}))
                       ((:emit (:events agent)) "session_start"
                         {:reason "resume" :previousSessionFile file-path})
                       (notify ctx (str "Imported session from " file-path))))))}

     ;; ── Phase 3 commands ───────────────────────────────────

     "settings"
     {:description "View or change settings"
      :handler (fn [_args ctx]
                 (handle-settings resources ctx))}

     "changelog"
     {:description "Show changelog"
      :handler (fn [_args ctx]
                 (let [path "CHANGELOG.md"]
                   (if (fs/existsSync path)
                     (show-info ctx (fs/readFileSync path "utf8"))
                     (notify ctx "No CHANGELOG.md found" "error"))))}

     "login"
     {:description "Set API key for a provider"
      :handler (fn [args ctx]
                 (handle-login args ctx))}

     "logout"
     {:description "Remove API key for a provider"
      :handler (fn [args ctx]
                 (let [provider (or (first args) "anthropic")
                       cred-path (str (.. js/process -env -HOME) "/.nyma/credentials.json")]
                   (if-not (fs/existsSync cred-path)
                     (notify ctx "No credentials found" "error")
                     (let [existing (js/JSON.parse (fs/readFileSync cred-path "utf8"))]
                       (js-delete existing provider)
                       (fs/writeFileSync cred-path (js/JSON.stringify existing nil 2))
                       (notify ctx (str "Removed " provider " API key"))))))}

     "scoped-models"
     {:description "Show or set per-extension model overrides"
      :handler (fn [args ctx]
                 (let [settings (:settings resources)
                       current  (when settings ((:get settings)))
                       scoped   (or (:scoped-models current) {})]
                   (if (>= (count args) 2)
                     ;; Set: /scoped-models ext-name model-id
                     (let [ext-name (first args)
                           model-id (str/join " " (rest args))
                           updated  (assoc scoped ext-name model-id)]
                       ((:set-override settings) :scoped-models updated)
                       (notify ctx (str ext-name " → " model-id)))
                     ;; Show all
                     (if (empty? scoped)
                       (notify ctx "No scoped model overrides set")
                       (show-info ctx
                         (str "Scoped models:\n"
                              (str/join "\n"
                                (map (fn [[k v]] (str "  " k " → " v)) scoped))))))))}

     "new-extension"
     {:description "Create a new extension from template"
      :handler (fn [args ctx]
                 (let [ext-name (or (first args) "my-extension")
                       ext-dir  (str (.. js/process -env -HOME) "/.nyma/extensions")
                       file     (str ext-dir "/" ext-name ".cljs")]
                   (if (fs/existsSync file)
                     (notify ctx (str "Extension already exists: " file) "error")
                     (do
                       (when-not (fs/existsSync ext-dir)
                         (fs/mkdirSync ext-dir #js {:recursive true}))
                       (fs/writeFileSync file (scaffold-extension ext-name))
                       (notify ctx (str "Created extension: " file))))))}}))
