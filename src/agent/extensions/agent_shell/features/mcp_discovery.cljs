(ns agent.extensions.agent-shell.features.mcp-discovery
  "Discover MCP servers from a stack of config files. Precedence
   (lowest → highest, later overrides earlier on name collision):

     1. ~/.nyma/mcp.json           — user-global
     2. <cwd>/.nyma/mcp.json       — project, shared via VCS
     3. <cwd>/.cursor/mcp.json     — Cursor compat
     4. <cwd>/.mcp.json            — project, CC convention

   Servers are made available to nyma's main LLM via the
   mcp-client extension (registered as `mcp__<server>__<tool>`),
   and forwarded to ACP subprocess agents at session/new."
  (:require [agent.utils.home :as home]
             [agent.debug :as d]
            [agent.trust :as trust]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]))

(defn- notify [api msg & [level]]
  (when (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))))

;;; ─── Config reading ────────────────────────────────────────────

(defn malformed-config-message
  "The error shown for an .mcp.json that will not parse. Names the exact
   path — a present-but-broken config silently yielding 0 servers is
   indistinguishable from having no config at all."
  [file-path err-msg]
  (str "Malformed MCP config: " file-path " — " err-msg
       ". Its servers will NOT load."))

(defn- read-mcp-json
  "Read and parse an .mcp.json file. Returns the mcpServers object or nil.
   On a parse failure `on-error` is called with a message naming the exact
   path (and the debug log still gets it). This used to only warn to
   debug.log, where nobody running nyma normally would ever see it."
  [file-path & [on-error]]
  (try
    (when (fs/existsSync file-path)
      (let [raw    (fs/readFileSync file-path "utf8")
            parsed (js/JSON.parse raw)]
        (.-mcpServers parsed)))
    (catch :default e
      (let [m (malformed-config-message file-path (.-message e))]
        (d/warn "[mcp-discovery]" m)
        (when on-error (on-error m)))
      nil)))

;;; ─── Candidate config files ───────────────────────────────────

