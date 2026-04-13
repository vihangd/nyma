(ns agent.file-access
  "File access restrictions via .nymaignore (gitignore-style patterns)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn load-ignore-patterns
  "Load patterns from .nymaignore. Returns vector of pattern strings."
  [cwd]
  (let [ignore-path (path/join cwd ".nymaignore")]
    (if (fs/existsSync ignore-path)
      (->> (str/split-lines (fs/readFileSync ignore-path "utf8"))
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           vec)
      [])))

(defn- pattern->regex
  "Convert a gitignore-style pattern to a regex string."
  [pattern]
  (-> pattern
      (.replace "." "\\.")
      (.replace "**" "<<GLOBSTAR>>")
      (.replace "*" "[^/]*")
      (.replace "<<GLOBSTAR>>" ".*")))

(defn matches-pattern?
  "Check if a file path matches a gitignore-style pattern."
  [file-path pattern]
  (let [regex (js/RegExp. (str "(?:^|/)" (pattern->regex pattern) "$"))]
    (.test regex file-path)))

(defn check-access
  "Check if a file path is allowed. Returns {:allowed bool :reason string}."
  [file-path patterns]
  (let [denied (some #(matches-pattern? file-path %) patterns)]
    (if denied
      {:allowed false :reason (str "Blocked by .nymaignore: " file-path)}
      {:allowed true})))

(defn register-access-check
  "Register a high-priority before_tool_call handler that checks file access."
  [events cwd]
  (let [patterns (load-ignore-patterns cwd)]
    (when (seq patterns)
      ((:on events) "before_tool_call"
        (fn [data]
          (let [tool-name (or (.-name data) (.-toolName data))
                args      (.-args data)
                path-arg  (when args (or (.-path args) (aget args "path")))]
            (when (and path-arg (#{"read" "write" "edit" "glob" "grep"} tool-name))
              (let [result (check-access path-arg patterns)]
                (when-not (:allowed result)
                  #js {:block true :reason (:reason result)})))))
        100)))) ;; priority 100 = runs before extension handlers
