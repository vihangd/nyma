(ns agent.resources.skills
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]
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

(defn first-skill-line
  "Return the first non-blank, non-heading line of a SKILL.md string."
  [markdown]
  (or (->> (str/split-lines (or markdown ""))
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           first)
      ""))

(defn ^:async activate-skill
  "Inject skill instructions into context and load skill tools.
   No-op if the skill is already active."
  [skills name agent]
  (when-let [skill (get skills name)]
    (when-not (contains? (:active-skills @(:state agent)) name)
      (swap! (:state agent)
             (fn [s]
               (-> s
                   (update :messages conj {:role "system" :content (:markdown skill)})
                   (update :active-skills conj name))))
      (when (:has-tools skill)
        (let [tool-file (or (let [p (path/join (:dir skill) "tools.cljs")]
                              (when (fs/existsSync p) p))
                            (let [p (path/join (:dir skill) "tools.ts")]
                              (when (fs/existsSync p) p)))]
          (when tool-file
            (let [ext-fn (js-await (load-extension tool-file))]
              (ext-fn (create-extension-api agent)))))))))

(defn deactivate-skill
  "Remove a skill from active-skills tracking.
   Cannot un-inject the system message, but prevents re-activation."
  [name agent]
  (swap! (:state agent) update :active-skills disj name))
