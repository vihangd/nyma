(ns agent.extensions.openwiki.git
  "Git plumbing for OpenWiki. Synchronous shell-outs (node:child_process),
   matching the upstream design — git evidence is collected in the host
   process before the agent turn starts. Every call is guarded; a failure
   yields \"\" rather than throwing so the command handler stays robust."
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(defn- git
  "Run a git command, return trimmed stdout, or \"\" on any failure."
  [cmd]
  (try
    (str/trim (cp/execSync (str "git " cmd) #js {:encoding "utf-8"}))
    (catch :default _ "")))

(defn in-git-repo? []
  (try
    (cp/execSync "git rev-parse --is-inside-work-tree" #js {:stdio "ignore"})
    true
    (catch :default _ false)))

(defn head []
  (git "rev-parse HEAD"))

(defn collect-context
  "Status + recent log + working-tree changes — the baseline evidence for init."
  []
  {:status (git "status --short")
   :log    (git "log --oneline --max-count=20")
   :diff   (git "diff --name-status HEAD")})

(defn changes-since
  "Committed changes since `sha` (name-status + oneline). Empty when `sha` is
   blank or invalid."
  [sha]
  (if (str/blank? (str sha))
    ""
    (git (str "log " sha "..HEAD --name-status --oneline"))))

(defn repo-tree
  "A bounded file listing for orientation (excludes common noise dirs)."
  []
  (try
    (str/trim
     (cp/execSync
      (str "git ls-files 2>/dev/null | head -200")
      #js {:encoding "utf-8" :shell "/bin/sh"}))
    (catch :default _ "")))
