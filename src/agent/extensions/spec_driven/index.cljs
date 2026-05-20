(ns agent.extensions.spec-driven
  "Spec-driven development support — surfaces durable plans from
   `.specify/specs/<feat>/{spec,plan,tasks}.md` (GitHub spec-kit shape,
   default) and `.kiro/specs/<feat>/{requirements,design,tasks}.md`
   (Kiro shape) as in-context structured plans the agent can read and
   advance.

   Both shapes are recognized at all times. The default for new specs
   and the winner-on-name-collision are settings-driven via
   `.nyma/settings.json#spec.{default-shape,shape-precedence}`.
   Out-of-the-box defaults match the May 2026 ecosystem signal where
   spec-kit is the cross-agent interop format.

   Slash commands:
     /spec list                       — list specs in repo with progress
     /spec new <feat> [--kiro]        — scaffold a new spec (default shape,
                                        or --kiro / --spec-kit)
     /spec import <feat> <file|dir>   — file: copy as primary doc, scaffold
                                        the rest. dir: copy every recognized
                                        file (spec.md/plan.md/tasks.md/
                                        data-model.md/quickstart.md/
                                        research.md/contracts/), scaffold
                                        whatever's missing.
     /spec scaffold <kind> [<feat>]   — fill in an optional artifact:
                                        data-model | quickstart | research |
                                        contracts | constitution. Refuses
                                        to overwrite existing files.
     /spec start <feat>               — activate; docs flow into every turn
     /spec next                       — narrate next unchecked task
     /spec done <pattern>             — mark task done (edits markdown)
     /spec end                        — clear active spec

   When a spec is active, the spec docs are appended to the system
   prompt via the `context_assembly` event so the model has the
   durable plan in scope for every turn.

   Tasks are markdown checkboxes (`- [ ]` / `- [x]`); spec-kit's
   `[P]` (parallelizable) marker is preserved through edits.

   Hook events emitted:
     spec_task_start    — fires from `/spec next` with {spec, task}
     spec_task_complete — fires from `/spec done` with {spec, task}

   These ride the existing claude_hook_bridge, providing the same
   pre/post-task lifecycle Kiro users get from the Kiro IDE."
  (:require ["node:path" :as path]
            ["node:fs"   :as fs]
            ["node:os"   :as os]
            ["ai" :refer [generateText]]
            [clojure.string :as str]
            [agent.extensions.spec-driven.clarify :as clarify]
            [agent.extensions.spec-driven.analyze :as analyze]
            [agent.extensions.spec-driven.import :as spec-import]
            [agent.extensions.spec-driven.speckit-adapter :as adapter]
            [agent.extensions.spec-driven.skill-content :as skill-content]
            [agent.loop :as agent-loop]
            [agent.debug :as dbg]))

;; ── Discovery ───────────────────────────────────────────────────

