(ns session-project.test
  "Which project a session belongs to: `repo-root` walks up to the nearest
   .git and stops; `session-cwd` prefers the recorded marker and otherwise
   infers from the paths the session's tool calls touched."
  (:require ["bun:test" :refer [describe it expect afterAll]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.sessions.project :refer [repo-root session-cwd infer-cwd project-label same-project?]]))

(def ^:private tmp (fs/mkdtempSync (path/join (os/tmpdir) "nyma-project-")))
(def ^:private repo (path/join tmp "repo"))
(def ^:private deep (path/join repo "src" "a" "b"))
(def ^:private other (path/join tmp "other"))

(fs/mkdirSync deep #js {:recursive true})
(fs/mkdirSync (path/join repo ".git") #js {:recursive true})
(fs/mkdirSync (path/join other ".git") #js {:recursive true})
(fs/writeFileSync (path/join deep "f.txt") "x")

(afterAll (fn [] (fs/rmSync tmp #js {:recursive true :force true})))

(defn- tool-call [args]
  #js {:role "tool_call" :metadata #js {:args (clj->js args)}})

(describe "sessions/project repo-root"
          (fn []
            (it "walks up from a nested directory to the directory holding .git"
                (fn []
                  (-> (expect (repo-root deep)) (.toBe repo))
                  (-> (expect (repo-root repo)) (.toBe repo))))

            (it "a path with no repo above it is nil, not / (and never climbs past the bound)"
                (fn []
                  (let [none (path/join tmp "none")]
                    (fs/mkdirSync none #js {:recursive true})
                    (-> (expect (repo-root none)) (.toBeNil))
                    (-> (expect (repo-root "/")) (.toBeNil)))))

            (it "relative and non-string input is nil"
                (fn []
                  (-> (expect (repo-root "src/a")) (.toBeNil))
                  (-> (expect (repo-root nil)) (.toBeNil))))))

(describe "sessions/project session-cwd"
          (fn []
            (it "the recorded session-meta cwd wins over anything the tools touched"
                (fn []
                  (let [entries [#js {:role "session-meta" :metadata #js {:cwd other}}
                                 (tool-call {:path (path/join deep "f.txt")})]]
                    (-> (expect (session-cwd entries)) (.toBe other)))))

            (it "without a marker, infers the repo of the paths the tools touched — the most frequent one"
                (fn []
                  (let [entries [(tool-call {:command (str "cat " (path/join other "x.txt"))})
                                 (tool-call {:path (path/join deep "f.txt")})
                                 (tool-call {:command (str "ls " repo " && ls " deep)})]]
                    (-> (expect (session-cwd entries)) (.toBe repo)))))

            (it "a session that touched nothing on disk is unknown (nil), never guessed"
                (fn []
                  (-> (expect (session-cwd [#js {:role "user" :content "hello"}])) (.toBeNil))
                  (-> (expect (infer-cwd [(tool-call {:command "echo hi"})])) (.toBeNil))
                  (-> (expect (session-cwd nil)) (.toBeNil))))

            (it "project-label is the basename and same-project? never matches an unknown"
                (fn []
                  (-> (expect (project-label repo)) (.toBe "repo"))
                  (-> (expect (project-label nil)) (.toBeNil))
                  (-> (expect (same-project? repo repo)) (.toBe true))
                  (-> (expect (same-project? nil nil)) (.toBe false))))))
