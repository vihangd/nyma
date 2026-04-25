(ns commands-share.test
  "Tests for messages->markdown and messages->html — pure conversion functions."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.share :refer [messages->markdown messages->html]]))

(def ^:private msgs
  [{:role "user"      :content "Hello"}
   {:role "assistant" :content "Hi there"}
   {:role "tool_call" :content "{\"tool\":\"bash\"}"}])

;;; ─── messages->markdown ───────────────────────────────────────────────────

(describe "messages->markdown" (fn []
                                 (it "includes session name in header"
                                     (fn []
                                       (let [md (messages->markdown msgs "my-session")]
                                         (-> (expect (.includes md "my-session")) (.toBe true)))))

                                 (it "includes NYMA attribution"
                                     (fn []
                                       (-> (expect (.includes (messages->markdown msgs "s") "NYMA")) (.toBe true))))

                                 (it "renders user role as heading"
                                     (fn []
                                       (-> (expect (.includes (messages->markdown msgs "s") "## User")) (.toBe true))))

                                 (it "renders assistant role as heading"
                                     (fn []
                                       (-> (expect (.includes (messages->markdown msgs "s") "## Assistant")) (.toBe true))))

                                 (it "wraps tool_call content in code fence"
                                     (fn []
                                       (let [md (messages->markdown msgs "s")]
                                         (-> (expect (.includes md "```")) (.toBe true))
                                         (-> (expect (.includes md "{\"tool\":\"bash\"}")) (.toBe true)))))

                                 (it "includes message content"
                                     (fn []
                                       (let [md (messages->markdown msgs "s")]
                                         (-> (expect (.includes md "Hello")) (.toBe true))
                                         (-> (expect (.includes md "Hi there")) (.toBe true)))))

                                 (it "handles empty message list"
                                     (fn []
                                       (let [md (messages->markdown [] "empty")]
                                         (-> (expect (string? md)) (.toBe true))
                                         (-> (expect (.includes md "empty")) (.toBe true)))))

                                 (it "handles messages with nil content"
                                     (fn []
                                       (let [md (messages->markdown [{:role "user" :content nil}] "s")]
                                         (-> (expect (string? md)) (.toBe true)))))))

;;; ─── messages->html ───────────────────────────────────────────────────────

(describe "messages->html" (fn []
                             (it "produces valid HTML doctype"
                                 (fn []
                                   (-> (expect (.startsWith (messages->html msgs "s") "<!DOCTYPE html>")) (.toBe true))))

                             (it "includes session name in title"
                                 (fn []
                                   (-> (expect (.includes (messages->html msgs "test-session") "test-session")) (.toBe true))))

                             (it "escapes & in session name"
                                 (fn []
                                   (let [html (messages->html msgs "a&b")]
                                     (-> (expect (.includes html "a&amp;b")) (.toBe true))
                                     (-> (expect (.includes html "a&b")) (.toBe false)))))

                             (it "escapes < and > in content"
                                 (fn []
                                   (let [html (messages->html [{:role "user" :content "<script>alert(1)</script>"}] "s")]
                                     (-> (expect (.includes html "&lt;script&gt;")) (.toBe true))
                                     (-> (expect (.includes html "<script>")) (.toBe false)))))

                             (it "escapes & in content"
                                 (fn []
                                   (let [html (messages->html [{:role "user" :content "a&b"}] "s")]
                                     (-> (expect (.includes html "a&amp;b")) (.toBe true)))))

                             (it "escapes double quotes in content"
                                 (fn []
                                   (let [html (messages->html [{:role "user" :content "say \"hi\""}] "s")]
                                     (-> (expect (.includes html "&quot;")) (.toBe true)))))

                             (it "applies msg-user class for user role"
                                 (fn []
                                   (-> (expect (.includes (messages->html msgs "s") "msg-user")) (.toBe true))))

                             (it "applies msg-assistant class for assistant role"
                                 (fn []
                                   (-> (expect (.includes (messages->html msgs "s") "msg-assistant")) (.toBe true))))

                             (it "wraps tool_call in pre/code"
                                 (fn []
                                   (let [html (messages->html msgs "s")]
                                     (-> (expect (.includes html "<pre><code>")) (.toBe true)))))

                             (it "handles empty message list"
                                 (fn []
                                   (let [html (messages->html [] "s")]
                                     (-> (expect (.startsWith html "<!DOCTYPE html>")) (.toBe true)))))

                             (it "handles nil content without throwing"
                                 (fn []
                                   (let [html (messages->html [{:role "assistant" :content nil}] "s")]
                                     (-> (expect (string? html)) (.toBe true)))))))
