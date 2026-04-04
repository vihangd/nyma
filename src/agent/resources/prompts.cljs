(ns agent.resources.prompts
  (:require ["node:path" :as path]
            ["node:fs" :as fs]))

(defn discover-prompts
  "Find .md files in a directory. Each becomes a /command."
  [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir)]
      (->> entries
           (filter #(.endsWith % ".md"))
           (map (fn [file]
                  (let [name    (subs file 0 (- (count file) 3))
                        content (fs/readFileSync (path/join dir file) "utf8")]
                    [name {:template content
                           :path     (path/join dir file)}])))
           (into {})))))

(defn expand-template
  "Replace {{variable}} placeholders in a template string."
  [template variables]
  (reduce-kv
    (fn [s k v]
      (.replace s (js/RegExp. (str "\\{\\{" (str k) "\\}\\}") "g") (str v)))
    template
    variables))
