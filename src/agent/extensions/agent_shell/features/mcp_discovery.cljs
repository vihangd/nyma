(ns agent.extensions.agent-shell.features.mcp-discovery
  "Discover MCP servers from project config files (.mcp.json, .cursor/mcp.json)
   and make them available to ACP agents via session/new."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.agent-shell.shared :as shared]))

(defn- notify [api msg & [level]]
  (when (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))))

;;; ─── Config reading ────────────────────────────────────────────

(defn- read-mcp-json
  "Read and parse an .mcp.json file. Returns the mcpServers object or nil."
  [file-path]
  (try
    (when (fs/existsSync file-path)
      (let [raw    (fs/readFileSync file-path "utf8")
            parsed (js/JSON.parse raw)]
        (.-mcpServers parsed)))
    (catch :default _e nil)))

(defn- expand-env
  "Expand ${ENV_VAR} placeholders in a string using process.env."
  [s]
  (if (string? s)
    (.replace s (js/RegExp. "\\$\\{([^}]+)\\}" "g")
      (fn [_match var-name]
        (or (aget js/process.env var-name) "")))
    s))

(defn- expand-env-obj
  "Expand ${ENV_VAR} placeholders in all string values of a JS object."
  [obj]
  (when obj
    (let [result #js {}]
      (doseq [k (js/Object.keys obj)]
        (aset result k (expand-env (aget obj k))))
      result)))

;;; ─── Format conversion ────────────────────────────────────────

(defn object->array
  "Convert MCP servers from .mcp.json object format to ACP array format.
   Input:  {\"my-db\": {command: \"npx\", args: [...], env: {...}}}
   Output: [{name: \"my-db\", command: \"npx\", args: [...], env: {...}}]"
  [servers-obj]
  (if (and servers-obj (> (.-length (js/Object.keys servers-obj)) 0))
    (let [keys (js/Object.keys servers-obj)]
      (vec
        (map (fn [name]
               (let [config (aget servers-obj name)]
                 {:name    name
                  :command (.-command config)
                  :args    (or (.-args config) [])
                  :env     (expand-env-obj (.-env config))}))
             keys)))
    []))

;;; ─── Discovery ─────────────────────────────────────────────────

(defn scan-mcp-servers
  "Scan project root for MCP server configs.
   Reads .mcp.json and .cursor/mcp.json, merges them (project .mcp.json wins).
   Returns array format [{:name :command :args :env}]."
  [project-root]
  (let [;; Read both config locations
        cursor-servers (read-mcp-json (path/join project-root ".cursor" "mcp.json"))
        project-servers (read-mcp-json (path/join project-root ".mcp.json"))
        ;; Merge: project .mcp.json overrides .cursor/mcp.json
        merged #js {}]
    ;; Copy cursor servers first (lower precedence)
    (when cursor-servers
      (doseq [k (js/Object.keys cursor-servers)]
        (aset merged k (aget cursor-servers k))))
    ;; Copy project servers second (higher precedence, overwrites)
    (when project-servers
      (doseq [k (js/Object.keys project-servers)]
        (aset merged k (aget project-servers k))))
    (object->array merged)))

(defn- scan-and-store!
  "Scan for MCP servers and update the shared atom."
  [project-root]
  (let [servers (scan-mcp-servers project-root)]
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

;;; ─── Activation ────────────────────────────────────────────────

(defn activate
  "Scan for MCP servers and register /mcp command."
  [api]
  ;; Initial scan
  (let [servers (scan-and-store! (js/process.cwd))]
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
                        (let [servers @shared/mcp-servers]
                          (if (empty? servers)
                            (notify api "No MCP servers discovered. Add a .mcp.json to your project root.")
                            (notify api (format-server-list servers))))

                        (= subcmd "refresh")
                        (let [servers (scan-and-store! (js/process.cwd))]
                          (notify api (str "Refreshed: " (count servers) " MCP server(s) found")))

                        :else
                        (notify api "Usage: /agent-shell__mcp [list|refresh]" "error"))))})

  ;; Return deactivator
  (fn []
    (.unregisterCommand api "mcp")
    (reset! shared/mcp-servers [])))
