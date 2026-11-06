(ns checkpoints-persist-test
  "Rewind points survive a restart, /undo picks the point, and files a bash
   command changed can be restored too.

   They were in memory only (gone on exit or /resume), covered only the edit
   tools — a `sed -i` or a formatter was invisible — and /undo could only step
   back one turn at a time."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.builtin-extensions :refer [registry]]
            [agent.extensions.checkpoints.index :as cpx]
            [agent.extensions.checkpoints.shared :as shared]))

(defn- mock-api [session-file]
  (let [handlers (atom {}) commands (atom {})]
    {:handlers handlers :commands commands
     :api #js {:on  (fn [evt h] (swap! handlers assoc evt h))
               :off (fn [evt _] (swap! handlers dissoc evt))
               :getSessionFile (fn [] session-file)
               :registerCommand   (fn [n spec] (swap! commands assoc n spec))
               :unregisterCommand (fn [n] (swap! commands dissoc n))}}))

(defn- edit-turn! [{:keys [handlers]} f content]
  ((get @handlers "before_tool_call") #js {:toolName "edit" :args #js {:path f}} nil)
  (fs/writeFileSync f content)
  ((get @handlers "tool_complete") #js {:toolName "edit" :args #js {:path f} :cancelled false :isError false} nil)
  ((get @handlers "turn_finalize") #js {} nil))

(defn- notes-ctx [notes & [select-answer]]
  #js {:ui #js {:notify (fn [m _] (swap! notes conj m))
                :select (fn [_ opts] (js/Promise.resolve (select-answer opts)))}})

(defn- diff-text [{:keys [commands]}]
  ((.-handler (get @commands "diff")) #js [] #js {:ui #js {:notify (fn [_ _] nil)}}))

(defn ^:async test-diff-follows-the-session []
  (let [d   (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cpp-diff-"))
        f   (path/join d "f.txt")
        sf  (atom (path/join d "session-one.jsonl"))
        m   (mock-api nil)]
    (aset (:api m) "getSessionFile" (fn [] @sf))
    (fs/writeFileSync f "v0")
    (cpx/activate (:api m))
    (edit-turn! m f "v1")
    (-> (expect (diff-text m)) (.toContain "f.txt"))
    ;; /new in the same process: not this session's file
    (reset! sf (path/join d "session-two.jsonl"))
    (-> (expect (diff-text m)) .-not (.toContain "f.txt"))
    ;; a restart and /resume bring it back
    (reset! sf (path/join d "session-one.jsonl"))
    (let [m2 (mock-api (path/join d "session-one.jsonl"))]
      (cpx/activate (:api m2))
      (-> (expect (diff-text m2)) (.toContain "f.txt")))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-survives-restart []
  (let [d  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cpp-"))
        sf (path/join d "session-abc.jsonl")
        f  (path/join d "f.txt")]
    (fs/writeFileSync f "v0")
    (let [m1 (mock-api sf)]
      (cpx/activate (:api m1))
      (edit-turn! m1 f "v1"))
    ;; a new process: a fresh activation over the same session
    (let [m2    (mock-api sf)
          _     (cpx/activate (:api m2))
          notes (atom [])]
      (js-await ((.-handler (get @(:commands m2) "rewind")) ["last"] (notes-ctx notes)))
      (-> (expect (fs/readFileSync f "utf8")) (.toBe "v0")))
    (fs/rmSync d #js {:recursive true})))

(defn ^:async test-picker []
  (let [d  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cpp-"))
        sf (path/join d "session-pick.jsonl")
        f  (path/join d "f.txt")
        m  (mock-api sf)]
    (fs/writeFileSync f "v0")
    (cpx/activate (:api m))
    (edit-turn! m f "v1")
    (edit-turn! m f "v2")
    (edit-turn! m f "v3")
    ;; options are newest first; pick the OLDEST → back to before turn 0
    (let [notes (atom [])]
      (js-await ((.-handler (get @(:commands m) "rewind")) [] (notes-ctx notes (fn [opts] (aget opts 2)))))
      (-> (expect (fs/readFileSync f "utf8")) (.toBe "v0"))
      (-> (expect (first @notes)) (.toContain "Restored to before turn 0")))
    (fs/rmSync d #js {:recursive true})))

(defn- git! [dir & args] (cp/execFileSync "git" (clj->js args) #js {:cwd dir :stdio "ignore"}))

(defn ^:async test-bash-change-restorable []
  (let [d      (fs/realpathSync (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cpp-git-")))
        _      (do (git! d "init" "-q") (git! d "config" "user.email" "t@t") (git! d "config" "user.name" "t")
                   (fs/writeFileSync (path/join d "a.txt") "committed\n")
                   (git! d "add" ".") (git! d "commit" "-qm" "init"))
        prev   (js/process.cwd)
        _      (js/process.chdir d)
        agent  (create-agent {:model "test" :system-prompt "s"})
        api    (create-extension-api agent)
        loaded (js-await (discover-and-load [] api (filter #(contains? #{"bash-suite" "checkpoints"} (:namespace %)) registry)))
        _      (set! (.-extension-api agent)
                     #js {:ui #js {:available true :select (fn [_ _] (js/Promise.resolve "Allow once"))}})]
    (try
      (js-await ((:execute (:middleware agent)) "bash" (get ((:all (:tool-registry agent))) "bash")
                                                {:command "printf 'shell edit\\n' >> a.txt"}))
      (-> (expect (fs/readFileSync (path/join d "a.txt") "utf8")) (.toBe "committed\nshell edit\n"))
      (let [notes (atom [])]
        (js-await ((:handler (get @(:commands agent) "checkpoints__rewind")) ["last"] (notes-ctx notes)))
        (-> (expect (fs/readFileSync (path/join d "a.txt") "utf8")) (.toBe "committed\n")))
      (finally
        (deactivate-all loaded)
        (js/process.chdir prev)
        (fs/rmSync d #js {:recursive true :force true})))))

(describe "persistent checkpoints"
          (fn []
            (it "survive a restart" test-survives-restart)
            (it "/undo picks the point and undoes everything after it" test-picker)
            (it "a file a bash command changed is restorable" test-bash-change-restorable 30000)))

(describe "turns-after-fork"
          (fn []
            (it "names the turns whose entry the new branch does not keep"
                (fn []
                  ;; u0 → a0 → u1 → a1 ; fork back to a0
                  (let [entries #js [#js {:id "u0" :parentId nil} #js {:id "a0" :parentId "u0"}
                                     #js {:id "u1" :parentId "a0"} #js {:id "a1" :parentId "u1"}]]
                    (-> (expect (clj->js (shared/turns-after-fork ["0" "1"] {"0" "a0" "1" "a1"} entries "a0")))
                        (.toEqual #js [1]))
                    ;; forking to the current leaf undoes nothing
                    (-> (expect (clj->js (shared/turns-after-fork ["0" "1"] {"0" "a0" "1" "a1"} entries "a1")))
                        (.toEqual #js [])))))))

(defn ^:async test-fork-offers-restore []
  (let [d      (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cpp-fork-"))
        f      (path/join d "f.txt")
        leaf   (atom nil)
        asked  (atom nil)
        {:keys [handlers api]} (mock-api (path/join d "session-fork.jsonl"))
        _      (do (aset api "getLeafId" (fn [] @leaf))
                   (aset api "getSessionEntries"
                         (fn [] #js [#js {:id "u0" :parentId nil} #js {:id "a0" :parentId "u0"}
                                     #js {:id "u1" :parentId "a0"} #js {:id "a1" :parentId "u1"}]))
                   (aset api "ui" #js {:confirm (fn [q] (reset! asked q) (js/Promise.resolve true))
                                       :notify (fn [& _] nil)}))
        m      {:handlers handlers}]
    (fs/writeFileSync f "v0")
    (cpx/activate api)
    (reset! leaf "a0") (edit-turn! m f "v1")   ;; turn 0 ends on a0
    (reset! leaf "a1") (edit-turn! m f "v2")   ;; turn 1 ends on a1
    ;; /fork back to a0: turn 1's change belongs to the abandoned branch
    (js-await ((get @handlers "session_before_fork") {:leaf-id "a1" :new-leaf "a0"} nil))
    (-> (expect (str @asked)) (.toContain "Restore 1 file(s) changed after this point?"))
    (-> (expect (fs/readFileSync f "utf8")) (.toBe "v1"))
    (fs/rmSync d #js {:recursive true})))

(describe "/diff"
  (fn [] (it "follows the session, across /new and a restart" test-diff-follows-the-session)))

(describe "/fork and files"
          (fn [] (it "offers to restore files changed after the fork point" test-fork-offers-restore)))

(defn ^:async test-content-absent []
  ;; `:absent` is the string "absent" in squint, so a file whose content was
  ;; exactly that was recorded as "did not exist" — and rewind deleted it.
  (let [d  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cpp-abs-"))
        f  (path/join d "f.txt")
        m  (mock-api (path/join d "session-abs.jsonl"))]
    (fs/writeFileSync f "absent")
    (cpx/activate (:api m))
    (edit-turn! m f "changed")
    (js-await ((.-handler (get @(:commands m) "rewind")) ["last"] (notes-ctx (atom []))))
    (-> (expect (fs/existsSync f)) (.toBe true))
    (-> (expect (fs/readFileSync f "utf8")) (.toBe "absent"))
    (fs/rmSync d #js {:recursive true})))

(describe "the absent sentinel"
          (fn [] (it "a file whose content is 'absent' is restored, not deleted" test-content-absent)))
