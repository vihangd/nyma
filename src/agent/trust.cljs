(ns agent.trust
  "Workspace trust: a checked-out repo may not make nyma run its commands until
   the user says so.

   A project directory controls files nyma reads from cwd, and several of them
   name commands nyma would otherwise run with no prompt at all: code in
   `.nyma/extensions/` (imported at startup), MCP servers in `.mcp.json` and
   friends (spawned at session start, in every mode), hooks, LSP server
   commands, `verify.cmd`, ACP agent commands. Cloning a repo and starting
   nyma in it was enough.

   So: `sources` lists every such entry; `decide!` records the user's answer
   (once per folder, in ~/.nyma/trust.json, keyed by path and a fingerprint of
   exactly those entries — a change to them asks again); and each reader asks
   `allow-file?` / `strip-settings` before honouring a project-controlled value.
   Until a decision is made the folder is UNTRUSTED. No sources means nothing to
   gate."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [clojure.string :as str]
            [agent.utils.home :as home]))

(def ^:private settings-files
  "Project files whose command-bearing KEYS are gated (the rest of each file
   is ordinary configuration and always applies)."
  [".nyma/settings.json" ".nyma/settings.local.json"])

(def ^:private command-files
  "Project files that exist only to name things to run — gated whole."
  [".mcp.json" ".nyma/mcp.json" ".cursor/mcp.json"
   ".claude/settings.json" ".claude/settings.local.json"
   ".agents/hooks.json"])

(def ^:private extensions-dir ".nyma/extensions")

(declare real)

(defn- read-json [f]
  (try (when (fs/existsSync f) (js/JSON.parse (fs/readFileSync f "utf8")))
       (catch :default _ nil)))

