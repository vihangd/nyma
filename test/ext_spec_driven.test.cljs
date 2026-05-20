(ns ext-spec-driven.test
  "Unit tests for the spec-driven extension. The slash-command dispatch
   is exercised indirectly via its pure helpers (parse-tasks,
   mark-task-done, find-task, next-open-task, discover-specs); the
   API-bound side effects (notify, command registration) are verified
   in the load-smoke test."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs"   :as fs]
            ["node:os"   :as os]
            ["node:path" :as path]
            ["./agent/extensions/spec_driven/index.mjs" :as spec]))

;; ── Helpers ─────────────────────────────────────────────────────

(defn- mktmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-spec-")))

(defn- write-file [p content]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p content))

(defn- write-spec
  "Drop a synthetic spec into <root>/<spec-root>/<name>/. shape is
   :kiro or :spec-kit. opts is a map with :req, :design, :tasks string
   contents (any nil-valued or omitted key writes nothing for that file).

   Squint doesn't reliably support `& {:keys [...]}` kwarg destructuring,
   so we take a plain map argument."
  ([root shape sname] (write-spec root shape sname {}))
  ([root shape sname opts]
   (let [req       (or (:req opts) (get opts "req"))
         design    (or (:design opts) (get opts "design"))
         tasks     (or (:tasks opts) (get opts "tasks"))
         spec-root (case shape
                     :kiro     ".kiro/specs"
                     :spec-kit ".specify/specs")
         req-name  (case shape :kiro "requirements.md" :spec-kit "spec.md")
         plan-name (case shape :kiro "design.md"       :spec-kit "plan.md")
         d (path/join root spec-root sname)]
     (fs/mkdirSync d #js {:recursive true})
     (when req    (write-file (path/join d req-name)  req))
     (when design (write-file (path/join d plan-name) design))
     (when tasks  (write-file (path/join d "tasks.md") tasks))
     d)))

;; ── parse-tasks ─────────────────────────────────────────────────

(describe "spec/parse-tasks"
          (fn []
            (it "nil / non-string → nil"
                (fn []
                  (-> (expect (spec/parse-tasks nil)) (.toBeNil))))

            (it "empty content → empty vec"
                (fn []
                  (-> (expect (count (spec/parse-tasks ""))) (.toBe 0))))

            (it "extracts dash-checkbox tasks with state and idx"
                (fn []
                  (let [content (str "# Tasks\n\n"
                                     "- [ ] First task\n"
                                     "- [x] Done task\n"
                                     "- [X] Capital-X done\n"
                                     "Free text\n"
                                     "- [ ] Last task\n")
                        tasks (spec/parse-tasks content)]
                    (-> (expect (count tasks)) (.toBe 4))
                    (-> (expect (:checked? (nth tasks 0))) (.toBe false))
                    (-> (expect (:checked? (nth tasks 1))) (.toBe true))
                    (-> (expect (:checked? (nth tasks 2))) (.toBe true))
                    (-> (expect (:checked? (nth tasks 3))) (.toBe false))
                    (-> (expect (:text (nth tasks 0))) (.toBe "First task"))
                    ;; line-idx must reflect original document position
                    (-> (expect (:line-idx (nth tasks 0))) (.toBe 2)))))

            (it "asterisk and numbered list checkboxes work"
                (fn []
                  (let [content (str "* [ ] asterisk task\n"
                                     "1. [ ] first numbered\n"
                                     "10. [x] tenth numbered done\n")
                        tasks (spec/parse-tasks content)]
                    (-> (expect (count tasks)) (.toBe 3))
                    (-> (expect (:checked? (nth tasks 2))) (.toBe true)))))

            (it "preserves [P] parallelizable marker in :text (spec-kit convention)"
                (fn []
                  (let [tasks (spec/parse-tasks "- [ ] [P] do thing in parallel")]
                    (-> (expect (.includes (:text (first tasks)) "[P]")) (.toBe true)))))))

;; ── next-open-task ──────────────────────────────────────────────

(describe "spec/next-open-task"
          (fn []
            (it "returns first unchecked task"
                (fn []
                  (let [tasks (spec/parse-tasks
                               (str "- [x] done\n- [ ] first open\n- [ ] second open\n"))
                        n (spec/next-open-task tasks)]
                    (-> (expect (:text n)) (.toBe "first open")))))

            (it "returns nil when all done"
                (fn []
                  (let [tasks (spec/parse-tasks "- [x] a\n- [x] b\n")]
                    (-> (expect (spec/next-open-task tasks)) (.toBeNil)))))))

;; ── find-task ───────────────────────────────────────────────────

(describe "spec/find-task"
          (fn []
            (it "case-insensitive substring match"
                (fn []
                  (let [tasks (spec/parse-tasks
                               (str "- [ ] Implement OAuth flow\n"
                                    "- [ ] Add unit tests\n"))
                        m (spec/find-task tasks "oauth")]
                    (-> (expect (:text m)) (.toBe "Implement OAuth flow")))))

            (it "no match → nil"
                (fn []
                  (let [tasks (spec/parse-tasks "- [ ] thing\n")]
                    (-> (expect (spec/find-task tasks "nope")) (.toBeNil)))))))

;; ── mark-task-done ──────────────────────────────────────────────

(describe "spec/mark-task-done"
          (fn []
            (it "flips [ ] to [x] at the right line"
                (fn []
                  (let [content (str "# Tasks\n"
                                     "\n"
                                     "- [ ] First\n"
                                     "- [ ] Second\n")
                        updated (spec/mark-task-done content 2)]
                    (-> (expect (.includes updated "[x] First")) (.toBe true))
                    (-> (expect (.includes updated "[ ] Second")) (.toBe true)))))

            (it "leaves other lines untouched"
                (fn []
                  (let [content "- [ ] One\n# Heading\n- [ ] Two\n"
                        updated (spec/mark-task-done content 0)]
                    (-> (expect (.includes updated "# Heading")) (.toBe true))
                    (-> (expect (.includes updated "[ ] Two")) (.toBe true)))))

            (it "does not double-flip an already-done task"
                (fn []
                  ;; The function targets `[ ]` only; a `[x]` at the same line
                  ;; passes through unchanged. (Trailing-newline preservation
                  ;; is a separate concern; we just assert the checkbox state
                  ;; was not toggled to e.g. `[xx]`.)
                  (let [content "- [x] Already done\n"
                        updated (spec/mark-task-done content 0)]
                    (-> (expect (.includes updated "[x] Already done")) (.toBe true))
                    (-> (expect (.includes updated "[xx]")) (.toBe false)))))

            (it "preserves trailing newline (POSIX text-file convention)"
                (fn []
                  ;; Regression: str/split-lines drops the trailing \n;
                  ;; naive str/join shrank tasks.md by 1 byte every call.
                  (let [content "- [ ] First\n- [ ] Second\n"
                        updated (spec/mark-task-done content 0)]
                    (-> (expect (.endsWith updated "\n")) (.toBe true)))))

            (it "doesn't add a trailing newline when the input had none"
                (fn []
                  (let [content "- [ ] First\n- [ ] Second"  ; no trailing \n
                        updated (spec/mark-task-done content 0)]
                    (-> (expect (.endsWith updated "\n")) (.toBe false)))))))

;; ── discover-specs ──────────────────────────────────────────────

(describe "spec/discover-specs"
          (fn []
            (it "no specs anywhere → empty"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (-> (expect (count (spec/discover-specs tmp))) (.toBe 0))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "finds Kiro shape"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (write-spec tmp :kiro "auth-flow" {:req "user stories" :design "arch"
                                                         :tasks "- [ ] step 1\n- [x] step 2\n"})
                      (let [m (spec/discover-specs tmp)
                            s (get m "auth-flow")]
                        (-> (expect (some? s)) (.toBe true))
                        (-> (expect (:source s)) (.toBe :kiro))
                        (-> (expect (.endsWith (:tasks s) "tasks.md")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "finds spec-kit shape"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (write-spec tmp :spec-kit "billing" {:req "spec content" :design "plan content"
                                                           :tasks "- [ ] task one\n"})
                      (let [m (spec/discover-specs tmp)
                            s (get m "billing")]
                        (-> (expect (some? s)) (.toBe true))
                        (-> (expect (:source s)) (.toBe :spec-kit)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "default settings: spec-kit wins on name collision"
                (fn []
                  ;; Reflects the May 2026 ecosystem default — spec-kit
                  ;; is the cross-agent interop format. Users in Kiro
                  ;; shops can override via .nyma/settings.json#spec.
                  (let [tmp (mktmp)]
                    (try
                      (write-spec tmp :spec-kit "shared" {:req "spec-kit version" :design "" :tasks ""})
                      (write-spec tmp :kiro "shared" {:req "kiro version" :design "" :tasks ""})
                      (let [m (spec/discover-specs tmp)
                            s (get m "shared")]
                        (-> (expect (:source s)) (.toBe :spec-kit)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "both shapes coexist for distinct names"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (write-spec tmp :kiro "feature-a" {:tasks "- [ ] x\n"})
                      (write-spec tmp :spec-kit "feature-b" {:tasks "- [ ] y\n"})
                      (let [m (spec/discover-specs tmp)]
                        (-> (expect (count m)) (.toBe 2))
                        (-> (expect (:source (get m "feature-a"))) (.toBe :kiro))
                        (-> (expect (:source (get m "feature-b"))) (.toBe :spec-kit)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

;; ── valid-spec-name? ───────────────────────────────────────────

(describe "spec/valid-spec-name?"
          (fn []
            (it "accepts simple lowercase names"
                (fn []
                  (-> (expect (spec/valid-spec-name? "auth-flow")) (.toBe true))
                  (-> (expect (spec/valid-spec-name? "billing")) (.toBe true))
                  (-> (expect (spec/valid-spec-name? "x")) (.toBe true))
                  (-> (expect (spec/valid-spec-name? "v2")) (.toBe true))))

            (it "rejects path traversal / slashes"
                (fn []
                  (-> (expect (spec/valid-spec-name? "../escape")) (.toBe false))
                  (-> (expect (spec/valid-spec-name? "a/b")) (.toBe false))
                  (-> (expect (spec/valid-spec-name? "..")) (.toBe false))))

            (it "rejects uppercase"
                (fn []
                  (-> (expect (spec/valid-spec-name? "AuthFlow")) (.toBe false))
                  (-> (expect (spec/valid-spec-name? "Auth-flow")) (.toBe false))))

            (it "rejects leading/trailing/consecutive hyphens"
                (fn []
                  (-> (expect (spec/valid-spec-name? "-auth")) (.toBe false))
                  (-> (expect (spec/valid-spec-name? "auth-")) (.toBe false))
                  (-> (expect (spec/valid-spec-name? "auth--flow")) (.toBe false))))

            (it "rejects empty / non-string"
                (fn []
                  (-> (expect (spec/valid-spec-name? "")) (.toBe false))
                  (-> (expect (spec/valid-spec-name? nil)) (.toBe false))))))

;; ── create-spec! ───────────────────────────────────────────────

(describe "spec/create-spec! — default shape (spec-kit)"
          (fn []
            (it "creates spec-kit files when no shape specified"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/create-spec! tmp "auth-flow" nil)]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (:source r)) (.toBe :spec-kit))
                        (let [base (path/join tmp ".specify/specs/auth-flow")]
                          (-> (expect (fs/existsSync (path/join base "spec.md"))) (.toBe true))
                          (-> (expect (fs/existsSync (path/join base "plan.md"))) (.toBe true))
                          (-> (expect (fs/existsSync (path/join base "tasks.md"))) (.toBe true))
                          (let [req (fs/readFileSync (path/join base "spec.md") "utf8")]
                            (-> (expect (.includes req "auth-flow")) (.toBe true)))))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "rejects invalid names"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/create-spec! tmp "../escape" nil)]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "Invalid spec name")) (.toBe true))
                        (-> (expect (fs/existsSync (path/join tmp ".specify"))) (.toBe false)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "refuses to clobber an existing spec"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (write-spec tmp :spec-kit "existing" {:tasks "- [ ] x\n"})
                      (let [r (spec/create-spec! tmp "existing" nil)]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "already exists")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "discoverable immediately after creation"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "fresh" nil)
                      (let [m (spec/discover-specs tmp)]
                        (-> (expect (some? (get m "fresh"))) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/create-spec! — explicit kiro shape"
          (fn []
            (it "creates kiro files with shape-name=\"kiro\""
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/create-spec! tmp "billing" "kiro")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (:source r)) (.toBe :kiro))
                        (let [base (path/join tmp ".kiro/specs/billing")]
                          (-> (expect (fs/existsSync (path/join base "requirements.md"))) (.toBe true))
                          (-> (expect (fs/existsSync (path/join base "design.md"))) (.toBe true))
                          (-> (expect (fs/existsSync (path/join base "tasks.md"))) (.toBe true))))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "creates spec-kit files with shape-name=\"spec-kit\""
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/create-spec! tmp "billing" "spec-kit")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (:source r)) (.toBe :spec-kit)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/parse-shape-flag"
          (fn []
            (it "--kiro → \"kiro\""
                (fn []
                  (-> (expect (spec/parse-shape-flag ["--kiro"])) (.toBe "kiro"))
                  (-> (expect (spec/parse-shape-flag ["--shape=kiro"])) (.toBe "kiro"))))

            (it "--spec-kit → \"spec-kit\""
                (fn []
                  (-> (expect (spec/parse-shape-flag ["--spec-kit"])) (.toBe "spec-kit"))
                  (-> (expect (spec/parse-shape-flag ["--shape=spec-kit"])) (.toBe "spec-kit"))))

            (it "no flags → nil"
                (fn []
                  (-> (expect (spec/parse-shape-flag [])) (.toBeNil))
                  (-> (expect (spec/parse-shape-flag ["--other"])) (.toBeNil))))))

(describe "spec/read-spec-settings"
          (fn []
            (it "missing settings → defaults (spec-kit-first)"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [s (spec/read-spec-settings tmp)]
                        (-> (expect (:default-shape s)) (.toBe "spec-kit"))
                        (-> (expect (last (:shape-precedence s))) (.toBe "spec-kit")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "user can flip default to kiro via settings.json"
                (fn []
                  (let [tmp (mktmp)
                        nyma-dir (path/join tmp ".nyma")]
                    (try
                      (fs/mkdirSync nyma-dir #js {:recursive true})
                      (fs/writeFileSync
                       (path/join nyma-dir "settings.json")
                       (js/JSON.stringify
                        #js {:spec #js {:default-shape    "kiro"
                                        :shape-precedence #js ["spec-kit" "kiro"]}}))
                      (let [s (spec/read-spec-settings tmp)]
                        (-> (expect (:default-shape s)) (.toBe "kiro"))
                        (-> (expect (last (:shape-precedence s))) (.toBe "kiro")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/discover-specs — collision precedence"
          (fn []
            (it "default: spec-kit wins on collision"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (write-spec tmp :spec-kit "shared" {:req "spec-kit version" :design "" :tasks ""})
                      (write-spec tmp :kiro "shared" {:req "kiro version" :design "" :tasks ""})
                      (let [m (spec/discover-specs tmp)
                            s (get m "shared")]
                        (-> (expect (:source s)) (.toBe :spec-kit)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

;; ── Optional artifacts (data-model.md, quickstart.md, research.md, contracts/) ─

(describe "spec/discover-specs — optional artifacts"
          (fn []
            (it "spec record carries optional file paths when present"
                (fn []
                  (let [tmp (mktmp)
                        d (write-spec tmp :spec-kit "auth" {:req "x" :design "y" :tasks "- [ ] z\n"})]
                    (try
                      ;; Drop optional files alongside the canonical three.
                      (fs/writeFileSync (path/join d "data-model.md") "# data\n")
                      (fs/writeFileSync (path/join d "quickstart.md") "# quickstart\n")
                      (let [m (spec/discover-specs tmp)
                            s (get m "auth")]
                        (-> (expect (count (:optionals s))) (.toBe 2))
                        (-> (expect (some #(.endsWith % "data-model.md") (:optionals s)))
                            (.toBe true))
                        ;; research.md missing → not present
                        (-> (expect (some #(.endsWith % "research.md") (:optionals s)))
                            (.toBeFalsy)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "optionals list is empty when none present"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-spec tmp :spec-kit "bare" {:req "x" :design "y" :tasks ""})]
                    (try
                      (let [s (get (spec/discover-specs tmp) "bare")]
                        (-> (expect (count (:optionals s))) (.toBe 0)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "optional contracts/ directory surfaces files"
                (fn []
                  (let [tmp (mktmp)
                        d (write-spec tmp :spec-kit "billing" {:req "x" :design "y" :tasks ""})]
                    (try
                      (fs/mkdirSync (path/join d "contracts") #js {:recursive true})
                      (fs/writeFileSync (path/join d "contracts/api-spec.json")
                                        "{\"openapi\":\"3.0.0\"}")
                      (fs/writeFileSync (path/join d "contracts/signalr-spec.md") "# notes\n")
                      (let [s (get (spec/discover-specs tmp) "billing")
                            dirs (:optional-dirs s)]
                        (-> (expect (count dirs)) (.toBe 1))
                        (-> (expect (:name (first dirs))) (.toBe "contracts"))
                        (-> (expect (count (:files (first dirs)))) (.toBe 2)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "kiro shape has no optional files (Kiro doesn't define them)"
                (fn []
                  (let [tmp (mktmp)
                        d (write-spec tmp :kiro "k1" {:req "x" :design "y" :tasks ""})]
                    (try
                      ;; Even if these files exist, Kiro's shape config doesn't
                      ;; list them, so they shouldn't get attached.
                      (fs/writeFileSync (path/join d "data-model.md") "# data\n")
                      (let [s (get (spec/discover-specs tmp) "k1")]
                        (-> (expect (count (:optionals s))) (.toBe 0)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/build-spec-context — optional artifacts inclusion"
          (fn []
            (it "inlines all optional artifacts when present"
                (fn []
                  (let [tmp (mktmp)
                        d (write-spec tmp :spec-kit "x" {:req "REQ-CONTENT"
                                                         :design "PLAN-CONTENT"
                                                         :tasks "- [ ] task one\n"})]
                    (try
                      (fs/writeFileSync (path/join d "data-model.md")  "# DATA-MODEL-CONTENT\n")
                      (fs/writeFileSync (path/join d "quickstart.md") "# QUICKSTART-CONTENT\n")
                      (fs/mkdirSync (path/join d "contracts") #js {:recursive true})
                      (fs/writeFileSync (path/join d "contracts/api.md") "API-CONTENT\n")
                      (let [s (get (spec/discover-specs tmp) "x")
                            ctx (spec/build-spec-context tmp s)]
                        (-> (expect (.includes ctx "REQ-CONTENT")) (.toBe true))
                        (-> (expect (.includes ctx "PLAN-CONTENT")) (.toBe true))
                        (-> (expect (.includes ctx "DATA-MODEL-CONTENT")) (.toBe true))
                        (-> (expect (.includes ctx "QUICKSTART-CONTENT")) (.toBe true))
                        (-> (expect (.includes ctx "API-CONTENT")) (.toBe true))
                        (-> (expect (.includes ctx "Supporting artifacts")) (.toBe true))
                        (-> (expect (.includes ctx "Contracts & references")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "skips empty sections when no optional artifacts"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-spec tmp :spec-kit "y" {:req "REQ" :design "PLAN" :tasks "- [ ] t\n"})]
                    (try
                      (let [s (get (spec/discover-specs tmp) "y")
                            ctx (spec/build-spec-context tmp s)]
                        (-> (expect (.includes ctx "Supporting artifacts")) (.toBe false))
                        (-> (expect (.includes ctx "Contracts & references")) (.toBe false)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "spec-kit constitution is included as project guidance"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-spec tmp :spec-kit "z" {:req "REQ" :design "PLAN" :tasks ""})]
                    (try
                      (fs/mkdirSync (path/join tmp ".specify/memory") #js {:recursive true})
                      (fs/writeFileSync (path/join tmp ".specify/memory/constitution.md")
                                        "# Project rules\n- be excellent\n")
                      (let [s (get (spec/discover-specs tmp) "z")
                            ctx (spec/build-spec-context tmp s)]
                        (-> (expect (.includes ctx "Project guidance")) (.toBe true))
                        (-> (expect (.includes ctx "be excellent")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "kiro steering files are included as project guidance"
                (fn []
                  (let [tmp (mktmp)
                        _   (write-spec tmp :kiro "k" {:req "REQ" :design "DESIGN" :tasks ""})]
                    (try
                      (fs/mkdirSync (path/join tmp ".kiro/steering") #js {:recursive true})
                      (fs/writeFileSync (path/join tmp ".kiro/steering/style.md")
                                        "# Style\nkebab-case names\n")
                      (fs/writeFileSync (path/join tmp ".kiro/steering/security.md")
                                        "# Security\nno secrets in code\n")
                      ;; Also drop a non-md file that should be ignored.
                      (fs/writeFileSync (path/join tmp ".kiro/steering/README.txt")
                                        "ignored")
                      (let [s (get (spec/discover-specs tmp) "k")
                            ctx (spec/build-spec-context tmp s)]
                        (-> (expect (.includes ctx "Project guidance")) (.toBe true))
                        (-> (expect (.includes ctx "kebab-case names")) (.toBe true))
                        (-> (expect (.includes ctx "no secrets in code")) (.toBe true))
                        (-> (expect (.includes ctx "ignored")) (.toBe false)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/build-spec-context — next-task callout (Pain 2 fix)"
          (fn []
            (it "annotates the first open task with '→ NEXT: '"
                (fn []
                  (let [tmp (mktmp)
                        _ (write-spec tmp :spec-kit "drive" {:req "R" :design "P"
                                                             :tasks "- [x] done item\n- [ ] first open\n- [ ] second\n"})
                        s (get (spec/discover-specs tmp) "drive")
                        ctx (spec/build-spec-context tmp s)]
                    (try
                      (-> (expect (.includes ctx "→ NEXT: - [ ] first open")) (.toBe true))
                      (-> (expect (.includes ctx "→ NEXT: - [ ] second")) (.toBe false))
                      (-> (expect (.includes ctx "replace `- [ ]` with `- [x]`")) (.toBe true))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "no callout when all tasks are done"
                (fn []
                  (let [tmp (mktmp)
                        _ (write-spec tmp :spec-kit "done-spec" {:req "R" :design "P"
                                                                 :tasks "- [x] only one\n"})
                        s (get (spec/discover-specs tmp) "done-spec")
                        ctx (spec/build-spec-context tmp s)]
                    (try
                      (-> (expect (.includes ctx "→ NEXT:")) (.toBe false))
                      (-> (expect (.includes ctx "replace `- [ ]`")) (.toBe false))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

;; ── Audit-driven regression coverage ───────────────────────────

(describe "spec/parse-shape-flag — conflicting flags"
          (fn []
            (it "--kiro and --spec-kit together → error map"
                (fn []
                  (let [r (spec/parse-shape-flag ["--kiro" "--spec-kit"])]
                    (-> (expect (some? (:error r))) (.toBe true)))))

            (it "--shape=kiro and --shape=spec-kit together → error map"
                (fn []
                  (let [r (spec/parse-shape-flag ["--shape=kiro" "--shape=spec-kit"])]
                    (-> (expect (some? (:error r))) (.toBe true)))))))

(describe "spec/create-spec! — unknown shape returns error map"
          (fn []
            (it "no longer throws; returns {:ok? false :error <str>}"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/create-spec! tmp "auth-flow" "kiro2")]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "Unknown spec shape")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/read-spec-settings — robustness"
          (fn []
            (it "malformed JSON → defaults"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (fs/mkdirSync (path/join tmp ".nyma") #js {:recursive true})
                      (fs/writeFileSync (path/join tmp ".nyma/settings.json")
                                        "{ this is not JSON")
                      (let [s (spec/read-spec-settings tmp)]
                        (-> (expect (:default-shape s)) (.toBe "spec-kit")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "settings.json without 'spec' key → defaults"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (fs/mkdirSync (path/join tmp ".nyma") #js {:recursive true})
                      (fs/writeFileSync (path/join tmp ".nyma/settings.json") "{}")
                      (let [s (spec/read-spec-settings tmp)]
                        (-> (expect (:default-shape s)) (.toBe "spec-kit")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "shape-precedence as a string (not array) → defaults"
                (fn []
                  ;; Defends against `(vec (js/Array.from "kiro"))` which
                  ;; would split into [\"k\" \"i\" \"r\" \"o\"] and silently
                  ;; make discover-specs no-op.
                  (let [tmp (mktmp)]
                    (try
                      (fs/mkdirSync (path/join tmp ".nyma") #js {:recursive true})
                      (fs/writeFileSync
                       (path/join tmp ".nyma/settings.json")
                       (js/JSON.stringify
                        #js {:spec #js {:shape-precedence "kiro"}}))
                      (let [s (spec/read-spec-settings tmp)]
                        (-> (expect (last (:shape-precedence s))) (.toBe "spec-kit")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "shape-precedence with unknown names → filters them out"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (fs/mkdirSync (path/join tmp ".nyma") #js {:recursive true})
                      (fs/writeFileSync
                       (path/join tmp ".nyma/settings.json")
                       (js/JSON.stringify
                        #js {:spec #js {:shape-precedence #js ["typo" "kiro"]}}))
                      (let [s (spec/read-spec-settings tmp)
                            p (:shape-precedence s)]
                        ;; "typo" dropped; only "kiro" remains
                        (-> (expect (count p)) (.toBe 1))
                        (-> (expect (first p)) (.toBe "kiro")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "shape-precedence with all unknown names → falls back to defaults"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (fs/mkdirSync (path/join tmp ".nyma") #js {:recursive true})
                      (fs/writeFileSync
                       (path/join tmp ".nyma/settings.json")
                       (js/JSON.stringify
                        #js {:spec #js {:shape-precedence #js ["typo1" "typo2"]}}))
                      (let [s (spec/read-spec-settings tmp)]
                        ;; All filtered out → defaults restored.
                        (-> (expect (last (:shape-precedence s))) (.toBe "spec-kit")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

;; ── scaffold-artifact! ─────────────────────────────────────────

(describe "spec/scaffold-artifact! — per-spec optional files"
          (fn []
            (it "creates data-model.md from template"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "auth-flow" "spec-kit")
                      (let [r (spec/scaffold-artifact! tmp "auth-flow" "data-model")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (let [p (path/join tmp ".specify/specs/auth-flow/data-model.md")]
                          (-> (expect (fs/existsSync p)) (.toBe true))
                          (-> (expect (.includes (fs/readFileSync p "utf8")
                                                 "auth-flow"))
                              (.toBe true))))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "creates quickstart.md from template"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "x" "spec-kit")
                      (let [r (spec/scaffold-artifact! tmp "x" "quickstart")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (fs/existsSync
                                     (path/join tmp ".specify/specs/x/quickstart.md")))
                            (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "creates research.md from template"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "x" "spec-kit")
                      (let [r (spec/scaffold-artifact! tmp "x" "research")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (fs/existsSync
                                     (path/join tmp ".specify/specs/x/research.md")))
                            (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "creates contracts/api-spec.json AND contracts/README.md"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "x" "spec-kit")
                      (let [r (spec/scaffold-artifact! tmp "x" "contracts")
                            base (path/join tmp ".specify/specs/x/contracts")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (fs/existsSync (path/join base "api-spec.json"))) (.toBe true))
                        (-> (expect (fs/existsSync (path/join base "README.md"))) (.toBe true))
                        (-> (expect (count (:paths r))) (.toBe 2)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "refuses to clobber an existing file"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "x" "spec-kit")
                      (spec/scaffold-artifact! tmp "x" "data-model")
                      (let [r (spec/scaffold-artifact! tmp "x" "data-model")]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "Already exists")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "rejects unknown kinds"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "x" "spec-kit")
                      (let [r (spec/scaffold-artifact! tmp "x" "nonsense")]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "Unknown artifact kind"))
                            (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "errors when spec doesn't exist"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/scaffold-artifact! tmp "ghost" "data-model")]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "not found")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "rejects per-spec kinds for Kiro shape"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/create-spec! tmp "k" "kiro")
                      (let [r (spec/scaffold-artifact! tmp "k" "data-model")]
                        ;; data-model is a spec-kit-only artifact
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "not defined")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

(describe "spec/scaffold-artifact! — project-wide constitution"
          (fn []
            (it "creates .specify/memory/constitution.md without needing a spec"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (spec/scaffold-artifact! tmp nil "constitution")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (fs/existsSync
                                     (path/join tmp ".specify/memory/constitution.md")))
                            (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "refuses to clobber an existing constitution"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (spec/scaffold-artifact! tmp nil "constitution")
                      (let [r (spec/scaffold-artifact! tmp nil "constitution")]
                        (-> (expect (:ok? r)) (.toBe false)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))

;; ── import-from-dir! ───────────────────────────────────────────

(describe "spec/import-from-dir!"
          (fn []
            (it "copies recognized files, scaffolds the rest"
                (fn []
                  (let [tmp (mktmp)
                        src (path/join tmp "user-source")]
                    (try
                      (fs/mkdirSync src #js {:recursive true})
                      (fs/writeFileSync (path/join src "spec.md") "USER-SPEC")
                      (fs/writeFileSync (path/join src "data-model.md") "USER-DATA")
                      ;; tasks.md and plan.md missing → expect templates
                      (let [r (spec/import-from-dir! tmp "auth-flow" "spec-kit" src)]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (count (:copied r))) (.toBe 2))
                        ;; The scaffolded list = create-spec!'s files MINUS those copied
                        (-> (expect (count (:scaffolded r))) (.toBe 2))
                        ;; Verify content
                        (let [base (path/join tmp ".specify/specs/auth-flow")]
                          (-> (expect (fs/readFileSync (path/join base "spec.md") "utf8"))
                              (.toBe "USER-SPEC"))
                          (-> (expect (fs/readFileSync (path/join base "data-model.md") "utf8"))
                              (.toBe "USER-DATA"))
                          ;; tasks.md is the template
                          (-> (expect (.includes (fs/readFileSync (path/join base "tasks.md") "utf8")
                                                 "First task"))
                              (.toBe true))))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "translates filenames across shapes (requirements.md → spec.md)"
                (fn []
                  (let [tmp (mktmp)
                        src (path/join tmp "kiro-style-src")]
                    (try
                      (fs/mkdirSync src #js {:recursive true})
                      ;; Source uses Kiro filenames; we import as spec-kit.
                      (fs/writeFileSync (path/join src "requirements.md") "FROM-REQ")
                      (fs/writeFileSync (path/join src "design.md")       "FROM-DESIGN")
                      (let [r (spec/import-from-dir! tmp "auth" "spec-kit" src)
                            base (path/join tmp ".specify/specs/auth")]
                        (-> (expect (:ok? r)) (.toBe true))
                        ;; Should land under spec.md and plan.md (spec-kit names)
                        (-> (expect (fs/readFileSync (path/join base "spec.md") "utf8"))
                            (.toBe "FROM-REQ"))
                        (-> (expect (fs/readFileSync (path/join base "plan.md") "utf8"))
                            (.toBe "FROM-DESIGN")))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "copies contracts/ subdirectory"
                (fn []
                  (let [tmp (mktmp)
                        src (path/join tmp "src")]
                    (try
                      (fs/mkdirSync (path/join src "contracts") #js {:recursive true})
                      (fs/writeFileSync (path/join src "spec.md") "S")
                      (fs/writeFileSync (path/join src "contracts/api-spec.json") "{}")
                      (fs/writeFileSync (path/join src "contracts/notes.md") "# notes")
                      (let [r (spec/import-from-dir! tmp "x" "spec-kit" src)
                            base (path/join tmp ".specify/specs/x")]
                        (-> (expect (:ok? r)) (.toBe true))
                        (-> (expect (fs/existsSync (path/join base "contracts/api-spec.json")))
                            (.toBe true))
                        (-> (expect (fs/existsSync (path/join base "contracts/notes.md")))
                            (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "rolls back if create-spec! reports a name collision"
                (fn []
                  (let [tmp (mktmp)
                        src (path/join tmp "src")]
                    (try
                      (fs/mkdirSync src #js {:recursive true})
                      (fs/writeFileSync (path/join src "spec.md") "S")
                      (spec/create-spec! tmp "exists" "spec-kit")
                      (let [r (spec/import-from-dir! tmp "exists" "spec-kit" src)]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "already exists")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))
