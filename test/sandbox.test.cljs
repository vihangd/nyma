(ns sandbox-test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.tools :refer [bash-execute]]
            [agent.utils.sandbox :as sandbox]
            [agent.extensions.bash-suite.permissions :as permissions]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [permission-check-enter]]))

(def mac? (and (= "darwin" js/process.platform) (some? (js/Bun.which "sandbox-exec"))))

(defn ^:async in-child
  "Run `f` with a worktree child's sandbox on `root`, then turn it off."
  [root cfg f]
  (let [prev (.. js/process -env -NYMA_WRITE_ROOT)
        key  (.. js/process -env -ANTHROPIC_API_KEY)]
    (aset (.-env js/process) "NYMA_WRITE_ROOT" root)
    (aset (.-env js/process) "ANTHROPIC_API_KEY" "sk-test")
    (try
      (js-await (sandbox/configure! cfg))
      (js-await (f))
      (finally
        (if prev (aset (.-env js/process) "NYMA_WRITE_ROOT" prev)
            (js-delete (.-env js/process) "NYMA_WRITE_ROOT"))
        (if key (aset (.-env js/process) "ANTHROPIC_API_KEY" key)
            (js-delete (.-env js/process) "ANTHROPIC_API_KEY"))
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
                                                     " echo key=$ANTHROPIC_API_KEY;"
                                                     " curl -sS -m 5 https://example.com/ -o /dev/null")}))]
                     (-> (expect #js [(e? "in") (fs/existsSync (path/join d "out")) (e? ".git/hooks/x")
                                      (e? ".git/modules/sub/hooks/x") (e? ".git/modules/sub/config")
                                      (e? ".git/objects/ok") (e? ".nyma/s")
                                      (.includes (str r) "s3cret")
                                      (.includes (str r) "key=sk-test")])
                         (.toEqual #js [true false false false false true false false false]))
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

(describe "sandbox env" (fn []
  (it "drops keys and agent sockets, keeps the rest"
      (fn []
        (-> (expect (js/Object.keys (sandbox/scrub-env #js {:ANTHROPIC_API_KEY "a" :FOO_TOKEN "b" :SSH_AUTH_SOCK "s"
                                                            :GH_TOKEN "g" :PATH "/bin" :HTTPS_PROXY "p" :HOME "/h"})))
            (.toEqual #js ["PATH" "HTTPS_PROXY" "HOME"]))))))

(defn ^:async test-host-asker []
  (let [asked (atom 0) saved (atom nil)
        ask   (fn [c] (fn [_] (swap! asked inc) (js/Promise.resolve c)))
        q     #js {:host "example.org" :port 443}]
    ;; a child is never asked
    (-> (expect (js-await ((sandbox/host-asker true (ask "Allow once") nil) q))) (.not.toBe true))
    (-> (expect @asked) (.toBe 0))
    ;; no one to ask: refused
    (-> (expect (js-await ((sandbox/host-asker false nil nil) q))) (.not.toBe true))
    (-> (expect (js-await ((sandbox/host-asker false (ask nil) nil) q))) (.not.toBe true))
    ;; session: asked once
    (let [f (sandbox/host-asker false (ask "Allow for this session") nil)]
      (reset! asked 0)
      (js-await (f q))
      (-> (expect (js-await (f q))) (.toBe true))
      (-> (expect @asked) (.toBe 1)))
    ;; always: saved
    (js-await ((sandbox/host-asker false (ask "Allow always (every project)") #(reset! saved %)) q))
    (-> (expect @saved) (.toBe "example.org"))))

(describe "sandbox host prompt" (fn []
  (it "asks the user once per session, never for a child, and saves an always" test-host-asker)))

(defn ^:async test-escalation []
  (let [events (create-event-bus)
        _      ((:on events) "permission_request" (fn [_] #js {:decision "allow"}))
        gate   #(permission-check-enter events nil {:tool-name "bash" :args {:command "make" :unsandboxed %}})]
    (reset! sandbox/root "/")
    (try
      ;; full-auto's allow does not cover leaving the sandbox; no UI → denied
      (-> (expect (:cancelled (js-await (gate true)))) (.toBe true))
      (-> (expect (boolean (:cancelled (js-await (gate false))))) (.toBe false))
      (aset (.-env js/process) "NYMA_WRITE_ROOT" "/x")
      (-> (expect (:cancel-reason (js-await (gate true)))) (.toMatch #"worktree subagent"))
      (finally
        (js-delete (.-env js/process) "NYMA_WRITE_ROOT")
        (reset! sandbox/root nil)))
    ;; no sandbox: nothing to leave
    (-> (expect (boolean (:cancelled (js-await (gate true))))) (.toBe false))))

(describe "sandbox escalation" (fn []
  (it "asks to leave the sandbox even when allowed, never for a child" test-escalation)))
