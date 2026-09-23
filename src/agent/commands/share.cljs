(ns agent.commands.share
  "Session export to Markdown and HTML."
  (:require [clojure.string :as str]))

(defn- escape-html
  "Escape a string for safe insertion into HTML content."
  [s]
  (-> (str s)
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;")))

(defn content->text
  "Flatten message :content to a string. Assistant turns that called tools
   carry a VECTOR of content blocks — stringifying that yields
   `[object Object],…`. Extract text blocks; label the rest by type."
  [content]
  (cond
    (string? content) content
    (or (vector? content) (js/Array.isArray content))
    (->> content
         (map (fn [b]
                (cond
                  (string? b) b
                  (some? (or (:text b) (aget b "text"))) (str (or (:text b) (aget b "text")))
                  :else (str "[" (or (:type b) (aget b "type") "content") "]"))))
         (str/join "
"))
    (nil? content) ""
    :else (str content)))

(defn messages->markdown
  "Convert messages to a Markdown document."
  [messages session-name]
  (let [header (str "# Session: " session-name "\n\n"
                    "_Exported from NYMA_\n\n---\n\n")]
    (str header
         (str/join "\n\n---\n\n"
                   (map (fn [msg]
                          (let [role (or (:role msg) "unknown")
                                content (content->text (:content msg))]
                            (str "## " (.toUpperCase (.charAt role 0)) (.slice role 1) "\n\n"
                                 (if (or (= role "tool_call") (= role "tool_result"))
                                   (str "```\n" content "\n```")
                                   content))))
                        messages)))))

(defn messages->html
  "Convert messages to a self-contained HTML page with dark theme."
  [messages session-name]
  (let [safe-name (escape-html session-name)
        msg-html
        (str/join "\n"
                  (map (fn [msg]
                         (let [role (or (:role msg) "unknown")
                               content (content->text (:content msg))
                               escaped (escape-html content)
                               role-class (case role
                                            "user"      "msg-user"
                                            "assistant" "msg-assistant"
                                            "msg-other")]
                           (str "<div class=\"message " role-class "\">"
                                "<div class=\"role\">" role "</div>"
                                (if (or (= role "tool_call") (= role "tool_result"))
                                  (str "<pre><code>" escaped "</code></pre>")
                                  (str "<div class=\"content\">" escaped "</div>"))
                                "</div>")))
                       messages))]
    (str "<!DOCTYPE html>
<html lang=\"en\">
<head>
<meta charset=\"UTF-8\">
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
<title>Session: " safe-name "</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { background: #1a1b26; color: #c0caf5; font-family: 'SF Mono', Monaco, monospace; padding: 2rem; line-height: 1.6; }
  h1 { color: #7aa2f7; margin-bottom: 1rem; font-size: 1.3rem; }
  .meta { color: #565f89; margin-bottom: 2rem; font-size: 0.85rem; }
  .message { margin-bottom: 1.5rem; padding: 1rem; border-radius: 8px; border-left: 3px solid #3b4261; }
  .msg-user { border-left-color: #7aa2f7; background: #1f2335; }
  .msg-assistant { border-left-color: #9ece6a; background: #1f2335; }
  .msg-other { border-left-color: #bb9af7; background: #1f2335; }
  .role { font-weight: bold; font-size: 0.8rem; text-transform: uppercase; margin-bottom: 0.5rem; color: #565f89; }
  .msg-user .role { color: #7aa2f7; }
  .msg-assistant .role { color: #9ece6a; }
  .content { white-space: pre-wrap; word-break: break-word; }
  pre { background: #16161e; padding: 0.75rem; border-radius: 4px; overflow-x: auto; margin-top: 0.5rem; }
  code { color: #c0caf5; font-size: 0.9rem; }
</style>
</head>
<body>
<h1>Session: " safe-name "</h1>
<div class=\"meta\">Exported from NYMA on " (js/Date.) "</div>
" msg-html "
</body>
</html>")))
