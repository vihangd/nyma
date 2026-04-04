(ns tools.test
  (:require ["bun:test" :refer [describe it expect beforeAll afterAll]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["ai" :refer [tool]]
            ["zod" :as z]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [agent.tools :refer [read-execute write-execute edit-execute bash-execute
                                  think-execute ls-execute glob-execute grep-execute
                                  web-fetch-execute web-search-execute
                                  builtin-tools read-tool write-tool edit-tool bash-tool
                                  try-binary detect-search-binary]]
            [agent.tool-registry :refer [create-registry]]
            [agent.middleware :refer [create-pipeline wrap-tools-with-middleware]]
            [agent.events :refer [create-event-bus]]))

(defn- make-tmp-dir []
  (.mkdtempSync fs (str (.tmpdir os) "/nyma-tool-test-")))

(defn- cleanup [dir]
  (.rmSync fs dir #js {:recursive true}))

;; async test helpers (defn ^:async works, fn ^:async doesn't)
(defn ^:async test-bash-stdout []
  (let [result (js-await (bash-execute {:command "echo hello"}))
        parsed (js/JSON.parse result)]
    (-> (expect (.trim (.-stdout parsed))) (.toBe "hello"))))

(defn ^:async test-bash-exit-0 []
  (let [result (js-await (bash-execute {:command "true"}))
        parsed (js/JSON.parse result)]
    (-> (expect (.-exitCode parsed)) (.toBe 0))))

(defn ^:async test-bash-exit-nonzero []
  (let [result (js-await (bash-execute {:command "exit 42"}))
        parsed (js/JSON.parse result)]
    (-> (expect (.-exitCode parsed)) (.toBe 42))))

(defn ^:async test-bash-stderr []
  (let [result (js-await (bash-execute {:command "echo errmsg >&2"}))
        parsed (js/JSON.parse result)]
    (-> (expect (.includes (.-stderr parsed) "errmsg")) (.toBe true))))

(defn ^:async test-read-entire []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "test.txt")]
    (.writeFileSync fs tmp-file "hello world")
    (let [result (js-await (read-execute {:path tmp-file}))]
      (-> (expect result) (.toBe "hello world")))
    (cleanup tmp-dir)))

(defn ^:async test-read-range []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "lines.txt")]
    (.writeFileSync fs tmp-file "line1\nline2\nline3\nline4")
    (let [result (js-await (read-execute {:path tmp-file :range [2 3]}))]
      (-> (expect result) (.toBe "line2\nline3")))
    (cleanup tmp-dir)))

(defn ^:async test-write-content []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "out.txt")]
    (js-await (write-execute {:path tmp-file :content "test content"}))
    (let [content (.readFileSync fs tmp-file "utf8")]
      (-> (expect content) (.toBe "test content")))
    (cleanup tmp-dir)))

(defn ^:async test-write-byte-count []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "out.txt")
        result   (js-await (write-execute {:path tmp-file :content "abc"}))]
    (-> (expect (.includes result "3")) (.toBe true))
    (cleanup tmp-dir)))

(defn ^:async test-edit-replace []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "edit.txt")]
    (.writeFileSync fs tmp-file "hello world")
    (js-await (edit-execute {:path tmp-file
                             :old_string "world"
                             :new_string "earth"}))
    (let [content (.readFileSync fs tmp-file "utf8")]
      (-> (expect content) (.toBe "hello earth")))
    (cleanup tmp-dir)))

(defn ^:async test-edit-throws []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "edit.txt")]
    (.writeFileSync fs tmp-file "hello world")
    (try
      (js-await (edit-execute {:path tmp-file
                                :old_string "missing"
                                :new_string "x"}))
      ;; Should not reach here
      (-> (expect true) (.toBe false))
      (catch :default e
        (-> (expect (.-message e)) (.toBe "old_string not found in file"))))
    (cleanup tmp-dir)))

(describe "agent.tools - bash"
  (fn []
    (it "returns stdout from echo command" test-bash-stdout)
    (it "returns exit code 0 on success" test-bash-exit-0)
    (it "returns non-zero exit code on failure" test-bash-exit-nonzero)
    (it "captures stderr" test-bash-stderr)))

