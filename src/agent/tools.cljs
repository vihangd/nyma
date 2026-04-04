(ns agent.tools
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["turndown" :as turndown-mod]
            ["linkedom" :refer [parseHTML]]
            [agent.utils.ansi :refer [truncate-text]]))

(defn ^:async read-execute [{:keys [path range]}]
  (let [content (js-await (.text (js/Bun.file path)))]
    (if range
      (let [lines (.split content "\n")]
        (.join (.slice lines (dec (first range)) (second range)) "\n"))
      content)))

(def read-tool
  (tool
    #js {:description "Read file contents"
         :inputSchema  (.object z
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
         :inputSchema  (.object z
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
         :inputSchema  (.object z
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
    #js {:description "Run a shell command. Use only for build, test, git, and install commands. For file operations use the dedicated read/write/edit/ls/glob/grep tools instead."
         :inputSchema  (.object z
                        #js {:command (.string z)
                             :timeout (-> (.number z) (.optional)
                                          (.describe "Timeout in ms, default 30000"))})
         :execute bash-execute}))

;;; ─── think ─────────────────────────────────────────────────

(defn think-execute [{:keys [thought]}]
  "Thought recorded.")

(def think-tool
  (tool
    #js {:description "Use this tool to think through complex problems step-by-step before taking action. Your thought is recorded but no action is taken."
         :inputSchema  (.object z
                        #js {:thought (-> (.string z)
                                          (.describe "Your reasoning, analysis, or plan"))})
         :execute think-execute}))

;;; ─── ls ────────────────────────────────────────────────────

