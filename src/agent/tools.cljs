(ns agent.tools
  (:require ["ai" :refer [tool]]
            ["zod" :as z]))

(defn ^:async read-execute [{:keys [path range]}]
  (let [content (js-await (.text (js/Bun.file path)))]
    (if range
      (let [lines (.split content "\n")]
        (.join (.slice lines (dec (first range)) (second range)) "\n"))
      content)))

(def read-tool
  (tool
    #js {:description "Read file contents"
         :parameters  (.object z
                        #js {:path  (-> (.string z)
                                        (.describe "File path to read"))
                             :range (-> (.tuple z #js [(.number z) (.number z)])
                                        (.optional)
                                        (.describe "Line range [start, end]"))})
         :execute read-execute}))

(defn ^:async write-execute [{:keys [path content]}]
  (js-await (js/Bun.write path content))
  (str "Wrote " (count content) " bytes to " path))

(def write-tool
  (tool
    #js {:description "Write content to a file, creating directories as needed"
         :parameters  (.object z
                        #js {:path    (.string z)
                             :content (.string z)})
         :execute write-execute}))

(defn ^:async edit-execute [{:keys [path old_string new_string]}]
  (let [content (js-await (.text (js/Bun.file path)))
        updated (.replace content old_string new_string)]
    (when (= updated content)
      (throw (js/Error. "old_string not found in file")))
    (js-await (js/Bun.write path updated))
    "Edit applied successfully"))

(def edit-tool
  (tool
    #js {:description "Replace exact text in a file"
         :parameters  (.object z
                        #js {:path       (.string z)
                             :old_string (.string z)
                             :new_string (.string z)})
         :execute edit-execute}))

(defn ^:async bash-execute [{:keys [command timeout]}]
  (let [proc   (js/Bun.spawn #js ["sh" "-c" command]
                 #js {:timeout (or timeout 30000)
                      :stdout  "pipe"
                      :stderr  "pipe"})
        stdout (js-await (.text (js/Response. (.-stdout proc))))
        stderr (js-await (.text (js/Response. (.-stderr proc))))
        code   (js-await (.-exited proc))]
    (js/JSON.stringify
      #js {:stdout   stdout
           :stderr   stderr
           :exitCode code})))

(def bash-tool
  (tool
    #js {:description "Run a shell command and return stdout/stderr"
         :parameters  (.object z
                        #js {:command (.string z)
                             :timeout (-> (.number z) (.optional)
                                          (.describe "Timeout in ms, default 30000"))})
         :execute bash-execute}))

(def builtin-tools
  {"read"  read-tool
   "write" write-tool
   "edit"  edit-tool
   "bash"  bash-tool})
