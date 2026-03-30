(ns agent.resources.loader
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]
            [clojure.string :as str]))

(def global-dir (path/join (os/homedir) ".agent"))
(def project-dir ".agent")

(def default-system-prompt
  "You are Nyma, a helpful coding agent. You have access to tools to read files,
write files, edit files, and run shell commands. Use them to help the user with
their software engineering tasks. Be concise and precise.")

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
  "Discover all resources from global and project directories."
  []
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
                      (read-if-exists (path/join global-dir "SYSTEM.md")))]

    {:skills    skills
     :prompts   prompts
     :themes    themes
     :agents-md agents-md
     :system-md system-md
     :theme     nil

     :build-system-prompt
     (fn [& [{:keys [append]}]]
       (str (or system-md default-system-prompt)
            (when (seq agents-md) (str "\n\n" agents-md))
            (when append (str "\n\n" append))))

     :extension-dirs
     [(path/join global-dir "extensions")
      (path/join project-dir "extensions")]}))
