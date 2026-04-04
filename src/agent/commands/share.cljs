(ns agent.commands.share
  (:require [clojure.string :as str]
            ["node:path" :as path]
            ["node:fs" :as fs]))

(defn- escape-html
  "Escape a string for safe insertion into HTML content."
  [s]
  (-> (str s)
      (.replace "&" "&amp;")
      (.replace "<" "&lt;")
      (.replace ">" "&gt;")
      (.replace "\"" "&quot;")))

(defn messages->markdown
  "Convert messages to a Markdown document."
  [messages session-name]
  (let [header (str "# Session: " session-name "\n\n"
                    "_Exported from NYMA_\n\n---\n\n")]
    (str header
      (str/join "\n\n---\n\n"
        (map (fn [msg]
               (let [role (or (:role msg) "unknown")
                     content (or (:content msg) "")]
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
                       content (or (:content msg) "")
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

(defn ^:async share-to-file
  "Write session messages to a local file.
   Format is :html or :md. Returns the output file path."
  [messages session-name format output-dir]
  (let [ext     (case format :html ".html" :md ".md" ".html")
        content (case format
                  :html (messages->html messages session-name)
                  :md   (messages->markdown messages session-name)
                  (messages->html messages session-name))
        ;; Sanitize session name for filename
        safe-name (-> session-name
                      (.replace (js/RegExp. "[^a-zA-Z0-9_-]" "g") "_")
                      (.slice 0 50))
        file-name (str safe-name "-" (js/Date.now) ext)
        out-path  (path/join output-dir file-name)]
    ;; Ensure output directory exists
    (when-not (fs/existsSync output-dir)
      (fs/mkdirSync output-dir #js {:recursive true}))
    (js-await (js/Bun.write out-path content))
    out-path))

(defn ^:async share-handler
  "Handle the /share command. Exports session to local file."
  [agent session args _ctx]
  (let [format-arg (first args)
        format     (case format-arg
                     "md"   :md
                     "html" :html
                     nil    :html
                     :html)
        messages   ((:build-context session))
        name-str   (or (:session-file agent) "session")
        ;; Extract just the filename without extension
        base-name  (-> (path/basename name-str) (.replace ".jsonl" ""))
        output-dir (path/join (or (.cwd js/process) ".") ".nyma" "exports")
        result     (js-await (share-to-file messages base-name format output-dir))]
    (js/console.log (str "[nyma] Session exported to: " result))
    result))

(defn create-share-command
  "Create the /share command for session export."
  [agent session]
  {:description "Export session (/share [html|md])"
   :handler     (fn [args ctx] (share-handler agent session args ctx))})
