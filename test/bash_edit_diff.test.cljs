(ns bash-edit-diff.test
  "A bash command that rewrote files used to leave no trace in its result.
   The edit-diff feature snapshots the git working tree around the command
   and appends one line per changed file."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [agent.extensions.bash-suite.edit-diff :as ed]))

(def ^:private dirs (atom []))

(defn- git-repo! []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-editdiff-"))]
    (swap! dirs conj dir)
    (cp/execSync "git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init" #js {:cwd dir :stdio "ignore"})
    (fs/writeFileSync (path/join dir "a.txt") "one\ntwo\n")
    (cp/execSync "git add . && git -c user.email=t@t -c user.name=t commit -q -m a" #js {:cwd dir :stdio "ignore"})
    dir))

(defn- commit-all! [dir]
  (cp/execSync "git add . && git -c user.email=t@t -c user.name=t commit -q -m more" #js {:cwd dir :stdio "ignore"}))

(defn- pre-of [dir paths]
  (let [snap {:root dir :status {}}]
    (into {} (map (fn [e] [(path/basename (:path e)) (:state e)])
                  (ed/pre-states snap snap paths)))))

(describe "bash-suite:edit-diff pre-states" (fn []
  (afterEach (fn [] (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true})) (reset! dirs []) nil))

  (it "gives HEAD's content, and nil only for a file HEAD does not have"
      (fn []
        (let [dir (git-repo!)
              pre (pre-of dir ["a.txt" "new.txt"])]
          (-> (expect (get pre "a.txt")) (.toBe "one\ntwo\n"))
          (-> (expect (contains? pre "new.txt")) (.toBe true))
          (-> (expect (get pre "new.txt")) (.toBeFalsy)))))

  (it "leaves out what it cannot state, because nil would make /undo delete it"
      (fn []
        (let [dir (git-repo!)]
          (fs/writeFileSync (path/join dir "bin.dat") (js/Buffer.from #js [255 254 0 1]))
          (fs/writeFileSync (path/join dir "big.txt") (.repeat "x" (+ 1 (* 2 1024 1024))))
          (commit-all! dir)
          (let [pre (pre-of dir ["bin.dat" "big.txt"])]
            (-> (expect (contains? pre "bin.dat")) (.toBe false))
            (-> (expect (contains? pre "big.txt")) (.toBe false))))))

  (it "says nothing when git cannot answer"
      (fn []
        (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-editdiff-"))]
          (swap! dirs conj dir)
          (-> (expect (contains? (pre-of dir ["a.txt"]) "a.txt")) (.toBe false)))))

  (it "stops at a bound however many files a command touched"
      (fn []
        (let [dir (git-repo!)]
          (-> (expect (count (pre-of dir (mapv #(str "f" %) (range 500))))) (.toBe 200)))))))

(describe "bash-suite:edit-diff" (fn []
                                   (afterEach (fn [] (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true})) (reset! dirs []) nil))

                                   (it "changed-paths sees a status change, an mtime change, and a new file"
                                       (fn []
                                         (let [before {:status {"a" " M"} :mtimes {"a" 1}}
                                               after  {:status {"a" " M" "b" "??"} :mtimes {"a" 2 "b" 5}}]
                                           (-> (expect (ed/changed-paths before after)) (.toEqual (clj->js ["a" "b"])))
                                           (-> (expect (ed/changed-paths before before)) (.toEqual (clj->js []))))))

                                   (it "summary names modified, new and deleted files with counts"
                                       (fn []
                                         (let [dir    (git-repo!)
                                               before (ed/snapshot dir)]
                                           (fs/writeFileSync (path/join dir "a.txt") "one\nthree\nfour\n")
                                           (fs/writeFileSync (path/join dir "new.txt") "x\ny\n")
                                           (let [s (ed/summary dir before (ed/snapshot dir))]
                                             (-> (expect s) (.toContain "Files changed by this command:"))
                                             (-> (expect s) (.toContain "a.txt (+2/-1)"))
                                             (-> (expect s) (.toContain "new.txt (new, 2 lines)"))))))

                                   (it "a file that merely became clean is not listed; a removed one is (deleted)"
      (fn []
        (let [dir (git-repo!)]
          (fs/writeFileSync (path/join dir "a.txt") "changed\n")
          (let [before (ed/snapshot dir)]
            (cp/execSync "git -c user.email=t@t -c user.name=t commit -qam c" #js {:cwd dir :stdio "ignore"})
            (-> (expect (ed/summary dir before (ed/snapshot dir))) (.toBeNil)))
          (let [before (ed/snapshot dir)]
            (fs/rmSync (path/join dir "a.txt"))
            (-> (expect (ed/summary dir before (ed/snapshot dir))) (.toContain "a.txt (deleted)"))))))

  (it "with-summary adds a filesChanged field to the bash envelope, keeps it JSON"
      (fn []
        (let [out (ed/with-summary (js/JSON.stringify #js {:stdout "hi" :stderr "" :exitCode 0}) "Files changed by this command:\n  a (+1/-0)")
              p   (js/JSON.parse out)]
          (-> (expect (.-stdout p)) (.toBe "hi"))
          (-> (expect (.-filesChanged p)) (.toContain "a (+1/-0)")))
        (-> (expect (ed/with-summary "plain text" "S")) (.toBe "plain text\n\nS"))))

  (it "paths resolve against the repo root, not the process cwd"
      (fn []
        (let [dir (git-repo!)
              sub (path/join dir "pkg")]
          (fs/mkdirSync sub)
          (let [before (ed/snapshot sub)]
            (fs/writeFileSync (path/join dir "a.txt") "one\ntwo\nthree\n")
            (-> (expect (ed/summary sub before (ed/snapshot sub))) (.toContain "a.txt (+1/-0)"))))))

  (it "nothing changed → nil; outside a repo → nil snapshot"
                                       (fn []
                                         (let [dir    (git-repo!)
                                               before (ed/snapshot dir)]
                                           (-> (expect (ed/summary dir before (ed/snapshot dir))) (.toBeNil)))
                                         (let [plain (fs/mkdtempSync (path/join (os/tmpdir) "nyma-norepo-"))]
                                           (swap! dirs conj plain)
                                           (-> (expect (ed/snapshot plain)) (.toBeNil)))))))