(describe "agent.tools - read"
  (fn []
    (it "reads entire file" test-read-entire)
    (it "reads line range" test-read-range)))

(describe "agent.tools - write"
  (fn []
    (it "creates file with content" test-write-content)
    (it "returns byte count message" test-write-byte-count)))

(describe "agent.tools - edit"
  (fn []
    (it "replaces exact text" test-edit-replace)
    (it "throws when old_string not found" test-edit-throws)))

;; --- Tool definition structure tests ---

(describe "agent.tools - builtin-tools map"
  (fn []
    (it "contains exactly 10 tools"
      (fn []
        (-> (expect (count builtin-tools)) (.toBe 10))))

    (it "has all builtin tool keys"
      (fn []
        (doseq [name ["read" "write" "edit" "bash" "think" "ls" "glob" "grep" "web_fetch" "web_search"]]
          (-> (expect (get builtin-tools name)) (.toBeTruthy)))))))

;; --- Tool definitions must have the properties the AI SDK reads ---
;; The AI SDK's prepareToolsAndToolChoice reads `tool.inputSchema` (NOT `tool.parameters`).
;; `tool()` is an identity function — it returns its input unchanged.
;; `asSchema(tool.inputSchema)` handles Zod schemas via the ~standard interface.

(describe "agent.tools - tool definitions have required properties"
  (fn []
    (it "read-tool has description, inputSchema, and execute"
      (fn []
        (-> (expect (.-description read-tool)) (.toBeTruthy))
        (-> (expect (.-inputSchema read-tool)) (.toBeTruthy))
        (-> (expect (.-execute read-tool)) (.toBeTruthy))))

    (it "write-tool has description, inputSchema, and execute"
      (fn []
        (-> (expect (.-description write-tool)) (.toBeTruthy))
        (-> (expect (.-inputSchema write-tool)) (.toBeTruthy))
        (-> (expect (.-execute write-tool)) (.toBeTruthy))))

    (it "edit-tool has description, inputSchema, and execute"
      (fn []
        (-> (expect (.-description edit-tool)) (.toBeTruthy))
        (-> (expect (.-inputSchema edit-tool)) (.toBeTruthy))
        (-> (expect (.-execute edit-tool)) (.toBeTruthy))))

    (it "bash-tool has description, inputSchema, and execute"
      (fn []
        (-> (expect (.-description bash-tool)) (.toBeTruthy))
        (-> (expect (.-inputSchema bash-tool)) (.toBeTruthy))
        (-> (expect (.-execute bash-tool)) (.toBeTruthy))))))

;; --- Critical: asSchema(tool.inputSchema) produces valid JSON Schema ---
;; This is the EXACT check the AI SDK performs before sending to Anthropic.
;; asSchema detects Zod schemas via ~standard interface and converts to JSON Schema 7.
;; The resulting JSON Schema MUST have `type: "object"` or Anthropic returns 400.

(describe "agent.tools - inputSchema produces valid JSON Schema via asSchema"
  (fn []
    (it "read-tool inputSchema produces JSON Schema with type:object"
      (fn []
        (let [schema (.-jsonSchema (asSchema (.-inputSchema read-tool)))]
          (-> (expect (.-type schema)) (.toBe "object"))
          (-> (expect (.-properties schema)) (.toBeTruthy)))))

    (it "write-tool inputSchema produces JSON Schema with type:object"
      (fn []
        (let [schema (.-jsonSchema (asSchema (.-inputSchema write-tool)))]
          (-> (expect (.-type schema)) (.toBe "object")))))

    (it "edit-tool inputSchema produces JSON Schema with type:object"
      (fn []
        (let [schema (.-jsonSchema (asSchema (.-inputSchema edit-tool)))]
          (-> (expect (.-type schema)) (.toBe "object")))))

    (it "bash-tool inputSchema produces JSON Schema with type:object"
      (fn []
        (let [schema (.-jsonSchema (asSchema (.-inputSchema bash-tool)))]
          (-> (expect (.-type schema)) (.toBe "object")))))

    (it "all builtin-tools produce valid JSON Schema"
      (fn []
        (doseq [[name t] builtin-tools]
          (let [schema (.-jsonSchema (asSchema (.-inputSchema t)))]
            (-> (expect (.-type schema)) (.toBe "object"))))))))

