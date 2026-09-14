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
            [clojure.string :as str]
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
   (let [req       (:req opts)
         design    (:design opts)
         tasks     (:tasks opts)
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

;; ── Activation-time behaviour: persistence + agent-driven task hooks ──
;;
;; Neither surface had any coverage. Both are prerequisites for a phase
;; loop: it must survive a restart, and it must be able to see the agent
;; completing a task rather than only the user typing /spec done.

(defn- fake-api [state-atom sink handlers cwd]
  #js {:getState      (fn [] @state-atom)
       :__state_atom  state-atom
       ;; Stand-ins for model_roles (owns :active-role) and the store.
       :emitGlobal    (fn [ev d] (when (= "role_change" (str ev))
                                   (swap! state-atom assoc :active-role (str (.-role d)))))
       :dispatchState (fn [t _] (when (= "messages-cleared" (str t))
                                  (swap! state-atom assoc :messages [])))
       :state         (let [store (atom {})]
                        #js {:get    (fn [k] (get @store k))
                             :set    (fn [k v] (swap! store assoc k v))
                             :delete (fn [k] (swap! store dissoc k))
                             :keys   (fn [] (clj->js (vec (keys @store))))
                             :clear  (fn [] (reset! store {}))})
       :events        #js {:emit (fn [n d] (swap! sink conj [n d]))}
       :on            (fn [e h] (swap! handlers update e (fnil conj []) h))
       :off           (fn [_e _h] nil)
       :registerCommand   (fn [_n _c] nil)
       :unregisterCommand (fn [_n] nil)
       :sendUserMessage   (fn [_m _o] nil)
       :ui            #js {:notify (fn [_m] nil)}
       :__cwd         cwd})

