(ns tools.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.tools :refer [read-execute write-execute edit-execute bash-execute]]))

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