(def ^:private spec-shapes
  "The two converged spec layouts. Each entry maps to:
     :root            - directory under cwd that holds individual specs
     :req-file        - 'what + why' filename (always created by `new`)
     :design-file     - 'how' filename (always created by `new`)
     :tasks-file      - shared filename across both shapes (always)
     :optional-files  - per-spec optional doc filenames, scanned at
                        discovery and surfaced in the prompt only when
                        they exist on disk
     :optional-dirs   - per-spec optional sub-directories of supporting
                        artifacts. Scanned recursively (1-level deep)
                        and inlined as fenced blocks
     :project-files   - project-wide files (not per-feature) like
                        spec-kit's memory/constitution.md or Kiro's
                        steering docs. Surfaced once at the top of the
                        active-spec block.

   Both shapes are recognized at all times. The DEFAULT for new specs
   and the COLLISION-WINNER are settings-driven (see read-spec-settings)
   so the rule is generic, not hardcoded. Out-of-the-box defaults match
   the May 2026 ecosystem signal: spec-kit is what every non-Kiro agent
   integrates with (~10 default integrations vs Kiro's 1), so it wins."
  {"spec-kit" {:source         :spec-kit
               :root           ".specify/specs"
               :req-file       "spec.md"
               :design-file    "plan.md"
               :tasks-file     "tasks.md"
               :optional-files ["data-model.md" "quickstart.md" "research.md"]
               :optional-dirs  ["contracts"]
               :project-files  [".specify/memory/constitution.md"]}
   "kiro"     {:source         :kiro
               :root           ".kiro/specs"
               :req-file       "requirements.md"
               :design-file    "design.md"
               :tasks-file     "tasks.md"
               :optional-files []
               :optional-dirs  []
               ;; Kiro's project-wide guidance lives under .kiro/steering/.
               ;; Treated as a directory to glob (* expansion happens later).
               :project-files  [".kiro/steering"]}})

;; ── Settings-driven shape selection ────────────────────────────

(def ^:private default-shape-precedence
  ;; Higher index = higher precedence. The LAST entry wins on name
  ;; collision when discover-specs merges. Spec-kit is the ecosystem
  ;; default for cross-agent interop in 2026; Kiro is supported but
  ;; doesn't win when both are present in the same project.
  ["kiro" "spec-kit"])

(def ^:private default-default-shape "spec-kit")

(defn read-spec-settings
  "Read `.nyma/settings.json#spec` from cwd if present, returning a
   normalized settings map. The two governing dials are:

     :default-shape       - which shape `/spec new` creates by default.
                            Values: \"spec-kit\" or \"kiro\".
     :shape-precedence    - vec of shape names lowest→highest. Affects
                            which one wins when the same spec name
                            exists in both `.kiro/specs/` and
                            `.specify/specs/`. Last entry wins.

   Missing file or missing keys → defaults. Out-of-the-box defaults
   match the 2026 ecosystem: spec-kit default + spec-kit-wins-on-collision.
   Kiro shops can override either dial by setting them in their
   `.nyma/settings.json`."
  [cwd]
  (let [defaults {:default-shape    default-default-shape
                  :shape-precedence default-shape-precedence}
        path-    (path/join cwd ".nyma" "settings.json")]
    (if-not (fs/existsSync path-)
      defaults
      (try
        (let [parsed (js/JSON.parse (fs/readFileSync path- "utf8"))
              spec   (aget parsed "spec")]
          (if (nil? spec)
            defaults
            {:default-shape    (or (.-default-shape spec)
                                   (aget spec "default-shape")
                                   default-default-shape)
             :shape-precedence (let [p (or (.-shape-precedence spec)
                                           (aget spec "shape-precedence"))]
                                 (if (and p (.isArray js/Array p))
                                   ;; Sanity-bound: keep only known shape names.
                                   ;; Drops typos silently rather than producing
                                   ;; a list that makes discover-specs no-op.
                                   (let [filtered (->> (js/Array.from p)
                                                       (filter #(get spec-shapes %))
                                                       vec)]
                                     (if (seq filtered) filtered default-shape-precedence))
                                   default-shape-precedence))}))
        (catch :default _ defaults)))))

(defn- read-if-exists [p]
  (when (and p (fs/existsSync p))
    (try (fs/readFileSync p "utf8") (catch :default _ nil))))

(defn- list-files-in-dir
  "List immediate-child files of `dir` (no recursion). Returns absolute
   paths. Returns empty vec if the dir doesn't exist or isn't readable."
  [dir]
  (if-not (fs/existsSync dir)
    []
    (try
      (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
        (->> entries
             (filter #(.isFile %))
             (map (fn [e] (path/join dir (.-name e))))
             vec))
      (catch :default _ []))))

(defn- existing-optionals
  "Given a spec dir and a shape, return the subset of optional file
   paths that actually exist on disk."
  [spec-dir shape]
  (->> (or (:optional-files shape) [])
       (map #(path/join spec-dir %))
       (filter fs/existsSync)
       vec))

(defn- existing-optional-dirs
  "Given a spec dir and a shape, return [{:name <dir-basename>
                                            :files [<path> ...]}]
   for each optional sub-directory that exists and contains files."
  [spec-dir shape]
  (->> (or (:optional-dirs shape) [])
       (map (fn [d]
              (let [full (path/join spec-dir d)
                    files (list-files-in-dir full)]
                (when (seq files)
                  {:name d :files files}))))
       (remove nil?)
       vec))

(defn- list-specs-under
  "Return per-shape spec records for every immediate subdirectory of the
   spec-shape root. Each record exposes the canonical 3 file paths plus
   any optional files / directories that exist on disk."
  [cwd shape]
  (let [root (path/join cwd (:root shape))]
    (when (fs/existsSync root)
      (let [entries (fs/readdirSync root #js {:withFileTypes true})]
        (->> entries
             (filter #(.isDirectory %))
             (map (fn [entry]
                    (let [n (.-name entry)
                          d (path/join root n)]
                      {:name        n
                       :source      (:source shape)
                       :dir         d
                       :req         (path/join d (:req-file shape))
                       :design      (path/join d (:design-file shape))
                       :tasks       (path/join d (:tasks-file shape))
                       :optionals   (existing-optionals d shape)
                       :optional-dirs (existing-optional-dirs d shape)})))
             vec)))))

(defn discover-specs
  "Return all specs found under cwd, keyed by name. The shape that wins
   on name collision is governed by `:shape-precedence` from settings —
   later entries override earlier (`merge` semantics).

   Default precedence puts spec-kit last (winner) per the 2026 cross-agent
   adoption signal; users can flip back to Kiro-wins via
   `.nyma/settings.json#spec.shape-precedence: [\"spec-kit\", \"kiro\"]`."
  [cwd]
  (let [{:keys [shape-precedence]} (read-spec-settings cwd)
        ;; Walk precedence lowest → highest; later merge() wins.
        shapes (keep #(get spec-shapes %) shape-precedence)
        all    (mapcat #(or (list-specs-under cwd %) []) shapes)]
    (->> all
         (map (fn [s] [(:name s) s]))
         (into {}))))

;; ── Tasks parsing ───────────────────────────────────────────────

(defn parse-tasks
  "Parse a `tasks.md` file body into [{:text :checked? :line-idx :raw}].
   Recognized line shapes (case-insensitive on x):
     - [ ] ...     (open)
     - [x] ...     (done)
     - [X] ...     (done)
     * [ ] ...     (open, asterisk variant)
     1. [ ] ...    (numbered list, open)
   Lines that don't match are passed through untouched in :raw but
   omitted from the parsed list."
  [content]
  (when (string? content)
    (let [lines (str/split-lines content)
          re    (js/RegExp. "^(\\s*(?:[-*]|\\d+\\.)\\s+)\\[([ xX])\\]\\s+(.*)$")]
      (->> lines
           (map-indexed
            (fn [idx line]
              (when-let [m (.match line re)]
                {:line-idx idx
                 :prefix   (aget m 1)
                 :checked? (not= (aget m 2) " ")
                 :text     (aget m 3)
                 :raw      line})))
           (remove nil?)
           vec))))

(defn- task-progress [tasks]
  (let [n     (count tasks)
        done  (count (filter :checked? tasks))]
    {:done done :total n}))

(defn next-open-task [tasks]
  (first (remove :checked? tasks)))

(defn mark-task-done
  "Return new tasks-md content with the line at line-idx flipped from
   `[ ]` to `[x]`. Pure — caller writes back to disk.

   Preserves the trailing newline if the input had one. `str/split-lines`
   discards it, so a naive str/join drops a byte on every call; over many
   /spec done invocations this would drift the file out of POSIX
   text-file convention."
  [content line-idx]
  (let [lines (str/split-lines content)
        old   (get (vec lines) line-idx)]
    (when old
      (let [new-line  (.replace old (js/RegExp. "\\[\\s\\]" "") "[x]")
            updated   (assoc (vec lines) line-idx new-line)
            joined    (str/join "\n" updated)
            trailing? (.endsWith (or content "") "\n")]
        (if trailing? (str joined "\n") joined)))))

(defn find-task
  "Find the first task whose :text contains pattern (case-insensitive).
   Returns the task map or nil."
  [tasks pattern]
  (let [pat (.toLowerCase (str pattern))]
    (first (filter #(.includes (.toLowerCase (:text %)) pat) tasks))))

;; ── Context injection ──────────────────────────────────────────

(defn- read-project-files
  "Resolve the shape's :project-files entries into [{:label :content}]
   pairs for files that exist on disk. Each entry can be either a file
   path (read directly) or a directory (read all top-level *.md files
   inside, e.g. .kiro/steering/)."
  [cwd shape]
  (let [paths (or (:project-files shape) [])]
    (->> paths
         (mapcat (fn [p]
                   (let [full (path/join cwd p)]
                     (cond
                       (not (fs/existsSync full))
                       []

                       (try (.isDirectory (fs/statSync full)) (catch :default _ false))
                       (->> (list-files-in-dir full)
                            (filter #(.endsWith % ".md"))
                            (map (fn [f]
                                   {:label (path/relative cwd f)
                                    :content (read-if-exists f)})))

                       :else
                       [{:label (path/relative cwd full)
                         :content (read-if-exists full)}]))))
         (remove #(empty? (:content %)))
         vec)))

(defn- inline-block
  "Render one optional artifact as a markdown sub-section."
  [label content]
  (str "\n#### " label "\n"
       content
       (when-not (.endsWith (or content "") "\n") "\n")))

(defn- annotate-next-task
  "Prepend `→ NEXT: ` to the first unchecked task line in `tasks` so the
   model sees a moving cursor instead of a flat list. Returns the
   tasks string unchanged when no tasks are open or `tasks` is nil."
  [tasks parsed]
  (if (empty? tasks)
    tasks
    (if-let [next- (next-open-task parsed)]
      (let [lines (.split tasks "\n")
            idx   (:line-idx next-)]
        (when (< idx (.-length lines))
          (aset lines idx (str "→ NEXT: " (aget lines idx))))
        (.join lines "\n"))
      tasks)))

(defn build-spec-context
  "Build the system-prompt addendum for an active spec.

   Layout (top → bottom):
     ## Active Spec: <name>  (<shape>; X/Y tasks done)
     [project-wide steering / constitution, if present]
     ### Requirements / Spec
     ### Design / Plan
     ### Tasks
     [optional per-spec artifacts: data-model.md, quickstart.md, research.md]
     [optional per-spec directories: contracts/*]"
  [cwd spec]
  (let [shape    (get spec-shapes (case (:source spec)
                                    :spec-kit "spec-kit"
                                    :kiro     "kiro"))
        req      (read-if-exists (:req spec))
        design   (read-if-exists (:design spec))
        tasks    (read-if-exists (:tasks spec))
        progress (task-progress (parse-tasks tasks))
        project  (read-project-files cwd shape)
        ;; Optional per-spec markdown files (data-model.md, quickstart.md,
        ;; research.md). Already filtered by existence in list-specs-under.
        opt-blocks (->> (:optionals spec)
                        (map (fn [p]
                               (when-let [c (read-if-exists p)]
                                 (inline-block (path/basename p) c))))
                        (remove nil?)
                        (str/join ""))
        ;; Optional per-spec sub-directories (contracts/). Each contained
        ;; file gets its own labeled block.
        dir-blocks (->> (:optional-dirs spec)
                        (mapcat (fn [{:keys [name files]}]
                                  (for [f files
                                        :let [c (read-if-exists f)]
                                        :when c]
                                    (inline-block (str name "/" (path/basename f)) c))))
                        (str/join ""))]
    (str "\n\n## Active Spec: " (:name spec) "  ("
         (case (:source spec) :kiro "Kiro" :spec-kit "spec-kit" "spec")
         "; " (:done progress) "/" (:total progress) " tasks done)\n"
         (when (seq project)
           (str "\n### Project guidance\n"
                (->> project
                     (map (fn [{:keys [label content]}]
                            (inline-block label content)))
                     (str/join ""))))
         (when req
           (str "\n### "
                (case (:source spec) :spec-kit "Spec" :kiro "Requirements" "Spec")
                "\n" req "\n"))
         (when design
           (str "\n### "
                (case (:source spec) :spec-kit "Plan" :kiro "Design" "Plan")
                "\n" design "\n"))
         (when tasks
           (let [parsed     (parse-tasks tasks)
                 annotated  (annotate-next-task tasks parsed)
                 has-open?  (some? (next-open-task parsed))]
             (str "\n### Tasks\n" annotated "\n"
                  (when has-open?
                    (str "\n_When you finish the **NEXT** task above, "
                         "edit tasks.md in the same turn: replace "
                         "`- [ ]` with `- [x]` on its line. Then move "
                         "to the next open task. Do not skip the update._\n")))))
         (when (seq opt-blocks)
           (str "\n### Supporting artifacts" opt-blocks))
         (when (seq dir-blocks)
           (str "\n### Contracts & references" dir-blocks)))))

;; ── Slash command formatting ────────────────────────────────────

;; ── Scaffolding new specs ──────────────────────────────────────

(def ^:private valid-name-pattern
  ;; Lowercase a-z, digits, hyphens. No leading/trailing or consecutive hyphens.
  ;; Same shape as agentskills.io skill names — keeps URLs/dirs portable.
  (js/RegExp. "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$"))

(defn valid-spec-name?
  "True iff `s` is safe to use as a spec directory name. Rejects path
   traversal, slashes, leading/trailing/consecutive hyphens, uppercase,
   and the empty string. Mirrors the agentskills.io rule."
  [s]
  (boolean
   (and (string? s)
        (.test valid-name-pattern s)
        (not (.includes s ".."))
        (not (.includes s "--")))))

(defn requirements-template
  "Primary 'what + why' template. The :spec-kit shape calls this file
   `spec.md` and the heading reflects that; :kiro calls it
   `requirements.md`."
  ([feat-name] (requirements-template feat-name :spec-kit))
  ([feat-name source]
   (let [section (case source :kiro "Requirements" "Spec")]
     (str "# " feat-name " — " section "\n\n"
          "## What & Why\n\n"
          "<!-- A short description of what this feature is and why it exists.\n"
          "     Include user stories or acceptance criteria. -->\n\n"
          "## Acceptance criteria\n\n"
          "- [ ] Criterion one\n"
          "- [ ] Criterion two\n"))))

(defn design-template
  "Primary 'how' template. :spec-kit calls this `plan.md`, :kiro calls
   it `design.md`. Headings differ accordingly."
  ([feat-name] (design-template feat-name :spec-kit))
  ([feat-name source]
   (let [section (case source :kiro "Design" "Plan")]
     (str "# " feat-name " — " section "\n\n"
          "## Approach\n\n"
          "<!-- The technical plan: data flows, key types, file changes. -->\n\n"
          "## Files affected\n\n"
          "- `src/...`\n\n"
          "## Open questions\n\n"
          "- ...\n"))))

(defn tasks-template [feat-name]
  (str "# " feat-name " — Tasks\n\n"
       "<!-- Use markdown checkboxes. The agent advances them with\n"
       "     /spec next and /spec done <pattern>. Order top-to-bottom. -->\n\n"
       "- [ ] First task\n"
       "- [ ] Second task\n"))

;; ── Optional-artifact templates ────────────────────────────────

(defn data-model-template [feat-name]
  (str "# " feat-name " — Data Model\n\n"
       "<!-- Entities, relationships, invariants. One section per entity. -->\n\n"
       "## Entities\n\n"
       "### Example\n\n"
       "```\n"
       "Example {\n"
       "  id: string  // primary key\n"
       "  ...\n"
       "}\n"
       "```\n\n"
       "## Relationships\n\n"
       "- ...\n\n"
       "## Invariants\n\n"
       "- ...\n"))

(defn quickstart-template [feat-name]
  (str "# " feat-name " — Quickstart\n\n"
       "<!-- The 5-minute path from clean checkout to a working demo of\n"
       "     this feature. Useful for new contributors and for the agent\n"
       "     when it needs to verify its work. -->\n\n"
       "## Prerequisites\n\n"
       "- ...\n\n"
       "## Steps\n\n"
       "1. ...\n"
       "2. ...\n\n"
       "## Expected output\n\n"
       "```\n"
       "...\n"
       "```\n"))

(defn research-template [feat-name]
  (str "# " feat-name " — Research\n\n"
       "<!-- Open questions, alternatives considered, references, prior art.\n"
       "     This file captures the WHY behind decisions in plan.md. -->\n\n"
       "## Questions\n\n"
       "- ...\n\n"
       "## Alternatives considered\n\n"
       "- **Option A:** …  *(rejected because: …)*\n"
       "- **Option B:** …  *(chosen because: …)*\n\n"
       "## References\n\n"
       "- ...\n"))

(defn contracts-readme-template [feat-name]
  (str "# " feat-name " — Contracts\n\n"
       "<!-- Drop API specs, schemas, protocol notes here. Common shapes:\n"
       "     api-spec.json (OpenAPI), graphql-spec.graphql, signalr-spec.md,\n"
       "     events-spec.md, etc. The agent reads every file in this dir\n"
       "     when the spec is active. -->\n"))

(defn contracts-api-template []
  (str "{\n"
       "  \"openapi\": \"3.0.3\",\n"
       "  \"info\": { \"title\": \"TBD\", \"version\": \"0.1.0\" },\n"
       "  \"paths\": {}\n"
       "}\n"))

(defn constitution-template []
  (str "# Project Constitution\n\n"
       "<!-- Project-wide governing principles auto-included on every turn\n"
       "     when any spec is active. Keep it short and decision-focused. -->\n\n"
       "## Code style\n\n"
       "- ...\n\n"
       "## Testing\n\n"
       "- ...\n\n"
       "## Release / deploy\n\n"
       "- ...\n"))

(defn parse-shape-flag
  "Extract the desired shape name from CLI-style option args.

   Recognizes `--spec-kit`, `--shape=spec-kit`, `--kiro`, `--shape=kiro`.
   Returns:
     - the shape name string when exactly one shape flag is present
     - nil when none is present (caller falls back to settings :default-shape)
     - {:error <string>} when multiple conflicting shape flags are present
       (caller should surface this to the user)"
  [opts]
  (let [kiro?     (boolean (some #(or (= % "--kiro") (= % "--shape=kiro")) opts))
        spec-kit? (boolean (some #(or (= % "--spec-kit") (= % "--shape=spec-kit")) opts))]
    (cond
      (and kiro? spec-kit?)
      {:error "Conflicting shape flags: pass either --kiro or --spec-kit, not both."}

      kiro?     "kiro"
      spec-kit? "spec-kit"
      :else     nil)))

(defn create-spec!
  "Scaffold a new spec under cwd in the named shape (\"spec-kit\" or
   \"kiro\"). Defaults from `read-spec-settings` when shape is nil.
   Returns {:ok? true :dir <path> :files [...] :source <kw>} or
   {:ok? false :error <string>}.

   spec-kit shape: spec.md / plan.md / tasks.md
   Kiro shape:     requirements.md / design.md / tasks.md"
  [cwd feat-name shape-name]
  (cond
    (not (valid-spec-name? feat-name))
    {:ok? false
     :error (str "Invalid spec name: \"" feat-name
                 "\". Use lowercase letters, digits, and single hyphens "
                 "(e.g. \"auth-flow\", \"billing-v2\").")}

    :else
    (let [resolved-name (or shape-name (:default-shape (read-spec-settings cwd)))
          shape         (get spec-shapes resolved-name)]
      (if-not shape
        ;; Unknown shape (typo in --kiro/--spec-kit, or malformed
        ;; settings.json#spec.default-shape). Return an error map per
        ;; the rest of this fn's contract — slash-command dispatchers
        ;; expect {:ok? false :error ...}, not exceptions.
        {:ok?   false
         :error (str "Unknown spec shape: \"" resolved-name
                     "\". Valid: \"spec-kit\", \"kiro\".")}
        (let [root     (path/join cwd (:root shape))
              dir      (path/join root feat-name)
              req-name (:req-file shape)
              dsn-name (:design-file shape)
              tsk-name (:tasks-file shape)]
          (cond
            (fs/existsSync dir)
            {:ok? false
             :error (str "Spec already exists: "
                         (path/relative cwd dir)
                         ". Pick a different name or edit the files directly.")}

            :else
            (try
              (fs/mkdirSync dir #js {:recursive true})
              (fs/writeFileSync (path/join dir req-name)
                                (requirements-template feat-name (:source shape)))
              (fs/writeFileSync (path/join dir dsn-name)
                                (design-template feat-name (:source shape)))
              (fs/writeFileSync (path/join dir tsk-name) (tasks-template feat-name))
              {:ok?    true
               :dir    dir
               :files  [(path/relative cwd (path/join dir req-name))
                        (path/relative cwd (path/join dir dsn-name))
                        (path/relative cwd (path/join dir tsk-name))]
               :source (:source shape)}
              (catch :default e
                {:ok? false
                 :error (str "Failed to create spec: " (or (.-message e) (str e)))}))))))))

;; ── /spec scaffold — fill in optional artifacts ────────────────

(def ^:private artifact-recipes
  "Each entry maps a kind keyword to:
     :path-fn    - (fn [spec-dir cwd shape]) → absolute path to write
     :content-fn - (fn [feat-name shape]) → file content
     :shapes     - shape names this kind is meaningful for. nil = all
     :clobber?   - if true, overwrite existing file; default false
   The `constitution` kind is a special case: writes to a project-wide
   path (`.specify/memory/constitution.md`), not under any spec dir."
  {"data-model"
   {:path-fn    (fn [spec-dir _ _] (path/join spec-dir "data-model.md"))
    :content-fn (fn [feat-name _] (data-model-template feat-name))
    :shapes     ["spec-kit"]}

   "quickstart"
   {:path-fn    (fn [spec-dir _ _] (path/join spec-dir "quickstart.md"))
    :content-fn (fn [feat-name _] (quickstart-template feat-name))
    :shapes     ["spec-kit"]}

   "research"
   {:path-fn    (fn [spec-dir _ _] (path/join spec-dir "research.md"))
    :content-fn (fn [feat-name _] (research-template feat-name))
    :shapes     ["spec-kit"]}

   "contracts"
   {:path-fn    (fn [spec-dir _ _] (path/join spec-dir "contracts" "api-spec.json"))
    :content-fn (fn [_ _] (contracts-api-template))
    :shapes     ["spec-kit"]
    :extra-files [{:path-fn (fn [spec-dir _ _]
                              (path/join spec-dir "contracts" "README.md"))
                   :content-fn (fn [feat-name _] (contracts-readme-template feat-name))}]}

   "constitution"
   {:path-fn    (fn [_ cwd _] (path/join cwd ".specify/memory/constitution.md"))
    :content-fn (fn [_ _] (constitution-template))
    :shapes     ["spec-kit"]
    :project-wide? true}})

(defn scaffold-artifact!
  "Create one optional spec-kit artifact. `kind` is a string like
   \"data-model\", \"quickstart\", \"research\", \"contracts\", or
   \"constitution\". For project-wide artifacts (constitution),
   `feat-name` may be nil. Returns {:ok? true :paths [...]} or
   {:ok? false :error <string>}.

   Refuses to overwrite an existing file by default — the user must
   delete the file first if they want to regenerate."
  [cwd feat-name kind]
  (let [recipe (get artifact-recipes kind)]
    (cond
      (nil? recipe)
      {:ok? false
       :error (str "Unknown artifact kind: \"" kind "\". "
                   "Valid: " (str/join ", " (sort (keys artifact-recipes))) ".")}

      (and (not (:project-wide? recipe))
           (or (nil? feat-name)
               (not (valid-spec-name? feat-name))))
      {:ok? false
       :error (str "Need a valid spec name to scaffold a per-spec artifact. "
                   "Got: " (pr-str feat-name))}

      :else
      (let [;; Resolve the active shape for this spec (project-wide kinds
            ;; pick the default shape since they live outside any spec dir).
            settings  (read-spec-settings cwd)
            specs     (when feat-name (discover-specs cwd))
            spec      (when feat-name (get specs feat-name))
            shape-key (cond
                        (:project-wide? recipe) (:default-shape settings)
                        spec                    (case (:source spec)
                                                  :spec-kit "spec-kit"
                                                  :kiro     "kiro")
                        :else                   (:default-shape settings))
            shape     (get spec-shapes shape-key)
            spec-dir  (when spec (:dir spec))
            allowed?  (or (nil? (:shapes recipe))
                          (some #(= shape-key %) (:shapes recipe)))]
        (cond
          (not allowed?)
          {:ok? false
           :error (str "Artifact \"" kind "\" is not defined for "
                       (case shape-key "kiro" "Kiro" "spec-kit" "spec-kit" "this")
                       " shape. Valid shapes: "
                       (str/join ", " (:shapes recipe)) ".")}

          (and (not (:project-wide? recipe)) (not spec))
          {:ok? false
           :error (str "Spec \"" feat-name "\" not found. "
                       "Run /spec new " feat-name " first.")}

          :else
          (try
            (let [primary-fn  (:path-fn recipe)
                  primary     (primary-fn spec-dir cwd shape)
                  extras      (or (:extra-files recipe) [])
                  all-paths   (cons primary
                                    (map (fn [e] ((:path-fn e) spec-dir cwd shape))
                                         extras))
                  existing    (filter fs/existsSync all-paths)]
              (cond
                (and (seq existing) (not (:clobber? recipe)))
                {:ok? false
                 :error (str "Already exists: "
                             (str/join ", " (map #(path/relative cwd %) existing))
                             ". Delete it first or edit in place.")}

                :else
                (do
                  ;; Primary
                  (fs/mkdirSync (path/dirname primary) #js {:recursive true})
                  (fs/writeFileSync primary
                                    ((:content-fn recipe) feat-name shape))
                  ;; Extras
                  (doseq [e extras]
                    (let [p ((:path-fn e) spec-dir cwd shape)]
                      (fs/mkdirSync (path/dirname p) #js {:recursive true})
                      (fs/writeFileSync p ((:content-fn e) feat-name shape))))
                  {:ok?   true
                   :kind  kind
                   :paths (vec (map #(path/relative cwd %) all-paths))})))
            (catch :default e
              {:ok? false
               :error (str "Failed to scaffold " kind ": "
                           (or (.-message e) (str e)))})))))))

;; ── /spec import directory mode ────────────────────────────────

(def ^:private importable-filenames
  "Filenames we recognize and copy verbatim from a directory into the
   spec dir. Both Kiro's and spec-kit's primary docs are listed so an
   import works regardless of which shape the source dir was authored in
   — we copy by filename and let the target shape's discover-specs pick
   up whatever's there."
  ["spec.md" "requirements.md"
   "plan.md" "design.md"
   "tasks.md"
   "data-model.md" "quickstart.md" "research.md"])

(defn import-from-dir!
  "Copy files from `src-dir` into the new spec at <cwd>/<shape-root>/<feat-name>/.
   Recognized filenames (see `importable-filenames`) plus any `contracts/`
   subdirectory are copied. Anything else in src-dir is ignored.
   Returns {:ok? true :copied [...] :scaffolded [...]} or {:ok? false :error}."
  [cwd feat-name shape-name src-dir]
  (let [create-result (create-spec! cwd feat-name shape-name)]
    (if-not (:ok? create-result)
      create-result
      (let [shape-key (or shape-name (:default-shape (read-spec-settings cwd)))
            shape    (get spec-shapes shape-key)
            spec-dir (:dir create-result)
            ;; Map import filename → target filename inside the spec dir.
            ;; spec.md and requirements.md both go to the shape's :req-file;
            ;; plan.md and design.md both go to the shape's :design-file.
            target-of (fn [fname]
                        (cond
                          (or (= fname "spec.md") (= fname "requirements.md"))
                          (:req-file shape)
                          (or (= fname "plan.md") (= fname "design.md"))
                          (:design-file shape)
                          (= fname "tasks.md") (:tasks-file shape)
                          :else fname))
            try-copy (fn [src-name]
                       (let [src (path/join src-dir src-name)]
                         (when (fs/existsSync src)
                           (let [dest (path/join spec-dir (target-of src-name))]
                             (fs/writeFileSync dest (fs/readFileSync src "utf8"))
                             {:from src-name :to (path/relative cwd dest)}))))]
        (try
          (let [copied (vec (keep try-copy importable-filenames))
                ;; Optional contracts/ directory
                src-contracts (path/join src-dir "contracts")
                contract-files (when (fs/existsSync src-contracts)
                                 (try
                                   (let [entries (fs/readdirSync src-contracts
                                                                 #js {:withFileTypes true})]
                                     (->> entries
                                          (filter #(.isFile %))
                                          (map (fn [e]
                                                 (let [src (path/join src-contracts (.-name e))
                                                       dest-dir (path/join spec-dir "contracts")
                                                       dest (path/join dest-dir (.-name e))]
                                                   (fs/mkdirSync dest-dir #js {:recursive true})
                                                   (fs/writeFileSync dest (fs/readFileSync src))
                                                   {:from (str "contracts/" (.-name e))
                                                    :to (path/relative cwd dest)})))
                                          vec))
                                   (catch :default _ [])))
                all-copied (vec (concat copied (or contract-files [])))
                ;; Anything from the canonical set that wasn't copied stays
                ;; as the template `create-spec!` wrote — that's the
                ;; "scaffolded" list.
                copied-targets (set (map :to all-copied))
                scaffolded (->> (:files create-result)
                                (remove copied-targets)
                                vec)]
            {:ok?         true
             :dir         spec-dir
             :source      (:source create-result)
             :copied      all-copied
             :scaffolded  scaffolded})
          (catch :default e
            ;; Roll back on any copy failure so the user isn't left with
            ;; a half-imported spec dir.
            (try (fs/rmSync spec-dir #js {:recursive true :force true})
                 (catch :default _ nil))
            {:ok? false
             :error (str "Failed to import directory: "
                         (or (.-message e) (str e)) ". Rolled back.")}))))))

(defn- format-spec-list [specs cwd active]
  (if (empty? specs)
    (str "No specs found.\n"
         "  spec-kit shape: " (path/join cwd ".specify/specs/<name>/") " (default)\n"
         "  Kiro shape:     " (path/join cwd ".kiro/specs/<name>/") "\n"
         "\nRun `/spec new <name>` to scaffold one, "
         "or `/spec import <name> <path>` to ingest an existing markdown file.")
    (->> specs
         (map (fn [[name spec]]
                (let [tasks (parse-tasks (read-if-exists (:tasks spec)))
                      {:keys [done total]} (task-progress tasks)
                      marker (if (= name active) " ◀ active" "")
                      shape  (case (:source spec)
                               :kiro     "Kiro"
                               :spec-kit "spec-kit"
                               "spec")]
                  (str "  " name " [" shape "] — " done "/" total " tasks done"
                       marker))))
         (str/join "\n")
         (str "Specs:\n"))))

;; ── Extension entry ────────────────────────────────────────────

(defn ^:export default [api]
  (let [handlers (atom [])
        ;; Active-spec name lives in extension state, like model-roles
        ;; uses :active-role.
        get-active (fn []
                     (let [s (.getState api)]
                       (or (:active-spec s) (get s "active-spec"))))
        set-active! (fn [name]
                      (swap! (.-__state-atom api) assoc :active-spec name))

        cwd-of (fn [_ctx] (js/process.cwd))

        on-context-assembly
        (fn [data]
          (let [active (get-active)
                cwd    (js/process.cwd)
                specs  (when active (discover-specs cwd))
                spec   (when active (get specs active))]
            (when spec
              (let [base (or (.-systemPrompt data) "")
                    addendum (build-spec-context cwd spec)]
                #js {:system (str base addendum)}))))

        ;; /spec — top-level dispatcher.  Subcommands: list, start, next,
        ;; done, end. With no args, behaves like `list`.
        spec-cmd
        (fn [args ctx]
          (let [cwd   (cwd-of ctx)
                sub   (or (first args) "list")
                rest- (vec (drop 1 args))
                specs (discover-specs cwd)
                active (get-active)]
            (case sub
              "list"
              (.notify (.-ui ctx) (format-spec-list specs cwd active))

              "start"
              ;; Strip --force from anywhere in the arg list so the user
              ;; can write `/spec start --force <name>` OR
              ;; `/spec start <name> --force` — order shouldn't matter.
              (let [force?    (boolean (some #(= % "--force") rest-))
                    pos-args  (vec (remove #(= % "--force") rest-))
                    target    (first pos-args)]
                (cond
                  (empty? target)
                  (.notify (.-ui ctx) "Usage: /spec start <name> [--force]" "error")

                  (not (get specs target))
                  (.notify (.-ui ctx)
                           (str "Unknown spec: \"" target "\". "
                                (count specs) " spec(s) found. Use /spec list.")
                           "error")

                  :else
                  (let [;; Soft-block: warn if last analyze had unresolved
                        ;; CRITICAL findings or the spec content has
                        ;; drifted since the last analyze. --force skips.
                        ;; Skipped automatically when the spec is still
                        ;; the unedited template — running analyze on
                        ;; placeholder content adds bureaucracy with no
                        ;; signal.
                        spec     (get specs target)
                        spec-c   (read-if-exists (:req spec))
                        plan-c   (read-if-exists (:design spec))
                        tasks-c  (read-if-exists (:tasks spec))
                        const-c  (adapter/read-constitution cwd)
                        cur-hash (analyze/compute-content-hash
                                  {:spec-content         spec-c
                                   :plan-content         plan-c
                                   :tasks-content        tasks-c
                                   :constitution-content const-c})
                        state    (analyze/read-spec-state cwd)
                        warn     (analyze/should-warn-on-start?
                                  state target cur-hash spec-c tasks-c)]
                    (if (and warn (not force?))
                      (.notify (.-ui ctx)
                               (str "⚠ /spec analyze recommends review:\n  "
                                    (:detail warn)
                                    "\nRun /spec analyze " target
                                    " to see findings, then re-run /spec start "
                                    target ", or pass --force to start anyway.")
                               "warning")
                      (do
                        (set-active! target)
                        (.notify (.-ui ctx)
                                 (str "Active spec: " target
                                      (when force? " (forced past analyze warnings)")
                                      ".\nDocs are now appended to the system prompt for every turn. "
                                      "Use /spec next to advance, /spec end to clear.")))))))

              "new"
              (let [target          (first rest-)
                    opts            (vec (drop 1 rest-))
                    shape-or-err    (parse-shape-flag opts)
                    shape-flag-err? (and (map? shape-or-err) (:error shape-or-err))
                    shape-name      (when-not shape-flag-err? shape-or-err)
                    settings        (read-spec-settings cwd)
                    chosen          (or shape-name (:default-shape settings))]
                (cond
                  shape-flag-err?
                  (.notify (.-ui ctx) (:error shape-or-err) "error")

                  (empty? target)
                  (.notify (.-ui ctx)
                           (str "Usage: /spec new <name> [--spec-kit | --kiro]\n"
                                "  Default shape: " chosen
                                " (set `.nyma/settings.json#spec.default-shape` to change).\n"
                                "  spec-kit → .specify/specs/<name>/{spec,plan,tasks}.md\n"
                                "  kiro     → .kiro/specs/<name>/{requirements,design,tasks}.md")
                           "error")

                  :else
                  (let [result (create-spec! cwd target shape-name)]
                    (if (:ok? result)
                      (.notify (.-ui ctx)
                               (str "✓ Created " (case (:source result)
                                                   :kiro "Kiro" :spec-kit "spec-kit" "spec")
                                    " spec: " target "\n"
                                    (->> (:files result)
                                         (map #(str "    " %))
                                         (str/join "\n"))
                                    "\n\nEdit the files, then `/spec start " target
                                    "` to activate."))
                      (.notify (.-ui ctx) (:error result) "error")))))

              "import"
              (let [target          (first rest-)
                    src-path        (second rest-)
                    opts            (vec (drop 2 rest-))
                    shape-or-err    (parse-shape-flag opts)
                    shape-flag-err? (and (map? shape-or-err) (:error shape-or-err))
                    shape-name      (when-not shape-flag-err? shape-or-err)
                    settings        (read-spec-settings cwd)
                    chosen          (or shape-name (:default-shape settings))]
                (cond
                  shape-flag-err?
                  (.notify (.-ui ctx) (:error shape-or-err) "error")

                  (or (empty? target) (empty? src-path))
                  (.notify (.-ui ctx)
                           (str "Usage: /spec import <name> <path-to-markdown> [--spec-kit | --kiro]\n"
                                "  Default shape: " chosen ".\n"
                                "  Copies <path> as the spec's primary doc (spec.md or requirements.md);\n"
                                "  creates empty design.md/plan.md and a starter tasks.md.")
                           "error")

                  (not (fs/existsSync src-path))
                  (.notify (.-ui ctx)
                           (str "Path not found: " src-path)
                           "error")

                  ;; Directory mode: copy every recognized file from the
                  ;; source dir into the new spec; scaffold whatever's
                  ;; missing.
                  (try (.isDirectory (fs/statSync src-path))
                       (catch :default _ false))
                  (let [result (import-from-dir! cwd target shape-name src-path)]
                    (if-not (:ok? result)
                      (.notify (.-ui ctx) (:error result) "error")
                      (.notify (.-ui ctx)
                               (str "✓ Imported "
                                    (case (:source result)
                                      :kiro "Kiro" :spec-kit "spec-kit" "spec")
                                    " spec: " target "\n"
                                    (when (seq (:copied result))
                                      (str "  copied:\n"
                                           (->> (:copied result)
                                                (map (fn [{:keys [from to]}]
                                                       (str "    " to " ← " from)))
                                                (str/join "\n"))
                                           "\n"))
                                    (when (seq (:scaffolded result))
                                      (str "  scaffolded (template):\n"
                                           (->> (:scaffolded result)
                                                (map #(str "    " %))
                                                (str/join "\n"))
                                           "\n"))
                                    "\nReview the files, then `/spec start " target
                                    "` to activate."))))

                  :else
                  ;; File mode: scaffold via create-spec!, then enqueue an
                  ;; LLM-driven decomposition seed via agent-loop/follow-up.
                  ;; The next agent turn reads the source and populates
                  ;; spec.md / plan.md / tasks.md (and research.md for
                  ;; leftovers) using the standard tool loop.
                  (let [result (create-spec! cwd target shape-name)]
                    (if-not (:ok? result)
                      (.notify (.-ui ctx) (:error result) "error")
                      (try
                        (let [src-content (fs/readFileSync src-path "utf8")
                              target-dir  (path/relative cwd (:dir result))
                              seed (spec-import/compose-import-seed
                                    {:spec-name      target
                                     :source-path    (path/relative cwd src-path)
                                     :target-dir     target-dir
                                     :source-content src-content})
                              agent (aget ctx "agent")]
                          (if-not agent
                            (.notify (.-ui ctx)
                                     (str "Imported scaffolds for " target ", but no agent "
                                          "context is available to seed the decomposition. "
                                          "Run `/spec clarify " target "` to fill them in.")
                                     "warning")
                            (do
                              (agent-loop/follow-up agent {:content seed})
                              (.notify (.-ui ctx)
                                       (str "✓ Imported " (case (:source result)
                                                            :kiro "Kiro" :spec-kit "spec-kit" "spec")
                                            " spec: " target "\n"
                                            "  scaffolded:\n"
                                            (->> (:files result)
                                                 (map #(str "    " %))
                                                 (str/join "\n"))
                                            "\n\nDecomposition queued — the next agent turn will read\n"
                                            "  " (path/relative cwd src-path) "\n"
                                            "and populate the files. Send any message (e.g. \"go\") to start.\n"
                                            "Then run `/spec clarify " target "` to resolve any "
                                            "[NEEDS CLARIFICATION] markers.")))))
                        (catch :default e
                          (try (fs/rmSync (:dir result)
                                          #js {:recursive true :force true})
                               (catch :default _ nil))
                          (.notify (.-ui ctx)
                                   (str "Failed to import " src-path ": "
                                        (or (.-message e) (str e))
                                        "\nRolled back; the spec was not created.")
                                   "error")))))))

              "scaffold"
              (let [kind   (first rest-)
                    target (second rest-)
                    valid-kinds (sort (keys artifact-recipes))]
                (cond
                  (empty? kind)
                  (.notify (.-ui ctx)
                           (str "Usage: /spec scaffold <kind> [<spec-name>]\n"
                                "  Kinds: " (str/join " | " valid-kinds) "\n"
                                "  All kinds except `constitution` need a spec name.\n"
                                "  Refuses to overwrite an existing file.")
                           "error")

                  :else
                  (let [result (scaffold-artifact! cwd target kind)]
                    (if (:ok? result)
                      (.notify (.-ui ctx)
                               (str "✓ Scaffolded " kind ":\n"
                                    (->> (:paths result)
                                         (map #(str "    " %))
                                         (str/join "\n"))))
                      (.notify (.-ui ctx) (:error result) "error")))))

              "clarify"
              (let [target (first rest-)
                    target (or target active)]
                (cond
                  (empty? target)
                  (.notify (.-ui ctx)
                           (str "Usage: /spec clarify <name>\n"
                                "  (or activate a spec with /spec start first, then /spec clarify)")
                           "error")

                  (not (get specs target))
                  (.notify (.-ui ctx)
                           (str "Unknown spec: \"" target "\". Use /spec list.")
                           "error")

                  :else
                  (let [spec (get specs target)
                        content (read-if-exists (:req spec))]
                    (cond
                      (nil? content)
                      (.notify (.-ui ctx)
                               (str "Spec \"" target "\" has no primary doc on disk: "
                                    (path/relative cwd (:req spec)))
                               "error")

                      :else
                      (let [seed (clarify/compose-clarify-seed
                                  {:spec-name target
                                   :spec-content content
                                   :spec-path (path/relative cwd (:req spec))})
                            ;; The slash command's ctx (built in
                            ;; modes/interactive.cljs:96-102) carries the
                            ;; key as a hyphenated string `"append-message"`.
                            ;; Squint's `(.-append-message ctx)` would
                            ;; translate to `ctx.append_message` (underscore)
                            ;; and never find it — use aget with the literal
                            ;; key instead.
                            append! (aget ctx "append-message")]
                        ;; Inject the clarify instructions as a system
                        ;; message; the next turn runs the Q&A naturally.
                        ;; Fallback: if the host doesn't expose append-message
                        ;; (e.g. gateway mode), push through agent state.
                        (if append!
                          (append! #js {:role "system" :content seed})
                          (when-let [a (aget ctx "agent")]
                            (swap! (:state a) update :messages
                                   conj {:role "system" :content seed})))
                        (.notify (.-ui ctx)
                                 (str "✓ Clarify session seeded for " target ".\n"
                                      "Send any message to begin "
                                      "(e.g. \"start\" or your first thought) — "
                                      "the model will ask up to 5 questions and "
                                      "update " (path/relative cwd (:req spec))
                                      " as you answer.")))))))

              "analyze"
              (let [target (first rest-)
                    target (or target active)]
                (cond
                  (empty? target)
                  (.notify (.-ui ctx)
                           "Usage: /spec analyze <name>"
                           "error")

                  (not (get specs target))
                  (.notify (.-ui ctx)
                           (str "Unknown spec: \"" target "\". Use /spec list.")
                           "error")

                  :else
                  (let [spec     (get specs target)
                        spec-c   (read-if-exists (:req spec))
                        plan-c   (read-if-exists (:design spec))
                        tasks-c  (read-if-exists (:tasks spec))
                        const-c  (adapter/read-constitution cwd)
                        prompt   (analyze/compose-analyze-prompt
                                  {:spec-content         spec-c
                                   :plan-content         plan-c
                                   :tasks-content        tasks-c
                                   :constitution-content const-c})
                        ;; Run the analyze pass synchronously. Errors
                        ;; here surface as a notify; nothing on disk
                        ;; changes regardless.
                        agent    (.-agent ctx)
                        model    (:model (:config agent))]
                    (cond
                      (nil? model)
                      (.notify (.-ui ctx)
                               "No model configured. Run /login or /model first."
                               "error")

                      :else
                      (-> (generateText
                           #js {:model    model
                                :messages #js [#js {:role    "user"
                                                    :content prompt}]
                                :maxTokens 4096})
                          (.then
                           (fn [result]
                             (let [report (.-text result)
                                   parsed (analyze/parse-analyze-output report)
                                   hash   (analyze/compute-content-hash
                                           {:spec-content         spec-c
                                            :plan-content         plan-c
                                            :tasks-content        tasks-c
                                            :constitution-content const-c})]
                               ;; Persist the run for soft-block on start.
                               (analyze/write-analyze-result!
                                cwd target
                                {:content-hash   hash
                                 :critical-count (:critical-count parsed)
                                 :finding-count  (count (:findings parsed))})
                               ;; Render the full Markdown report.
                               (.notify (.-ui ctx) (or report "")))))
                          (.catch
                           (fn [e]
                             (.notify (.-ui ctx)
                                      (str "Analyze failed: "
                                           (or (.-message e) (str e)))
                                      "error"))))))))

              "install-skill"
              (let [opts   (vec rest-)
                    force? (some #(= % "--force") opts)
                    home   (os/homedir)
                    result (skill-content/install-skill! home force?)]
                (if (:ok? result)
                  (.notify (.-ui ctx)
                           (str "✓ Installed spec-driven-dev skill: "
                                (:path result) "\n"
                                "Restart nyma to load it. The skill activates "
                                "when you work in spec dirs (per its `paths` hint) "
                                "and teaches the model the spec-kit conventions."))
                  (.notify (.-ui ctx) (:error result) "error")))

              "end"
              (do (swap! (.-__state-atom api) dissoc :active-spec)
                  (.notify (.-ui ctx) "Active spec cleared."))

              "next"
              (cond
                (nil? active)
                (.notify (.-ui ctx) "No active spec. Use /spec start <name> first." "error")

                :else
                (let [spec  (get specs active)
                      tasks (parse-tasks (read-if-exists (:tasks spec)))
                      task  (next-open-task tasks)]
                  (cond
                    (empty? tasks)
                    (.notify (.-ui ctx) (str "Spec " active " has no tasks yet."))

                    (nil? task)
                    (.notify (.-ui ctx) (str "All tasks in " active " are done. 🎉"))

                    :else
                    (do
                      ;; Notify hook listeners so users can wire pre-task automation.
                      (when-let [emit (.-emit (.-events api))]
                        (emit "spec_task_start"
                              #js {:spec active :task (:text task) :line (:line-idx task)}))
                      (.notify (.-ui ctx)
                               (str "Next task in " active ":\n  • " (:text task)))))))

              "done"
              (let [pattern (str/join " " rest-)]
                (cond
                  (nil? active)
                  (.notify (.-ui ctx) "No active spec. Use /spec start <name> first." "error")

                  (empty? pattern)
                  (.notify (.-ui ctx)
                           "Usage: /spec done <substring matching the task text>"
                           "error")

                  :else
                  (let [spec    (get specs active)
                        content (read-if-exists (:tasks spec))
                        tasks   (parse-tasks content)
                        task    (find-task tasks pattern)]
                    (cond
                      (nil? task)
                      (.notify (.-ui ctx)
                               (str "No task matching: \"" pattern "\"")
                               "error")

                      (:checked? task)
                      (.notify (.-ui ctx)
                               (str "Already done: " (:text task)))

                      :else
                      (let [updated (mark-task-done content (:line-idx task))]
                        (when updated
                          (fs/writeFileSync (:tasks spec) updated))
                        (when-let [emit (.-emit (.-events api))]
                          (emit "spec_task_complete"
                                #js {:spec active :task (:text task)
                                     :line (:line-idx task)}))
                        (.notify (.-ui ctx)
                                 (str "✓ Marked done: " (:text task))))))))

              ;; Unknown subcommand
              (.notify (.-ui ctx)
                       (str "Unknown subcommand: \"" sub "\"\n"
                            "Usage: /spec [list | new <name> | import <name> <path> | "
                            "scaffold <kind> [<name>] | clarify <name> | "
                            "analyze <name> | install-skill | start <name> [--force] | "
                            "next | done <pattern> | end]")
                       "error"))))]

    (.on api "context_assembly" on-context-assembly)
    (swap! handlers conj ["context_assembly" on-context-assembly])

    ;; Auto-continue when an active-spec session hits the step budget.
    ;; AI SDK reports `tool-calls` as the finish reason when the model
    ;; wanted another step but `stopWhen: stepCountIs(N)` cut it off —
    ;; that's the signal we hit the cap mid-implementation. `length`
    ;; covers token-budget exhaustion (rarer). Cap auto-continues at 3
    ;; per session so a wedged model can't loop forever.
    (let [auto-continue-cap   3
          auto-continue-count (atom 0)
          on-agent-end
          (fn [data]
            (let [reason  (or (.-finishReason data)
                              (get data "finishReason")
                              "unknown")
                  active  (get-active)
                  done?   (or (= reason "stop") (= reason "content-filter"))]
              (cond
                done?
                ;; Natural completion — reset the counter so the next
                ;; user prompt gets a fresh budget for auto-continues.
                (reset! auto-continue-count 0)

                (and active
                     (or (= reason "tool-calls") (= reason "length"))
                     (< @auto-continue-count auto-continue-cap))
                (let [cwd   (js/process.cwd)
                      specs (discover-specs cwd)
                      spec  (get specs active)
                      open? (some? (next-open-task
                                    (parse-tasks (read-if-exists (:tasks spec)))))]
                  (when (and spec open?)
                    (let [n (swap! auto-continue-count inc)]
                      (dbg/debug "spec_driven/auto-continue"
                                 (str "spec=" active " reason=" reason
                                      " count=" n "/" auto-continue-cap))
                      ;; sendUserMessage with deliverAs: "followUp" routes
                      ;; through agent-loop/follow-up under the hood.
                      ;; Provider-agnostic, mode-agnostic.
                      ((.-sendUserMessage api)
                       (str "Continue with the next open task in `"
                            active "`. Update tasks.md as you "
                            "complete items (replace `- [ ]` "
                            "with `- [x]` on each task line). "
                            "Stop when there are no more open "
                            "tasks or you need user input.")
                       #js {:deliverAs "followUp"})))))))]
      (.on api "agent_end" on-agent-end)
      (swap! handlers conj ["agent_end" on-agent-end]))

    (.registerCommand api "spec"
                      #js {:description "Spec-driven development. Usage: /spec [list|new|import|scaffold|clarify|analyze|start|next|done|end]"
                           :handler spec-cmd})

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.unregisterCommand api "spec")
      ;; Clear active-spec from extension state so a hot-reload doesn't
      ;; silently re-attach with stale spec docs in every turn.
      (try (swap! (.-__state-atom api) dissoc :active-spec)
           (catch :default _ nil)))))
