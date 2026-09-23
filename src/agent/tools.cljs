(ns agent.tools
  "Built-in tools: read, write, edit, bash."
  (:require [agent.utils.data :as data]
            ["ai" :refer [tool]]
            [agent.schema :as schema]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.multimodal :as mm]
            [agent.tool-result-policy :as policy]
            [agent.utils.ansi :refer [truncate-text]]
            [agent.utils.stream-drain :as drain]))

(def read-default-line-cap 2000)

(defn- number-lines
  "cat -n style: right-aligned 1-based line number, tab, content."
  [lines start]
  (.join (.map lines (fn [line i]
                       (str (.padStart (str (+ start i)) 6) "\t" line)))
         "\n"))

(defn ^:async read-execute [{:keys [path range]}]
  (when-not (fs/existsSync path)
    (throw (js/Error. (str "File not found: " path))))
  (let [content (js-await (.text (js/Bun.file path)))
        lines   (.split content "\n")
        total   (.-length lines)
        [start end] (if range
                      [(first range) (second range)]
                      [1 (min total read-default-line-cap)])
        slice   (.slice lines (dec start) end)
        body    (number-lines slice start)]
    (if (< end total)
      (str body "\n… [" (- total end) " more lines — read with range [" (inc end) ", " total "]]")
      body)))

(def read-tool
  (tool
   #js {:description (str "Read file contents with line numbers (cat -n format: `   N\\tcontent`). "
                          "Returns the first " read-default-line-cap " lines unless a range is given. "
                          "When using edit, old_string must be the raw file text — never include the line-number prefix.")
        :inputSchema  (schema/->zod {
                                    :path [:string "File path to read"]
                                    :range [:array :number {:optional true :length 2 :doc "Line range [start, end], 1-based inclusive"}]})
        :execute read-execute}))

(defn ^:async write-execute [{:keys [path content]}]
  (js-await (js/Bun.write path content))
  (str "Wrote " (count content) " bytes to " path))

(def write-tool
  (tool
   #js {:description "Write content to a file, creating directories as needed"
        :inputSchema  (schema/->zod {
                                    :path [:string]
                                    :content [:string]})
        :execute write-execute}))

(defn count-occurrences
  "Count non-overlapping literal occurrences of `s` in `content`."
  [content s]
  (if (empty? s)
    0
    (loop [idx 0 n 0]
      (let [i (.indexOf content s idx)]
        (if (neg? i) n (recur (+ i (count s)) (inc n)))))))

(defn edit-line
  "1-based line on which the first occurrence of `s` starts in `content`."
  [content s]
  (let [pos (.indexOf content s)]
    (inc (count-occurrences (.slice content 0 (max pos 0)) "\n"))))

(defn edit-window
  "Numbered lines of `updated` around an edit that began on `line` and
   inserted `inserted`: three lines of context before, two after."
  [updated line inserted]
  (let [lines (.split updated "\n")
        added (if (empty? inserted) 0 (count (.split inserted "\n")))
        start (max 1 (- line 3))
        end   (min (.-length lines) (+ line added 1))]
    (number-lines (.slice lines (dec start) end) start)))

