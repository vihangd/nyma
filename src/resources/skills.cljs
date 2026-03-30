(ns agent.resources.skills
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            [agent.extension-loader :refer [load-extension]]
            [agent.extensions :refer [create-extension-api]]))

(defn discover-skills
  "Scan a directory for skill packages."
  [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
      (->> entries
           (filter #(.isDirectory %))
           (filter #(fs/existsSync (path/join dir (.-name %) "SKILL.md")))
           (map (fn [entry]
                  (let [name      (.-name entry)
                        skill-dir (path/join dir name)
                        md        (fs/readFileSync
                                    (path/join skill-dir "SKILL.md") "utf8")
                        has-tools (or (fs/existsSync (path/join skill-dir "tools.ts"))
                                      (fs/existsSync (path/join skill-dir "tools.cljs")))]
                    [name {:dir       skill-dir
                           :markdown  md
                           :has-tools has-tools
                           :active    false}])))
           (into {})))))

(defn ^:async activate-skill
  "Inject skill instructions into context and load skill tools."
  [skills name agent]
  (when-let [skill (get skills name)]
    (swap! (:state agent) update :messages conj
      {:role "system" :content (:markdown skill)})
    (when (:has-tools skill)
      (let [tool-file (or (let [p (path/join (:dir skill) "tools.cljs")]
                            (when (fs/existsSync p) p))
                          (let [p (path/join (:dir skill) "tools.ts")]
                            (when (fs/existsSync p) p)))]
        (when tool-file
          (let [ext-fn (js-await (load-extension tool-file))]
            (ext-fn (create-extension-api agent))))))))
