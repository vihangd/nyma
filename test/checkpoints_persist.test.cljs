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
            [agent.extensions.checkpoints.index :as cpx]))

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
