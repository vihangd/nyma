(ns share.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.commands.share :refer [messages->markdown messages->html
                                          share-to-file create-share-command]]))

(def sample-messages
  [{:role "user" :content "Fix the login bug"}
   {:role "assistant" :content "I'll look at the auth module."}
   {:role "tool_call" :content "read /src/auth.cljs"}
   {:role "assistant" :content "Found the issue. The session token check was inverted."}])

(describe "messages->markdown" (fn []
  (it "includes session name header"
    (fn []
      (let [md (messages->markdown sample-messages "my-session")]
        (-> (expect md) (.toContain "# Session: my-session")))))

  (it "includes all message roles"
    (fn []
      (let [md (messages->markdown sample-messages "test")]
        (-> (expect md) (.toContain "## User"))
        (-> (expect md) (.toContain "## Assistant"))
        (-> (expect md) (.toContain "## Tool_call")))))

  (it "wraps tool_call content in code blocks"
    (fn []
      (let [md (messages->markdown sample-messages "test")]
        (-> (expect md) (.toContain "```\nread /src/auth.cljs\n```")))))))

(describe "messages->html" (fn []
  (it "produces valid HTML structure"
    (fn []
      (let [html (messages->html sample-messages "my-session")]
        (-> (expect html) (.toContain "<!DOCTYPE html"))
        (-> (expect html) (.toContain "<title>Session: my-session</title>"))
        (-> (expect html) (.toContain "</html>")))))

  (it "includes inline CSS"
    (fn []
      (let [html (messages->html sample-messages "test")]
        (-> (expect html) (.toContain "<style>")))))

  (it "includes message content"
    (fn []
      (let [html (messages->html sample-messages "test")]
        (-> (expect html) (.toContain "Fix the login bug"))
        (-> (expect html) (.toContain "auth module")))))))

(defn ^:async test-share-to-file-html []
  (let [tmp-dir (.mkdtempSync fs (str (.tmpdir os) "/nyma-share-"))]
    (try
      (let [result (js-await (share-to-file sample-messages "test" :html tmp-dir))]
        (-> (expect (.endsWith result ".html")) (.toBe true))
        (-> (expect (fs/existsSync result)) (.toBe true))
        (let [content (.readFileSync fs result "utf8")]
          (-> (expect content) (.toContain "<!DOCTYPE html"))))
      (finally
        (.rmSync fs tmp-dir #js {:recursive true})))))

(defn ^:async test-share-to-file-md []
  (let [tmp-dir (.mkdtempSync fs (str (.tmpdir os) "/nyma-share-"))]
    (try
      (let [result (js-await (share-to-file sample-messages "test" :md tmp-dir))]
        (-> (expect (.endsWith result ".md")) (.toBe true))
        (-> (expect (fs/existsSync result)) (.toBe true))
        (let [content (.readFileSync fs result "utf8")]
          (-> (expect content) (.toContain "# Session: test"))))
      (finally
        (.rmSync fs tmp-dir #js {:recursive true})))))

(describe "share-to-file" (fn []
  (it "writes HTML file" test-share-to-file-html)
  (it "writes Markdown file" test-share-to-file-md)))

(describe "messages->html - XSS safety" (fn []
  (it "escapes session name in title and heading"
    (fn []
      (let [html (messages->html [] "<script>alert(1)</script>")]
        (-> (expect html) (.not.toContain "<script>"))
        (-> (expect html) (.toContain "&lt;script&gt;")))))

  (it "handles empty messages"
    (fn []
      (let [html (messages->html [] "test")]
        (-> (expect html) (.toContain "<!DOCTYPE html"))
        (-> (expect html) (.toContain "</html>")))))))

(describe "messages->markdown - edge cases" (fn []
  (it "handles empty messages"
    (fn []
      (let [md (messages->markdown [] "test")]
        (-> (expect md) (.toContain "# Session: test")))))))

(describe "create-share-command" (fn []
  (it "returns map with description and handler"
    (fn []
      (let [cmd (create-share-command {} {})]
        (-> (expect (:description cmd)) (.toContain "share"))
        (-> (expect (fn? (:handler cmd))) (.toBe true)))))))
