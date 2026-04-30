(ns agent.extensions.claude-hook-bridge.tool-names
  "Bidirectional mapping between Claude Code's TitleCase tool names and
   nyma's lowercase canonical names. The bridge translates at the
   boundary so user-facing config (and ecosystem hook scripts) see CC
   names while the native event bus stays lowercase.")

(def cc->nyma
  {"Bash"          "bash"
   "Read"          "read"
   "Edit"          "edit"
   "Write"         "write"
   "Glob"          "glob"
   "Grep"          "grep"
   "LS"            "ls"
   "WebFetch"      "web_fetch"
   "WebSearch"     "web_search"
   "Agent"         "agent"
   "Task"          "agent"
   "ExitPlanMode"  "exit_plan_mode"
   "Think"         "think"
   "AskUserQuestion" "ask_user_question"
   "TaskCreate"    "task_create"
   "TaskUpdate"    "task_update"
   "TaskList"      "task_list"
   "NotebookEdit"  "notebook_edit"})

(def nyma->cc
  (into {} (map (fn [[k v]] [v k]) cc->nyma)))

(defn cc-name
  "Translate a nyma lowercase tool name to its CC TitleCase form.
   MCP tools (mcp__server__tool) pass through unchanged. Unknown tools
   are returned as-is so the matcher still gets to evaluate them."
  [nyma-name]
  (let [s (str nyma-name)]
    (cond
      (.startsWith s "mcp__") s
      (contains? nyma->cc s)  (get nyma->cc s)
      :else s)))

(defn nyma-name
  "Translate a CC TitleCase tool name to nyma's lowercase. MCP tools
   pass through unchanged."
  [cc-name-str]
  (let [s (str cc-name-str)]
    (cond
      (.startsWith s "mcp__") s
      (contains? cc->nyma s)  (get cc->nyma s)
      :else s)))
