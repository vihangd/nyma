(ns agent.resources.loader
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]
            [clojure.string :as str]
            [agent.resources.skills :as skills]
            [agent.settings.manager :refer [defaults]]
            [agent.builtin-extensions :as builtin-extensions]))

(def global-dir (path/join (os/homedir) ".nyma"))
(def project-dir ".nyma")

;; Builtins are no longer discovered by scanning dist/agent/extensions: that path
;; resolves INSIDE a single-file binary, where it does not exist, so a bundled
;; nyma loaded 2 of 40 extensions and nothing reported it. They come from
;; agent.builtin-extensions, which imports each one statically.

(def default-system-prompt
  "You are Nyma, an interactive CLI coding agent. You help users with software engineering tasks: fixing bugs, writing features, refactoring code, answering questions, and exploring codebases.

## Tools

You have specialized tools — always prefer them over bash:

- **read** — Read file contents. Use this, not `cat`/`head`/`tail` in bash.
- **write** — Write content to a file. Use this, not `echo >` in bash.
- **edit** — Replace exact text in a file. Use this, not `sed`/`awk` in bash.
- **bash** — Run shell commands. Only use for build, test, git, and install commands — not for tasks that have a dedicated tool.
- **think** — Reason through complex problems step-by-step before acting.
- **ls** — List directory contents. Use this, not `ls` in bash.
- **glob** — Find files by pattern. Use this, not `find` in bash.
- **grep** — Search file contents with regex. Use this, not `grep`/`rg` in bash.
- **web_fetch** — Fetch and extract content from a URL. Use this, not `curl` in bash.
- **web_search** — Search the web for information. Always use this for any web lookup — never use curl to search engines in bash.

When multiple independent tool calls are needed, make them in parallel.

## Guidelines

- Be concise. Keep responses under 4 lines unless the user asks for detail.
- Use markdown formatting.
- Do not add emojis unless the user requests them.
- Do not start responses with filler (\"Great!\", \"Sure!\", \"Certainly!\").
- Prefer editing existing files over creating new ones.
- Respect existing code conventions, libraries, and patterns in the codebase.
- Only make changes that are requested or clearly necessary — avoid over-engineering.
- When unsure about requirements, ask the user rather than guessing.
- Never generate or guess URLs unless confident they are correct.
- Use the think tool to plan before making complex multi-step changes.")

(defn- read-if-exists [file-path]
  (when (fs/existsSync file-path)
    (fs/readFileSync file-path "utf8")))

;;; ─── AGENTS.md discovery ───────────────────────────────────────
;;
;; Per the Linux Foundation Agentic AI Foundation `AGENTS.md` standard
;; (agents.md): a single Markdown file at project root with optional
;; nested AGENTS.md in subprojects. Closest to the file being edited
;; wins in a monorepo.
;;
;; nyma's policy: at session start we don't yet know which file the
;; agent will edit, so we collect every AGENTS.md walking UP from cwd
;; to (or just under) the user's home directory, plus three globally
;; scoped variants (`~/.nyma/AGENTS.md`, `~/AGENTS.md`,
;; `<project>/.nyma/AGENTS.md`). The discover function concatenates
;; them in lowest→highest precedence so later sections override
;; earlier on conflict.
;;
;; Optional YAML frontmatter (Cursor MDC convention: `description`,
;; `globs`, `alwaysApply`) is parsed but only the body is injected
;; into the system prompt — frontmatter governs *whether* the file
;; applies, not its content.

(def default-context-files
  "The `context-files` setting's default, for callers with no settings."
  (:context-files defaults))

(defn- first-existing
  "The first of `names` present in `dir`, as a path, or nil. One file per
   directory level: a repo that carries AGENTS.md and a CLAUDE.md copy
   for another tool must not have both injected."
  [dir names]
  (some (fn [n] (let [p (path/join dir n)] (when (fs/existsSync p) p))) names))

(defn walk-up-for-agents-md
  "Walk up from start-dir collecting the context file at each level (the
   first of `names` that exists there, AGENTS.md then CLAUDE.md by default).
   Stops at filesystem root or once the parent matches stop-at-dir
   (typically the user's home directory; the global AGENTS.md is
   collected separately to avoid double-counting).

   Returns paths in farthest-first order (lowest precedence first), so
   the caller can concatenate and have the closest-to-cwd file win on
   conflict — the agents.md spec's monorepo nesting rule."
  [start-dir stop-at-dir & [names]]
  (let [names   (or names default-context-files)
        results (atom [])]
    (loop [d start-dir]
      (when d
        (when-let [candidate (first-existing d names)]
          (swap! results conj candidate))
        (let [parent (path/dirname d)]
          (when (and (not= parent d)              ; not at filesystem root
                     (not= d stop-at-dir))         ; haven't crossed home yet
            (recur parent)))))
    (vec (reverse @results))))

(defn agents-body-or-nil
  "Given the raw content of an AGENTS.md file, return the body to inject
   into the system prompt, or nil if the file's frontmatter says it
   should be skipped at session-start.

   Skip rule: `alwaysApply: false` in YAML frontmatter (Cursor MDC
   convention). Path-scoped activation via `globs:` is honored at
   runtime (when a target file is known), not at session-start."
  [content]
  (let [parsed (skills/parse-frontmatter content)
        fm     (:frontmatter parsed)
        v      (when fm (aget fm "alwaysApply"))]
    (when-not (false? v)
      (:body parsed))))

(defn find-repo-root
  "Walk up from start-dir looking for a `.git` entry. Returns the
   directory containing it, or nil if none is found before reaching
   home-dir or the filesystem root. Used as a safe upper bound for
   the session-start ancestor walk — we'd rather miss a stray
   AGENTS.md than pick up `/tmp/AGENTS.md` or `~/AGENTS.md` twice."
  [start-dir home-dir]
  (loop [d start-dir]
    (cond
      (nil? d)        nil
      (= d home-dir)  nil
      (fs/existsSync (path/join d ".git")) d
      :else
      (let [parent (path/dirname d)]
        (when (not= parent d) (recur parent))))))

(defn find-agents-files
  "Returns a vec of context-file paths to load at session start, in lowest→
   highest precedence:
     1. ~/AGENTS.md                          (user global, Codex convention)
     2. ~/.nyma/AGENTS.md                    (user global, nyma-scoped)
     3. <repo-root>/AGENTS.md … <cwd-parent>/AGENTS.md
        (intermediate ancestors, farthest first — Codex/Goose monorepo
        parity when launching inside a subdir of a git repo)
     4. <cwd>/AGENTS.md                      (project root or subdir)
     5. <cwd>/.nyma/AGENTS.md                (nyma-private project scope)

   At each of those directories the first of `names` that exists is taken
   (the `context-files` setting; AGENTS.md then CLAUDE.md by default), so
   a level holding both contributes one file.

   The ancestor walk is bounded by the nearest repository root, so we
   never escape the repo and never pick up unrelated parents like
   `/tmp/AGENTS.md`. If cwd is not inside a repo (or is itself the
   repo root), step 3 contributes nothing.

   Runtime monorepo resolution when the editing target is known (e.g.
   `packages/foo/AGENTS.md` overriding the root) still uses
   `walk-up-for-agents-md` from the call-site."
  ([] (find-agents-files (os/homedir) (js/process.cwd)))
  ([home cwd] (find-agents-files home cwd nil))
  ([home cwd names]
   (let [names     (or names default-context-files)
         repo-root (find-repo-root cwd home)
         ancestors (if (and repo-root (not= repo-root cwd))
                     (walk-up-for-agents-md (path/dirname cwd) repo-root names)
                     [])]
     (->> (concat
           [(first-existing home names)
            (first-existing (path/join home ".nyma") names)]
           ancestors
           [(first-existing cwd names)
            (first-existing (path/join cwd ".nyma") names)])
          (remove nil?)
          (distinct)
          vec))))

(defn- discover-themes [dirs]
  (reduce
   (fn [acc dir]
     (if (fs/existsSync dir)
       (let [entries (fs/readdirSync dir)]
         (reduce
          (fn [m file]
            ;; `if`, not `when`: a nil return for a non-.json entry
            ;; (.DS_Store, README, subdir) would become the accumulator and
            ;; silently wipe every theme collected so far.
            (if (.endsWith file ".json")
              (let [name (subs file 0 (- (count file) 5))]
                (assoc m name {:path (path/join dir file)}))
              m))
          acc
          entries))
       acc))
   {}
   dirs))

(defn- discover-skills
  "Delegates to agent.resources.skills/discover-skills which parses YAML
   frontmatter and produces records with :description, :allowed-tools,
   :paths, :disable-model-invocation, etc. per agentskills.io."
  [dir]
  (skills/discover-skills dir))

(def ^:private cross-vendor-skill-roots
  "Skill folders we read in addition to .nyma/skills/, in lowest→highest
   precedence order. Mirrors Cursor 2.4's cross-vendor discovery.

   The ordering matters when the same skill name appears in multiple
   sources: the LAST one wins (we use `merge` not `merge-with`). So:
     1. cross-vendor global   (~/.codex, ~/.cursor, ~/.claude, ~/.agents)
     2. nyma global           (~/.nyma)
     3. cross-vendor project  (project/.codex, .cursor, .claude, .agents)
     4. nyma project          (project/.nyma)        ← always wins"
  [".codex/skills"
   ".cursor/skills"
   ".claude/skills"
   ".agents/skills"])

(defn discover-all-skills
  "Walk every supported skill folder under home and project, merging
   the records in precedence order. Adds a :source field so tooling
   can show which directory a skill came from."
  [home-dir cwd]
  (let [resolve   (fn [base sub] (path/join base sub))
        from-dir  (fn [d source]
                    (when-let [m (skills/discover-skills d source)]
                      (->> m
                           (map (fn [[k v]] [k (assoc v :source source)]))
                           (into {}))))
        ;; Lowest → highest precedence:
        global-cv (->> cross-vendor-skill-roots
                       (map #(from-dir (resolve home-dir %) (str "global:" %)))
                       (reduce merge {}))
        global-nyma (or (from-dir (resolve home-dir ".nyma/skills") "global:.nyma/skills") {})
        project-cv (->> cross-vendor-skill-roots
                        (map #(from-dir (resolve cwd %) (str "project:" %)))
                        (reduce merge {}))
        project-nyma (or (from-dir (resolve cwd ".nyma/skills") "project:.nyma/skills") {})]
    (merge global-cv global-nyma project-cv project-nyma)))

(defn discover-prompts
  "`<dir>/<name>.md` → {name {:template :description :argument-hint :path}}.
   Optional frontmatter (`description`, `argument-hint`) describes the
   `/<name>` command; the body is the template."
  [dir]
  (when (fs/existsSync dir)
    (let [entries (fs/readdirSync dir)]
      (->> entries
           (filter #(.endsWith % ".md"))
           (map (fn [file]
                  (let [name (subs file 0 (- (count file) 3))
                        content (fs/readFileSync (path/join dir file) "utf8")
                        {:keys [frontmatter body]} (skills/parse-frontmatter content)
                        fm (fn [k] (when frontmatter (aget frontmatter k)))]
                    [name {:template      body
                           :description   (fm "description")
                           :argument-hint (fm "argument-hint")
                           :path          (path/join dir file)}])))
           (into {})))))

(defn ^:async discover
  "Discover all resources from global and project directories.
   Optionally accepts an events bus to emit resources_discover for extensions,
   and `:context-files` (the setting) naming the files to read as AGENTS.md;
   the loader has no settings manager of its own, so the caller passes it."
  [& [{:keys [events reason context-files]}]]
  (let [;; Skill discovery walks both nyma's own paths AND the cross-vendor
        ;; paths used by Claude Code, Cursor 2.4, Codex, and the proposed
        ;; agentskills.io standard. Project paths win over global; nyma's
        ;; own paths win over cross-vendor on name collision (matches MCP
        ;; discovery precedence in agent_shell/features/mcp_discovery.cljs).
        cwd     (js/process.cwd)
        home    (os/homedir)
        skills  (discover-all-skills home cwd)

        prompts (merge
                 (or (discover-prompts (path/join global-dir "prompts")) {})
                 (or (discover-prompts (path/join project-dir "prompts")) {}))

        themes  (discover-themes [(path/join global-dir "themes")
                                  (path/join project-dir "themes")])

        ;; AGENTS.md: walk every collected file, parse optional YAML
        ;; frontmatter, and concatenate the BODIES (not the raw files
        ;; with their frontmatter blocks).
        agents-md (->> (find-agents-files home cwd context-files)
                       (map (fn [p] (fs/readFileSync p "utf8")))
                       (keep agents-body-or-nil)
                       (str/join "\n\n"))

        system-md (or (read-if-exists (path/join project-dir "SYSTEM.md"))
                      (read-if-exists (path/join global-dir "SYSTEM.md")))

        ;; Emit resources_discover event so extensions can contribute paths
        ext-resources (when events
                        (js-await
                         ((:emit-collect events) "resources_discover"
                                                 #js {:cwd    (js/process.cwd)
                                                      :reason (or reason "startup")})))

        ;; Merge extension-contributed resources
        ext-skill-paths  (get ext-resources "skillPaths")
        ext-prompt-paths (get ext-resources "promptPaths")
        ext-theme-paths  (get ext-resources "themePaths")

        extra-skills  (when (seq ext-skill-paths)
                        (reduce (fn [acc p] (merge acc (or (discover-skills p) {})))
                                {} ext-skill-paths))
        extra-prompts (when (seq ext-prompt-paths)
                        (reduce (fn [acc p] (merge acc (or (discover-prompts p) {})))
                                {} ext-prompt-paths))
        extra-themes  (when (seq ext-theme-paths)
                        (discover-themes ext-theme-paths))]

    {:skills    (merge skills (or extra-skills {}))
     :prompts   (merge prompts (or extra-prompts {}))
     :themes    (merge themes (or extra-themes {}))
     :agents-md agents-md
     :system-md system-md
     :theme     nil

     :build-system-prompt
     (fn [& [{:keys [append]}]]
       (let [env-block (str "\n\n## Environment\n"
                            "- Working directory: " (js/process.cwd) "\n"
                            "- Platform: " (.-platform js/process) " " (.-arch js/process) "\n"
                            "- Date: " (.toISOString (js/Date.)) "\n")
             ;; Skills auto-listed for the model: those NOT marked
             ;; `disable-model-invocation: true`. Hidden skills remain
             ;; reachable via `/skill <name>` but are kept out of the
             ;; system prompt to save tokens.
             listable    (->> skills
                              (remove (fn [[_ s]] (:disable-model-invocation s))))
             skills-block (when (seq listable)
                            (str "\n\n## Available Skills\n"
                                 "Call the `skill` tool with a name to load one when its "
                                 "description fits the task; the user can also /skill <name> or /skills.\n"
                                 (->> listable
                                      (map (fn [[sname skill]]
                                             (let [desc (or (:description skill)
                                                            (skills/first-skill-line
                                                             (or (:body skill) (:markdown skill))))]
                                               (str "- " sname (when (seq desc) (str ": " desc))))))
                                      (str/join "\n"))))]
         (str (or system-md default-system-prompt)
              env-block
              skills-block
              (when (seq agents-md) (str "\n\n" agents-md))
              (when append (str "\n\n" append)))))

     ;; User extension directories only. These are real directories on the
     ;; machine running nyma, so scanning them works from a source tree and from
     ;; a binary alike.
     :extension-dirs
     [(path/join global-dir "extensions")
      (path/join project-dir "extensions")]

     ;; The statically compiled builtins. NYMA_NO_BUILTIN_EXT still turns them
     ;; off wholesale — it was the only thing the old dir-list branch did.
     :builtin-extensions
     (if (.-NYMA_NO_BUILTIN_EXT js/process.env)
       []
       builtin-extensions/registry)}))
