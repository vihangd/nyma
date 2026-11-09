(ns sandbox-test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.tools :refer [bash-execute]]
            [agent.utils.sandbox :as sandbox]
            [agent.extensions.bash-suite.permissions :as permissions]))

(def mac? (and (= "darwin" js/process.platform) (some? (js/Bun.which "sandbox-exec"))))

(defn ^:async in-child
  "Run `f` with a worktree child's sandbox on `root`, then turn it off."
  [root cfg f]
  (let [prev (.. js/process -env -NYMA_WRITE_ROOT)]
    (aset (.-env js/process) "NYMA_WRITE_ROOT" root)
    (try
      (js-await (sandbox/configure! cfg))
      (js-await (f))
      (finally
        (if prev (aset (.-env js/process) "NYMA_WRITE_ROOT" prev)
            (js-delete (.-env js/process) "NYMA_WRITE_ROOT"))
        (js-await (sandbox/configure! {:bash "off"}))))))

(defn ^:async test-confines []
  ;; under HOME, not tmp: tmp is writable from the sandbox
  (let [d      (fs/realpathSync (fs/mkdtempSync (path/join (os/homedir) ".nyma-sbx-")))
        root   (path/join d "wt")
        secret (path/join d "secret")
        e?     #(fs/existsSync (path/join root %))]
    (fs/mkdirSync root)
    (fs/writeFileSync secret "s3cret")
    (js/Bun.spawnSync #js ["git" "init" "-q" root])
    (fs/mkdirSync (path/join root ".git" "modules" "sub" "hooks") #js {:recursive true})
    (try
      (js-await
       (in-child root {:deny-read [secret]}
                 ^:async
                 (fn []
                   (let [r (js-await (bash-execute
                                      {:command (str "cd " root " && touch in; touch ../out; touch .git/hooks/x;"
                                                     " touch .git/modules/sub/hooks/x; echo >> .git/modules/sub/config;"
                                                     " touch .git/objects/ok; mkdir -p .nyma && touch .nyma/s;"
                                                     " cat " secret "; osascript -e 'tell application \"Finder\" to get name';"
                                                     " curl -sS -m 5 https://example.com/ -o /dev/null")}))]
                     (-> (expect #js [(e? "in") (fs/existsSync (path/join d "out")) (e? ".git/hooks/x")
                                      (e? ".git/modules/sub/hooks/x") (e? ".git/modules/sub/config")
                                      (e? ".git/objects/ok") (e? ".nyma/s")
                                      (.includes (str r) "s3cret")])
                         (.toEqual #js [true false false false false true false false]))
                     ;; no network for a child: the proxy refuses the host
                     (-> (expect (str r)) (.toMatch #"403|CONNECT|not permitted|resolve"))))))
      (finally
        (fs/rmSync d #js {:recursive true :force true})))))

(describe "sandbox" (fn []
  (if mac?
    (it "confines writes, secret reads, Apple Events and network" test-confines 30000)
    (it.skip "needs sandbox-exec" (fn [])))
  (it "is plain sh with no root"
      ^:async (fn []
                (js-await (sandbox/configure! {:bash "off"}))
                (-> (expect (clj->js (js-await (sandbox/wrap "x"))))
                    (.toEqual (clj->js {:argv ["sh" "-c" "x"] :env nil})))))
  (it "still asks for an unlisted command inside the sandbox"
      (fn []
        (let [cfg {:auto-approve-safe true}
              c   {:level "unknown"}]
          (reset! sandbox/root "/")
          (try
            (-> (expect (permissions/check-decision "gh repo delete x" c cfg (atom {})))
                (.toBe :needs-approval))
            (finally (reset! sandbox/root nil))))))))