(defn- code-files [dir]
  (try
    (->> (fs/readdirSync dir #js {:recursive true})
         (map str)
         (filter #(re-find #"\.(cljs|cljc|ts|js|mjs)$" %))
         sort
         vec)
    (catch :default _ [])))

(defn- content-hash
  "Hash of `files` under `dir`, by content. In the entry but never shown: what
   the code says is the thing trusted, and a pull that rewrites a file changes
   neither its name nor the count."
  [dir files]
  (let [h (crypto/createHash "sha256")]
    (doseq [f files]
      (.update h (str f))
      (try (.update h (fs/readFileSync (path/join dir f)))
           (catch :default _ nil)))
    (.digest h "hex")))

(defn- settings-entries
  "Command-bearing entries in one parsed project settings object."
  [rel obj]
  (when obj
    (let [lsp    (aget obj "lsp")
          agents (some-> (aget obj "agent-shell") (aget "agents"))]
      (concat
       (when-let [h (aget obj "hooks")]
         (for [ev (js/Object.keys h)] {:source (str rel " hooks." ev) :detail (js/JSON.stringify (aget h ev))}))
       (when lsp
         (for [id (js/Object.keys lsp)
               :let [e (aget lsp id)]
               :when (and e (or (aget e "command") (aget e "env")))]
           {:source (str rel " lsp." id) :detail (js/JSON.stringify (or (aget e "command") (aget e "env")))}))
       (when-let [cmd (some-> (aget obj "verify") (aget "cmd"))]
         [{:source (str rel " verify.cmd") :detail (str cmd)}])
       ;; an OpenAPI spec's base URL and headers (`${ENV}`-expanded) decide
       ;; where the user's secrets are sent
       (when-let [apis (some-> (aget obj "codemode") (aget "apis"))]
         (for [k (js/Object.keys apis)]
           {:source (str rel " codemode.apis." k) :detail (js/JSON.stringify (aget apis k))}))
       (when agents
         (for [k (js/Object.keys agents)]
           {:source (str rel " agent-shell.agents." k)
            :detail (js/JSON.stringify (or (some-> (aget agents k) (aget "command")) (aget agents k)))}))))))

(defn- command-file-entries [rel obj]
  (when obj
    (if-let [servers (aget obj "mcpServers")]
      (for [k (js/Object.keys servers)
            :let [s (aget servers k)]]
        ;; :raw for the fingerprint: env and headers can change what runs and
        ;; where secrets go, and are not for printing.
        {:source (str rel " " k) :detail (str/join " " (cons (or (aget s "command") (aget s "url") "")
                                                             (vec (or (aget s "args") []))))
         :raw    (js/JSON.stringify s)})
      (if-let [h (or (aget obj "hooks") (when (= rel ".agents/hooks.json") obj))]
        (for [ev (js/Object.keys h)] {:source (str rel " hooks." ev) :detail (js/JSON.stringify (aget h ev))})
        []))))

(defn- hooks-compat
  "Which foreign hook files the hook bridge would load: `hooks-compat` from
   the first settings file that has it (project local, project, global) — the
   bridge's own rule (claude_hook_bridge/config load-compat-flags). Read here
   rather than required, so core does not depend on an extension."
  [cwd]
  (let [c (some (fn [f] (some-> (read-json f) (aget "hooks-compat")))
                [(path/join cwd ".nyma" "settings.local.json")
                 (path/join cwd ".nyma" "settings.json")
                 (path/join (home/dir) ".nyma" "settings.json")])]
    {:claude (boolean (and c (aget c "claude")))
     :agents (boolean (and c (aget c "agents")))}))

(defn sources
  "Every project-controlled entry in `cwd` that would make nyma run something.
   [{:source \".mcp.json db\" :detail \"npx db-mcp\"} …], in a stable order."
  [cwd]
  (if (= (real cwd) (real (home/dir)))
    [] ; run from HOME, ./.nyma is the user's own global config
  (vec
   (concat
    (let [dir   (path/join cwd extensions-dir)
          files (code-files dir)]
      (when (seq files)
        [{:source extensions-dir :detail (str (count files) " code file(s): " (str/join ", " (take 5 files)))
          :hash   (content-hash dir files)}]))
    ;; code-mode saved scripts: squint a script can call by name
    (let [dir   (path/join cwd ".nyma" "scripts")
          files (when (fs/existsSync dir) (sort (filter #(.endsWith % ".cljs") (vec (fs/readdirSync dir)))))]
      (when (seq files)
        [{:source ".nyma/scripts" :detail (str (count files) " saved script(s): " (str/join ", " (take 5 files)))
          :hash   (content-hash dir files)}]))
    (mapcat #(settings-entries % (read-json (path/join cwd %))) settings-files)
    ;; .claude/ and .agents/ hooks only when hooks-compat would load them:
    ;; asking about commands that can never run is noise that teaches people
    ;; to click Trust without reading.
    (let [compat (hooks-compat cwd)
          live?  (fn [rel] (cond (.startsWith rel ".claude/") (:claude compat)
                                 (.startsWith rel ".agents/") (:agents compat)
                                 :else true))]
      (mapcat #(command-file-entries % (read-json (path/join cwd %)))
              (filter live? command-files)))))))

(defn fingerprint [srcs]
  (-> (crypto/createHash "sha256") (.update (js/JSON.stringify (clj->js srcs))) (.digest "hex")))

(defn- store-path [] (path/join (home/dir) ".nyma" "trust.json"))

(defn- read-store [] (or (read-json (store-path)) #js {}))

(defn stored
  "The stored decision for `cwd`, if it still matches these sources:
   \"trusted\" | \"never\" | nil."
  [cwd srcs]
  (let [e (aget (read-store) (path/resolve cwd))]
    (when (and e (= (aget e "fingerprint") (fingerprint srcs)))
      (aget e "decision"))))

(defn remember!
  "Persist `decision` (\"trusted\" | \"never\") for `cwd` + these sources.
   Atomic write; never replaces an unparseable store (it is left, and the
   decision applies to this session only)."
  [cwd srcs decision]
  (let [f   (store-path)
        raw (when (fs/existsSync f) (fs/readFileSync f "utf8"))
        cur (if (str/blank? raw) #js {} (try (js/JSON.parse raw) (catch :default _ nil)))]
    (when cur
      (aset cur (path/resolve cwd) #js {:fingerprint (fingerprint srcs) :decision decision
                                        :at (.toISOString (js/Date.))})
      (fs/mkdirSync (path/dirname f) #js {:recursive true})
      (let [tmp (str f ".tmp-" (.-pid js/process))]
        (fs/writeFileSync tmp (js/JSON.stringify cur nil 2))
        (fs/renameSync tmp f)))
    decision))

;; The decision for THIS process: :trusted, or :untrusted until decided.
(defonce ^:private decision (atom nil))

(defn decide! [d] (reset! decision d) d)

(defn trusted?
  "Whether project-controlled commands may run. Before `decide!` this falls
   back to the stored decision (or trusted when there is nothing to gate), so
   library and test callers get a safe answer without a prompt."
  []
  (cond
    ;; Run from HOME, ./.nyma IS the user's own global config.
    (= (real (js/process.cwd)) (real (home/dir))) true
    (some? @decision) (= :trusted @decision)
    :else (let [cwd  (js/process.cwd)
                srcs (sources cwd)]
            (or (empty? srcs) (= "trusted" (stored cwd srcs))))))

(defn- real
  "realpath, or — for a file that does not exist yet — its parent's realpath
   plus the name. Both sides of the containment test go through this: on macOS
   cwd is /private/var/… while a path built from os.tmpdir() is /var/…, and a
   symlinked project dir would otherwise look like it was OUTSIDE the project."
  [p]
  (let [abs (path/resolve p)]
    (try (fs/realpathSync abs)
         (catch :default _
           (try (path/join (fs/realpathSync (path/dirname abs)) (path/basename abs))
                (catch :default _ abs))))))

(defn- rel-to-cwd [abs] (path/relative (real (js/process.cwd)) (real abs)))

(defn- project-relative? [abs]
  (let [rel (rel-to-cwd abs)]
    (and (not (str/starts-with? rel "..")) (not (path/isAbsolute rel)))))

(defn allow-file?
  "May nyma honour the project-controlled FILE at `abs`? Files outside cwd (the
   user's own ~/.nyma etc.) always; gated files and the extensions dir only when
   trusted."
  [abs]
  (or (trusted?)
      (not (project-relative? abs))
      (let [rel (rel-to-cwd abs)]
        (not (or (some #(= rel %) command-files)
                 (= rel extensions-dir)
                 (str/starts-with? rel (str extensions-dir "/")))))))

(defn project-file-allowed?
  "Stricter than `allow-file?`: ANY file inside cwd is refused while untrusted.
   For readers that take only command-bearing keys from a file (the hook
   bridge reads nothing but `hooks` from `.nyma/settings.json`)."
  [abs]
  (or (trusted?) (not (project-relative? abs))))

(defn strip-settings
  "A project settings object (plain JS, parsed) with its command-bearing keys
   removed when the folder is untrusted. Never mutates `obj`."
  [obj]
  (if (or (nil? obj) (trusted?))
    obj
    (let [o (js/Object.assign #js {} obj)]
      (js-delete o "hooks")
      (when-let [v (aget o "verify")]
        (let [v' (js/Object.assign #js {} v)] (js-delete v' "cmd") (aset o "verify" v')))
      (when-let [lsp (aget o "lsp")]
        (let [l' #js {}]
          (doseq [id (js/Object.keys lsp)]
            (let [e (js/Object.assign #js {} (aget lsp id))]
              (js-delete e "command")
              (js-delete e "env")
              (aset l' id e)))
          (aset o "lsp" l')))
      (when-let [as (aget o "agent-shell")]
        (let [a' (js/Object.assign #js {} as)] (js-delete a' "agents") (aset o "agent-shell" a')))
      (when-let [cm (aget o "codemode")]
        (let [c' (js/Object.assign #js {} cm)] (js-delete c' "apis") (aset o "codemode" c')))
      o)))

(defn plan
  "What to do about trust at startup. Pure.
     :srcs          (sources cwd)
     :stored        \"trusted\" | \"never\" | nil
     :flag?         --trust / NYMA_TRUST=1 given
     :interactive?  the TUI will run AND stdin and stderr are terminals
   → :trusted | :untrusted | :ask"
  [{:keys [srcs stored flag? interactive?]}]
  (cond
    (empty? srcs)          :trusted
    flag?                  :trusted
    (= stored "trusted")   :trusted
    (= stored "never")     :untrusted
    interactive?           :ask
    :else                  :untrusted))

(defn prompt-text
  "The question, listing what would run. One line per source, clipped."
  [srcs]
  (let [clip (fn [s n] (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s))]
    (str "\n⚠  This folder's project files name commands nyma would run:\n"
         (str/join "\n" (for [{:keys [source detail]} (take 12 srcs)]
                          (str "   " (clip source 44) "  " (clip (str/replace (str detail) #"\s+" " ") 60))))
         (when (> (count srcs) 12) (str "\n   … and " (- (count srcs) 12) " more"))
         "\n\n   [t] Trust this folder   [i] Ignore them this session   [n] Never for this folder"
         "\n   Choice [i]: ")))

(defn ignored-note
  "One stderr line for a run that goes ahead untrusted."
  [srcs]
  (str "nyma: ignoring " (count srcs) " command(s) from this folder's project files ("
       (str/join ", " (map :source (take 3 srcs))) (when (> (count srcs) 3) ", …")
       ") — the folder is not trusted. Run nyma here interactively to review, or pass --trust.\n"))