(def ^:private skip-dirs #{"node_modules" ".git" "dist" ".nyma" ".claude"})

(defn- format-entry [entry base-path]
  (if (.isDirectory entry)
    (str (.-name entry) "/")
    (let [full (path/join base-path (.-name entry))
          size (try (.-size (fs/statSync full)) (catch :default _ 0))]
      (str (.-name entry) "  (" size " bytes)"))))

(defn- walk-dir [dir-path show-all depth max-entries results]
  (when (and (< (count @results) max-entries) (< depth 10))
    (let [entries (try (fs/readdirSync dir-path #js {:withFileTypes true})
                       (catch :default _ []))]
      (doseq [entry entries]
        (when (< (count @results) max-entries)
          (let [name (.-name entry)]
            (when (or show-all (not (.startsWith name ".")))
              (when-not (contains? skip-dirs name)
                (let [rel (path/relative "." (path/join dir-path name))]
                  (if (.isDirectory entry)
                    (do
                      (swap! results conj (str rel "/"))
                      (walk-dir (path/join dir-path name) show-all (inc depth) max-entries results))
                    (let [size (try (.-size (fs/statSync (path/join dir-path name)))
                                    (catch :default _ 0))]
                      (swap! results conj (str rel "  (" size " bytes)")))))))))))))

(defn ^:async ls-execute [{:keys [path all recursive]}]
  (let [dir (or path ".")]
    (when-not (fs/existsSync dir)
      (throw (js/Error. (str "Directory not found: " dir))))
    (if recursive
      (let [results (atom [])]
        (walk-dir dir all 0 1000 results)
        (.join @results "\n"))
      (let [entries (fs/readdirSync dir #js {:withFileTypes true})
            filtered (if all
                       entries
                       (filter #(not (.startsWith (.-name %) ".")) entries))
            formatted (map #(format-entry % dir) filtered)]
        (.join (vec (take 1000 formatted)) "\n")))))

(def ls-tool
  (tool
    #js {:description "List directory contents. Shows files with sizes and directories with trailing /."
         :inputSchema  (.object z
                        #js {:path      (-> (.string z) (.optional)
                                            (.describe "Directory path (default: current directory)"))
                             :recursive (-> (.boolean z) (.optional)
                                            (.describe "Recurse into subdirectories"))
                             :all       (-> (.boolean z) (.optional)
                                            (.describe "Include hidden files (dotfiles)"))})
         :execute ls-execute}))

;;; ─── glob ──────────────────────────────────────────────────

(defn ^:async glob-execute [{:keys [pattern path exclude]}]
  (let [dir      (or path ".")
        glob-obj (js/Bun.Glob. pattern)
        results  (atom [])
        exclude-glob (when exclude (js/Bun.Glob. exclude))
        iter     (.scan glob-obj #js {:cwd dir :dot false :onlyFiles true :absolute false})]
    (loop []
      (let [chunk (js-await (.next iter))]
        (when-not (.-done chunk)
          (let [file (.-value chunk)]
            (when (and (< (count @results) 500)
                       (not (or (.includes file "node_modules/")
                                (.includes file ".git/")))
                       (or (not exclude-glob)
                           (not (.match exclude-glob file))))
              (swap! results conj file)))
          (recur))))
    (let [sorted (sort @results)]
      (.join sorted "\n"))))

(def glob-tool
  (tool
    #js {:description "Find files matching a glob pattern. Returns matching file paths."
         :inputSchema  (.object z
                        #js {:pattern (-> (.string z)
                                          (.describe "Glob pattern, e.g. '**/*.ts', 'src/**/*.cljs'"))
                             :path    (-> (.string z) (.optional)
                                          (.describe "Base directory to search in (default: current directory)"))
                             :exclude (-> (.string z) (.optional)
                                          (.describe "Glob pattern to exclude, e.g. 'test/**'"))})
         :execute glob-execute}))

;;; ─── grep ──────────────────────────────────────────────────

(def ^:private detected-binary (atom nil))

(defn ^:async try-binary
  "Check if a binary is available via `which`."
  [name]
  (try
    (let [proc (js/Bun.spawn #js ["which" name]
                 #js {:stdout "pipe" :stderr "pipe"})
          code (js-await (.-exited proc))]
      (= code 0))
    (catch :default _ false)))

(defn ^:async detect-search-binary
  "Detect the best available search binary. Caches result."
  []
  (if-let [cached @detected-binary]
    cached
    (let [result (cond
                   (js-await (try-binary "rg")) "rg"
                   (js-await (try-binary "ag")) "ag"
                   :else                        "grep")]
      (reset! detected-binary result)
      result)))

(defn- build-grep-args [binary pattern search-path glob-filter ignore-case context output-mode max-results multiline literal type-filter]
  (let [args (atom [])]
    (case binary
      "rg"
      (do
        (swap! args conj "--no-config")
        (when ignore-case (swap! args conj "-i"))
        (when multiline (swap! args conj "-U" "--multiline-dotall"))
        (when literal (swap! args conj "-F"))
        (when context (swap! args conj "-C" (str context)))
        (case output-mode
          "files" (swap! args conj "-l")
          "count" (swap! args conj "-c")
          nil)
        (when glob-filter (swap! args conj "--glob" glob-filter))
        (when type-filter (swap! args conj "--type" type-filter))
        (when max-results (swap! args conj "--max-count" (str max-results)))
        (swap! args conj pattern)
        (when search-path (swap! args conj search-path)))

      "ag"
      (do
        (when ignore-case (swap! args conj "-i"))
        (when literal (swap! args conj "-F"))
        ;; ag does not support multiline
        (when context (swap! args conj "-C" (str context)))
        (case output-mode
          "files" (swap! args conj "-l")
          "count" (swap! args conj "-c")
          nil)
        (when glob-filter (swap! args conj "-G" glob-filter))
        (when type-filter (swap! args conj "-G" (str "\\." type-filter "$")))
        (swap! args conj pattern)
        (when search-path (swap! args conj search-path)))

      ;; grep fallback
      (do
        (swap! args conj "-r")
        (when ignore-case (swap! args conj "-i"))
        (when literal (swap! args conj "-F"))
        ;; grep does not support multiline
        (when context (swap! args conj "-C" (str context)))
        (case output-mode
          "files" (swap! args conj "-l")
          "count" (swap! args conj "-c")
          nil)
        (when glob-filter (swap! args conj "--include" glob-filter))
        (when type-filter (swap! args conj "--include" (str "*." type-filter)))
        (swap! args conj pattern)
        (swap! args conj (or search-path "."))))
    @args))

(defn ^:async grep-execute [{:keys [pattern path glob ignore_case context output_mode max_results multiline literal type_filter]}]
  (let [binary     (js-await (detect-search-binary))
        search-path (or path ".")
        max-res    (or max_results 100)
        args       (build-grep-args binary pattern search-path glob ignore_case context output_mode max-res multiline literal type_filter)
        ;; Clear RIPGREP_CONFIG_PATH to prevent user config interference
        env        (let [e (js/Object.assign #js {} (.-env js/process))]
                     (js-delete e "RIPGREP_CONFIG_PATH")
                     e)
        proc       (js/Bun.spawn (into-array (cons binary args))
                     #js {:stdout "pipe" :stderr "pipe" :timeout 30000 :env env})
        stdout     (js-await (.text (js/Response. (.-stdout proc))))
        stderr     (js-await (.text (js/Response. (.-stderr proc))))
        code       (js-await (.-exited proc))]
    (cond
      (= code 0) (truncate-text stdout max-res)
      (= code 1) ""  ;; No matches — not an error
      :else      (str "Search error: " (.trim stderr)))))

(def grep-tool
  (tool
    #js {:description "Search file contents using regex patterns. Always use this instead of running grep/rg in bash. Uses ripgrep if available."
         :inputSchema  (.object z
                        #js {:pattern     (-> (.string z)
                                              (.describe "Regex pattern to search for"))
                             :path        (-> (.string z) (.optional)
                                              (.describe "File or directory to search (default: current directory)"))
                             :glob        (-> (.string z) (.optional)
                                              (.describe "File pattern filter, e.g. '*.ts', '*.cljs'"))
                             :ignore_case (-> (.boolean z) (.optional)
                                              (.describe "Case-insensitive search"))
                             :context     (-> (.number z) (.optional)
                                              (.describe "Number of context lines around matches"))
                             :output_mode (-> (.enum z #js ["content" "files" "count"]) (.optional)
                                              (.describe "Output mode: content (default), files (paths only), count"))
                             :max_results (-> (.number z) (.optional)
                                              (.describe "Maximum result lines (default: 100)"))
                             :multiline   (-> (.boolean z) (.optional)
                                              (.describe "Enable multiline matching (rg only, -U --multiline-dotall)"))
                             :literal     (-> (.boolean z) (.optional)
                                              (.describe "Treat pattern as literal string, not regex (-F)"))
                             :type_filter (-> (.string z) (.optional)
                                              (.describe "File type filter, e.g. 'ts', 'py', 'rust' (rg --type)"))})
         :execute grep-execute}))

;;; ─── web_fetch ─────────────────────────────────────────────

(defn- html-to-markdown
  "Convert HTML to Markdown using turndown + linkedom."
  [html]
  (let [parsed   (parseHTML html)
        document (.-document parsed)
        TurndownCtor (or (.-default turndown-mod) turndown-mod)
        td       (TurndownCtor. #js {:headingStyle "atx" :codeBlockStyle "fenced"})]
    (.turndown td document)))

(defn- html-to-text-fallback
  "Strip HTML to plain text. Regex-based fallback, no external deps."
  [html]
  (-> html
      ;; Remove script, style, noscript blocks
      (.replace (js/RegExp. "<script[^>]*>[\\s\\S]*?</script>" "gi") "")
      (.replace (js/RegExp. "<style[^>]*>[\\s\\S]*?</style>" "gi") "")
      (.replace (js/RegExp. "<noscript[^>]*>[\\s\\S]*?</noscript>" "gi") "")
      ;; Convert block elements to newlines
      (.replace (js/RegExp. "<(br|p|div|li|tr|h[1-6])[^>]*>" "gi") "\n")
      ;; Strip remaining tags
      (.replace (js/RegExp. "<[^>]+>" "g") "")
      ;; Decode common entities
      (.replace (js/RegExp. "&amp;" "g") "&")
      (.replace (js/RegExp. "&lt;" "g") "<")
      (.replace (js/RegExp. "&gt;" "g") ">")
      (.replace (js/RegExp. "&quot;" "g") "\"")
      (.replace (js/RegExp. "&#39;" "g") "'")
      (.replace (js/RegExp. "&nbsp;" "g") " ")
      ;; Decode numeric entities
      (.replace (js/RegExp. "&#(\\d+);" "g")
                (fn [_ code] (js/String.fromCharCode (js/parseInt code 10))))
      ;; Collapse whitespace
      (.replace (js/RegExp. "[ \\t]+" "g") " ")
      (.replace (js/RegExp. "\\n\\s*\\n" "g") "\n\n")
      (.trim)))

(defn ^:async web-fetch-execute [{:keys [url format max_length]}]
  ;; Validate URL
  (try (js/URL. url) (catch :default _ (throw (js/Error. (str "Invalid URL: " url)))))
  (let [max-len  (or max_length 20000)
        fmt      (or format "markdown")
        response (try
                   (js-await
                     (js/fetch url
                       #js {:signal  (js/AbortSignal.timeout 15000)
                            :headers #js {"User-Agent" "Nyma/1.0"}}))
                   (catch :default e
                     (throw (js/Error. (str "Fetch failed: " (.-message e))))))]
    (when-not (.-ok response)
      (throw (js/Error. (str "HTTP error: " (.-status response) " " (.-statusText response)))))
    (let [content-type (or (.get (.-headers response) "content-type") "")]
      (when-not (or (.includes content-type "text/") (.includes content-type "application/json"))
        (throw (js/Error. (str "Cannot extract text from content-type: " content-type))))
      (let [body (js-await (.text response))
            result (cond
                     (= fmt "html") body
                     (not (.includes content-type "text/html")) body
                     (= fmt "markdown") (html-to-markdown body)
                     :else (html-to-text-fallback body))]
        (if (> (count result) max-len)
          (str (subs result 0 max-len) "\n[truncated — " (count result) " total characters]")
          result)))))

(def web-fetch-tool
  (tool
    #js {:description "Fetch content from a URL and extract text. Always use this instead of curl in bash. Supports HTML (converts to markdown), JSON, and plain text."
         :inputSchema  (.object z
                        #js {:url        (-> (.string z)
                                             (.describe "URL to fetch"))
                             :format     (-> (.enum z #js ["text" "markdown" "html"]) (.optional)
                                             (.describe "Output format: markdown (default, converts HTML to Markdown), text (strips HTML), or html (raw)"))
                             :max_length (-> (.number z) (.optional)
                                             (.describe "Maximum output characters (default: 20000)"))})
         :execute web-fetch-execute}))

;;; ─── web_search ────────────────────────────────────────────

(def ^:private last-ddg-request (atom 0))

(defn ^:async ddg-search
  "Search DuckDuckGo Lite (no API key required)."
  [query num-results]
  ;; Rate limit: 2s between requests
  (let [now    (js/Date.now)
        elapsed (- now @last-ddg-request)]
    (when (< elapsed 2000)
      (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve (- 2000 elapsed))))))
    (reset! last-ddg-request (js/Date.now)))
  (let [q          (if (> (count query) 499) (subs query 0 499) query)
        body       (str "q=" (js/encodeURIComponent q) "&kl=wt-wt")
        response   (js-await
                     (js/fetch "https://lite.duckduckgo.com/lite/"
                       #js {:method  "POST"
                            :body    body
                            :signal  (js/AbortSignal.timeout 15000)
                            :headers #js {"Content-Type"  "application/x-www-form-urlencoded"
                                          "User-Agent"    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                          "Referer"       "https://lite.duckduckgo.com/"}}))
        html       (js-await (.text response))]
    (when-not (.-ok response)
      (throw (js/Error. (str "DuckDuckGo returned HTTP " (.-status response)
                             (when (or (= 403 (.-status response)) (= 429 (.-status response)))
                               ". Rate limited — try again in a few seconds.")))))
    ;; Parse results from DDG Lite HTML tables
    (let [links    (atom [])
          snippets (atom [])
          ;; Extract links: <a rel="nofollow" href="URL" class='result-link'>Title</a>
          link-re  (js/RegExp. "href=[\"']([^\"']+)[\"'][^>]*class=[\"']result-link[\"'][^>]*>([^<]+)</a>" "gi")]
      (loop []
        (let [m (.exec link-re html)]
          (when m
            (swap! links conj {:url (aget m 1) :title (.trim (aget m 2))})
            (recur))))
      ;; Extract snippets: <td class="result-snippet">...</td>
      (let [snip-re (js/RegExp. "class=[\"']result-snippet[\"'][^>]*>([\\s\\S]*?)</td>" "gi")]
        (loop []
          (let [m (.exec snip-re html)]
            (when m
              (swap! snippets conj (-> (aget m 1)
                                       (.replace (js/RegExp. "<[^>]+>" "g") "")
                                       (.trim)))
              (recur)))))
      (let [results (take (or num-results 5)
                          (map-indexed
                            (fn [i link]
                              (str (inc i) ". " (:title link) "\n"
                                   "   " (:url link) "\n"
                                   "   " (or (get @snippets i) "")))
                            @links))]
        (if (seq results)
          (.join (vec results) "\n\n")
          "No results found.")))))

(defn ^:async brave-search
  "Search via Brave Search API (requires BRAVE_SEARCH_API_KEY)."
  [query num-results]
  (let [api-key (or (.. js/process -env -BRAVE_SEARCH_API_KEY) "")]
    (when (empty? api-key)
      (throw (js/Error. "Brave Search requires BRAVE_SEARCH_API_KEY environment variable. Get a free key at https://brave.com/search/api/")))
    (let [url      (str "https://api.search.brave.com/res/v1/web/search?q="
                        (js/encodeURIComponent query)
                        "&count=" (or num-results 5))
          response (js-await
                     (js/fetch url
                       #js {:signal  (js/AbortSignal.timeout 15000)
                            :headers #js {"X-Subscription-Token" api-key
                                          "Accept"               "application/json"}}))
          data     (js-await (.json response))]
      (when-not (.-ok response)
        (throw (js/Error. (str "Brave Search error: HTTP " (.-status response)
                               (when (= 401 (.-status response))
                                 ". Check your BRAVE_SEARCH_API_KEY.")
                               (when (= 429 (.-status response))
                                 ". Rate limited — try again later.")))))
      (let [web-results (or (.. data -web -results) #js [])
            formatted   (map-indexed
                          (fn [i r]
                            (str (inc i) ". " (.-title r) "\n"
                                 "   " (.-url r) "\n"
                                 "   " (or (.-description r) "")))
                          web-results)]
        (if (seq formatted)
          (.join (vec formatted) "\n\n")
          "No results found.")))))

(defn ^:async web-search-execute [{:keys [query num_results provider]}]
  (when (or (nil? query) (empty? query))
    (throw (js/Error. "Search query cannot be empty")))
  (let [prov (or provider
                 (when (.. js/process -env -BRAVE_SEARCH_API_KEY) "brave")
                 "duckduckgo")]
    (case prov
      "brave"      (js-await (brave-search query num_results))
      "duckduckgo" (js-await (ddg-search query num_results))
      (js-await (ddg-search query num_results)))))

(def web-search-tool
  (tool
    #js {:description "Search the web for information. Always use this for any web lookup — never use curl to search engines in bash. Uses DuckDuckGo by default, or Brave Search if BRAVE_SEARCH_API_KEY is set."
         :inputSchema  (.object z
                        #js {:query       (-> (.string z)
                                              (.describe "Search query"))
                             :num_results (-> (.number z) (.optional)
                                              (.describe "Number of results (default: 5, max: 20)"))
                             :provider    (-> (.enum z #js ["duckduckgo" "brave"]) (.optional)
                                              (.describe "Search provider (default: duckduckgo)"))})
         :execute web-search-execute}))

;;; ─── builtin tools map ─────────────────────────────────────

(def builtin-tools
  {"read"       read-tool
   "write"      write-tool
   "edit"       edit-tool
   "bash"       bash-tool
   "think"      think-tool
   "ls"         ls-tool
   "glob"       glob-tool
   "grep"       grep-tool
   "web_fetch"  web-fetch-tool
   "web_search" web-search-tool})
