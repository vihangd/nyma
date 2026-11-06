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
                                 ;; stderr IGNORED, not inherited. execSync sends
                                 ;; the child's stderr to the parent's unless
                                 ;; told otherwise, and a failing git prints
                                 ;; there — `fatal: no upstream configured for
                                 ;; branch 'main'` is the common case. The status
                                 ;; line polls every 5s, so that is twelve writes
                                 ;; a minute landing mid-render and corrupting
                                 ;; the TUI, which is exactly what debug.cljs
                                 ;; routes its own output to a file to avoid.
                                 :stdio #js ["ignore" "pipe" "ignore"]
                                 :maxBuffer (* 64 1024 1024)}))
     (catch :default _ ""))))

(defn run-git-args
  "`run-git` with no shell between: for arguments nyma did not write, such as
   paths a model named. \"\" on any failure."
  [args cwd]
  (try
    (str/trim (cp/execFileSync "git" (clj->js args)
                               #js {:encoding "utf-8"
                                    :cwd (or cwd js/undefined)
                                    ;; See run-git: stderr must not reach the TUI.
                                    :stdio #js ["ignore" "pipe" "ignore"]
                                    :maxBuffer (* 64 1024 1024)}))
    (catch :default _ "")))

(defn run-git-raw
  "Like `run-git` but trims only the TRAILING newline, never leading whitespace.

   `run-git` trims both ends, which destroys the first line of
   `status --porcelain`: a worktree-only change is ` M path`, and trimming turns
   it into `M path` — a staged change. `edit_diff/status-lines` sidesteps that by
   parsing with a regex instead of by column; anything that needs the two status
   COLUMNS has to read the output untrimmed."
  ([cmd] (run-git-raw cmd nil))
  ([cmd cwd]
   (try
     (str/replace (cp/execSync (str "git " cmd)
                               #js {:encoding "utf-8"
                                    :cwd (or cwd js/undefined)
                                    ;; See run-git: stderr must not reach the TUI.
                                    :stdio #js ["ignore" "pipe" "ignore"]
                                    :maxBuffer (* 64 1024 1024)})
                  #"\n+$" "")
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

;; ── Repo state, for the project card and the status line ─────────
;;
;; The parsers are separate from the shell-outs on purpose. `git` is an
;; execSync away and the status line re-renders at 10 Hz while streaming, so
;; nothing here may be called from a render — a poller writes an atom and the
;; segment reads it. Keeping the parsing pure also means it is testable without
;; a repo, which is how the porcelain column semantics below got pinned down.

(defn parse-porcelain
  "Tally `git status --porcelain=v1` lines into
   `{:staged n :unstaged n :untracked n :conflicted n}`.

   The two columns are independent: X is the index, Y the worktree, so one file
   can be both staged and unstaged and is counted in both. `??` is untracked and
   `UU`/`AA`/`DD` (and any line with U in either column) is a conflict, which
   must not be miscounted as an ordinary staged change."
  [out]
  (reduce
   (fn [acc line]
     (if (< (count line) 3)
       acc
       (let [x (nth line 0) y (nth line 1)]
         (cond
           (and (= x "?") (= y "?")) (update acc :untracked inc)
           (or (= x "U") (= y "U")
               (and (= x "A") (= y "A"))
               (and (= x "D") (= y "D")))
           (update acc :conflicted inc)
           :else (cond-> acc
                   (not (contains? #{" " "?"} x)) (update :staged inc)
                   (not (contains? #{" " "?"} y)) (update :unstaged inc))))))
   {:staged 0 :unstaged 0 :untracked 0 :conflicted 0}
   (remove str/blank? (str/split-lines (str out)))))

(defn parse-ahead-behind
  "`git rev-list --left-right --count @{upstream}...HEAD` gives \"behind\\tahead\".
   nil when there is no upstream, which is what the empty string from `run-git`
   means — distinct from 0/0, which is a branch that is in sync."
  [out]
  (let [parts (->> (str/split (str/trim (str out)) #"\s+")
                   (remove str/blank?))]
    (when (= 2 (count parts))
      (let [b (js/parseInt (first parts) 10)
            a (js/parseInt (second parts) 10)]
        (when-not (or (js/isNaN a) (js/isNaN b))
          {:ahead a :behind b})))))

(defn repo-state
  "A snapshot of the repo at `cwd`, or nil when this is not a repo.

   Three shell-outs. Call it from a poller or once at startup, never from a
   render. `:branch` is nil on a detached HEAD, where `--abbrev-ref` yields
   \"HEAD\" and naming that as a branch would be a lie."
  [cwd]
  (when (in-git-repo? cwd)
    (let [branch (run-git "rev-parse --abbrev-ref HEAD" cwd)
          ;; run-git-raw, NOT run-git: the leading space of a ` M` first line is
          ;; the worktree column and trimming it reports the change as staged.
          status (parse-porcelain (run-git-raw "status --porcelain=v1 --untracked-files=all" cwd))
          ab     (parse-ahead-behind (run-git "rev-list --left-right --count @{upstream}...HEAD" cwd))]
      (merge {:branch (when-not (or (str/blank? branch) (= "HEAD" branch)) branch)
              :detached? (= "HEAD" branch)}
             status
             (or ab {})))))