;; --- Schema integrity through the full wrapping pipeline ---
;; Tools go through: builtin-tools → registry → get-active → wrap-tools-with-middleware
;; → reduce-kv (for streamText). inputSchema must survive every step.

(describe "agent.tools - inputSchema survives full production pipeline"
  (fn []
    (it "inputSchema survives Object.assign wrapping"
      (fn []
        (let [wrapped (js/Object.assign #js {} read-tool
                        #js {:execute (fn [_] "wrapped")})]
          (-> (expect (.-inputSchema wrapped)) (.toBeTruthy))
          (let [schema (.-jsonSchema (asSchema (.-inputSchema wrapped)))]
            (-> (expect (.-type schema)) (.toBe "object"))))))

    (it "inputSchema survives registry → get-active → wrap → reduce-kv"
      (fn []
        (let [events   (create-event-bus)
              pipeline (create-pipeline events)
              registry (create-registry builtin-tools)
              active   ((:get-active registry))
              wrapped  (wrap-tools-with-middleware active pipeline events)
              js-tools (reduce-kv (fn [acc k v] (doto acc (aset k v))) #js {} wrapped)]
          ;; Every tool must have inputSchema that produces valid JSON Schema
          (doseq [name #js ["read" "write" "edit" "bash"]]
            (let [t      (aget js-tools name)
                  schema (.-jsonSchema (asSchema (.-inputSchema t)))]
              (-> (expect (.-inputSchema t)) (.toBeTruthy))
              (-> (expect (.-type schema)) (.toBe "object"))
              (-> (expect (.-execute t)) (.toBeInstanceOf js/Function)))))))))

;; --- Behavioral tests: think ---

(describe "agent.tools - think (behavioral)"
  (fn []
    (it "returns acknowledgment string"
      (fn []
        (let [result (think-execute {:thought "Let me reason about this..."})]
          (-> (expect result) (.toBe "Thought recorded.")))))

    (it "returns same response regardless of input"
      (fn []
        (let [result (think-execute {:thought ""})]
          (-> (expect result) (.toBe "Thought recorded.")))))))

;; --- Behavioral tests: ls ---

(defn ^:async test-ls-basic []
  (let [tmp-dir (make-tmp-dir)]
    (.writeFileSync fs (.join path tmp-dir "file1.txt") "hello")
    (.writeFileSync fs (.join path tmp-dir "file2.txt") "world")
    (.mkdirSync fs (.join path tmp-dir "subdir"))
    (let [result (js-await (ls-execute {:path tmp-dir}))]
      (-> (expect (.includes result "file1.txt")) (.toBe true))
      (-> (expect (.includes result "file2.txt")) (.toBe true))
      (-> (expect (.includes result "subdir/")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-ls-shows-sizes []
  (let [tmp-dir (make-tmp-dir)]
    (.writeFileSync fs (.join path tmp-dir "test.txt") "12345")
    (let [result (js-await (ls-execute {:path tmp-dir}))]
      (-> (expect (.includes result "5 bytes")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-ls-hidden-files []
  (let [tmp-dir (make-tmp-dir)]
    (.writeFileSync fs (.join path tmp-dir ".hidden") "secret")
    (.writeFileSync fs (.join path tmp-dir "visible.txt") "hi")
    ;; Without :all, hidden files should be filtered
    (let [result (js-await (ls-execute {:path tmp-dir}))]
      (-> (expect (.includes result ".hidden")) (.toBe false))
      (-> (expect (.includes result "visible.txt")) (.toBe true)))
    ;; With :all, hidden files shown
    (let [result (js-await (ls-execute {:path tmp-dir :all true}))]
      (-> (expect (.includes result ".hidden")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-ls-recursive []
  (let [tmp-dir (make-tmp-dir)]
    (.mkdirSync fs (.join path tmp-dir "sub"))
    (.writeFileSync fs (.join path tmp-dir "sub" "deep.txt") "deep")
    (let [result (js-await (ls-execute {:path tmp-dir :recursive true}))]
      (-> (expect (.includes result "deep.txt")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-ls-missing-dir []
  (try
    (js-await (ls-execute {:path "/nonexistent-dir-xyz"}))
    (-> (expect true) (.toBe false))
    (catch :default e
      (-> (expect (.includes (.-message e) "not found")) (.toBe true)))))

(describe "agent.tools - ls (behavioral)"
  (fn []
    (it "lists files and directories" test-ls-basic)
    (it "shows file sizes" test-ls-shows-sizes)
    (it "filters hidden files by default, shows with :all" test-ls-hidden-files)
    (it "recursive mode shows nested files" test-ls-recursive)
    (it "throws on missing directory" test-ls-missing-dir)))

;; --- Behavioral tests: glob ---

(defn ^:async test-glob-finds-files []
  (let [tmp-dir (make-tmp-dir)]
    (.writeFileSync fs (.join path tmp-dir "a.txt") "a")
    (.writeFileSync fs (.join path tmp-dir "b.txt") "b")
    (.writeFileSync fs (.join path tmp-dir "c.js") "c")
    (let [result (js-await (glob-execute {:pattern "*.txt" :path tmp-dir}))]
      (-> (expect (.includes result "a.txt")) (.toBe true))
      (-> (expect (.includes result "b.txt")) (.toBe true))
      (-> (expect (.includes result "c.js")) (.toBe false)))
    (cleanup tmp-dir)))

(defn ^:async test-glob-exclude []
  (let [tmp-dir (make-tmp-dir)]
    (.mkdirSync fs (.join path tmp-dir "keep"))
    (.mkdirSync fs (.join path tmp-dir "skip"))
    (.writeFileSync fs (.join path tmp-dir "keep" "a.txt") "a")
    (.writeFileSync fs (.join path tmp-dir "skip" "b.txt") "b")
    (let [result (js-await (glob-execute {:pattern "**/*.txt" :path tmp-dir :exclude "skip/**"}))]
      (-> (expect (.includes result "a.txt")) (.toBe true))
      (-> (expect (.includes result "b.txt")) (.toBe false)))
    (cleanup tmp-dir)))

(defn ^:async test-glob-no-matches []
  (let [tmp-dir (make-tmp-dir)]
    (.writeFileSync fs (.join path tmp-dir "a.txt") "a")
    (let [result (js-await (glob-execute {:pattern "*.xyz" :path tmp-dir}))]
      (-> (expect result) (.toBe "")))
    (cleanup tmp-dir)))

(describe "agent.tools - glob (behavioral)"
  (fn []
    (it "finds files matching pattern" test-glob-finds-files)
    (it "respects exclude parameter" test-glob-exclude)
    (it "returns empty string for no matches" test-glob-no-matches)))

;; --- Behavioral tests: grep ---

(defn ^:async test-grep-finds-content []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "search.txt")]
    (.writeFileSync fs tmp-file "hello world\nfoo bar\nhello again")
    (let [result (js-await (grep-execute {:pattern "hello" :path tmp-dir}))]
      (-> (expect (.includes result "hello world")) (.toBe true))
      (-> (expect (.includes result "hello again")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-grep-no-matches []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "search.txt")]
    (.writeFileSync fs tmp-file "hello world")
    (let [result (js-await (grep-execute {:pattern "zzzznotfound" :path tmp-dir}))]
      (-> (expect result) (.toBe "")))
    (cleanup tmp-dir)))

(defn ^:async test-grep-ignore-case []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "case.txt")]
    (.writeFileSync fs tmp-file "Hello World")
    (let [result (js-await (grep-execute {:pattern "hello" :path tmp-dir :ignore_case true}))]
      (-> (expect (.includes result "Hello World")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-grep-literal []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "literal.txt")]
    (.writeFileSync fs tmp-file "foo.bar\nfooXbar")
    ;; Literal search for "foo.bar" — should NOT match "fooXbar"
    (let [result (js-await (grep-execute {:pattern "foo.bar" :path tmp-dir :literal true}))]
      (-> (expect (.includes result "foo.bar")) (.toBe true))
      (-> (expect (.includes result "fooXbar")) (.toBe false)))
    (cleanup tmp-dir)))

(defn ^:async test-grep-files-mode []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "data.txt")]
    (.writeFileSync fs tmp-file "match me")
    (let [result (js-await (grep-execute {:pattern "match" :path tmp-dir :output_mode "files"}))]
      (-> (expect (.includes result "data.txt")) (.toBe true)))
    (cleanup tmp-dir)))

(defn ^:async test-grep-count-mode []
  (let [tmp-dir  (make-tmp-dir)
        tmp-file (.join path tmp-dir "data.txt")]
    (.writeFileSync fs tmp-file "aaa\naaa\nbbb")
    (let [result (js-await (grep-execute {:pattern "aaa" :path tmp-dir :output_mode "count"}))]
      (-> (expect (.includes result "2")) (.toBe true)))
    (cleanup tmp-dir)))

(describe "agent.tools - grep (behavioral)"
  (fn []
    (it "finds matching content" test-grep-finds-content)
    (it "returns empty for no matches" test-grep-no-matches)
    (it "supports case-insensitive search" test-grep-ignore-case)
    (it "literal flag prevents regex interpretation" test-grep-literal)
    (it "files output mode returns paths" test-grep-files-mode)
    (it "count output mode returns match counts" test-grep-count-mode)))

;; --- Behavioral tests: web_fetch (mocked) ---

(defn- mock-fetch [response-body content-type status]
  (fn [_url _opts]
    (js/Promise.resolve
      #js {:ok        (< status 400)
           :status    status
           :statusText (if (< status 400) "OK" "Error")
           :headers   #js {:get (fn [h] (when (= (.toLowerCase h) "content-type") content-type))}
           :text      (fn [] (js/Promise.resolve response-body))})))

(defn ^:async test-web-fetch-markdown []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch (mock-fetch "<html><body><h1>Title</h1><p>Hello world</p></body></html>" "text/html" 200))
    (try
      (let [result (js-await (web-fetch-execute {:url "https://example.com" :format "markdown"}))]
        (-> (expect (.includes result "Title")) (.toBe true))
        (-> (expect (.includes result "Hello world")) (.toBe true)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-fetch-text []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch (mock-fetch "<html><body><h1>Title</h1><p>Content</p></body></html>" "text/html" 200))
    (try
      (let [result (js-await (web-fetch-execute {:url "https://example.com" :format "text"}))]
        (-> (expect (.includes result "Title")) (.toBe true))
        (-> (expect (.includes result "Content")) (.toBe true))
        ;; text mode should strip HTML tags
        (-> (expect (.includes result "<h1>")) (.toBe false)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-fetch-html []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch (mock-fetch "<html><body><h1>Title</h1></body></html>" "text/html" 200))
    (try
      (let [result (js-await (web-fetch-execute {:url "https://example.com" :format "html"}))]
        ;; html mode returns raw
        (-> (expect (.includes result "<h1>Title</h1>")) (.toBe true)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-fetch-truncate []
  (let [saved js/globalThis.fetch
        long-body (str "<html><body>" (.repeat "x" 100) "</body></html>")]
    (set! js/globalThis.fetch (mock-fetch long-body "text/html" 200))
    (try
      (let [result (js-await (web-fetch-execute {:url "https://example.com" :format "html" :max_length 50}))]
        (-> (expect (.includes result "[truncated")) (.toBe true)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-fetch-invalid-url []
  (try
    (js-await (web-fetch-execute {:url "not a url"}))
    (-> (expect true) (.toBe false))
    (catch :default e
      (-> (expect (.includes (.-message e) "Invalid URL")) (.toBe true)))))

(defn ^:async test-web-fetch-http-error []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch (mock-fetch "" "text/html" 404))
    (try
      (js-await (web-fetch-execute {:url "https://example.com"}))
      (-> (expect true) (.toBe false))
      (catch :default e
        (-> (expect (.includes (.-message e) "HTTP error")) (.toBe true)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-fetch-non-text []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch (mock-fetch "" "image/png" 200))
    (try
      (js-await (web-fetch-execute {:url "https://example.com"}))
      (-> (expect true) (.toBe false))
      (catch :default e
        (-> (expect (.includes (.-message e) "Cannot extract text")) (.toBe true)))
      (finally
        (set! js/globalThis.fetch saved)))))

(describe "agent.tools - web_fetch (behavioral)"
  (fn []
    (it "returns markdown for HTML content" test-web-fetch-markdown)
    (it "returns stripped text with format=text" test-web-fetch-text)
    (it "returns raw HTML with format=html" test-web-fetch-html)
    (it "truncates to max_length" test-web-fetch-truncate)
    (it "throws on invalid URL" test-web-fetch-invalid-url)
    (it "throws on HTTP errors" test-web-fetch-http-error)
    (it "throws on non-text content types" test-web-fetch-non-text)))

;; --- Behavioral tests: web_search (mocked) ---

(defn- mock-ddg-response []
  (str "<html><body><table>"
       "<tr><td><a rel=\"nofollow\" href=\"https://example.com/1\" class='result-link'>Result One</a></td></tr>"
       "<tr><td class='result-snippet'>First snippet here</td></tr>"
       "<tr><td><a rel=\"nofollow\" href=\"https://example.com/2\" class='result-link'>Result Two</a></td></tr>"
       "<tr><td class='result-snippet'>Second snippet here</td></tr>"
       "</table></body></html>"))

(defn ^:async test-web-search-parses-results []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch
      (fn [_url _opts]
        (js/Promise.resolve
          #js {:ok true :text (fn [] (js/Promise.resolve (mock-ddg-response)))})))
    (try
      (let [result (js-await (web-search-execute {:query "test query" :provider "duckduckgo"}))]
        (-> (expect (.includes result "Result One")) (.toBe true))
        (-> (expect (.includes result "example.com/1")) (.toBe true))
        (-> (expect (.includes result "First snippet")) (.toBe true)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-search-respects-num-results []
  (let [saved js/globalThis.fetch]
    (set! js/globalThis.fetch
      (fn [_url _opts]
        (js/Promise.resolve
          #js {:ok true :text (fn [] (js/Promise.resolve (mock-ddg-response)))})))
    (try
      (let [result (js-await (web-search-execute {:query "test" :num_results 1 :provider "duckduckgo"}))]
        ;; Should have Result One but NOT Result Two
        (-> (expect (.includes result "Result One")) (.toBe true))
        (-> (expect (.includes result "Result Two")) (.toBe false)))
      (finally
        (set! js/globalThis.fetch saved)))))

(defn ^:async test-web-search-empty-query []
  (try
    (js-await (web-search-execute {:query ""}))
    (-> (expect true) (.toBe false))
    (catch :default e
      (-> (expect (.-message e)) (.toBeTruthy)))))

(describe "agent.tools - web_search (behavioral)"
  (fn []
    (it "parses DDG results correctly" test-web-search-parses-results)
    (it "respects num_results limit" test-web-search-respects-num-results)
    (it "throws on empty query" test-web-search-empty-query)))

;; ── Tool helpers ─────────────────────────────────────────────

(defn ^:async test-try-binary-exists []
  (let [result (js-await (try-binary "sh"))]
    (-> (expect result) (.toBe true))))

(defn ^:async test-try-binary-nonexistent []
  (let [result (js-await (try-binary "nonexistent-binary-xyz-999"))]
    (-> (expect result) (.toBe false))))

(defn ^:async test-detect-search-binary []
  (let [result (js-await (detect-search-binary))]
    ;; Should return one of the known binaries
    (-> (expect (contains? #{"rg" "ag" "grep"} result)) (.toBe true))))

(describe "tool helpers" (fn []
  (it "try-binary returns true for existing binary" test-try-binary-exists)
  (it "try-binary returns false for nonexistent binary" test-try-binary-nonexistent)
  (it "detect-search-binary returns valid binary" test-detect-search-binary)))
