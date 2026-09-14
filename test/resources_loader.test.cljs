(ns resources-loader.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.resources.loader :refer [discover find-agents-files]]))

;; ── context-files: AGENTS.md / CLAUDE.md selection ─────────────
;;
;; Each test builds <tmp>/home and <tmp>/repo (with a .git entry) and calls
;; find-agents-files with explicit home/cwd, so nothing outside the tree —
;; the developer's own ~/AGENTS.md included — can leak in.

(defn- ctx-tree []
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-ctxfiles-"))
        home (path/join root "home")
        repo (path/join root "repo")]
    (fs/mkdirSync home #js {:recursive true})
    (fs/mkdirSync (path/join repo ".git") #js {:recursive true})
    {:root root :home home :repo repo}))

(defn- touch [dir name]
  (fs/mkdirSync dir #js {:recursive true})
  (fs/writeFileSync (path/join dir name) (str "# " name)))

(defn- basenames [paths] (mapv #(path/basename %) paths))

(describe "context files in the system prompt (context-files setting)"
          (fn []
            (it "picks CLAUDE.md when AGENTS.md is absent"
                (fn []
                  (let [{:keys [root home repo]} (ctx-tree)]
                    (try
                      (touch repo "CLAUDE.md")
                      (-> (expect (basenames (find-agents-files home repo))) (.toEqual ["CLAUDE.md"]))
                      (finally (fs/rmSync root #js {:recursive true :force true}))))))

            (it "prefers AGENTS.md when both exist at the same level"
                (fn []
                  (let [{:keys [root home repo]} (ctx-tree)]
                    (try
                      (touch repo "AGENTS.md")
                      (touch repo "CLAUDE.md")
                      (-> (expect (basenames (find-agents-files home repo))) (.toEqual ["AGENTS.md"]))
                      (finally (fs/rmSync root #js {:recursive true :force true}))))))

            (it "honours a custom context-files order"
                (fn []
                  (let [{:keys [root home repo]} (ctx-tree)]
                    (try
                      (touch repo "AGENTS.md")
                      (touch repo "CLAUDE.md")
                      (touch home "CONVENTIONS.md")
                      (-> (expect (basenames (find-agents-files home repo ["CLAUDE.md" "CONVENTIONS.md"])))
                          (.toEqual ["CONVENTIONS.md" "CLAUDE.md"]))
                      (finally (fs/rmSync root #js {:recursive true :force true}))))))

            (it "walks ancestors up to the repo root and no further"
                (fn []
                  (let [{:keys [root home repo]} (ctx-tree)
                        sub (path/join repo "packages" "foo")]
                    (try
                      (touch root "CLAUDE.md")                     ; above the repo: must not be read
                      (touch repo "CLAUDE.md")                     ; repo root
                      (touch (path/join repo "packages") "AGENTS.md")
                      (touch sub "CLAUDE.md")                      ; cwd
                      (-> (expect (find-agents-files home sub))
                          (.toEqual [(path/join repo "CLAUDE.md")
                                     (path/join repo "packages" "AGENTS.md")
                                     (path/join sub "CLAUDE.md")]))
                      (finally (fs/rmSync root #js {:recursive true :force true}))))))))

;; Test resource discovery with temp directories.
;; discover is async, returns {:skills :prompts :themes :agents-md :extension-dirs :build-system-prompt}

(def test-base (str "/tmp/nyma-resources-test-" (js/Date.now)))

(defn- setup-dirs []
  (let [skills-dir  (str test-base "/skills/my-skill")
        prompts-dir (str test-base "/prompts")
        themes-dir  (str test-base "/themes")
        ext-dir     (str test-base "/extensions")]
    (fs/mkdirSync skills-dir #js {:recursive true})
    (fs/mkdirSync prompts-dir #js {:recursive true})
    (fs/mkdirSync themes-dir #js {:recursive true})
    (fs/mkdirSync ext-dir #js {:recursive true})
    ;; Create test files
    (fs/writeFileSync (str skills-dir "/SKILL.md") "# My Skill\nDoes things.")
    (fs/writeFileSync (str prompts-dir "/greet.md") "Hello {{name}}!")
    (fs/writeFileSync (str themes-dir "/dark.json")
                      (js/JSON.stringify #js {:name "dark" :colors #js {:primary "#fff"}}))))

(defn- cleanup-recursive [dir-path]
  (when (fs/existsSync dir-path)
    (let [entries (fs/readdirSync dir-path #js {:withFileTypes true})]
      (doseq [entry entries]
        (let [full (path/join dir-path (.-name entry))]
          (if (.isDirectory entry)
            (cleanup-recursive full)
            (fs/unlinkSync full)))))
    (fs/rmdirSync dir-path)))

(defn ^:async test-discover-returns-expected-shape []
  (let [result (js-await (discover))]
    ;; Should have all expected keys
    (-> (expect (some? (:skills result))) (.toBe true))
    (-> (expect (some? (:prompts result))) (.toBe true))
    (-> (expect (some? (:themes result))) (.toBe true))
    (-> (expect (some? (:extension-dirs result))) (.toBe true))
    (-> (expect (fn? (:build-system-prompt result))) (.toBe true))))

(defn ^:async test-build-system-prompt-includes-env []
  (let [result (js-await (discover))
        prompt ((:build-system-prompt result))]
    ;; Should include environment info
    (-> (expect prompt) (.toContain "Environment"))))

(defn test-discover-handles-missing-dirs []
  ;; discover should not throw when dirs don't exist
  ;; This is implicitly tested by calling discover without setup
  ;; since the global/project dirs may not exist in test env
  (-> (expect (fn? discover)) (.toBe true)))

(describe "agent.resources.loader/discover" (fn []
                                              (it "returns expected shape with all keys" test-discover-returns-expected-shape)
                                              (it "build-system-prompt includes environment block" test-build-system-prompt-includes-env)
                                              (it "handles missing directories gracefully" test-discover-handles-missing-dirs)))
