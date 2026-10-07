(ns agent.utils.worktree
  "A separate git checkout for work that must not touch the user's tree.

   A worktree lives at `<repo>/.nyma/worktrees/<name>` on branch `nyma/<name>`,
   started from the current HEAD. When the work is done its changes are
   committed on that branch and the checkout is removed; the branch stays for
   the user to merge. Work that changed nothing leaves nothing behind."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn- git [cwd & args]
  (str/trim (str (cp/execFileSync "git" (clj->js args)
                                  #js {:cwd cwd :encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]}))))

(defn repo-root
  "Top of the git repo containing `cwd`, or nil outside one."
  [cwd]
  (try (git cwd "rev-parse" "--show-toplevel") (catch :default _ nil)))

(defn worktrees-dir [root] (path/join root ".nyma" "worktrees"))

(defn safe-name
  "A branch- and path-safe name: `[a-z0-9._-]`, at most 40 chars."
  [s]
  (let [n (-> (str/lower-case (str s))
              (.replace (js/RegExp. "[^a-z0-9._-]+" "g") "-")
              (.replace (js/RegExp. "^[-.]+|[-.]+$" "g") ""))]
    (if (seq n) (subs n 0 (min 40 (count n))) "wt")))

(defn create!
  "New worktree `name` off HEAD. Returns {:path :branch :base :root}.
   The worktrees dir ignores itself, so the user's .gitignore is untouched."
  [cwd name]
  (let [root (or (repo-root cwd) (throw (js/Error. "not inside a git repository")))
        dir  (worktrees-dir root)
        nm   (safe-name name)
        p    (path/join dir nm)
        br   (str "nyma/" nm)
        base (git root "rev-parse" "HEAD")]
    (fs/mkdirSync dir #js {:recursive true})
    (let [ign (path/join dir ".gitignore")]
      (when-not (fs/existsSync ign) (fs/writeFileSync ign "*\n")))
    (git root "worktree" "add" "-q" "-b" br p base)
    {:path p :branch br :base base :root root}))

(defn open!
  "Worktree `name`, created off HEAD if it does not exist yet — so
   `nyma --worktree feat` twice returns to the same checkout."
  [cwd name]
  (let [root (or (repo-root cwd) (throw (js/Error. "not inside a git repository")))
        nm   (safe-name name)
        p    (path/join (worktrees-dir root) nm)]
    (if (fs/existsSync p)
      {:path p :branch (str "nyma/" nm) :root root}
      (create! cwd name))))

(defn remove!
  "Delete worktree `name`'s checkout; its branch too when already merged.
   Returns :merged, :kept (unmerged branch left for the user) or nil."
  [cwd name]
  (when-let [root (repo-root cwd)]
    (let [nm (safe-name name)
          p  (path/join (worktrees-dir root) nm)
          br (str "nyma/" nm)]
      (when (fs/existsSync p)
        (git root "worktree" "remove" "--force" p)
        (try (git root "branch" "-d" br) :merged
             (catch :default _ :kept))))))

(defn- commit-all! [p message]
  ;; .nyma/ is the child's own state (ext-state, plans), not its work.
  (git p "add" "-A" "--" "." ":!.nyma")
  (try (git p "commit" "-q" "-m" message)
       ;; No identity configured (a CI box, a fresh container): commit as nyma
       ;; rather than lose the work.
       (catch :default _
         (git p "-c" "user.name=nyma" "-c" "user.email=nyma@localhost" "commit" "-q" "-m" message))))

(defn finish!
  "Close worktree `wt`. Uncommitted changes are committed on its branch.
   Returns {:branch :stat :commits} when the branch moved past its base —
   the checkout is removed, the branch kept — or nil when nothing changed,
   in which case branch and checkout are both gone."
  [{:keys [path branch base root]} message]
  (when (seq (git path "status" "--porcelain"))
    (commit-all! path message))
  (let [head    (git path "rev-parse" "HEAD")
        changed (not= head base)
        result  (when changed
                  {:branch  branch
                   :commits (js/Number (git root "rev-list" "--count" (str base ".." branch)))
                   :stat    (git root "diff" "--stat" (str base ".." branch))})]
    (git root "worktree" "remove" "--force" path)
    (when-not changed (git root "branch" "-D" branch))
    result))

(defn list-all
  "nyma's worktrees in the repo at `cwd`: [{:path :branch}]."
  [cwd]
  (if-let [root (repo-root cwd)]
    (let [dir (worktrees-dir root)
          dir (if (fs/existsSync dir) (fs/realpathSync.native dir) (str dir "/"))]
      (->> (.split (git root "worktree" "list" "--porcelain") "\n\n")
           (keep (fn [block]
                   (let [lines (.split block "\n")
                         p     (some #(when (.startsWith % "worktree ") (subs % 9)) lines)
                         b     (some #(when (.startsWith % "branch refs/heads/") (subs % 18)) lines)]
                     (when (and p (fs/existsSync p) (.startsWith (fs/realpathSync.native p) dir))
                       {:path p :branch b}))))
           vec))
    []))