(defn ^:async edit-execute [{:keys [path old_string new_string replace_all]}]
  (let [content (js-await (.text (js/Bun.file path)))
        n       (count-occurrences content old_string)]
    (cond
      (= old_string new_string)
      (throw (js/Error. "old_string and new_string are identical — no change to apply"))

      (zero? n)
      (throw (js/Error. "old_string not found in file"))

      (and (> n 1) (not replace_all))
      (throw (js/Error. (str "old_string matches " n " times in " path
                             " — provide a larger unique snippet, or pass replace_all: true")))

      :else
      ;; Function replacement: with a string replacement, replaceAll runs
      ;; GetSubstitution — $&, $$, $`, $' in new_string would silently write
      ;; corrupted content instead of the literal text.
      (let [updated (.replaceAll content old_string (fn [] new_string))]
        (js-await (js/Bun.write path updated))
        (if (> n 1)
          (str "Edit applied (" n " replacements)")
          ;; Borrowed from apprentice's replace tool: hand back the edited
          ;; region, numbered, so the model can check indentation and
          ;; neighbours without spending a `read` on every edit.
          (let [line (edit-line content old_string)]
            (str "Edit applied at line " line " of " path ". It now reads:\n"
                 (edit-window updated line new_string))))))))

(def edit-tool
  (tool
   #js {:description "Replace exact text in a file. old_string must match exactly once — pass replace_all to replace every occurrence."
        :inputSchema  (schema/->zod {
                                    :path [:string]
                                    :old_string [:string]
                                    :new_string [:string]
                                    :replace_all [:boolean {:optional true :doc "Replace all occurrences instead of requiring a unique match"}]})
        :execute edit-execute}))

(defn ^:async bash-execute [{:keys [command timeout]} & [ext-ctx]]
  (let [limit-ms (or timeout 30000)
        started  (js/Date.now)
        proc     (js/Bun.spawn #js ["sh" "-c" command]
                               #js {:timeout limit-ms
                                    :stdout  "pipe"
                                    :stderr  "pipe"})
        signal   (when ext-ctx (aget ext-ctx "abortSignal"))
        on-abort (fn [] (.kill proc))
        _        (when signal
                   (if (.-aborted signal)
                     (on-abort)
                     (.addEventListener signal "abort" on-abort #js {:once true})))
        ;; Drains START here, before `exited` is awaited: a command that fills
        ;; the 64KB pipe buffer blocks on write until someone reads it, so
        ;; reading only after exit would deadlock.
        stop     (drain/deferred-stop)
        out-p    (drain/read-text-until (.-stdout proc) (:promise stop))
        err-p    (drain/read-text-until (.-stderr proc) (:promise stop))
        code     (js-await (.-exited proc))
        ;; Orthogonal outcomes, reported independently. A run can time out AND
        ;; exit 0, because the command trapped the signal — `trap "exit 0" TERM`
        ;; in a Makefile or a test runner's cleanup. Measured on Bun 1.4: a plain
        ;; timeout gives exitCode 143 + signalCode SIGTERM, a trapping one gives
        ;; exitCode 0 + signalCode null, indistinguishable from success by the
        ;; process fields alone. (`proc.killed` is no help: it reads true for
        ;; EVERY exited process, timeout or not.) So the deadline itself is the
        ;; witness — the fact reported is "this run reached its limit", which is
        ;; exactly what the exit code cannot say.
        ;; Measured at EXIT, not after the pipes drain. It used to include the
        ;; drain, so a command that exited immediately but left a background
        ;; child holding stdout read as timed out — the ceiling this comment
        ;; used to name. The drain is now bounded and happens after this line.
        elapsed  (- (js/Date.now) started)
        ;; Anything still holding the pipe is an orphan we did not spawn: kill a
        ;; shell and whatever it forked keeps stdout open. Partial output beats
        ;; waiting for a process that is not ours.
        _        ((:stop-in! stop) 300)
        stdout   (js-await out-p)
        stderr   (js-await err-p)
        aborted? (boolean (and signal (.-aborted signal)))
        timed-out? (and (not aborted?) (>= elapsed limit-ms))]
    (when signal (.removeEventListener signal "abort" on-abort))
    (js/JSON.stringify
     #js {:stdout   stdout
          :stderr   stderr
          :exitCode code
          :timedOut timed-out?
          :signal   (.-signalCode proc)
          :aborted  aborted?})))

(defn ^:async bash-tool-execute
  "Tool-facing wrapper: the same JSON payload the model has always seen, plus
   the exit code as STRUCTURED metadata instead of only inside an opaque string.

   Consumers previously had to re-parse the payload to learn whether a command
   failed — `tool_result_policy` and `bash_suite/output_handling` both
   do exactly that by hand. `:details` rides the `tool_result` event
   (`middleware.cljs`), so the signal is available without the parsing.

   Deliberately does NOT set `:isError`. A command that exits non-zero still
   RAN — the tool call succeeded and returned its output; `isError` means the
   call itself failed. Conflating the two is not academic: `claude_hook_bridge`
   dispatches `PostToolUseFailure` instead of `PostToolUse` when `isError` is
   set (`events/post_tool_use.cljs,61`), so flagging every non-zero exit
   silently stops a user's PostToolUse hooks from firing for `grep` with no
   match, `test -f`, `git diff --quiet` — all routine, all exit 1. An earlier
   version of this function set `isError` and would have broken a live hook
   setup for no gain: nothing in-tree consumes `:result-is-error` for bash
   (`checkpoints` and `verify_gate` both gate on `edit-tool?`).

   Nothing the model sees changes: `normalize-tool-result` renders `content`
   back to exactly this string, and the SDK is handed that string because bash
   declares no `toModelOutput`.

   `bash-execute` itself keeps returning a string — the editor's `!` command
   (`ui/editor_bash.cljs`) and the tool tests parse it directly."
  [args & [ext-ctx]]
  (let [payload (js-await (bash-execute args ext-ctx))
        parsed  (data/parse-json payload)
        code    (when parsed (aget parsed "exitCode"))
        ;; Rides `tool_result` beside the exit code: a consumer asking "did this
        ;; command succeed?" cannot answer from exitCode alone once a timeout
        ;; can be trapped into a 0.
        timed?  (boolean (when parsed (aget parsed "timedOut")))]
    #js {:content #js [#js {:type "text" :text payload}]
         :details #js {:exitCode code :timedOut timed?}}))

(def bash-tool
  (tool
   #js {:description "Run a shell command. Use only for build, test, git, and install commands. For file operations use the dedicated read/write/edit/ls/glob/grep tools instead."
        :inputSchema  (schema/->zod {
                                    :command [:string]
                                    :timeout [:number {:optional true :doc "Timeout in ms, default 30000"}]})
        :execute bash-tool-execute}))

;;; ─── think ─────────────────────────────────────────────────

(defn think-execute [{:keys [thought]}]
  "Thought recorded.")

(def think-tool
  (tool
   #js {:description "Use this tool to think through complex problems step-by-step before taking action. Your thought is recorded but no action is taken."
        :inputSchema  (schema/->zod {
                                    :thought [:string "Your reasoning, analysis, or plan"]})
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
        :inputSchema  (schema/->zod {
                                    :path [:string {:optional true :doc "Directory path (default: current directory)"}]
                                    :recursive [:boolean {:optional true :doc "Recurse into subdirectories"}]
                                    :all [:boolean {:optional true :doc "Include hidden files (dotfiles)"}]})
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
        :inputSchema  (schema/->zod {
                                    :pattern [:string "Glob pattern, e.g. '**/*.ts', 'src/**/*.cljs'"]
                                    :path [:string {:optional true :doc "Base directory to search in (default: current directory)"}]
                                    :exclude [:string {:optional true :doc "Glob pattern to exclude, e.g. 'test/**'"}]})
        :execute glob-execute}))

;;; ─── grep ──────────────────────────────────────────────────

(def ^:private detected-binary (atom nil))

(defn try-binary
  "Is `name` on PATH?

   `Bun.which` resolves it in-process: 0.018ms against 5.1ms to spawn `which`
   and wait for it, measured on this machine. Same answer, one fewer process."
  [name]
  (try
    (some? (js/Bun.which name))
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
        :inputSchema  (schema/->zod {
                                    :pattern [:string "Regex pattern to search for"]
                                    :path [:string {:optional true :doc "File or directory to search (default: current directory)"}]
                                    :glob [:string {:optional true :doc "File pattern filter, e.g. '*.ts', '*.cljs'"}]
                                    :ignore_case [:boolean {:optional true :doc "Case-insensitive search"}]
                                    :context [:number {:optional true :doc "Number of context lines around matches"}]
                                    :output_mode [:enum ["content" "files" "count"] {:optional true :doc "Output mode: content (default), files (paths only), count"}]
                                    :max_results [:number {:optional true :doc "Maximum result lines (default: 100)"}]
                                    :multiline [:boolean {:optional true :doc "Enable multiline matching (rg only, -U --multiline-dotall)"}]
                                    :literal [:boolean {:optional true :doc "Treat pattern as literal string, not regex (-F)"}]
                                    :type_filter [:string {:optional true :doc "File type filter, e.g. 'ts', 'py', 'rust' (rg --type)"}]})
        :execute grep-execute}))

;;; ─── credentials ──────────────────────────────────────────

(defn- read-credential [key]
  (let [home (.. js/process -env -HOME)
        p    (and home (path/join home ".nyma" "credentials.json"))]
    (when (and p (fs/existsSync p))
      (try
        (let [parsed (js/JSON.parse (fs/readFileSync p "utf8"))]
          (aget parsed key))
        (catch :default _ nil)))))

(defn- resolve-jina-key []
  (or (.. js/process -env -JINA_API_KEY)
      (read-credential "jina")))

;;; ─── web_fetch ─────────────────────────────────────────────

(def ^:private html-mods
  "Memoized {:parseHTML f :Turndown ctor}, loaded on first use.

   These were top-level requires, so EVERY entry point — the gateway, headless
   -p, the SDK, a test run — paid 26ms and 37MB of RSS (linkedom alone is +29MB,
   the largest dependency cost in the process) for two libraries that only the
   web_fetch markdown path below uses. deep_research never reaches them: it calls
   Jina/Perplexity and gets prose back."
  (atom nil))

(defn ^:async load-html-mods!
  []
  (or @html-mods
      (let [linkedom  (js-await (js/import "linkedom"))
            turndown  (js-await (js/import "turndown"))
            mods      {:parseHTML (.-parseHTML linkedom)
                       :Turndown  (or (.-default turndown) turndown)}]
        (reset! html-mods mods)
        mods)))

(defn ^:async html-to-markdown
  "Convert HTML to Markdown using turndown + linkedom, both loaded on demand."
  [html]
  (let [{:keys [parseHTML Turndown]} (js-await (load-html-mods!))
        parsed   (parseHTML html)
        document (.-document parsed)
        td       (Turndown. #js {:headingStyle "atx" :codeBlockStyle "fenced"})]
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

(defn- truncate-result [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "\n[truncated — " (count s) " total characters]")
    s))

(defn ^:async direct-fetch [url fmt max-len]
  (let [response (try
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
                     (= fmt "markdown") (js-await (html-to-markdown body))
                     :else (html-to-text-fallback body))]
        (truncate-result result max-len)))))

(defn ^:async jina-fetch [url fmt max-len]
  (let [api-key    (resolve-jina-key)
        jina-url   (str "https://r.jina.ai/" url)
        return-fmt (case fmt
                     "html"     "html"
                     "text"     "text"
                     "markdown")
        headers    #js {"Accept"          "text/plain"
                        "X-Return-Format" return-fmt
                        "User-Agent"      "Nyma/1.0"}
        _          (when api-key (aset headers "Authorization" (str "Bearer " api-key)))
        response   (try
                     (js-await
                      (js/fetch jina-url
                                #js {:signal  (js/AbortSignal.timeout 30000)
                                     :headers headers}))
                     (catch :default e
                       (throw (js/Error. (str "Jina Reader fetch failed: " (.-message e))))))]
    (when-not (.-ok response)
      (throw (js/Error. (str "Jina Reader error: HTTP " (.-status response) " " (.-statusText response)
                             (when (= 401 (.-status response)) ". Check JINA_API_KEY.")
                             (when (= 429 (.-status response)) ". Rate limited — set JINA_API_KEY for higher limits.")))))
    (truncate-result (js-await (.text response)) max-len)))

(defn- tinyfish-key
  "TINYFISH_API_KEY, else 'tinyfish' from ~/.nyma/credentials.json, else nil."
  []
  (or (.. js/process -env -TINYFISH_API_KEY)
      (read-credential "tinyfish")))

(defn ^:async tinyfish-fetch
  "Fetch via Tinyfish (api.fetch.tinyfish.ai). Headless-browser-rendered,
   handles JS-heavy sites and PDFs. Requires TINYFISH_API_KEY or
   'tinyfish' in ~/.nyma/credentials.json. The Tinyfish API supports
   batched fetches (up to 10 URLs); this helper sends one URL per call
   to match nyma's web_fetch single-URL surface."
  [url fmt max-len]
  (let [api-key (tinyfish-key)]
    (when-not api-key
      (throw (js/Error. "Tinyfish requires TINYFISH_API_KEY env var or 'tinyfish' in ~/.nyma/credentials.json. Get a key at https://agent.tinyfish.ai/api-keys")))
    (let [tinyfish-fmt (case fmt
                         "html" "html"
                         "json" "json"
                         "markdown")
          body         (js/JSON.stringify
                        #js {:urls   #js [url]
                             :format tinyfish-fmt})
          response     (try
                         (js-await
                          (js/fetch "https://api.fetch.tinyfish.ai"
                                    #js {:method  "POST"
                                         :body    body
                                         :signal  (js/AbortSignal.timeout 30000)
                                         :headers #js {"X-API-Key"    api-key
                                                       "Content-Type" "application/json"}}))
                         (catch :default e
                           (throw (js/Error. (str "Tinyfish fetch failed: " (.-message e))))))]
      (when-not (.-ok response)
        (throw (js/Error. (str "Tinyfish error: HTTP " (.-status response)
                               (when (= 401 (.-status response)) ". Check TINYFISH_API_KEY.")
                               (when (= 429 (.-status response)) ". Rate limited.")))))
      (let [data    (js-await (.json response))
            results (or (.-results data) #js [])
            errors  (or (.-errors data) #js [])
            first-r (when (pos? (.-length results)) (aget results 0))]
        (cond
          first-r
          (truncate-result (or (.-text first-r) "") max-len)

          (pos? (.-length errors))
          (throw (js/Error. (str "Tinyfish error for " url ": "
                                 (js/JSON.stringify (aget errors 0)))))

          :else
          (throw (js/Error. "Tinyfish returned no results")))))))

(defn- has-tinyfish-key?
  "Cheap, side-effect-free key check used to skip Tinyfish in the auto
   chain when no key is set — saves an HTTP round-trip on a request
   that would deterministically throw `Tinyfish requires …`."
  []
  (boolean (tinyfish-key)))

(defn ^:async web-fetch-execute [{:keys [url format max_length provider]}]
  ;; Validate URL
  (try (js/URL. url) (catch :default _ (throw (js/Error. (str "Invalid URL: " url)))))
  (let [max-len (or max_length 20000)
        fmt     (or format "markdown")
        prov    (or provider "auto")]
    (case prov
      "direct"   (js-await (direct-fetch url fmt max-len))
      "jina"     (js-await (jina-fetch url fmt max-len))
      "tinyfish" (js-await (tinyfish-fetch url fmt max-len))
      ;; auto: direct → tinyfish (if key) → jina. Tinyfish slots
      ;; before Jina because both are JS-rendering fallbacks; if a
      ;; user has a Tinyfish key, that's a stronger signal of
      ;; preference than Jina (which works anonymously). Tinyfish
      ;; skipped entirely when no key is set — no point burning
      ;; latency on a request that would throw "missing key".
      (try
        (js-await (direct-fetch url fmt max-len))
        (catch :default e1
          (if (has-tinyfish-key?)
            (try
              (js-await (tinyfish-fetch url fmt max-len))
              (catch :default e2
                (try
                  (js-await (jina-fetch url fmt max-len))
                  (catch :default e3
                    (throw (js/Error.
                            (str "All providers failed.\n"
                                 "  direct:   " (or (.-message e1) (str e1)) "\n"
                                 "  tinyfish: " (or (.-message e2) (str e2)) "\n"
                                 "  jina:     " (or (.-message e3) (str e3)))))))))
            (try
              (js-await (jina-fetch url fmt max-len))
              (catch :default e3
                (throw (js/Error.
                        (str "Both providers failed.\n"
                             "  direct: " (or (.-message e1) (str e1)) "\n"
                             "  jina:   " (or (.-message e3) (str e3)))))))))))))

(def web-fetch-tool
  (tool
   #js {:description "Fetch content from a URL and extract text. Always use this instead of curl in bash. Supports HTML (converts to markdown), JSON, and plain text. Auto fallback chain: direct → Tinyfish (if TINYFISH_API_KEY set) → Jina Reader. Tinyfish and Jina both handle JS-rendered + anti-bot pages."
        :inputSchema  (schema/->zod {
                                    :url [:string "URL to fetch"]
                                    :format [:enum ["text" "markdown" "html"] {:optional true :doc "Output format: markdown (default, converts HTML to Markdown), text (strips HTML), or html (raw)"}]
                                    :max_length [:number {:optional true :doc "Maximum output characters (default: 20000)"}]
                                    :provider [:enum ["auto" "direct" "jina" "tinyfish"] {:optional true :doc "Fetch provider. auto (default): direct fetch, then Tinyfish when TINYFISH_API_KEY is set, then Jina Reader. direct: raw fetch only. jina: Jina Reader only (handles JS-rendered pages, anti-bot, PDFs). tinyfish: Tinyfish Fetch API — headless browser, JS rendering, PDF text extraction (requires TINYFISH_API_KEY)."}]})
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

(defn- resolve-tavily-key []
  (or (.. js/process -env -TAVILY_API_KEY)
      (read-credential "tavily")))

(defn ^:async tavily-search
  "Search via Tavily (requires TAVILY_API_KEY or 'tavily' in ~/.nyma/credentials.json).
   Returns formatted results plus, when available, Tavily's pre-synthesized answer."
  [query num-results]
  (let [api-key (resolve-tavily-key)]
    (when-not api-key
      (throw (js/Error. "Tavily requires TAVILY_API_KEY env var or 'tavily' in ~/.nyma/credentials.json. Get a free key at https://tavily.com")))
    (let [body     (js/JSON.stringify
                    #js {:query         query
                         :max_results   (or num-results 5)
                         :search_depth  "basic"
                         :include_answer true})
          response (js-await
                    (js/fetch "https://api.tavily.com/search"
                              #js {:method  "POST"
                                   :body    body
                                   :signal  (js/AbortSignal.timeout 20000)
                                   :headers #js {"Content-Type"  "application/json"
                                                 "Authorization" (str "Bearer " api-key)}}))]
      (when-not (.-ok response)
        (throw (js/Error. (str "Tavily error: HTTP " (.-status response)
                               (when (= 401 (.-status response)) ". Check your TAVILY_API_KEY.")
                               (when (= 429 (.-status response)) ". Rate limited — try again later.")))))
      (let [data      (js-await (.json response))
            answer    (.-answer data)
            results   (or (.-results data) #js [])
            formatted (map-indexed
                       (fn [i r]
                         (str (inc i) ". " (.-title r) "\n"
                              "   " (.-url r) "\n"
                              "   " (or (.-content r) "")))
                       results)]
        (if (seq formatted)
          (str (when (and answer (not (empty? answer)))
                 (str "Answer: " answer "\n\n"))
               (.join (vec formatted) "\n\n"))
          "No results found.")))))

(defn ^:async tinyfish-search
  "Search via Tinyfish (api.search.tinyfish.ai). Requires TINYFISH_API_KEY
   or 'tinyfish' in ~/.nyma/credentials.json. Search calls don't burn
   credits per Tinyfish docs. Optional :location (ISO country code) and
   :language (language code) for geo-targeted results."
  [query num-results & [{:keys [location language]}]]
  (let [api-key (tinyfish-key)]
    (when-not api-key
      (throw (js/Error. "Tinyfish requires TINYFISH_API_KEY env var or 'tinyfish' in ~/.nyma/credentials.json. Get a key at https://agent.tinyfish.ai/api-keys")))
    (let [params (js/URLSearchParams.)
          _      (.append params "query" query)
          _      (when location (.append params "location" location))
          _      (when language (.append params "language" language))
          url    (str "https://api.search.tinyfish.ai?" (.toString params))
          response (js-await
                    (js/fetch url
                              #js {:signal  (js/AbortSignal.timeout 20000)
                                   :headers #js {"X-API-Key" api-key
                                                 "Accept"    "application/json"}}))]
      (when-not (.-ok response)
        (throw (js/Error. (str "Tinyfish search error: HTTP " (.-status response)
                               (when (= 401 (.-status response)) ". Check your TINYFISH_API_KEY.")
                               (when (= 429 (.-status response)) ". Rate limited — try again later.")))))
      (let [data    (js-await (.json response))
            results (take (or num-results 5)
                          (or (.-results data) #js []))
            formatted (map-indexed
                       (fn [i r]
                         (str (inc i) ". " (or (.-title r) "(no title)") "\n"
                              "   " (or (.-url r) "")
                              (when-let [snippet (.-snippet r)]
                                (str "\n   " snippet))))
                       results)]
        (if (seq formatted)
          (.join (vec formatted) "\n\n")
          "No results found.")))))

(defn ^:async jina-search
  "Search via Jina (s.jina.ai). Returns search results with extracted page content
   in one call. Works without a key (rate-limited) or with JINA_API_KEY."
  [query num-results]
  (let [api-key (resolve-jina-key)
        url     (str "https://s.jina.ai/?q=" (js/encodeURIComponent query))
        headers #js {"Accept"          "application/json"
                     "X-Return-Format" "markdown"
                     "User-Agent"      "Nyma/1.0"}
        _       (when api-key (aset headers "Authorization" (str "Bearer " api-key)))
        response (js-await
                  (js/fetch url
                            #js {:signal  (js/AbortSignal.timeout 30000)
                                 :headers headers}))]
    (when-not (.-ok response)
      (throw (js/Error. (str "Jina Search error: HTTP " (.-status response)
                             (when (= 401 (.-status response)) ". Check JINA_API_KEY.")
                             (when (= 429 (.-status response)) ". Rate limited — set JINA_API_KEY for higher limits.")))))
    (let [data    (js-await (.json response))
          results (take (or num-results 5)
                        (or (.-data data) #js []))
          formatted (map-indexed
                     (fn [i r]
                       (str (inc i) ". " (or (.-title r) "(no title)") "\n"
                            "   " (or (.-url r) "")
                            (when-let [desc (.-description r)]
                              (str "\n   " desc))))
                     results)]
      (if (seq formatted)
        (.join (vec formatted) "\n\n")
        "No results found."))))

(defn ^:async web-search-execute [{:keys [query num_results provider location language]}]
  (when (or (nil? query) (empty? query))
    (throw (js/Error. "Search query cannot be empty")))
  (let [prov (or provider "auto")]
    (case prov
      ;; Tavily and Brave are EXPLICIT-only — pass `provider:` to use them.
      "tavily"     (js-await (tavily-search query num_results))
      "brave"      (js-await (brave-search query num_results))
      ;; The three providers in the auto fallback chain are also
      ;; addressable explicitly.
      "tinyfish"   (js-await (tinyfish-search query num_results
                                              {:location location
                                               :language language}))
      "jina"       (js-await (jina-search query num_results))
      "duckduckgo" (js-await (ddg-search query num_results))
      ;; auto: tinyfish (if key) → jina → ddg. Same key-check skip as
      ;; web_fetch's chain so we don't spend a round-trip on a known-
      ;; missing key. Jina works anonymously (rate-limited); ddg is
      ;; the unconditional safety net.
      (if (has-tinyfish-key?)
        (try
          (js-await (tinyfish-search query num_results
                                     {:location location
                                      :language language}))
          (catch :default e1
            (try
              (js-await (jina-search query num_results))
              (catch :default e2
                (try
                  (js-await (ddg-search query num_results))
                  (catch :default e3
                    (throw (js/Error.
                            (str "All providers failed.\n"
                                 "  tinyfish: " (or (.-message e1) (str e1)) "\n"
                                 "  jina:     " (or (.-message e2) (str e2)) "\n"
                                 "  ddg:      " (or (.-message e3) (str e3)))))))))))
        (try
          (js-await (jina-search query num_results))
          (catch :default e2
            (try
              (js-await (ddg-search query num_results))
              (catch :default e3
                (throw (js/Error.
                        (str "Both providers failed.\n"
                             "  jina: " (or (.-message e2) (str e2)) "\n"
                             "  ddg:  " (or (.-message e3) (str e3)))))))))))))

(def web-search-tool
  (tool
   #js {:description "Search the web for information. Always use this for any web lookup — never use curl to search engines in bash. Auto fallback chain: Tinyfish (if TINYFISH_API_KEY set) → Jina (free, rate-limited) → DuckDuckGo. Tavily and Brave are explicit-only — pass provider=tavily or provider=brave to use them."
        :inputSchema  (schema/->zod {
                                    :query [:string "Search query"]
                                    :num_results [:number {:optional true :doc "Number of results (default: 5, max: 20)"}]
                                    :provider [:enum ["tinyfish" "jina" "duckduckgo" "tavily" "brave"] {:optional true :doc "Search provider. Auto chain (default): tinyfish (if key) → jina → duckduckgo. Tavily and Brave require explicit provider= to use; they're not in the auto chain to avoid surprise billing."}]
                                    :location [:string {:optional true :doc "ISO country code for geo-targeted results (Tinyfish only, e.g. 'US', 'FR'). Ignored by other providers."}]
                                    :language [:string {:optional true :doc "Language code for results (Tinyfish only, e.g. 'en', 'fr'). Ignored by other providers."}]})
        :execute web-search-execute}))

;;; ─── deep_research ─────────────────────────────────────────

(defn ^:async openai-chat-completion
  "POST an OpenAI-compatible chat/completions request and return content + citations."
  [base-url model api-key query timeout-ms]
  (let [body     (js/JSON.stringify
                  #js {:model    model
                       :messages #js [#js {:role "user" :content query}]})
        response (js-await
                  (js/fetch (str base-url "/chat/completions")
                            #js {:method  "POST"
                                 :body    body
                                 :signal  (js/AbortSignal.timeout timeout-ms)
                                 :headers #js {"Content-Type"  "application/json"
                                               "Authorization" (str "Bearer " api-key)}}))]
    (when-not (.-ok response)
      (let [err-text (try (js-await (.text response)) (catch :default _ ""))]
        (throw (js/Error. (str "HTTP " (.-status response) " " (.-statusText response)
                               (when (= 401 (.-status response)) ". Check API key.")
                               (when (= 429 (.-status response)) ". Rate limited.")
                               (when (and err-text (not (empty? err-text)))
                                 (str " — " (subs err-text 0 (min 300 (count err-text))))))))))
    (let [data      (js-await (.json response))
          choices   (or (.-choices data) #js [])
          choice    (aget choices 0)
          content   (or (and choice (.. choice -message -content)) "")
          citations (or (.-citations data) #js [])]
      (if (and citations (pos? (.-length citations)))
        (str content
             "\n\nSources:\n"
             (.join (.map citations
                          (fn [c i] (str (inc i) ". " c)))
                    "\n"))
        content))))

(defn ^:async perplexity-research [query]
  (let [k (or (.. js/process -env -PERPLEXITY_API_KEY)
              (read-credential "perplexity"))]
    (when-not k
      (throw (js/Error. "Perplexity requires PERPLEXITY_API_KEY or 'perplexity' in ~/.nyma/credentials.json")))
    (js-await (openai-chat-completion "https://api.perplexity.ai" "sonar-pro" k query 120000))))

(defn ^:async jina-deepsearch [query]
  (let [k (resolve-jina-key)]
    (when-not k
      (throw (js/Error. "Jina DeepSearch requires JINA_API_KEY or 'jina' in ~/.nyma/credentials.json")))
    (js-await (openai-chat-completion "https://deepsearch.jina.ai/v1" "jina-deepsearch-v1" k query 180000))))

(defn ^:async deep-research-execute [{:keys [query provider]}]
  (when (or (nil? query) (empty? query))
    (throw (js/Error. "Research query cannot be empty")))
  (let [perplexity-available? (or (.. js/process -env -PERPLEXITY_API_KEY)
                                  (read-credential "perplexity"))
        prov (or provider
                 (if perplexity-available? "auto" "jina"))]
    (case prov
      "perplexity" (js-await (perplexity-research query))
      "jina"       (js-await (jina-deepsearch query))
      "auto"       (try
                     (js-await (perplexity-research query))
                     (catch :default e
                       (try
                         (js-await (jina-deepsearch query))
                         (catch :default je
                           (throw (js/Error. (str "Both providers failed. perplexity: "
                                                  (or (.-message e) (str e))
                                                  " | jina: "
                                                  (or (.-message je) (str je)))))))))
      (js-await (jina-deepsearch query)))))

(def deep-research-tool
  (tool
   #js {:description "Run an agentic deep-research query — returns a synthesized answer with citations. Use for open-ended research questions where you'd otherwise need many web_search + web_fetch turns. Slow (30-120s). Auto-selects Perplexity Sonar (if PERPLEXITY_API_KEY set) with Jina DeepSearch fallback; otherwise uses Jina DeepSearch (reuses 'jina' credential from ~/.nyma/credentials.json)."
        :inputSchema  (schema/->zod {
                                    :query [:string "Research question — be specific. Single string, not keywords."]
                                    :provider [:enum ["auto" "perplexity" "jina"] {:optional true :doc "Provider override. auto (default if Perplexity key set): try Perplexity, fall back to Jina DeepSearch. perplexity: Perplexity Sonar Pro only. jina: Jina DeepSearch only."}]})
        :execute deep-research-execute}))

;;; ─── view_image (multimodal) ───────────────────────────────

(def ^:private max-image-bytes (* 10 1024 1024))   ; sanity cap

(defn ^:async view-image-execute [{:keys [path]}]
  (cond
    (not (fs/existsSync path))
    (str "view_image: file not found: " path)
    ;; Size-cap BEFORE reading, so a huge/wrong file can't OOM the process.
    (> (.-size (fs/statSync path)) max-image-bytes)
    (str "view_image: " path " is ~" (js/Math.round (/ (.-size (fs/statSync path)) 1048576))
         " MB — too large; render/screenshot at a smaller scale.")
    :else
    (let [buf   (js-await (.arrayBuffer (js/Bun.file path)))
          bytes (.-byteLength buf)
          u8    (js/Uint8Array. buf)
          ;; Extension first, then magic-byte sniff (extension-less screenshots).
          mt    (or (mm/media-type-for path) (mm/sniff-media-type u8))]
      (if (nil? mt)
        (str "view_image: not a recognized image (png/jpg/webp/gif): " path)
        (mm/image-result (.toString (js/Buffer.from buf) "base64") mt
                         (str "image " path " (" (js/Math.round (/ bytes 1024)) " KB, " mt ")"))))))

(def view-image-tool
  (tool
   #js {:description
        "View an image file (PNG/JPG/WebP/GIF) so you can SEE it — a screenshot or a rendered document/slide. Use to VERIFY visual output (does it look right?); for editing prefer structured/text reads. The provider auto-downscales; look sparingly."
        :inputSchema (schema/->zod {
                                    :path [:string "Path to the image file"]})
        :execute view-image-execute
        :toModelOutput mm/tool-model-output}))

;;; ─── retrieve_result ───────────────────────────────────────
;;; Recall for anything tool-result-policy truncated. A builtin rather than an
;;; extension because truncation is core and unconditional — put recall in an
;;; extension and every handle dangles whenever that extension is off.

(def ^:private recall-page-chars
  "Default page size. Under the 12000 cap this tool's own output is subject to,
   with room for the header and footer, so a page is never itself truncated."
  8000)

(defn- retrieve-result-execute [args]
  (let [id     (str (or (.-id args) ""))
        offset (let [n (.-offset args)] (if (number? n) (js/Math.max 0 (js/Math.floor n)) 0))
        limit  (let [n (.-limit args)]
                 (if (number? n)
                   (js/Math.min recall-page-chars (js/Math.max 1 (js/Math.floor n)))
                   recall-page-chars))
        {:keys [found? tool total start end body eof?]} (policy/store-read id offset limit)]
    (if-not found?
      ;; Never throw: an unknown id is a normal thing for a model to hit after
      ;; eviction, and the recovery it needs is to re-run the tool.
      (str "retrieve_result: no stored output for id \"" id "\" "
           "(handles are per-session and bounded; re-run the tool)")
      (str "[retrieve_result " id " (" tool ") — chars " start "–" end " of " total "]\n"
           body "\n"
           (if eof?
             (str "[eof — " total " chars total]")
             (str "[nextOffset=" end " — retrieve_result(id=\"" id "\", offset=" end ") for more]"))))))

(def retrieve-result-tool
  (tool
   #js {:description (str "Retrieve the part of a tool result that was truncated. "
                          "Use the id and offset from a `retrieve_result(...)` truncation notice. "
                          "Reach for this when the truncated head is not enough to answer — "
                          "it pages, so follow nextOffset until eof.")
        :inputSchema (schema/->zod {
                                    :id [:string "The id from the truncation notice"]
                                    :offset [:number {:optional true :doc "Character offset to resume from; use the notice's offset, then each reply's nextOffset. Default 0."}]
                                    :limit [:number {:optional true :doc (str "Max characters to return (default and max " recall-page-chars ")")}]})
        :execute retrieve-result-execute}))

;;; ─── builtin tools map ─────────────────────────────────────

(def builtin-tools
  {"read"       read-tool
   "view_image" view-image-tool
   "write"      write-tool
   "edit"       edit-tool
   "bash"       bash-tool
   "think"      think-tool
   "ls"         ls-tool
   "glob"       glob-tool
   "grep"       grep-tool
   "web_fetch"     web-fetch-tool
   "web_search"    web-search-tool
   "deep_research" deep-research-tool
   "retrieve_result" retrieve-result-tool})
