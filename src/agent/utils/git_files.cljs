(ns agent.utils.git-files
  "Synchronous git shell-outs shared by core and extensions. Every call is
   guarded; a failure yields \"\" (or false / []) rather than throwing, so a
   caller outside a repo — or without git at all — degrades instead of dying."
  (:require ["node:child_process" :as cp]
            [clojure.string :as str]))

(defn run-git
  "Run `git <cmd>` in `cwd` (default: process cwd), return trimmed stdout,
   or \"\" on any failure."
  ([cmd] (run-git cmd nil))
  ([cmd cwd]
   (try
     ;; maxBuffer: execSync defaults to 1 MB, and `ls-files` on a large repo
     ;; exceeds it — which threw ENOBUFS, hit the catch, and returned "". The
     ;; caller cannot tell that from "clean repo".
     (str/trim (cp/execSync (str "git " cmd)
                            #js {:encoding "utf-8"
                                 :cwd (or cwd js/undefined)
                                 :maxBuffer (* 64 1024 1024)}))
     (catch :default _ ""))))

(defn in-git-repo?
  ([] (in-git-repo? nil))
  ([cwd]
   (try
     (cp/execSync "git rev-parse --is-inside-work-tree"
                  #js {:stdio "ignore" :cwd (or cwd js/undefined)})
     true
     (catch :default _ false))))

(defn list-files
  "Tracked plus untracked-but-not-ignored files under `cwd`, relative paths.
   [] outside a repo."
  [cwd]
  (->> (str/split-lines (run-git "ls-files --cached --others --exclude-standard" cwd))
       (map str/trim)
       (remove str/blank?)
       vec))
