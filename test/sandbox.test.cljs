(ns sandbox-test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.tools :refer [bash-execute]]
            [agent.utils.sandbox :as sandbox]
            [agent.extensions.bash-suite.permissions :as permissions]))

(def mac? (and (= "darwin" js/process.platform) (some? (js/Bun.which "sandbox-exec"))))

(defn ^:async test-confines []
  (let [d    (fs/realpathSync (fs/mkdtempSync (path/join (os/homedir) ".nyma-sbx-")))
        root (path/join d "wt")]
    (fs/mkdirSync root)
    (js/Bun.spawnSync #js ["git" "init" "-q" root])
    (fs/mkdirSync (path/join root ".git" "modules" "sub" "hooks") #js {:recursive true})
    (reset! sandbox/root root)
    (try
      (js-await (bash-execute {:command (str "cd " root " && touch in; touch ../out; touch .git/hooks/x; touch .git/modules/sub/hooks/x; echo >> .git/modules/sub/config; touch .git/objects/ok")}))
      (-> (expect #js [(fs/existsSync (path/join root "in"))
                       (fs/existsSync (path/join d "out"))
                       (fs/existsSync (path/join root ".git" "hooks" "x"))
                       (fs/existsSync (path/join root ".git" "modules" "sub" "hooks" "x"))
                       (fs/existsSync (path/join root ".git" "modules" "sub" "config"))
                       (fs/existsSync (path/join root ".git" "objects" "ok"))])
          (.toEqual #js [true false false false false true]))
      (finally
        (reset! sandbox/root nil)
        (fs/rmSync d #js {:recursive true :force true})))))

(describe "sandbox" (fn []
  (if mac?
    (it "lets bash write only under its root, never into git hooks" test-confines)
    (it.skip "needs sandbox-exec" (fn [])))
  (it "is off with no root" (fn []
    (-> (expect (clj->js (sandbox/wrap ["sh" "-c" "x"]))) (.toEqual #js ["sh" "-c" "x"]))))
  (it "still asks for an unlisted command inside the sandbox"
      (fn []
        (let [cfg {:auto-approve-safe true}
              c   {:level "unknown"}]
          (reset! sandbox/root "/")
          (try
            (-> (expect (permissions/check-decision "gh repo delete x" c cfg (atom {})))
                (.toBe :needs-approval))
            (finally (reset! sandbox/root nil))))))))
