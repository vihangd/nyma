(ns agent.extensions.codemode.files
  "File helpers for scripts, registered as ordinary tools so the gate,
   `.nymaignore` (any tool with a `path` arg) and checkpoints
   (`tool-metadata/file-editing?`) see them. Scripts call them as
   `nyma/save-file` and `nyma/pdf-text`; they are not offered directly.

   The case they exist for: an MCP attachment the bridge already saved to
   ~/Downloads/nyma, which a script copies where the user asked, or reads."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.utils.pdf :as pdf]))

(defn- resolve-path [p] (path/resolve (js/process.cwd) (str p)))

(defn ^:async save-file
  "{path, from | base64 | text} → {path, bytes}. Creates parent dirs."
  [args]
  (let [p    (resolve-path (aget args "path"))
        from (aget args "from")
        b64  (aget args "base64")
        text (aget args "text")
        buf  (cond from        (let [f (resolve-path from)]
                                 ;; a benchmark model passed an attachment id here
                                 (when-not (fs/existsSync f)
                                   (throw (js/Error. (str "no file at " from " — :from takes a path; a saved attachment's is "
                                                          "(:path (first (:files (js-await (ns/get-attachment {…})))))"))))
                                 (fs/readFileSync f))
                   (string? b64) (js/Buffer.from b64 "base64")
                   (some? text)  (js/Buffer.from (str text) "utf8")
                   :else (throw (js/Error. "save-file needs one of :from, :base64 or :text")))]
    (fs/mkdirSync (path/dirname p) #js {:recursive true})
    (fs/writeFileSync p buf)
    (js/JSON.stringify #js {:path p :bytes (.-length buf)})))

(defn ^:async pdf-text
  "{path, from?, to?} → {text, pages}."
  [args]
  (let [buf (fs/readFileSync (resolve-path (aget args "path")))
        r   (js-await (pdf/extract-text buf {:from (aget args "from") :to (aget args "to")}))]
    (when (:error r) (throw (js/Error. (str (:error r)))))
    (js/JSON.stringify #js {:text (:text r) :pages (:pages r)})))

(def tools
  "name (registered under the extension's prefix) → tool def."
  {"nyma__save_file"
   #js {:description "Write a file: copy `from` (e.g. a saved attachment), or write `base64` bytes or `text`. Returns {path, bytes}."
        :inputSchema #js {:type "object"
                          :properties #js {:path   #js {:type "string" :description "Destination, relative to the project"}
                                           :from   #js {:type "string" :description "Existing file to copy"}
                                           :base64 #js {:type "string"}
                                           :text   #js {:type "string"}}
                          :required #js ["path"]}
        :outputSchema #js {:type "object" :properties #js {:path #js {:type "string"} :bytes #js {:type "integer"}}}
        :safety #js {:destructive? true :requires-confirmation? true
                     :capabilities #js ["filesystem" "write"] :category "file"}
        :execute (fn [args] (save-file args))}
   "nyma__pdf_text"
   #js {:description "Text of a PDF file, optionally pages from..to. Returns {text, pages}."
        :inputSchema #js {:type "object"
                          :properties #js {:path #js {:type "string"}
                                           :from #js {:type "integer"}
                                           :to   #js {:type "integer"}}
                          :required #js ["path"]}
        :outputSchema #js {:type "object" :properties #js {:text #js {:type "string"} :pages #js {:type "integer"}}}
        :safety #js {:read-only? true :capabilities #js ["filesystem"] :category "file"}
        :execute (fn [args] (pdf-text args))}})
