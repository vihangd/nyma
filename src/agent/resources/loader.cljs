(ns agent.resources.loader
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]
            [clojure.string :as str]))

(def global-dir (path/join (os/homedir) ".nyma"))
(def project-dir ".nyma")

;; Built-in extensions dir: resolved relative to this module's compiled location.
;; This module compiles to dist/agent/resources/loader.mjs,
;; so ../extensions/ resolves to dist/agent/extensions/.
(def builtin-extensions-dir
  (path/resolve (js* "import.meta.dir") ".." "extensions"))

(def default-system-prompt
  "You are Nyma, an interactive CLI coding agent. You help users with software engineering tasks: fixing bugs, writing features, refactoring code, answering questions, and exploring codebases.

## Tools

You have specialized tools — always prefer them over bash:

- **read** — Read file contents. Use this, not `cat`/`head`/`tail` in bash.
- **write** — Write content to a file. Use this, not `echo >` in bash.
- **edit** — Replace exact text in a file. Use this, not `sed`/`awk` in bash.
- **bash** — Run shell commands. Only use for build, test, git, and install commands — not for tasks that have a dedicated tool.
- **think** — Reason through complex problems step-by-step before acting.
- **ls** — List directory contents. Use this, not `ls` in bash.
- **glob** — Find files by pattern. Use this, not `find` in bash.
- **grep** — Search file contents with regex. Use this, not `grep`/`rg` in bash.
- **web_fetch** — Fetch and extract content from a URL. Use this, not `curl` in bash.
- **web_search** — Search the web for information. Always use this for any web lookup — never use curl to search engines in bash.

When multiple independent tool calls are needed, make them in parallel.

## Guidelines

- Be concise. Keep responses under 4 lines unless the user asks for detail.
- Use markdown formatting.
- Do not add emojis unless the user requests them.
- Do not start responses with filler (\"Great!\", \"Sure!\", \"Certainly!\").
- Prefer editing existing files over creating new ones.
- Respect existing code conventions, libraries, and patterns in the codebase.
- Only make changes that are requested or clearly necessary — avoid over-engineering.
- When unsure about requirements, ask the user rather than guessing.
- Never generate or guess URLs unless confident they are correct.
- Use the think tool to plan before making complex multi-step changes.")

(defn- read-if-exists [file-path]
  (when (fs/existsSync file-path)
    (fs/readFileSync file-path "utf8")))

(defn- find-agents-files []
  (->> [(path/join global-dir "AGENTS.md")
        (path/join project-dir "AGENTS.md")
        "AGENTS.md"]
       (filter #(fs/existsSync %))))

(defn- discover-themes [dirs]
  (reduce
    (fn [acc dir]
      (if (fs/existsSync dir)
        (let [entries (fs/readdirSync dir)]
          (reduce
            (fn [m file]
              (when (.endsWith file ".json")
                (let [name (subs file 0 (- (count file) 5))]
                  (assoc m name {:path (path/join dir file)}))))
            acc
            entries))
        acc))
    {}
    dirs))

(defn- discover-skills [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
      (->> entries
           (filter #(.isDirectory %))
           (filter #(fs/existsSync (path/join dir (.-name %) "SKILL.md")))
           (map (fn [entry]
                  (let [name      (.-name entry)
                        skill-dir (path/join dir name)
                        md        (fs/readFileSync (path/join skill-dir "SKILL.md") "utf8")
                        has-tools (or (fs/existsSync (path/join skill-dir "tools.ts"))
                                      (fs/existsSync (path/join skill-dir "tools.cljs")))]
                    [name {:dir skill-dir :markdown md :has-tools has-tools :active false}])))
           (into {})))))

(defn- discover-prompts [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir)]
      (->> entries
           (filter #(.endsWith % ".md"))
           (map (fn [file]
                  (let [name (subs file 0 (- (count file) 3))
                        content (fs/readFileSync (path/join dir file) "utf8")]
                    [name {:template content :path (path/join dir file)}])))
           (into {})))))

(defn ^:async discover
  "Discover all resources from global and project directories.
   Optionally accepts an events bus to emit resources_discover for extensions."
  [& [{:keys [events reason]}]]
  (let [skills  (merge
                  (or (discover-skills (path/join global-dir "skills")) {})
                  (or (discover-skills (path/join project-dir "skills")) {}))

        prompts (merge
                  (or (discover-prompts (path/join global-dir "prompts")) {})
                  (or (discover-prompts (path/join project-dir "prompts")) {}))

        themes  (discover-themes [(path/join global-dir "themes")
                                  (path/join project-dir "themes")])

        agents-md (->> (find-agents-files)
                       (map #(fs/readFileSync % "utf8"))
                       (str/join "\n\n"))

        system-md (or (read-if-exists (path/join project-dir "SYSTEM.md"))
                      (read-if-exists (path/join global-dir "SYSTEM.md")))

        ;; Emit resources_discover event so extensions can contribute paths
        ext-resources (when events
                        (js-await
                          ((:emit-collect events) "resources_discover"
                            #js {:cwd    (js/process.cwd)
                                 :reason (or reason "startup")})))

        ;; Merge extension-contributed resources
        ext-skill-paths  (get ext-resources "skillPaths")
        ext-prompt-paths (get ext-resources "promptPaths")
        ext-theme-paths  (get ext-resources "themePaths")

        extra-skills  (when (seq ext-skill-paths)
                        (reduce (fn [acc p] (merge acc (or (discover-skills p) {})))
                                {} ext-skill-paths))
        extra-prompts (when (seq ext-prompt-paths)
                        (reduce (fn [acc p] (merge acc (or (discover-prompts p) {})))
                                {} ext-prompt-paths))
        extra-themes  (when (seq ext-theme-paths)
                        (discover-themes ext-theme-paths))]

    {:skills    (merge skills (or extra-skills {}))
     :prompts   (merge prompts (or extra-prompts {}))
     :themes    (merge themes (or extra-themes {}))
     :agents-md agents-md
     :system-md system-md
     :theme     nil

     :build-system-prompt
     (fn [& [{:keys [append]}]]
       (let [env-block (str "\n\n## Environment\n"
                            "- Working directory: " (js/process.cwd) "\n"
                            "- Platform: " (.-platform js/process) " " (.-arch js/process) "\n"
                            "- Date: " (.toISOString (js/Date.)) "\n")
             skills-block (when (seq skills)
                            (str "\n\n## Available Skills\n"
                                 "Use /skill <name> to activate, or /skills to browse.\n"
                                 (->> skills
                                      (map (fn [[sname {:keys [markdown]}]]
                                             (let [desc (->> (str/split-lines (or markdown ""))
                                                             (remove #(or (str/blank? %) (str/starts-with? % "#")))
                                                             first)]
                                               (str "- " sname (when (seq desc) (str ": " desc))))))
                                      (str/join "\n"))))]
         (str (or system-md default-system-prompt)
              env-block
              skills-block
              (when (seq agents-md) (str "\n\n" agents-md))
              (when append (str "\n\n" append)))))

     :extension-dirs
     (let [user-dirs [(path/join global-dir "extensions")
                      (path/join project-dir "extensions")]]
       (if (.-NYMA_NO_BUILTIN_EXT js/process.env)
         user-dirs
         (into [builtin-extensions-dir] user-dirs)))}))
