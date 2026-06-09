(ns agent.extensions.subagent.agents-md
  "Markdown agent loader. Scans ~/.nyma/agents and .nyma/agents for
   *.md files with YAML frontmatter and compiles each to a role config.

   Frontmatter keys: name, description, model, provider, tools (YAML list
   or comma string). Body after the frontmatter becomes :system-prompt.

   Frontmatter parsing is delegated to the shared real-YAML parser in
   agent.resources.skills (Bun.YAML) — the same one skills/resources use —
   so agent .md files accept the same YAML as SKILL.md (lists, quotes,
   multi-line) instead of a hand-rolled key:value subset.

   Project agents (.nyma/agents) override user agents (~/.nyma/agents)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [clojure.string :as str]
            [agent.resources.skills :as skills]))

(defn- fm-get [fm k]
  ;; Bun.YAML returns a JS object (string keys); tolerate CLJS maps too.
  (when fm (or (get fm k) (aget fm k))))

(defn- parse-tools
  "Accept tools as a YAML list (array) or a comma/space-separated string."
  [t]
  (cond
    (nil? t) nil
    (js/Array.isArray t) (vec t)
    (string? t) (->> (str/split t #"[,\s]+") (remove str/blank?) vec)
    :else nil))

(defn- md->role
  "Convert parsed frontmatter (JS obj or map) + body into [role-name role-config]."
  [fm body fallback-name]
  (let [nm       (or (fm-get fm "name") fallback-name)
        tools    (parse-tools (fm-get fm "tools"))
        provider (or (fm-get fm "provider") "anthropic")
        model    (fm-get fm "model")]
    [nm (cond-> {:provider provider
                 :description (or (fm-get fm "description") "")
                 :system-prompt (or body "")}
          model       (assoc :model model)
          (seq tools) (assoc :allowed-tools tools))]))

(defn parse-md
  "Pure: parse a markdown agent string into [role-name role-config].
   Exposed for testing."
  [text fallback-name]
  (let [{:keys [frontmatter body]} (skills/parse-frontmatter text)]
    (md->role frontmatter body fallback-name)))

(defn- read-dir
  "Read all *.md agent files in `dir`. Returns {role-name role-config}.
   Missing dir → {}."
  [dir]
  (try
    (if-not (fs/existsSync dir)
      {}
      (reduce
       (fn [acc fname]
         (if-not (str/ends-with? fname ".md")
           acc
           (let [full (path/join dir fname)
                 text (str (fs/readFileSync full "utf8"))
                 base (.slice fname 0 (- (count fname) 3))
                 [k cfg] (parse-md text base)]
             (assoc acc k cfg))))
       {}
       (vec (fs/readdirSync dir))))
    (catch :default _e {})))

(defn load-agents
  "Load markdown agents. Project (.nyma/agents) overrides user
   (~/.nyma/agents). Returns {role-name role-config}."
  []
  (let [home (os/homedir)
        user (read-dir (path/join home ".nyma" "agents"))
        proj (read-dir (path/join (js/process.cwd) ".nyma" "agents"))]
    (merge user proj)))
