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
   [(path/join home ".nyma" "mcp.json")
    (path/join project-root ".nyma" "mcp.json")
    (path/join project-root ".cursor" "mcp.json")
    (path/join project-root ".mcp.json")]))

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
                   {:name    server-name
                    :url     (expand-env url)
                    :type    type-
                    :headers (obj->name-value-array (.-headers config))}

                   ;; Stdio transport
                   command
                   {:name    server-name
                    :command command
                    :args    (or (expand-env-args (.-args config)) [])
                    :env     (obj->name-value-array (.-env config))}

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
         user-global     (read-mcp-json p-user on-error)
         nyma-project    (read-mcp-json p-nyma on-error)
         cursor-servers  (read-mcp-json p-cursor on-error)
         project-servers (read-mcp-json p-project on-error)
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
                    #js {:description "List or refresh project MCP servers"
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

                                        :else
                                        (notify api "Usage: /agent-shell__mcp [list|refresh]" "error"))))})

  ;; Return deactivator
  (fn []
    (.unregisterCommand api "mcp")
    (reset! shared/mcp-servers [])))
