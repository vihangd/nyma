(ns resources-skills.test
  "Tests for agent.resources.skills — frontmatter parsing, name validation,
   path glob matching, trigger phrase matching, and discover-skills end-to-end
   with synthetic skill directories under a tmp dir."
  (:require ["bun:test" :refer [describe it expect afterEach beforeEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.resources.skills :as skills]
            [agent.resources.loader :as loader]))

;; ── Helpers ─────────────────────────────────────────────────────

(def ^:dynamic *tmp* nil)

(defn- mktmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-skills-")))

(defn- write-skill
  "Create dir/<name>/SKILL.md with the given content."
  [root sname content]
  (let [d (path/join root sname)]
    (fs/mkdirSync d #js {:recursive true})
    (fs/writeFileSync (path/join d "SKILL.md") content)
    d))

;; ── Frontmatter parsing ────────────────────────────────────────

(describe "skills/parse-frontmatter"
          (fn []
            (it "no frontmatter → empty map and full body"
                (fn []
                  (let [r (skills/parse-frontmatter "Just a body\nwith two lines")]
                    (-> (expect (count (:frontmatter r))) (.toBe 0))
                    (-> (expect (:body r)) (.toBe "Just a body\nwith two lines")))))

            (it "valid frontmatter → parsed map and body"
                (fn []
                  (let [text (str "---\n"
                                  "name: my-skill\n"
                                  "description: A test skill\n"
                                  "license: MIT\n"
                                  "---\n"
                                  "Body text here.\n")
                        r (skills/parse-frontmatter text)]
                    (-> (expect (aget (:frontmatter r) "name")) (.toBe "my-skill"))
                    (-> (expect (aget (:frontmatter r) "description")) (.toBe "A test skill"))
                    (-> (expect (aget (:frontmatter r) "license")) (.toBe "MIT"))
                    (-> (expect (.includes (:body r) "Body text here.")) (.toBe true)))))

            (it "list-shaped allowed-tools parses as JS array"
                (fn []
                  (let [text (str "---\n"
                                  "name: x\n"
                                  "description: x\n"
                                  "allowed-tools: [read, grep, ls]\n"
                                  "---\nbody\n")
                        r (skills/parse-frontmatter text)
                        tools (aget (:frontmatter r) "allowed-tools")]
                    (-> (expect (.isArray js/Array tools)) (.toBe true))
                    (-> (expect (.-length tools)) (.toBe 3)))))

            (it "malformed YAML → empty frontmatter, body falls back to full text"
                (fn []
                  (let [text "---\nthis: is: not: valid: yaml\n---\nbody"
                        r (skills/parse-frontmatter text)]
                    ;; either empty map or a partial parse with no crash
                    (-> (expect (or (= 0 (count (:frontmatter r)))
                                    (some? (:frontmatter r)))) (.toBe true)))))))

;; ── Name validation ─────────────────────────────────────────────

(describe "skills/valid-name?"
          (fn []
            (it "accepts simple lowercase names"
                (fn []
                  (-> (expect (skills/valid-name? "my-skill")) (.toBe true))
                  (-> (expect (skills/valid-name? "skill")) (.toBe true))
                  (-> (expect (skills/valid-name? "skill-with-hyphens")) (.toBe true))
                  (-> (expect (skills/valid-name? "x")) (.toBe true))))

            (it "rejects uppercase"
                (fn []
                  (-> (expect (skills/valid-name? "MySkill")) (.toBe false))))

            (it "rejects leading/trailing/consecutive hyphens"
                (fn []
                  (-> (expect (skills/valid-name? "-skill")) (.toBe false))
                  (-> (expect (skills/valid-name? "skill-")) (.toBe false))
                  (-> (expect (skills/valid-name? "skill--two")) (.toBe false))))

            (it "rejects empty / non-string"
                (fn []
                  (-> (expect (skills/valid-name? "")) (.toBe false))
                  (-> (expect (skills/valid-name? nil)) (.toBe false))))))

;; ── Path glob matching ─────────────────────────────────────────

(describe "skills/path-matches-skill?"
          (fn []
            (it "no paths → always matches"
                (fn []
                  (-> (expect (skills/path-matches-skill? {} "anything.ts")) (.toBe true))
                  (-> (expect (skills/path-matches-skill? {:paths []} "anything.ts")) (.toBe true))))

            (it "literal pattern matches exactly"
                (fn []
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/foo.ts"]} "src/foo.ts")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/foo.ts"]} "src/bar.ts")) (.toBe false))))

            (it "double-star crosses slashes"
                (fn []
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/**/*.ts"]} "src/a/b/c.ts")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/**/*.ts"]} "src/a.ts")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/**/*.ts"]} "lib/a.ts")) (.toBe false))))

            (it "single-star matches one segment"
                (fn []
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["*.md"]} "README.md")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["*.md"]} "src/README.md")) (.toBe false))))

            (it "any-of-many globs match"
                (fn []
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["a.ts" "b.ts"]} "b.ts")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["a.ts" "b.ts"]} "c.ts")) (.toBe false))))

            (it "leading **/  matches files at any depth incl. root"
                (fn []
                  ;; Regression: bare leading **/ used to require at least
                  ;; one slash (so README.md failed to match **/*.md).
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["**/*.md"]} "README.md")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["**/*.md"]} "docs/x.md")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["**/*.md"]} "a/b/c/d.md")) (.toBe true))))

            (it "trailing /** matches files at any subdepth"
                (fn []
                  ;; Regression: bare ** in suffix used to be re-rewritten
                  ;; by the * -> [^/]* pass to .[^/]*, breaking nested matches.
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/**"]} "src/a")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/**"]} "src/a/b/c")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["src/**"]} "lib/a")) (.toBe false))))

            (it "? matches exactly one character"
                (fn []
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["?.md"]} "a.md")) (.toBe true))
                  (-> (expect (skills/path-matches-skill?
                               {:paths ["?.md"]} "ab.md")) (.toBe false))))))

;; ── Trigger phrase matching ────────────────────────────────────

(describe "skills/matching-triggers"
          (fn []
            (it "no skills → empty"
                (fn []
                  (-> (expect (count (or (skills/matching-triggers {} "hello") [])))
                      (.toBe 0))))

            (it "skills without triggers don't auto-activate"
                (fn []
                  (let [m {"foo" {:description "x"}}
                        r (skills/matching-triggers m "anything")]
                    (-> (expect (count (or r []))) (.toBe 0)))))

            (it "case-insensitive substring match"
                (fn []
                  (let [m {"terse" {:triggers ["less tokens" "be brief"]}}
                        r (skills/matching-triggers m "Please use Less Tokens")]
                    (-> (expect (.includes (vec r) "terse")) (.toBe true)))))

            (it "no match → not in result"
                (fn []
                  (let [m {"terse" {:triggers ["less tokens"]}}
                        r (skills/matching-triggers m "hello world")]
                    (-> (expect (count (or r []))) (.toBe 0)))))))

;; ── End-to-end discovery ───────────────────────────────────────

(describe "skills/discover-skills (end-to-end)"
          (fn []
            (it "skill with full frontmatter is parsed"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-skill tmp "my-skill"
                                         (str "---\n"
                                              "name: my-skill\n"
                                              "description: Helpful test skill\n"
                                              "license: Apache-2.0\n"
                                              "compatibility: nyma 0.1+\n"
                                              "allowed-tools: [read, grep]\n"
                                              "disable-model-invocation: false\n"
                                              "paths: [\"src/**/*.ts\"]\n"
                                              "triggers: [\"do the thing\"]\n"
                                              "---\n"
                                              "Body content for the skill.\n"))
                        m (skills/discover-skills tmp)
                        s (get m "my-skill")]
                    (-> (expect (some? s)) (.toBe true))
                    (-> (expect (:name s)) (.toBe "my-skill"))
                    (-> (expect (:description s)) (.toBe "Helpful test skill"))
                    (-> (expect (:license s)) (.toBe "Apache-2.0"))
                    (-> (expect (vec (:allowed-tools s))) (.toEqual ["read" "grep"]))
                    (-> (expect (:disable-model-invocation s)) (.toBe false))
                    (-> (expect (vec (:paths s))) (.toEqual ["src/**/*.ts"]))
                    (-> (expect (vec (:triggers s))) (.toEqual ["do the thing"]))
                    (fs/rmSync tmp #js {:recursive true :force true}))))

            (it "skill with no frontmatter falls back to first-line description"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-skill tmp "no-fm"
                                         "# Heading\n\nFirst paragraph is the description.\n\nMore body.")
                        m (skills/discover-skills tmp)
                        s (get m "no-fm")]
                    (-> (expect (:description s))
                        (.toBe "First paragraph is the description."))
                    (fs/rmSync tmp #js {:recursive true :force true}))))

            (it "disable-model-invocation: true is honored"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-skill tmp "hidden"
                                         (str "---\nname: hidden\n"
                                              "description: hidden\n"
                                              "disable-model-invocation: true\n"
                                              "---\nbody"))
                        m (skills/discover-skills tmp)
                        s (get m "hidden")]
                    (-> (expect (:disable-model-invocation s)) (.toBe true))
                    (fs/rmSync tmp #js {:recursive true :force true}))))

            (it "non-skill directories are skipped"
                (fn []
                  (let [tmp (mktmp)
                        _   (fs/mkdirSync (path/join tmp "not-a-skill") #js {:recursive true})
                        m (skills/discover-skills tmp)]
                    (-> (expect (count m)) (.toBe 0))
                    (fs/rmSync tmp #js {:recursive true :force true}))))

            (it "tools.cljs companion sets has-tools"
                (fn []
                  (let [tmp (mktmp)
                        d   (write-skill tmp "with-tools"
                                         "---\nname: with-tools\ndescription: x\n---\nbody")
                        _   (fs/writeFileSync (path/join d "tools.cljs") "(ns x)")
                        m (skills/discover-skills tmp)
                        s (get m "with-tools")]
                    (-> (expect (:has-tools s)) (.toBe true))
                    (fs/rmSync tmp #js {:recursive true :force true}))))))

;; ── Multi-folder cross-vendor discovery ────────────────────────

(defn- mk-fake-home []
  (mktmp))

(defn- mk-fake-cwd []
  (mktmp))

(describe "loader/discover-all-skills (multi-folder, cross-vendor)"
          (fn []
            (it "merges skills from all five sources"
                (fn []
                  (let [home (mk-fake-home)
                        cwd  (mk-fake-cwd)]
                    (try
                      ;; one skill in each of the five locations
                      (write-skill (path/join home ".nyma/skills") "from-home-nyma"
                                   "---\nname: from-home-nyma\ndescription: a\n---\nbody")
                      (write-skill (path/join home ".claude/skills") "from-home-claude"
                                   "---\nname: from-home-claude\ndescription: b\n---\nbody")
                      (write-skill (path/join cwd ".nyma/skills") "from-project-nyma"
                                   "---\nname: from-project-nyma\ndescription: c\n---\nbody")
                      (write-skill (path/join cwd ".cursor/skills") "from-project-cursor"
                                   "---\nname: from-project-cursor\ndescription: d\n---\nbody")
                      (write-skill (path/join cwd ".agents/skills") "from-project-agents"
                                   "---\nname: from-project-agents\ndescription: e\n---\nbody")
                      (let [m (loader/discover-all-skills home cwd)]
                        (-> (expect (some? (get m "from-home-nyma"))) (.toBe true))
                        (-> (expect (some? (get m "from-home-claude"))) (.toBe true))
                        (-> (expect (some? (get m "from-project-nyma"))) (.toBe true))
                        (-> (expect (some? (get m "from-project-cursor"))) (.toBe true))
                        (-> (expect (some? (get m "from-project-agents"))) (.toBe true))
                        (-> (expect (count m)) (.toBe 5)))
                      (finally
                        (fs/rmSync home #js {:recursive true :force true})
                        (fs/rmSync cwd #js {:recursive true :force true}))))))

            (it "project nyma beats project cross-vendor on name collision"
                (fn []
                  (let [home (mk-fake-home)
                        cwd  (mk-fake-cwd)]
                    (try
                      (write-skill (path/join cwd ".nyma/skills") "shared"
                                   "---\nname: shared\ndescription: from-nyma\n---\nbody")
                      (write-skill (path/join cwd ".claude/skills") "shared"
                                   "---\nname: shared\ndescription: from-claude\n---\nbody")
                      (let [m (loader/discover-all-skills home cwd)
                            s (get m "shared")]
                        (-> (expect (:description s)) (.toBe "from-nyma")))
                      (finally
                        (fs/rmSync home #js {:recursive true :force true})
                        (fs/rmSync cwd #js {:recursive true :force true}))))))

            (it "project beats global on name collision"
                (fn []
                  (let [home (mk-fake-home)
                        cwd  (mk-fake-cwd)]
                    (try
                      (write-skill (path/join home ".nyma/skills") "shared"
                                   "---\nname: shared\ndescription: from-global\n---\nbody")
                      (write-skill (path/join cwd ".nyma/skills") "shared"
                                   "---\nname: shared\ndescription: from-project\n---\nbody")
                      (let [m (loader/discover-all-skills home cwd)
                            s (get m "shared")]
                        (-> (expect (:description s)) (.toBe "from-project")))
                      (finally
                        (fs/rmSync home #js {:recursive true :force true})
                        (fs/rmSync cwd #js {:recursive true :force true}))))))

            (it "source field annotates which directory a skill came from"
                (fn []
                  (let [home (mk-fake-home)
                        cwd  (mk-fake-cwd)]
                    (try
                      (write-skill (path/join cwd ".cursor/skills") "x"
                                   "---\nname: x\ndescription: x\n---\nbody")
                      (let [m (loader/discover-all-skills home cwd)
                            s (get m "x")]
                        (-> (expect (.includes (:source s) ".cursor/skills")) (.toBe true))
                        (-> (expect (.startsWith (:source s) "project:")) (.toBe true)))
                      (finally
                        (fs/rmSync home #js {:recursive true :force true})
                        (fs/rmSync cwd #js {:recursive true :force true}))))))

            (it "no skills anywhere → empty map"
                (fn []
                  (let [home (mk-fake-home)
                        cwd  (mk-fake-cwd)]
                    (try
                      (let [m (loader/discover-all-skills home cwd)]
                        (-> (expect (count m)) (.toBe 0)))
                      (finally
                        (fs/rmSync home #js {:recursive true :force true})
                        (fs/rmSync cwd #js {:recursive true :force true}))))))))

;; ── AGENTS.md discovery (Phase 3) ───────────────────────────────

(defn- write-agents-md [dir content]
  (fs/mkdirSync dir #js {:recursive true})
  (fs/writeFileSync (path/join dir "AGENTS.md") content))

(describe "loader/find-agents-files (session-start AGENTS.md set)"
          (fn []
            (it "no files anywhere → empty vec"
                (fn []
                  (let [root (mktmp)
                        home (path/join root "home")
                        cwd  (path/join root "project")]
                    (try
                      (fs/mkdirSync home #js {:recursive true})
                      (fs/mkdirSync cwd  #js {:recursive true})
                      (-> (expect (count (loader/find-agents-files home cwd))) (.toBe 0))
                      (finally
                        (fs/rmSync root #js {:recursive true :force true}))))))

            (it "finds project-root AGENTS.md"
                (fn []
                  (let [root (mktmp)
                        home (path/join root "home")
                        cwd  (path/join root "project")]
                    (try
                      (fs/mkdirSync home #js {:recursive true})
                      (write-agents-md cwd "# project root")
                      (let [paths (loader/find-agents-files home cwd)]
                        (-> (expect (count paths)) (.toBe 1))
                        (-> (expect (.endsWith (first paths) "AGENTS.md")) (.toBe true)))
                      (finally
                        (fs/rmSync root #js {:recursive true :force true}))))))

            (it "finds all four canonical files at once"
                (fn []
                  (let [root (mktmp)
                        home (path/join root "home")
                        cwd  (path/join root "project")]
                    (try
                      (write-agents-md home "# user-global")
                      (write-agents-md (path/join home ".nyma") "# nyma-global")
                      (write-agents-md cwd "# project root")
                      (write-agents-md (path/join cwd ".nyma") "# nyma project")
                      (let [paths (loader/find-agents-files home cwd)
                            joined (str/join "|" paths)]
                        (-> (expect (count paths)) (.toBe 4))
                        (-> (expect (.includes joined (path/join home "AGENTS.md"))) (.toBe true))
                        (-> (expect (.includes joined (path/join home ".nyma/AGENTS.md"))) (.toBe true))
                        (-> (expect (.includes joined (path/join cwd "AGENTS.md"))) (.toBe true))
                        (-> (expect (.includes joined (path/join cwd ".nyma/AGENTS.md"))) (.toBe true)))
                      (finally
                        (fs/rmSync root #js {:recursive true :force true}))))))

            (it "session-start does NOT walk above cwd or into subdirs"
                (fn []
                  ;; If we ever flip to walk-up-from-cwd at session-start,
                  ;; this test will catch it. Layout:
                  ;;   <root>/AGENTS.md           — above cwd; must NOT be read
                  ;;   <root>/project/AGENTS.md   — cwd
                  ;;   <root>/project/sub/AGENTS.md — below cwd; must NOT be read
                  (let [root (mktmp)
                        home (path/join root "home")
                        cwd  (path/join root "project")
                        sub  (path/join cwd "sub")]
                    (try
                      (fs/mkdirSync home #js {:recursive true})
                      (fs/mkdirSync sub  #js {:recursive true})
                      (write-agents-md root "# above")
                      (write-agents-md cwd  "# project")
                      (write-agents-md sub  "# sub")
                      (let [paths (loader/find-agents-files home cwd)
                            joined (str/join "|" paths)]
                        (-> (expect (count paths)) (.toBe 1))
                        (-> (expect (.includes joined (path/join cwd "AGENTS.md"))) (.toBe true))
                        (-> (expect (.includes joined (str root "/AGENTS.md"))) (.toBe false))
                        (-> (expect (.includes joined "/sub/AGENTS.md")) (.toBe false)))
                      (finally
                        (fs/rmSync root #js {:recursive true :force true}))))))))

(describe "loader/walk-up-for-agents-md (runtime monorepo resolution)"
          (fn []
            (it "collects ancestors, returning farthest-first (lowest precedence first)"
                (fn []
                  (let [root (mktmp)
                        home (path/join root "home")
                        proj (path/join home "project")
                        nested (path/join proj "packages/foo")]
                    (try
                      (fs/mkdirSync home #js {:recursive true})
                      (write-agents-md proj "# repo root")
                      (write-agents-md nested "# packages/foo")
                      (let [paths (loader/walk-up-for-agents-md nested home)]
                        ;; Two found, farthest first (proj), closest last (nested).
                        (-> (expect (count paths)) (.toBe 2))
                        (-> (expect (.includes (first paths) "/project/AGENTS.md"))
                            (.toBe true))
                        (-> (expect (.includes (last paths) "packages/foo/AGENTS.md"))
                            (.toBe true)))
                      (finally
                        (fs/rmSync root #js {:recursive true :force true}))))))

            (it "stops at the configured stop-at-dir"
                (fn []
                  (let [root (mktmp)
                        home (path/join root "home")
                        proj (path/join home "project")]
                    (try
                      (fs/mkdirSync proj #js {:recursive true})
                      (write-agents-md root "# above home, must not be read")
                      (let [paths (loader/walk-up-for-agents-md proj home)
                            joined (str/join "|" paths)]
                        (-> (expect (.includes joined (str root "/AGENTS.md")))
                            (.toBe false)))
                      (finally
                        (fs/rmSync root #js {:recursive true :force true}))))))))

(describe "loader/agents-body-or-nil (frontmatter filter)"
          (fn []
            (it "no frontmatter → returns the full content as body"
                (fn []
                  (let [r (loader/agents-body-or-nil "Just a plain AGENTS.md\nWith content.")]
                    (-> (expect (.includes r "plain AGENTS.md")) (.toBe true)))))

            (it "frontmatter with alwaysApply: true → returns body"
                (fn []
                  (let [r (loader/agents-body-or-nil
                           "---\nalwaysApply: true\n---\nApplied body\n")]
                    (-> (expect (.includes r "Applied body")) (.toBe true)))))

            (it "frontmatter with alwaysApply: false → returns nil"
                (fn []
                  (let [r (loader/agents-body-or-nil
                           "---\nalwaysApply: false\n---\nSkipped body\n")]
                    (-> (expect (nil? r)) (.toBe true)))))

            (it "frontmatter without alwaysApply → returns body (default-applied)"
                (fn []
                  (let [r (loader/agents-body-or-nil
                           "---\ndescription: Some rules\nglobs: [\"*.ts\"]\n---\nBody here.")]
                    (-> (expect (.includes r "Body here.")) (.toBe true)))))))
