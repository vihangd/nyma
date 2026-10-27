(ns bash-allow-patterns-test
  "\"Allow always\" for bash remembers a command PATTERN, and no grant of any
   kind skips the denylist.

   Before: one click on `ls` stored the whole `bash` tool, and every later
   command in the project ran unprompted — the permission fast path also
   skipped permission_request, so bash_suite's denylist never saw them."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [permission-check-enter resolve-ask]]
            [agent.settings.manager :as sm]
            [agent.utils.shell-grant :as sg]
            [agent.core]
            [agent.commands.builtins]))

(describe "grant-base / grant-matches?"
          (fn []
            (it "verb + subcommand for subcommand tools, else the verb; none for compound"
                (fn []
                  (doseq [[cmd want] [["git push origin main" "git push"]
                                      ["git -C x status" "git"]
                                      ["pytest -x tests/" "pytest"]
                                      ["ls" "ls"]
                                      ["echo hi; rm -rf ~" nil]
                                      ["cat a | sh" nil]
                                      ["" nil]]]
                    (-> (expect #js [cmd (or (sg/grant-base cmd) "none")]) (.toEqual #js [cmd (or want "none")])))))
            (it "interpreters and wrappers never get a grant, and an old one matches nothing"
                (fn []
                  (doseq [c ["bash -c 'ls'" "/bin/sh -c 'ls'" "python3 -c 'print(1)'" "sudo ls" "env FOO=1 ls" "xargs rm"]]
                    (-> (expect #js [c (boolean (sg/grant-base c))]) (.toEqual #js [c false])))
                  (-> (expect (sg/grant-matches? "bash" "bash -c 'rm -rf ~/work'")) (.toBe false))
                  (-> (expect (sg/grant-matches? "sudo" "sudo rm -rf /")) (.toBe false))
                  ;; ordinary commands are unaffected
                  (-> (expect (sg/grant-base "pytest -x")) (.toBe "pytest"))))

            (it "matches on a word boundary, simple commands only"
                (fn []
                  (-> (expect (sg/grant-matches? "git push" "git push --force")) (.toBe true))
                  (-> (expect (sg/grant-matches? "git push" "git push")) (.toBe true))
                  (-> (expect (sg/grant-matches? "git push" "git pushx")) (.toBe false))
                  (-> (expect (sg/grant-matches? "git push" "git push; rm -rf ~")) (.toBe false))
                  (-> (expect (sg/grant-matches? "git push" "git status")) (.toBe false))))))

(defn- temp-settings []
  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bashgrant-"))]
    (sm/create-settings-manager {:global-path (path/join d "g.json") :project-path (path/join d "p.json")})))

(defn ^:async test-always-stores-pattern []
  (let [settings (temp-settings)
        seen     (atom nil)
        ui       #js {:available true
                      :select (fn [_ opts] (reset! seen (vec opts))
                                (js/Promise.resolve (first (filter #(.startsWith % "Allow always") opts))))}]
    (js-await (resolve-ask settings {:tool-name "bash" :args {:command "git push origin main"}} ui "bash" nil))
    (-> (expect (clj->js @seen)) (.toContain "Allow always: git push * (this project)"))
    (-> (expect (clj->js (:bash ((:grants settings))))) (.toEqual #js ["git push"]))
    ;; NOT the whole tool
    (-> (expect ((:tool-allowed? settings) "bash")) (.toBe false))
    (-> (expect ((:bash-allowed? settings) "git push --tags")) (.toBe true))
    (-> (expect ((:bash-allowed? settings) "rm -rf build")) (.toBe false))
    ;; and /permissions can take it back
    (-> (expect ((:revoke-grant! settings) "git push")) (.toBe true))
    (-> (expect ((:bash-allowed? settings) "git push --tags")) (.toBe false))))

(defn ^:async test-compound-has-no-always []
  (let [seen (atom nil)
        ui   #js {:available true :select (fn [_ opts] (reset! seen (vec opts)) (js/Promise.resolve "Deny"))}]
    (js-await (resolve-ask (temp-settings) {:tool-name "bash" :args {:command "make && ./deploy.sh"}} ui "bash" nil))
    (-> (expect (some #(.startsWith % "Allow always") @seen)) (.toBeFalsy))))

(defn ^:async test-grant-never-skips-deny []
  ;; A pattern grant, a session grant and a legacy whole-tool grant all answer
  ;; an ASK — a deny from the denylist still wins.
  (let [settings (temp-settings)
        _        ((:append-bash-allow! settings) "rm")
        _        ((:append-allow-tool! settings) "bash")
        events   (create-event-bus)
        _        ((:on events) "permission_request" (fn [_] #js {:decision "deny" :reason "denylist"}))
        ctx      (js-await (permission-check-enter events settings
                                                   {:tool-name "bash" :args {:command "rm -rf /"} :session-allows (atom #{"bash"})}))]
    (-> (expect (:cancelled ctx)) (.toBe true))))

(defn ^:async test-grant-answers-ask []
  (let [settings (temp-settings)
        _        ((:append-bash-allow! settings) "git push")
        events   (create-event-bus)
        _        ((:on events) "permission_request" (fn [_] #js {:decision "ask"}))
        ok       (js-await (permission-check-enter events settings {:tool-name "bash" :args {:command "git push"}}))
        other    (js-await (permission-check-enter events settings {:tool-name "bash" :args {:command "git reset --hard"}}))]
    (-> (expect (boolean (:cancelled ok))) (.toBe false))
    ;; no UI on this ctx → an unanswered ask denies (never a silent allow)
    (-> (expect (:cancelled other)) (.toBe true))))

(describe "bash Allow always"
          (fn []
            (it "stores the pattern, not the tool, and can be revoked" test-always-stores-pattern)
            (it "offers no always-option for a compound command" test-compound-has-no-always)
            (it "no grant of any kind overrides a deny" test-grant-never-skips-deny)
            (it "a pattern answers the ask for matching commands only" test-grant-answers-ask)))

(defn ^:async test-permissions-command []
  (let [settings (temp-settings)
        a        (assoc (agent.core/create-agent {:model "m" :system-prompt "s"}) :settings settings)
        shown    (atom nil)
        notes    (atom [])
        ctx      #js {:ui #js {:available true
                               :notify (fn [m & _] (swap! notes conj m))
                               :showOverlay (fn [t] (reset! shown t))}}]
    ((:append-bash-allow! settings) "git push")
    ((:append-allow-tool! settings) "edit")
    (agent.commands.builtins/register-builtins a nil {})
    (let [h (:handler (get @(:commands a) "permissions"))]
      (h [] ctx)
      (-> (expect (str @shown)) (.toContain "git push *"))
      (-> (expect (str @shown)) (.toContain "edit"))
      (h ["revoke" "git" "push" "*"] ctx)
      (-> (expect (clj->js @notes)) (.toContain "Revoked: git push"))
      (-> (expect ((:bash-allowed? settings) "git push")) (.toBe false)))))

(describe "/permissions"
          (fn [] (it "lists grants and revokes one" test-permissions-command)))

(defn ^:async test-legacy-bash-grant-flagged []
  (let [settings (temp-settings)
        a        (assoc (agent.core/create-agent {:model "m" :system-prompt "s"}) :settings settings)
        shown    (atom nil)
        ctx      #js {:ui #js {:available true :notify (fn [& _] nil) :showOverlay (fn [t] (reset! shown t))}}]
    ((:append-allow-tool! settings) "bash")
    (agent.commands.builtins/register-builtins a nil {})
    ((:handler (get @(:commands a) "permissions")) [] ctx)
    (-> (expect (str @shown)) (.toContain "approves every shell command"))))

(describe "/permissions and old grants"
          (fn [] (it "flags a whole-tool bash grant" test-legacy-bash-grant-flagged)))

(describe "run overrides"
          (fn []
            (it "a tool allowed in --settings is allowed for the run; the files are untouched"
                (fn []
                  (let [settings (temp-settings)]
                    (-> (expect ((:tool-allowed? settings) "todo_write")) (.toBe false))
                    ((:apply-overrides settings) {:permissions {:allow ["todo_write"]}})
                    (-> (expect ((:tool-allowed? settings) "todo_write")) (.toBe true))
                    (-> (expect ((:tool-allowed? settings) "write")) (.toBe false)))))))