(defn candidate-paths
  "The four files consulted, lowest → highest precedence. One list, so the
   scan and the report it prints cannot drift apart."
  ([project-root] (candidate-paths project-root (home/dir)))
  ([project-root home]
   ;; Each FILE once. `project-root` is the cwd, so from the home directory the
   ;; first two name the same file: it was read and parsed twice, a malformed
   ;; one was reported to the user twice, and `/mcp list` showed it as two
   ;; separate config sources. The merged server set was always fine — servers
   ;; are keyed by name — so this is about the noise, not the result.
   (let [seen (atom #{})]
     (vec (keep (fn [p]
                  (let [k (try (fs/realpathSync p)
                               (catch :default _e (path/resolve p)))]
                    (when-not (contains? @seen k)
                      (swap! seen conj k)
                      p)))
                [(path/join home ".nyma" "mcp.json")
                 (path/join project-root ".nyma" "mcp.json")
                 (path/join project-root ".cursor" "mcp.json")
                 (path/join project-root ".mcp.json")])))))

(defn truncate-path-middle
  "Shorten a path to `max` chars by cutting from the MIDDLE, so the parent
   and basename — the parts that tell candidates apart — survive.

   The candidates were tail-truncated to the terminal width, which made
   `<deep project root>/.nyma/mcp.json` and `<deep project root>/.cursor/
   mcp.json` the same string. Shortening the head instead loses the parent;
   the middle is where the shared prefix lives."
  [p limit]
  (let [p (str p)]
    (if (<= (count p) limit)
      p
      (let [tail-n (min (count p) (max 12 (quot limit 2)))
            tail   (subs p (- (count p) tail-n))
            head-n (max 0 (- limit tail-n 1))]
        (str (subs p 0 head-n) "…" tail)))))

(defn format-candidates
  "Render `[{:file \"…\" :exists? bool}]` as the consulted-files report, one
   line per candidate with the status at the end. Pure, so both /mcp list and
   /mcp-status can print it without a filesystem in the test. `width` is the
   line budget (the terminal's columns); nil means no truncation."
  [entries & [width]]
  (str "Config files consulted (lowest → highest precedence):\n"
       (str/join "\n"
                 (map (fn [e]
                        (let [status (if (:exists? e) "" " (not found)")
                              file   (if width
                                       (truncate-path-middle (:file e) (- width 4 (count status)))
                                       (:file e))]
                          (str "  " (if (:exists? e) "✓" "·") " " file status)))
                      entries))))

(defn candidate-report
  "The consulted-files report for a real filesystem."
  ([project-root] (candidate-report project-root (home/dir)))
  ([project-root home]
   (format-candidates
    (mapv (fn [p] {:file p :exists? (boolean (fs/existsSync p))})
          (candidate-paths project-root home))
    ;; nil off a pipe: nothing wraps there, so nothing is cut. In the TUI the
    ;; info card's frame and indent take a few columns off the terminal's.
    (some-> (.-columns (.-stdout js/process)) (- 8)))))

(defn- expand-env
  "Expand ${ENV_VAR} placeholders in a string using process.env."
  [s]
  (if (string? s)
    (.replace s (js/RegExp. "\\$\\{([^}]+)\\}" "g")
              (fn [_match var-name]
                (or (aget js/process.env var-name) "")))
    s))

(defn- obj->name-value-array
  "Convert a JS object {K: V} to ACP array [{name: K, value: V}] with env-expansion."
  [obj]
  (if (and obj (< 0 (.-length (js/Object.keys obj))))
    (let [ks (js/Object.keys obj)]
      (mapv (fn [k] {:name k :value (expand-env (aget obj k))}) ks))
    []))

(defn- expand-env-args
  "Expand ${ENV_VAR} placeholders in each string element of an args array.
   Non-string elements pass through unchanged (defensive — JSON args are
   always strings, but be safe)."
  [args]
  (when args
    (let [out  #js []
          n    (.-length args)]
      (doseq [i (range n)]
        (let [v (aget args i)]
          (.push out (if (string? v) (expand-env v) v))))
      out)))

;;; ─── Format conversion ────────────────────────────────────────

(defn- nyma-opts
  "nyma-only per-server tuning from a .mcp.json entry, or nil. Kept under one
   `:nyma` key so it can be stripped before the list goes to an ACP agent,
   whose schema knows none of it."
  [config]
  (let [n   (fn [k] (let [v (aget config k)] (when (and (number? v) (pos? v)) v)))
        strs (fn [k] (let [v (aget config k)] (when (js/Array.isArray v) (vec (filter string? v)))))
        m   (cond-> {}
              (n "startupTimeoutMs") (assoc :startup-timeout-ms (n "startupTimeoutMs"))
              (n "callTimeoutMs")    (assoc :call-timeout-ms (n "callTimeoutMs"))
              (seq (strs "allowTools")) (assoc :allow-tools (strs "allowTools"))
              (seq (strs "denyTools"))  (assoc :deny-tools (strs "denyTools")))]
    (when (seq m) m)))

(defn object->array
  "Convert MCP servers from .mcp.json object format to ACP array format.
   Produces schema-valid objects:
   - Stdio: {name, command, args, env: [{name,value}...]}
   - HTTP/SSE: {name, url, type, headers: [{name,value}...]}

   Discriminator: explicit `type` > presence of `url` (defaults to \"http\") > stdio."
  [servers-obj]
  (if (and servers-obj (> (.-length (js/Object.keys servers-obj)) 0))
    (let [ks (js/Object.keys servers-obj)]
      (vec
       (keep (fn [server-name]
               (let [config  (aget servers-obj server-name)
                     url     (.-url config)
                     command (.-command config)
                     type-   (or (.-type config) (.-transportType config)
                                 (when url "http"))]
                 (cond
                   ;; HTTP or SSE transport
                   (and url (or (= type- "http") (= type- "sse")))
                   (cond-> {:name    server-name
                            :url     (expand-env url)
                            :type    type-
                            :headers (obj->name-value-array (.-headers config))}
                     (nyma-opts config) (assoc :nyma (nyma-opts config)))

                   ;; Stdio transport
                   command
                   (cond-> {:name    server-name
                            :command command
                            :args    (or (expand-env-args (.-args config)) [])
                            :env     (obj->name-value-array (.-env config))}
                     (nyma-opts config) (assoc :nyma (nyma-opts config)))

                   :else nil)))
             ks)))
    []))

;;; ─── Discovery ─────────────────────────────────────────────────

(defn scan-mcp-servers
  "Walk the source list in precedence order (lowest first), merging
   `mcpServers` blocks. Later sources override earlier ones on the
   same server name. Returns array format [{:name :command :args :env}].

   Sources, lowest → highest precedence:
     1. ~/.nyma/mcp.json
     2. <cwd>/.nyma/mcp.json
     3. <cwd>/.cursor/mcp.json
     4. <cwd>/.mcp.json"
  ([project-root] (scan-mcp-servers project-root (home/dir) nil))
  ([project-root home] (scan-mcp-servers project-root home nil))
  ([project-root home on-error]
   (let [[p-user p-nyma p-cursor p-project] (candidate-paths project-root home)
         ;; Project files name commands to spawn: honoured only in a trusted
         ;; folder (agent.trust). The user's own ~/.nyma/mcp.json always is.
         read*           (fn [p] (when (trust/allow-file? p) (read-mcp-json p on-error)))
         user-global     (read-mcp-json p-user on-error)
         nyma-project    (read* p-nyma)
         cursor-servers  (read* p-cursor)
         project-servers (read* p-project)
         merged #js {}
         copy-into! (fn [src]
                      (when src
                        (doseq [k (js/Object.keys src)]
                          (aset merged k (aget src k)))))]
     ;; Lowest → highest precedence — each pass overwrites prior names.
     (copy-into! user-global)
     (copy-into! nyma-project)
     (copy-into! cursor-servers)
     (copy-into! project-servers)
     (object->array merged))))

(defn- scan-and-store!
  "Scan for MCP servers and update the shared atom."
  [project-root & [on-error]]
  (let [servers (scan-mcp-servers project-root (home/dir) on-error)]
    (reset! shared/mcp-servers servers)
    servers))

;;; ─── Formatting ────────────────────────────────────────────────

(defn- format-server-list
  "Format discovered servers for display."
  [servers]
  (str "MCP servers (" (count servers) "):\n"
       (str/join "\n"
                 (map (fn [s]
                        (str "  " (:name s) " — " (:command s)
                             (when (seq (:args s))
                               (str " " (str/join " " (:args s))))))
                      servers))))

(defn server-list-report
  "Everything `/mcp list` prints: the discovered servers, then the config
   files that were consulted. \"No MCP servers discovered\" on its own never
   said where nyma had looked."
  ([servers project-root] (server-list-report servers project-root (home/dir)))
  ([servers project-root home]
   (str (if (empty? servers)
          "No MCP servers discovered. Add a .mcp.json to your project root."
          (format-server-list servers))
        "\n\n"
        (candidate-report project-root home))))

;;; ─── /mcp add · /mcp remove ────────────────────────────────────

(defn- kv [s]
  (let [i (.indexOf (str s) "=")]
    (when (pos? i) [(.slice s 0 i) (.slice s (inc i))])))

(defn parse-add-args
  "`/mcp add` tokens → {:name :entry :project?} or {:error}. Pure.
   Flags come BEFORE the command; from the command on, every token is the
   server's own argv, verbatim — so `npx … --flag` passes through. A `--`
   may mark the switch explicitly. `--url` makes an http server instead."
  [tokens]
  (let [[nm & more] (vec tokens)]
    (if (or (nil? nm) (str/starts-with? (str nm) "-"))
      {:error "usage: /mcp add <name> [--env K=V] [--timeout ms] [--allow g] [--deny g] [--project] [--] <command> [args…]  |  /mcp add <name> --url <url> [--header K=V]"}
      (loop [ts (vec more) acc {:env {} :headers {} :allow [] :deny []}]
        (let [[t v] ts]
          (cond
            (and (nil? t) (nil? (:url acc)))
            {:error (str "/mcp add " nm ": give a command to run, or --url")}

            (or (nil? t) (and (= t "--") (seq (rest ts))) (and (not (str/starts-with? t "--")) (nil? (:url acc))))
            (let [argv  (vec (if (= t "--") (rest ts) ts))
                  entry (cond-> (if-let [u (:url acc)]
                                  {"url" u}
                                  {"command" (first argv) "args" (vec (rest argv))})
                          (seq (:env acc))     (assoc "env" (:env acc))
                          (seq (:headers acc)) (assoc "headers" (:headers acc))
                          (:timeout acc)       (assoc "callTimeoutMs" (:timeout acc) "startupTimeoutMs" (:timeout acc))
                          (seq (:allow acc))   (assoc "allowTools" (:allow acc))
                          (seq (:deny acc))    (assoc "denyTools" (:deny acc)))]
              (if (and (nil? (:url acc)) (str/blank? (str (first argv))))
                {:error (str "/mcp add " nm ": give a command to run, or --url")}
                {:name nm :entry entry :project? (boolean (:project acc))}))

            (= t "--project") (recur (vec (rest ts)) (assoc acc :project true))
            (nil? v) {:error (str "/mcp add: " t " needs a value")}
            (= t "--url")     (recur (vec (drop 2 ts)) (assoc acc :url v))
            (= t "--timeout") (let [n (js/Number v)]
                                (if (and (js/Number.isFinite n) (pos? n))
                                  (recur (vec (drop 2 ts)) (assoc acc :timeout n))
                                  {:error (str "/mcp add: --timeout wants milliseconds, got " v)}))
            (= t "--allow")   (recur (vec (drop 2 ts)) (update acc :allow conj v))
            (= t "--deny")    (recur (vec (drop 2 ts)) (update acc :deny conj v))
            (contains? #{"--env" "--header"} t)
            (if-let [[k x] (kv v)]
              (recur (vec (drop 2 ts)) (update acc (if (= t "--env") :env :headers) assoc k x))
              {:error (str "/mcp add: " t " wants KEY=VALUE, got " v)})
            :else {:error (str "/mcp add: unknown flag " t)}))))))

(defn- read-mcp-file
  "Raw JSON of an mcp config file for a read-modify-write — NOT the settings
   reader, which kebab-cases keys (`mcpServers` → `mcp-servers`). Missing or
   blank → {}; present but unparsable → thrown, so nothing is overwritten."
  [f]
  (if-not (fs/existsSync f)
    #js {}
    (let [text (fs/readFileSync f "utf8")]
      (if (str/blank? text)
        #js {}
        (try (js/JSON.parse text)
             (catch :default e
               (throw (js/Error. (str "Not changing " f ": it does not parse (" (.-message e) ")")))))))))

(defn- write-mcp-file!
  "Atomic, and through a symlink to its target."
  [f obj]
  (let [f   (try (fs/realpathSync f) (catch :default _ f))
        tmp (str f ".tmp-" (.-pid js/process))]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/writeFileSync tmp (str (js/JSON.stringify obj nil 2) "\n"))
    (fs/renameSync tmp f)))

(defn mcp-file
  "Where /mcp add writes: ~/.nyma/mcp.json, or the project's .mcp.json."
  [project? cwd home]
  (if project? (path/join cwd ".mcp.json") (path/join home ".nyma" "mcp.json")))

(defn add-server!
  "Write `entry` as server `nm` into `f`, keeping everything else. Returns
   true when it replaced an existing entry."
  [f nm entry]
  (let [obj     (read-mcp-file f)
        servers (or (aget obj "mcpServers") #js {})
        existed (some? (aget servers nm))]
    (aset servers nm (clj->js entry))
    (aset obj "mcpServers" servers)
    (write-mcp-file! f obj)
    existed))

(defn remove-server!
  "Delete server `nm` from `f`. Returns false when it was not there."
  [f nm]
  (let [obj     (read-mcp-file f)
        servers (aget obj "mcpServers")]
    (if (and servers (some? (aget servers nm)))
      (do (js-delete servers nm)
          (write-mcp-file! f obj)
          true)
      false)))

(defn- tilde [p home] (if (str/starts-with? p home) (str "~" (subs p (count home))) p))

;;; ─── Activation ────────────────────────────────────────────────

(defn activate
  "Scan for MCP servers and register /mcp command."
  [api]
  ;; Initial scan. A broken config is an ERROR in the transcript naming the
  ;; file, not a line in debug.log nobody reads.
  (let [report-error! (fn [m] (notify api m "error"))
        servers (scan-and-store! (js/process.cwd) report-error!)]
    (when (pos? (count servers))
      (notify api (str "Discovered " (count servers) " MCP server"
                       (when (> (count servers) 1) "s")
                       " from project config"))))

  ;; Register /mcp command
  (.registerCommand api "mcp"
                    #js {:description "MCP servers: list, refresh, add <name> [flags] <command…> (writes ~/.nyma/mcp.json, or .mcp.json with --project), remove <name>"
                         :handler (fn [args _ctx]
                                    (let [subcmd (first args)]
                                      (cond
                                        (or (nil? subcmd) (= subcmd "list"))
                                        (notify api (server-list-report @shared/mcp-servers
                                                                        (js/process.cwd)))

                                        (= subcmd "refresh")
                                        (let [servers (scan-and-store! (js/process.cwd)
                                                                       (fn [m] (notify api m "error")))]
                                          (notify api (str "Refreshed: " (count servers) " MCP server(s) found\n\n"
                                                           (candidate-report (js/process.cwd)))))

                                        (= subcmd "add")
                                        (let [r (parse-add-args (rest args))]
                                          (if (:error r)
                                            (notify api (:error r) "error")
                                            (try
                                              (let [f    (mcp-file (:project? r) (js/process.cwd) (home/dir))
                                                    had? (add-server! f (:name r) (:entry r))]
                                                (scan-and-store! (js/process.cwd) (fn [m] (notify api m "error")))
                                                (notify api (str (if had? "Replaced " "Added ") (:name r) " in "
                                                                 (tilde f (home/dir))
                                                                 " — it starts with the next session; /mcp list to check"
                                                                 (when (:project? r) ". A project file asks for folder trust before it runs."))))
                                              (catch :default e (notify api (.-message e) "error")))))

                                        (= subcmd "remove")
                                        (let [nm       (second args)
                                              project? (some #(= "--project" %) args)]
                                          (if-not (and nm (not (str/starts-with? nm "-")))
                                            (notify api "usage: /mcp remove <name> [--project]" "error")
                                            (try
                                              (let [f (mcp-file project? (js/process.cwd) (home/dir))]
                                                (if (remove-server! f nm)
                                                  (do (scan-and-store! (js/process.cwd) (fn [m] (notify api m "error")))
                                                      (notify api (str "Removed " nm " from " (tilde f (home/dir)))))
                                                  (notify api (str "No server named " nm " in " (tilde f (home/dir))) "warning")))
                                              (catch :default e (notify api (.-message e) "error")))))

                                        :else
                                        (notify api "Usage: /mcp [list|refresh|add|remove]" "error"))))})

  ;; Return deactivator
  (fn []
    (.unregisterCommand api "mcp")
    (reset! shared/mcp-servers [])))
