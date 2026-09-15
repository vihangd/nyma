(ns git-files.test
  "The synchronous git shell-outs behind `@path` completion and the resource
   loader: `list-files` honours .gitignore and includes untracked files;
   outside a repo everything degrades to empty rather than throwing."
  (:require ["bun:test" :refer [describe it expect afterAll]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [agent.utils.git-files :refer [list-files in-git-repo? run-git]]))

(def ^:private tmp (fs/mkdtempSync (path/join (os/tmpdir) "nyma-git-files-")))
(def ^:private repo (path/join tmp "repo"))
(def ^:private plain (path/join tmp "plain"))

(fs/mkdirSync repo #js {:recursive true})
(fs/mkdirSync plain #js {:recursive true})
(cp/execSync "git init -q" #js {:cwd repo :stdio "ignore"})
(fs/writeFileSync (path/join repo ".gitignore") "ignored.log\nbuild/\n")
(fs/writeFileSync (path/join repo "tracked.txt") "t")
(fs/writeFileSync (path/join repo "untracked.txt") "u")
(fs/writeFileSync (path/join repo "ignored.log") "i")
(fs/mkdirSync (path/join repo "build"))
(fs/writeFileSync (path/join repo "build" "out.js") "o")
(fs/writeFileSync (path/join plain "loose.txt") "l")
(cp/execSync "git add tracked.txt .gitignore" #js {:cwd repo :stdio "ignore"})

(afterAll (fn [] (fs/rmSync tmp #js {:recursive true :force true})))

(describe "utils/git-files"
          (fn []
            (it "list-files: tracked and untracked files, nothing .gitignore excludes"
                (fn []
                  (let [files (set (list-files repo))]
                    (-> (expect (contains? files "tracked.txt")) (.toBe true))
                    (-> (expect (contains? files "untracked.txt")) (.toBe true))
                    (-> (expect (contains? files ".gitignore")) (.toBe true))
                    (-> (expect (contains? files "ignored.log")) (.toBe false))
                    (-> (expect (contains? files "build/out.js")) (.toBe false)))))

            (it "outside a repo: in-git-repo? is false, list-files is [] and run-git is \"\""
                (fn []
                  ;; A tmp dir can itself sit inside a repo on a developer
                  ;; machine; the plain dir is only meaningful when it does not.
                  (when-not (in-git-repo? plain)
                    (-> (expect (list-files plain)) (.toEqual []))
                    (-> (expect (run-git "status --porcelain" plain)) (.toBe "")))
                  (-> (expect (in-git-repo? repo)) (.toBe true))))

            (it "a failing git command yields \"\" instead of throwing"
                (fn []
                  (-> (expect (run-git "no-such-subcommand" repo)) (.toBe ""))))))