(defn- seed-spec! [dir]
  (let [d (path/join dir ".specify" "specs" "auth")]
    (fs/mkdirSync d #js {:recursive true})
    (fs/writeFileSync (path/join d "spec.md")  "# Spec\nauth\n")
    (fs/writeFileSync (path/join d "plan.md")  "# Plan\nplan\n")
    (fs/writeFileSync (path/join d "tasks.md") "# Tasks\n- [ ] first task\n- [ ] second task\n")
    (path/join d "tasks.md")))

(describe "spec-driven activation" (fn []

  (it "emits spec_task_complete when the AGENT edits tasks.md, not just /spec done"
      (fn []
        (let [tmp   (mktmp)
              tasks (seed-spec! tmp)
              prev  (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [st (atom {:active-spec "auth"}) sink (atom []) hs (atom {})
                  off ((.-default spec) (fake-api st sink hs tmp))
                  fire (fn [ev]
                         (doseq [h (get @hs ev)] (h #js {:toolName "edit"
                                                         :args #js {:path tasks}} #js {})))]
              (fire "tool_execution_start")
              (fs/writeFileSync tasks "# Tasks\n- [x] first task\n- [ ] second task\n")
              (fire "tool_execution_end")
              (let [emitted (filterv (fn [[n _]] (= n "spec_task_complete")) @sink)]
                (-> (expect (count emitted)) (.toBe 1))
                (-> (expect (.-task (second (first emitted)))) (.toBe "first task"))
                (-> (expect (.-source (second (first emitted)))) (.toBe "agent")))
              (when off (off)))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))

  (it "matches the tasks file whether the tool passes a relative or absolute path"
      (fn []
        ;; discover-specs builds absolute paths from process.cwd; the edit tool
        ;; is routinely handed a relative one. Comparing raw strings never matched.
        (let [tmp  (mktmp)
              _    (seed-spec! tmp)
              prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [rel  ".specify/specs/auth/tasks.md"
                  st (atom {:active-spec "auth"}) sink (atom []) hs (atom {})
                  off ((.-default spec) (fake-api st sink hs tmp))
                  fire (fn [ev] (doseq [h (get @hs ev)]
                                  (h #js {:toolName "edit" :args #js {:path rel}} #js {})))]
              (fire "tool_execution_start")
              (fs/writeFileSync rel "# Tasks\n- [x] first task\n- [x] second task\n")
              (fire "tool_execution_end")
              (-> (expect (count (filterv (fn [[n _]] (= n "spec_task_complete")) @sink)))
                  (.toBe 2))
              (when off (off)))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))

  (it "restores active-spec from persistent state on a later activation"
      (fn []
        (let [tmp  (mktmp) _ (seed-spec! tmp) prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [shared (atom {}) sink (atom []) hs (atom {})
                  api-of (fn [st]
                           (doto (fake-api st sink hs tmp)
                             (aset "state" #js {:get    (fn [k] (get @shared k))
                                                :set    (fn [k v] (swap! shared assoc k v))
                                                :delete (fn [k] (swap! shared dissoc k))
                                                :keys   (fn [] (clj->js (vec (keys @shared))))
                                                :clear  (fn [] (reset! shared {}))})))]
              (swap! shared assoc "active-spec" "auth")
              (let [st2 (atom {}) off ((.-default spec) (api-of st2))]
                (-> (expect (:active-spec @st2)) (.toBe "auth"))
                (when off (off))))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))

  (it "does not resurrect a spec that was deleted between sessions"
      (fn []
        (let [tmp (mktmp) prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [shared (atom {"active-spec" "ghost"}) sink (atom []) hs (atom {})
                  api (doto (fake-api (atom {}) sink hs tmp)
                        (aset "state" #js {:get    (fn [k] (get @shared k))
                                           :set    (fn [k v] (swap! shared assoc k v))
                                           :delete (fn [k] (swap! shared dissoc k))
                                           :keys   (fn [] (clj->js (vec (keys @shared))))
                                           :clear  (fn [] (reset! shared {}))}))
                  st  (atom {})]
              (aset api "getState" (fn [] @st))
              (aset api "__state_atom" st)
              (let [off ((.-default spec) api)]
                (-> (expect (:active-spec @st)) (.toBeUndefined))
                (-> (expect (get @shared "active-spec")) (.toBeUndefined))
                (when off (off))))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))))

;; ── /spec phase and /spec profile ────────────────────────────────
;;
;; The dispatcher had no test coverage at all. These bind a role, which is the
;; whole point: a phase IS a role (model + allowed-tools + permissions).

(defn- cmd-harness []
  (let [notes (atom []) st (atom {}) shared (atom {}) cmd (atom nil)
        api #js {:getState     (fn [] @st)
                 :__state_atom st
                 :getSettings  (fn [] #js {:roles #js {:fast #js {} :deep #js {}
                                                        :advisor #js {} :commit #js {}}})
                 :settings (fn [sec] (let [all #js {:roles #js {:fast #js {} :deep #js {}
                                                        :advisor #js {} :commit #js {}}}] (if sec (or (get all sec) {}) (or all {}))))
                 ;; Stand-ins for model_roles (owns :active-role) and the store.
                 :emitGlobal    (fn [ev d] (when (= "role_change" (str ev))
                                             (swap! st assoc :active-role (str (.-role d)))))
                 :dispatchState (fn [t _] (when (= "messages-cleared" (str t))
                                            (swap! st assoc :messages [])))
                 :state        #js {:get    (fn [k] (get @shared k))
                                    :set    (fn [k v] (swap! shared assoc k v))
                                    :delete (fn [k] (swap! shared dissoc k))
                                    :keys   (fn [] (clj->js (vec (keys @shared))))
                                    :clear  (fn [] (reset! shared {}))}
                 :events       #js {:emit (fn [_n _d] nil)}
                 :on           (fn [_e _h] nil)
                 :off          (fn [_e _h] nil)
                 :registerCommand   (fn [_n c] (reset! cmd (.-handler c)))
                 :unregisterCommand (fn [_n] nil)
                 :sendUserMessage   (fn [_m _o] nil)
                 :ui           #js {:notify (fn [m] (swap! notes conj m))}}
        off ((.-default spec) api)]
    {:run (fn [& args] (reset! notes []) (@cmd (vec args)
                                               #js {:ui #js {:notify (fn [m] (swap! notes conj m))}})
            (str/join "\n" @notes))
     :state st :off off}))

(describe "/spec phase + /spec profile" (fn []

  (it "binds :active-role when a phase is set, and re-binds on profile switch"
      (fn []
        (let [tmp (mktmp) _ (seed-spec! tmp) prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [{:keys [run state off]} (cmd-harness)]
              (run "start" "auth" "--force")
              ;; routed: execute → build
              (run "phase" "execute")
              (-> (expect (:active-role @state)) (.toBe "fast"))
              ;; thrifty: execute → default. Switching re-binds immediately,
              ;; not only at the next transition.
              (run "profile" "thrifty")
              (-> (expect (:active-role @state)) (.toBe "default"))
              (when off (off)))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))

  (it "rejects an unknown phase and an unknown profile without changing state"
      (fn []
        (let [tmp (mktmp) _ (seed-spec! tmp) prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [{:keys [run state off]} (cmd-harness)]
              (run "start" "auth" "--force")
              (run "phase" "execute")
              (let [before (:active-role @state)
                    out    (run "phase" "bogus")]
                (-> (expect (.includes out "Unknown phase")) (.toBe true))
                (-> (expect (:active-role @state)) (.toBe before)))
              (-> (expect (.includes (run "profile" "nope") "Unknown profile")) (.toBe true))
              (when off (off)))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))

  (it "refuses phase commands without an active spec"
      (fn []
        (let [tmp (mktmp) _ (seed-spec! tmp) prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [{:keys [run off]} (cmd-harness)]
              (-> (expect (.includes (run "phase") "No active spec")) (.toBe true))
              (when off (off)))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))

  (it "persists phase across activations and clears it on /spec end"
      (fn []
        (let [tmp (mktmp) _ (seed-spec! tmp) prev (js/process.cwd)]
          (try
            (.chdir js/process tmp)
            (let [{:keys [run state off]} (cmd-harness)]
              (run "start" "auth" "--force")
              (run "phase" "verify")
              (-> (expect (:spec-phase @state)) (.toBe "verify"))
              (run "end")
              (-> (expect (:spec-phase @state)) (.toBeUndefined))
              (when off (off)))
            (finally
              (.chdir js/process prev)
              (try (fs/rmSync tmp #js {:recursive true :force true}) (catch :default _ nil)))))))))

;; ── /spec run: the phase loop ────────────────────────────────────
;;
;; `on-agent-end` had no coverage at all before this. Each case is one of the
;; guards that keeps a broken or runaway run from looking like a finished one.

(defn- loop-harness [& [settings]]
  (let [notes (atom []) sent (atom []) st (atom {:messages ["m1" "m2" "m3"]}) shared (atom {})
        hs (atom {}) cmd (atom nil)
        api #js {:getState     (fn [] @st)
                 :__state_atom st
                 :getSettings  (fn [] (or settings
                                          #js {:roles #js {:fast #js {} :deep #js {}
                                                           :advisor #js {} :commit #js {}}}))
                 :settings (fn [sec] (let [all (or settings
                                          #js {:roles #js {:fast #js {} :deep #js {}
                                                           :advisor #js {} :commit #js {}}})] (if sec (or (get all sec) {}) (or all {}))))
                 ;; Stand-ins for model_roles (owns :active-role) and the store.
                 :emitGlobal    (fn [ev d] (when (= "role_change" (str ev))
                                             (swap! st assoc :active-role (str (.-role d)))))
                 :dispatchState (fn [t _] (when (= "messages-cleared" (str t))
                                            (swap! st assoc :messages [])))
                 :state        #js {:get    (fn [k] (get @shared k))
                                    :set    (fn [k v] (swap! shared assoc k v))
                                    :delete (fn [k] (swap! shared dissoc k))
                                    :keys   (fn [] (clj->js (vec (keys @shared))))
                                    :clear  (fn [] (reset! shared {}))}
                 :events       #js {:emit (fn [_n _d] nil)}
                 :on           (fn [e h] (swap! hs update e (fnil conj []) h))
                 :off          (fn [_e _h] nil)
                 :registerCommand   (fn [_n c] (reset! cmd (.-handler c)))
                 :unregisterCommand (fn [_n] nil)
                 :sendUserMessage   (fn [m _o] (swap! sent conj m))
                 :ui           #js {:notify (fn [m & _] (swap! notes conj m))}}
        off ((.-default spec) api)]
    {:run  (fn [& args] (reset! notes [])
             (@cmd (vec args) #js {:ui #js {:notify (fn [m & _] (swap! notes conj m))}})
             (str/join "\n" @notes))
     :end  (fn [] (reset! sent []) (reset! notes [])
             (doseq [h (get @hs "agent_end")] (h #js {:finishReason "stop"}))
             {:sent (count @sent) :notes (str/join " " @notes) :last (last @sent)})
     :fire (fn [ev] (doseq [h (get @hs ev)] (h #js {})))
     :state st :off off}))

(defn- with-tasks [body]
  (let [tmp (mktmp) tasks (seed-spec! tmp) prev (js/process.cwd)]
    (try (.chdir js/process tmp) (body tasks)
         (finally (.chdir js/process prev)
                  (try (fs/rmSync tmp #js {:recursive true :force true})
                       (catch :default _ nil))))))

(describe "/spec run — phase loop" (fn []

  (it "does nothing until armed, then continues while tasks remain"
      (fn []
        (with-tasks
          (fn [_tasks]
            (let [{:keys [run end off]} (loop-harness)]
              (run "start" "auth" "--force")
              (-> (expect (:sent (end))) (.toBe 0))       ; never self-arms
              (run "run")
              (-> (expect (:sent (end))) (.toBe 1))
              (when off (off)))))))

  (it "sends a BYTE-IDENTICAL follow-up each iteration"
      (fn []
        ;; Measured: an identical prompt keeps the provider cache (3648/3678
        ;; tokens); varying it drops to zero and costs ~2x a cold call. The task
        ;; comes from the file, never from this string.
        (with-tasks
          (fn [_tasks]
            (let [{:keys [run end off]} (loop-harness)]
              (run "start" "auth" "--force") (run "run")
              (let [a (:last (end)) b (:last (end)) c (:last (end))]
                (-> (expect a) (.toBe b))
                (-> (expect b) (.toBe c)))
              (when off (off)))))))

  (it "advances the phase and rebinds the role when every task is checked"
      (fn []
        (with-tasks
          (fn [tasks]
            (let [{:keys [run end state off]} (loop-harness)]
              (run "start" "auth" "--force") (run "run")
              (fs/writeFileSync tasks "# Tasks\n- [x] first task\n- [x] second task\n")
              (let [r (end)]
                (-> (expect (.includes (:notes r) "execute")) (.toBe true))
                (-> (expect (:spec-phase @state)) (.toBe "execute"))
                (-> (expect (:active-role @state)) (.toBe "fast")))
              (when off (off)))))))

  (it "holds while verify is red, then resumes when it goes green"
      (fn []
        ;; Holding rather than stopping is the point: disarming on a red build
        ;; would mean the loop never resumes once verify_gate fixes it.
        (with-tasks
          (fn [tasks]
            (let [{:keys [run end fire state off]} (loop-harness)]
              (run "start" "auth" "--force") (run "run")
              (fs/writeFileSync tasks "# Tasks\n- [x] first task\n- [x] second task\n")
              (fire "small-model/verify-fail")
              (-> (expect (:sent (end))) (.toBe 0))
              (-> (expect (:spec-loop-armed @state)) (.toBe true))   ; still armed
              (fire "small-model/verify-pass")
              (-> (expect (:sent (end))) (.toBe 1))
              (when off (off)))))))

  (it "refuses to treat a tasks file with no checkboxes as complete"
      (fn []
        (with-tasks
          (fn [tasks]
            (let [{:keys [run end off]} (loop-harness)]
              (run "start" "auth" "--force") (run "run")
              (fs/writeFileSync tasks "# Tasks\njust prose now\n")
              (let [r (end)]
                (-> (expect (:sent r)) (.toBe 0))
                (-> (expect (.includes (:notes r) "refusing")) (.toBe true)))
              (when off (off)))))))

  (it "/spec run off disarms mid-flight"
      (fn []
        (with-tasks
          (fn [_tasks]
            (let [{:keys [run end off]} (loop-harness)]
              (run "start" "auth" "--force") (run "run")
              (-> (expect (:sent (end))) (.toBe 1))
              (run "run" "off")
              (-> (expect (:sent (end))) (.toBe 0))
              (when off (off)))))))

  (it "stops at the iteration cap instead of looping forever"
      (fn []
        ;; The follow-queue drain upstream is an unbounded recur
        ;; (loop.cljs:586-594) — this cap is the only thing that applies.
        (with-tasks
          (fn [_tasks]
            (let [{:keys [run end off]}
                  (loop-harness #js {:roles #js {:fast #js {} :advisor #js {}}
                                     :spec  #js {:loop #js {:max-iterations 2}}})]
              (run "start" "auth" "--force") (run "run")
              (-> (expect (:sent (end))) (.toBe 1))
              (-> (expect (:sent (end))) (.toBe 1))
              (let [r (end)]                                  ; 3rd: cap reached
                (-> (expect (:sent r)) (.toBe 0))
                (-> (expect (.includes (:notes r) "cap")) (.toBe true)))
              (when off (off)))))))))

;; ── fresh-context (the Ralph reset) ──────────────────────────────

(describe "/spec run --fresh" (fn []

  (it "preserves the conversation by default"
      (fn []
        ;; OFF by default on purpose: clearing discards anything the user said
        ;; in chat, and Ralph's premise (all intent lives in the files) breaks
        ;; the moment someone adds an instruction in conversation.
        (with-tasks
          (fn [_t]
            (let [{:keys [run end state off]} (loop-harness)]
              (run "start" "auth" "--force") (run "run")
              (end)
              (-> (expect (count (:messages @state))) (.toBe 3))
              (when off (off)))))))

  (it "clears the conversation between iterations when asked, and says so"
      (fn []
        (with-tasks
          (fn [_t]
            (let [{:keys [run end state off]} (loop-harness)]
              (run "start" "auth" "--force")
              (let [armed (run "run" "--fresh")]
                (-> (expect (.includes armed "CLEARED")) (.toBe true)))
              (let [r (end)]
                (-> (expect (:sent r)) (.toBe 1))          ; still drives the loop
                (-> (expect (count (:messages @state))) (.toBe 0)))
              (when off (off)))))))

  (it "reads fresh-context from settings when no flag is given"
      (fn []
        (with-tasks
          (fn [_t]
            (let [{:keys [run end state off]}
                  (loop-harness #js {:roles #js {:fast #js {} :advisor #js {}}
                                     :spec  #js {:loop #js {:fresh-context true}}})]
              (run "start" "auth" "--force") (run "run")
              (end)
              (-> (expect (count (:messages @state))) (.toBe 0))
              (when off (off)))))))

  (it "--no-fresh overrides a settings default of true"
      (fn []
        (with-tasks
          (fn [_t]
            (let [{:keys [run end state off]}
                  (loop-harness #js {:roles #js {:fast #js {} :advisor #js {}}
                                     :spec  #js {:loop #js {:fresh-context true}}})]
              (run "start" "auth" "--force") (run "run" "--no-fresh")
              (end)
              (-> (expect (count (:messages @state))) (.toBe 3))
              (when off (off)))))))))

;;; ─── /spec import: newest artifact + --run ─────────────────────────────────

(describe "spec-driven/newest-plan-artifact" (fn []

  (it "returns the most recent .md in .nyma/plans"
      (fn []
        ;; So `/spec import <name>` can be typed without hand-copying a
        ;; timestamped filename that a machine generated.
        (let [tmp (mktmp) dir (path/join tmp ".nyma" "plans")]
          (try
            (fs/mkdirSync dir #js {:recursive true})
            (fs/writeFileSync (path/join dir "plan-2026-01-01T00-00-00-000Z.md") "old")
            (fs/writeFileSync (path/join dir "plan-2026-09-04T00-00-00-000Z.md") "new")
            ;; mtime decides, so make the intended winner newest
            (let [now (js/Date.)]
              (fs/utimesSync (path/join dir "plan-2026-09-04T00-00-00-000Z.md") now now))
            (-> (expect (.endsWith (spec/newest-plan-artifact tmp)
                                   "plan-2026-09-04T00-00-00-000Z.md"))
                (.toBe true))
            (finally (try (fs/rmSync tmp #js {:recursive true :force true})
                          (catch :default _ nil)))))))

  (it "ignores non-markdown files"
      (fn []
        (let [tmp (mktmp) dir (path/join tmp ".nyma" "plans")]
          (try
            (fs/mkdirSync dir #js {:recursive true})
            (fs/writeFileSync (path/join dir "notes.txt") "x")
            (-> (expect (spec/newest-plan-artifact tmp)) (.toBeNil))
            (finally (try (fs/rmSync tmp #js {:recursive true :force true})
                          (catch :default _ nil)))))))

  (it "is nil when the directory does not exist"
      (fn []
        (let [tmp (mktmp)]
          (try (-> (expect (spec/newest-plan-artifact tmp)) (.toBeNil))
               (finally (try (fs/rmSync tmp #js {:recursive true :force true})
                             (catch :default _ nil)))))))))
