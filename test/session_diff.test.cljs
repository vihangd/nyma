(ns session-diff-test
  "`/diff` — everything this session changed, ready to review before a commit.

   Each edit showed its own diff as it happened, and checkpoints knew every
   touched file, but there was no single view of the session's change."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.builtin-extensions :refer [registry]]
            [agent.extensions.checkpoints.shared :as shared]))

(defn- git! [dir & args]
  (cp/execFileSync "git" (clj->js args) #js {:cwd dir :stdio "ignore"}))

(defn- repo []
  (let [d (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "nyma-diff-")))]
    (git! d "init" "-q")
    (git! d "config" "user.email" "t@t") (git! d "config" "user.name" "t")
    (fs/writeFileSync (path/join d "a.txt") "one\n")
    (fs/writeFileSync (path/join d "c.txt") "mine\n")
    (git! d "add" ".") (git! d "commit" "-qm" "init")
    d))

(describe "diff-report"
          (fn []
            (it "shows the session's files vs HEAD plus new ones — not the user's other edits"
                (fn []
                  (let [d (repo)]
                    (fs/writeFileSync (path/join d "a.txt") "one\ntwo\n")
                    (fs/writeFileSync (path/join d "b.txt") "new\nfile\n")
                    (fs/writeFileSync (path/join d "c.txt") "mine, edited by hand\n")
                    (let [r (shared/diff-report d [(path/join d "a.txt") (path/join d "b.txt")])]
                      (-> (expect r) (.toContain "+two"))
                      (-> (expect r) (.toContain "new file: b.txt (2 lines)"))
                      (-> (expect r) (.not.toContain "c.txt")))
                    (fs/rmSync d #js {:recursive true}))))

            (it "says so when nothing is left, when nothing was touched, and outside git"
                (fn []
                  (let [d (repo)]
                    (-> (expect (shared/diff-report d [(path/join d "a.txt")])) (.toContain "No changes left"))
                    (-> (expect (shared/diff-report d [])) (.toContain "No files changed"))
                    (fs/rmSync d #js {:recursive true}))
                  (let [plain (fs/mkdtempSync (path/join (os/tmpdir) "nyma-diff-plain-"))]
                    (-> (expect (shared/diff-report plain [(path/join plain "x")])) (.toContain "Not a git repository"))
                    (fs/rmSync plain #js {:recursive true}))))))

(defn ^:async test-bash-change-reaches-diff []
  ;; A file changed by a SHELL command is in /diff too: bash_suite's edit-diff
  ;; announces it, checkpoints records it. The event name crosses extension
  ;; namespaces, which is exactly what a unit test would not catch.
  (let [d      (repo)
        prev   (js/process.cwd)
        _      (js/process.chdir d)
        agent  (create-agent {:model "test" :system-prompt "s"})
        api    (create-extension-api agent)
        loaded (js-await (discover-and-load [] api (filter #(contains? #{"bash-suite" "checkpoints"} (:namespace %)) registry)))
        ;; A redirect is a write, so the gate asks: answer it.
        _      (set! (.-extension-api agent)
                     #js {:ui #js {:available true :select (fn [_ _] (js/Promise.resolve "Allow once"))}})]
    (try
      (let [bash (get ((:all (:tool-registry agent))) "bash")]
        (js-await ((:execute (:middleware agent)) "bash" bash {:command "printf 'shell edit\\n' >> a.txt"}))
        (let [out ((:handler (get @(:commands agent) "checkpoints__diff")) [] #js {})]
          (-> (expect (str out)) (.toContain "+shell edit"))))
      (finally
        (deactivate-all loaded)
        (js/process.chdir prev)
        (fs/rmSync d #js {:recursive true :force true})))))

(describe "/diff across extensions"
          (fn [] (it "includes a file a bash command changed" test-bash-change-reaches-diff 30000)))
