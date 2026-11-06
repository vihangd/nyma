(ns worktree-test
  "Subagents in their own git worktree.

   Subagents ran in-process, sharing the user's working tree — so an editing
   subagent edited the user's files, and two in parallel edited the same
   ones. `isolation: \"worktree\"` runs the child as a separate nyma whose cwd
   is its own checkout; its work comes back as a branch."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [clojure.string :as str]
            [agent.utils.worktree :as wt]
            [agent.extensions.subagent.index :as sub]
            [test-util.fake-model-server :as fms]))

(defn- git [dir & args]
  (str/trim (str (cp/execFileSync "git" (clj->js args) #js {:cwd dir :encoding "utf8"}))))

(defn- temp-repo []
  (let [d (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "nyma-wt-")))]
    (git d "init" "-q")
    (git d "config" "user.email" "t@t")
    (git d "config" "user.name" "t")
    (fs/writeFileSync (path/join d "a.txt") "base\n")
    (git d "add" ".")
    (git d "commit" "-qm" "init")
    d))

(describe "worktree utils"
          (fn []
            (it "changes come back as a branch; no changes leave nothing"
                (fn []
                  (let [d  (temp-repo)
                        t1 (wt/create! d "Job One!")]
                    (-> (expect (:branch t1)) (.toBe "nyma/job-one"))
                    (fs/writeFileSync (path/join (:path t1) "b.txt") "new\n")
                    (fs/mkdirSync (path/join (:path t1) ".nyma") #js {:recursive true})
                    (fs/writeFileSync (path/join (:path t1) ".nyma" "state.json") "{}")
                    (-> (expect (count (wt/list-all d))) (.toBe 1))
                    (let [r (wt/finish! t1 "work")]
                      (-> (expect (:commits r)) (.toBe 1))
                      (-> (expect (:stat r)) (.toContain "b.txt"))
                      ;; the child's own state is not its work
                      (-> (expect (:stat r)) (.not.toContain "state.json")))
                    (-> (expect (fs/existsSync (:path t1))) (.toBe false))
                    (-> (expect (fs/existsSync (path/join d "b.txt"))) (.toBe false))
                    (-> (expect (git d "status" "--porcelain")) (.toBe ""))
                    (let [t2 (wt/create! d "idle")]
                      (-> (expect (wt/finish! t2 "nothing")) (.toBeFalsy))
                      (-> (expect (git d "branch" "--list" "nyma/idle")) (.toBe "")))
                    (fs/rmSync d #js {:recursive true :force true}))))

            (it "a child that changed only .nyma/ leaves nothing, and does not throw"
                (fn []
                  (let [d (temp-repo)
                        t (wt/create! d "state-only")]
                    (fs/mkdirSync (path/join (:path t) ".nyma") #js {:recursive true})
                    (fs/writeFileSync (path/join (:path t) ".nyma" "s.json") "{}")
                    (-> (expect (wt/finish! t "x")) (.toBeFalsy))
                    (-> (expect (git d "branch" "--list" "nyma/state-only")) (.toBe ""))
                    (fs/rmSync d #js {:recursive true :force true}))))

            (it "a pre-commit hook that rejects does not lose the work"
                (fn []
                  (let [d (temp-repo)
                        _ (fs/writeFileSync (path/join d ".git" "hooks" "pre-commit") "#!/bin/sh\nexit 1\n")
                        _ (fs/chmodSync (path/join d ".git" "hooks" "pre-commit") 493)
                        t (wt/create! d "hooked")]
                    (fs/writeFileSync (path/join (:path t) "w.txt") "w")
                    (-> (expect (:commits (wt/finish! t "x"))) (.toBe 1))
                    (fs/rmSync d #js {:recursive true :force true}))))

            (it "--worktree reuses a checkout; rm keeps an unmerged branch"
                (fn []
                  (let [d  (temp-repo)
                        t  (wt/open! d "feat")]
                    (-> (expect (:path (wt/open! d "feat"))) (.toBe (:path t)))
                    (fs/writeFileSync (path/join (:path t) "f.txt") "x")
                    (git (:path t) "add" ".")
                    (git (:path t) "commit" "-qm" "f")
                    (-> (expect (wt/remove! d "feat")) (.toBe "kept"))
                    (-> (expect (git d "branch" "--list" "nyma/feat")) (.toContain "nyma/feat"))
                    (-> (expect (wt/remove! d "feat")) (.toBeFalsy))
                    (fs/rmSync d #js {:recursive true :force true}))))))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn ^:async test-subagent-in-worktree []
  (let [srv   (fms/start!)
        home  (fms/temp-home! (:base-url srv))
        d     (temp-repo)
        prev  (js/process.cwd)
        env   (.-env js/process)
        saved {"HOME" (aget env "HOME") "NYMA_NO_MODEL_DISCOVERY" (aget env "NYMA_NO_MODEL_DISCOVERY")}]
    ((:script! srv) {:tool-call {:name "write" :args {:path "made.txt" :content "by the child\n"}}}
                    {:text "wrote made.txt"})
    (aset env "HOME" home)
    (aset env "NYMA_NO_MODEL_DISCOVERY" "1")
    (js/process.chdir d)
    (try
      (let [r (js-await (sub/run-in-worktree {:agent-name "worker" :task "- create made.txt"}
                                             {:provider "faketest" :model "m1"}))]
        (-> (expect (:ok r)) (.toBe true))
        (-> (expect (:text r)) (.toContain "wrote made.txt"))
        (-> (expect (:cache-read (:usage r))) (.toBe 0))
        (-> (expect (:text r)) (.toContain "git merge nyma/worker-"))
        ;; the user's tree is untouched; the branch has the file
        (-> (expect (fs/existsSync (path/join d "made.txt"))) (.toBe false))
        (let [br (first (filter #(str/starts-with? % "nyma/worker-")
                                (map str/trim (.split (git d "branch" "--format=%(refname:short)") "\n"))))]
          (-> (expect (git d "show" (str br ":made.txt"))) (.toBe "by the child"))))
      (finally
        (js/process.chdir prev)
        (doseq [[k v] saved] (if v (aset env k v) (js-delete env k)))
        ((:stop srv))
        (fs/rmSync d #js {:recursive true :force true})
        (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _ nil))))))

(defn ^:async test-noisy-child []
  ;; 200 KB on stderr before any stdout: reading stdout to EOF first left the
  ;; child blocked on a full stderr pipe and the parent waiting forever.
  (let [d (temp-repo) prev (js/process.cwd)]
    (js/process.chdir d)
    (try
      (let [r (js-await (sub/run-in-worktree
                         {:agent-name "noisy" :task "t"
                          :command ["sh" "-c" "head -c 200000 /dev/zero | tr '\\0' x >&2; echo '{\"result\":\"fine\"}'"]}
                         {:provider "p" :model "m"}))]
        (-> (expect (:text r)) (.toContain "fine")))
      (finally
        (js/process.chdir prev)
        (fs/rmSync d #js {:recursive true :force true})))))

(describe "worktree child process"
          (fn [] (it "drains stderr and stdout together" test-noisy-child 20000)))

((.skipIf describe (not (fs/existsSync cli))) "subagent isolation: worktree"
                                              (fn [] (it "edits land on a branch, not in the user's tree" test-subagent-in-worktree 60000)))
