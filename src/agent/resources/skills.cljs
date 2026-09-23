(ns agent.resources.skills
  "Discovery and activation of `SKILL.md` skill packages.

   Skills follow the agentskills.io specification: each skill is a directory
   under `.nyma/skills/` (or, in Phase 2, the cross-vendor `.agents/skills/`,
   `.claude/skills/`, `.cursor/skills/`, `.codex/skills/` paths). The directory
   name is the skill name. The directory contains:

     SKILL.md     - YAML frontmatter + markdown body. Required.
     tools.cljs   - Optional native tool extension, loaded on activation.
     tools.ts     - Optional alternative.
     scripts/     - Optional bundled scripts (Anthropic convention).
     references/  - Optional bundled references.
     assets/      - Optional bundled assets.

   Frontmatter fields supported in this loader:

     name                       - REQUIRED. Must equal the directory name.
                                  1-64 chars, lowercase a-z, digits, hyphens.
     description                - REQUIRED. 1-1024 chars. Replaces the legacy
                                  first-non-blank-line heuristic.
     license                    - Optional, pass-through.
     compatibility              - Optional, pass-through.
     metadata                   - Optional map, pass-through.
     allowed-tools              - Optional. Tools the skill may call without a
                                  permission prompt while it is active (a deny
                                  from a permission handler still wins).
     disable-model-invocation   - Optional bool. If true, skill is hidden from
                                  the model's auto-listing and from the `skill`
                                  tool; only `/skill <name>` triggers it.
     paths                      - Optional list of globs. If present, the skill
                                  description is only injected into the system
                                  prompt when an active file matches.
     triggers                   - Optional list of phrase substrings. If any
                                  match the user's prompt, auto-activate for
                                  that turn.

   Other Claude-Code-extended fields (`context: fork`, `hooks`, `model`,
   `effort`) are not read: nothing acts on them, and a parsed-but-ignored
   field reads as support. They stay reachable under `:frontmatter`.

   Reference: https://agentskills.io/specification"
  (:require [agent.debug :as d]
            ["node:path" :as path]
            ["node:fs" :as fs]
            ["ai" :refer [tool]]
            ["zod" :as z]
            [clojure.string :as str]
            [agent.token-estimation :refer [estimate-tokens]]
            [agent.utils.template-args :as template-args]
            [agent.extension-loader :refer [load-extension]]
            [agent.extensions :refer [create-extension-api]]))

;;; ─── Frontmatter parsing ───────────────────────────────────────

(def ^:private name-pattern
  ;; Per agentskills.io: 1-64 chars, lowercase a-z, digits, hyphens.
  ;; No leading/trailing/consecutive hyphens.
  (js/RegExp. "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$"))

(def max-description-length
  "agentskills.io caps `description` at 1024 characters. Over that is a
   conformance finding, not a rejection — the skill still works."
  1024)

(defn valid-name?
  "Per agentskills.io: 1-64 chars, lowercase a-z, digits, hyphens.
   No leading/trailing or consecutive hyphens."
  [s]
  (boolean
   (and (string? s)
        (.test name-pattern s)
        (not (.includes s "--")))))

(defn parse-frontmatter
  "Split a SKILL.md text into {:frontmatter <map>, :body <string>}.

   Frontmatter is YAML between the first two `---` lines. If absent or
   unparseable, returns an empty frontmatter map and the original text
   as body. Uses Bun.YAML.parse — no new dependency."
  [text]
  (if (or (nil? text) (not (string? text)))
    {:frontmatter {} :body (or text "")}
    ;; \r? everywhere: a SKILL.md saved with Windows line endings used to match
    ;; nothing here, so its frontmatter was never parsed and the whole file —
    ;; YAML block included — became the injected body, with name, description,
    ;; allowed-tools and triggers all silently reverting to defaults.
    (let [m (.match text (js/RegExp. "^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?([\\s\\S]*)$"))]
      (if (nil? m)
        {:frontmatter {} :body text}
        (let [yaml-text (aget m 1)
              body      (or (aget m 2) "")
              parsed    (try (.parse js/Bun.YAML yaml-text)
                             (catch :default _e nil))]
          ;; `:malformed?` separates "this file has no frontmatter" from "this
          ;; file has frontmatter that does not parse" — the second used to be
          ;; indistinguishable, so a typo cost a skill its description and its
          ;; triggers with nothing said.
          {:frontmatter (or parsed #js {})
           :malformed?  (nil? parsed)
           :body        body})))))

(defn- get-fm
  "Read a frontmatter field. Frontmatter is a JS object from Bun.YAML.parse,
   so keys are always strings."
  [fm k]
  (cond
    (nil? fm)    nil
    (object? fm) (aget fm k)
    (map? fm)    (get fm k)
    :else        nil))

(defn- list-fm
  "Read a list-shaped frontmatter field. Accepts JS array, CLJS vector,
   or whitespace-separated string (per agentskills.io which says
   `allowed-tools` is space-separated)."
  [fm k]
  (let [v (get-fm fm k)]
    (cond
      (nil? v)            nil
      (.isArray js/Array v) (vec (js/Array.from v))
      (vector? v)         v
      (sequential? v)     (vec v)
      (string? v)         (vec (filter (complement str/blank?)
                                       (str/split v #"\s+")))
      :else               nil)))

(defn- bool-fm [fm k]
  (let [v (get-fm fm k)]
    (cond
      (boolean? v)        v
      (= v "true")        true
      (= v "false")       false
      :else               nil)))

;;; ─── Skill record building ─────────────────────────────────────

(def default-description-budget
  "Fallback when no `skills.max-description-length` setting is passed."
  500)

(defn budget-description
  "Trim a description for the always-on listing. Same shape as the MCP tool
   bridge's cap: cut, then say so, so the model knows there was more."
  [desc & [limit]]
  (let [d (str (or desc ""))
        n (or limit default-description-budget)]
    (if (and (number? n) (pos? n) (> (count d) n))
      (str (subs d 0 n) " …[truncated; " (count d) " chars]")
      d)))

(defn first-skill-line
  "Legacy fallback for skills with no frontmatter description. Returns the
   first non-blank, non-heading line of a body string."
  [markdown]
  (or (->> (str/split-lines (or markdown ""))
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           first)
      ""))

(defn- build-skill-record
  "Read SKILL.md from skill-dir and return [dir-name skill-record], or nil
   when the directory holds no SKILL.md. Nothing else rejects a skill: a
   spec violation is recorded in `:findings` and the skill still loads, so
   `/skills doctor` can report it without breaking someone's setup."
  [skill-dir dir-name source]
  (let [skill-md-path (path/join skill-dir "SKILL.md")]
    (when (fs/existsSync skill-md-path)
      (let [raw       (fs/readFileSync skill-md-path "utf8")
            {:keys [frontmatter body malformed?]} (parse-frontmatter raw)
            fm-name   (get-fm frontmatter "name")
            ;; The DIRECTORY name is the skill's identity, per agentskills.io
            ;; ("must match the parent directory name"). Keying by the
            ;; frontmatter name instead let a skill in any directory declare
            ;; `name: <something-you-trust>` and, because the project tier
            ;; merges last, silently replace a global skill of that name. A
            ;; disagreeing frontmatter name is now a finding, not an identity.
            name      dir-name
            description (or (get-fm frontmatter "description")
                            (first-skill-line body))
            has-tools (or (fs/existsSync (path/join skill-dir "tools.ts"))
                          (fs/existsSync (path/join skill-dir "tools.cljs")))
            findings  (cond-> []
                        malformed?
                        (conj "frontmatter is not valid YAML; it was ignored")
                        (not (valid-name? dir-name))
                        (conj (str "directory name \"" dir-name "\" is not a valid skill name"
                                   " (1-64 chars, lowercase a-z, 0-9 and single hyphens)"))
                        (and fm-name (not= fm-name dir-name))
                        (conj (str "frontmatter name \"" fm-name "\" does not match directory \""
                                   dir-name "\"; the directory name is used"))
                        (> (count (str description)) max-description-length)
                        (conj (str "description is " (count (str description)) " chars; the spec caps it at "
                                   max-description-length)))]
        [name {:dir         skill-dir
               :name        name
               :declared-name fm-name
               :findings    findings
               :description description
               :license     (get-fm frontmatter "license")
               :compatibility (get-fm frontmatter "compatibility")
               :metadata    (get-fm frontmatter "metadata")
               :allowed-tools (list-fm frontmatter "allowed-tools")
               :disable-model-invocation (bool-fm frontmatter "disable-model-invocation")
               :paths       (list-fm frontmatter "paths")
               :triggers    (list-fm frontmatter "triggers")
               :frontmatter frontmatter
               :body        body
               ;; Legacy: kept so existing callers (activate-skill,
               ;; build-system-prompt) keep working unchanged.
               :markdown    raw
               :has-tools   has-tools
               :source      source
               :active      false}]))))

(defn discover-skills
  "Scan a directory for skill packages. Each subdirectory containing a
   SKILL.md becomes a skill entry keyed by its directory name."
  ([dir] (discover-skills dir nil))
  ([dir source]
   (when (fs/existsSync dir)
     (let [entries (fs/readdirSync dir #js {:withFileTypes true})]
       (->> entries
            (filter #(.isDirectory %))
            (keep (fn [entry]
                    (build-skill-record (path/join dir (.-name entry))
                                        (.-name entry)
                                        (or source dir))))
            (into {}))))))

;;; ─── Activation ────────────────────────────────────────────────

(defn skill-body
  "The instructions a skill injects: the markdown BODY. A record built by
   discover-skills carries it; a hand-built one (tests, extensions) may
   only carry the raw file, whose frontmatter is metadata, not prompt."
  [skill]
  (or (:body skill)
      (:body (parse-frontmatter (:markdown skill)))))

(defn- escape-attr
  "Make a value safe inside the `<skill name=\"...\">` wrapper. A skill's name
   comes from a directory a repository controls, so an unescaped one could
   close the tag and continue with instructions of its own."
  [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn project-skill?
  "True for a skill discovered under the working directory rather than the
   user's home. Such a skill is INSTRUCTIONS-ONLY: a repository you cloned
   must not be able to pre-approve tools or run code just because you opened
   it. `:scope` is stamped by `loader/discover-all-skills`; a record without
   one (tests, extension-contributed `skillPaths`) keeps full behaviour,
   because it already came from trusted code."
  [skill]
  (= :project (:scope skill)))

(defn reference-files
  "Level 3 of progressive disclosure: the files a skill bundles under
   `references/`. Listed on activation, never read — the model pulls in what
   it needs with the ordinary `read` tool, which is the whole point of the
   level existing. Relative paths, because that is what the body's own links
   use."
  [skill]
  (let [dir (path/join (str (:dir skill)) "references")]
    (if (and (:dir skill) (fs/existsSync dir))
      (->> (fs/readdirSync dir #js {:withFileTypes true})
           (filter #(.isFile %))
           (map #(str "references/" (.-name %)))
           sort
           vec)
      [])))

(defn- reference-block
  [skill]
  (when-let [refs (seq (reference-files skill))]
    (str "\n\nReference files in " (:dir skill) " — read one when you need it:\n"
         (str/join "\n" (map #(str "- " %) refs)))))

(defn skill-message
  "The system message an activated skill contributes. `:skill` tags it so
   deactivate-skill can find it again; the tag never reaches disk — the
   session persister keeps only user/assistant turns, and only their
   :role/:content."
  [skill args]
  {:role    "system"
   :skill   (:name skill)
   :content (str "<skill name=\"" (escape-attr (:name skill)) "\">\n"
                 ;; keep-when-empty?: a body read with no args must still show
                 ;; its quoted shell snippets (`echo $1`) as written.
                 (template-args/substitute (skill-body skill) args {:keep-when-empty? true})
                 (reference-block skill)
                 "\n</skill>")})

(defn- registry-tools
  "Every tool the registry currently holds, or an empty map for an agent
   built without one (tests)."
  [agent]
  (or (when-let [all (:all (:tool-registry agent))] (all)) {}))

(defn ^:async activate-skill
  "Inject skill instructions into context and load skill tools.
   No-op if the skill is already active. `args` fill `$ARGUMENTS`/`$N`
   in the body. Returns the injected body text (nil when nothing was
   injected) so a caller can show the model what it asked for."
  [skills name agent & [args]]
  (when-let [skill (get skills name)]
    (when-not (contains? (:active-skills @(:state agent)) name)
      (let [msg      (skill-message (assoc skill :name name) args)
            project? (project-skill? skill)
            allowed  (set (or (:allowed-tools skill) []))]
        ;; A project skill may not pre-approve its own tools: `allowed-tools`
        ;; downgrades a permission ask to an allow, so honouring it here would
        ;; let a cloned repo silence the prompt for bash.
        (when (and project? (seq allowed))
          (d/warn-quiet
           "skills"
           (str "project skill \"" name "\" may not pre-approve tools; ignoring allowed-tools: "
                (str/join ", " (sort allowed))
                " — you will be asked as usual. Move the skill to ~/.nyma/skills to trust it.")))
        ;; ...and may not run code. A skill's tools.* gets the FULL extension
        ;; API, which real extensions only reach after declaring capabilities.
        (when (and project? (:has-tools skill))
          (d/warn-quiet
           "skills"
           (str "project skill \"" name "\" may not run code; skipping "
                (:dir skill) "/tools.* — move the skill to ~/.nyma/skills to load it.")))
        ;; Load BEFORE touching state. A throwing tools.* used to leave the
        ;; skill marked active with its grant applied and its body injected.
        (when (and (not project?) (:has-tools skill))
          (let [tool-file (or (let [p (path/join (:dir skill) "tools.cljs")]
                                (when (fs/existsSync p) p))
                              (let [p (path/join (:dir skill) "tools.ts")]
                                (when (fs/existsSync p) p)))]
            (when tool-file
              ;; Remember what the skill adds, so deactivating can take it back
              ;; out again. Without this a skill's tools outlived the skill for
              ;; the rest of the session.
              (let [before (set (keys (registry-tools agent)))
                    ext-fn (js-await (load-extension tool-file))]
                (ext-fn (create-extension-api agent))
                (let [added (remove before (keys (registry-tools agent)))]
                  (when (seq added)
                    (swap! (:state agent) assoc-in [:skill-tools name] (set added))))))))
        (swap! (:state agent)
               (fn [s]
                 (-> s
                     (update :messages conj msg)
                     (update :active-skills conj name)
                     ;; The permission gate reads this rather than the skill
                     ;; records, which live on `resources`, not the agent.
                     ;; Key always present, empty for a project skill, so the
                     ;; shape stays uniform for `skill-allows-tool?`.
                     (assoc-in [:skill-allowed-tools name]
                               (if project? #{} allowed)))))
        (count-activation! name :explicit)
        (:content msg)))))

(defn deactivate-skill
  "Remove a skill: its tracking entry, its tool allowances, and the system
   message it injected."
  [name agent]
  ;; Tools the skill registered go too. They used to stay in the registry for
  ;; the rest of the session, so a deactivated skill still armed the model.
  (when-let [unregister (:unregister (:tool-registry agent))]
    (doseq [t (get (:skill-tools @(:state agent)) name)]
      (try (unregister t) (catch :default _e nil))))
  (swap! (:state agent)
         (fn [s]
           (-> s
               (update :active-skills disj name)
               (update :skill-allowed-tools dissoc name)
               (update :skill-tools dissoc name)
               (update :messages (fn [ms] (vec (remove #(= (:skill %) name) ms))))))))

;;; ─── The `skill` tool ──────────────────────────────────────────

(defn model-invocable
  "Skills the model may activate itself: everything not marked
   `disable-model-invocation`, sorted by name."
  [skills]
  (->> skills
       (remove (fn [[_ s]] (:disable-model-invocation s)))
       (sort-by first)))

(defn skill-tool-description
  [skills]
  ;; NAMES only. Every description already sits in the "## Available Skills"
  ;; block of the system prompt, and repeating them here sent all of them
  ;; twice in every request — the cost doubled with the size of the user's
  ;; skill library, for no information the model did not already have.
  (str "Activate a skill: its instructions are returned to you and stay in "
       "context for the rest of the session. See \"Available Skills\" in the "
       "system prompt for what each one is for. Names: "
       (str/join ", " (map first (model-invocable skills)))))

(defn ^:async skill-tool-execute
  [skills agent {:keys [name args]}]
  (let [sname (str name)
        argv  (vec (or args []))]
    (cond
      (or (not (get skills sname))
          (:disable-model-invocation (get skills sname)))
      (throw (js/Error. (str "Unknown skill \"" sname "\". Available: "
                             (str/join ", " (map first (model-invocable skills))))))

      (contains? (:active-skills @(:state agent)) sname)
      (str "Skill \"" sname "\" is already active; its instructions are in context.")

      :else
      (js-await (activate-skill skills sname agent argv)))))

(defn skill-tool
  "The model-callable counterpart of `/skill`. Same activation, same
   dedupe; the body comes back as the tool result so the model reads it
   in the turn it asked."
  [skills agent]
  (tool
   #js {:description (skill-tool-description skills)
        :inputSchema (.object z
                              #js {:name (-> (.string z) (.describe "Skill name"))
                                   :args (-> (.array z (.string z)) (.optional)
                                             (.describe "Arguments for $ARGUMENTS / $1… in the skill"))})
        :execute     (fn [input] (skill-tool-execute skills agent input))}))

(defn register-skill-tool!
  "Put the `skill` tool for `skills` on the agent's registry, replacing the
   one built at startup. Called again on /reload: the tool closes over the
   skill map, so without this a skill added since launch was `/skill`-able
   but \"Unknown skill\" to the model. With no skills the tool is removed.
   A --tools filter that left it inactive stays respected."
  [agent skills]
  (let [reg      (:tool-registry agent)
        existed? (contains? ((:all reg)) "skill")
        active?  (contains? ((:get-active reg)) "skill")]
    ;; Unregister first: registering over an existing name stashes the old
    ;; tool as an "original" that a later unregister would resurrect.
    (when existed? ((:unregister reg) "skill"))
    (when (seq skills)
      ((:register reg) "skill" (skill-tool skills agent))
      (when (and existed? (not active?))
        ((:set-active reg) (disj (set (keys ((:get-active reg)))) "skill"))))))

;;; ─── Path glob matching for path-scoped skills ─────────────────

(defn- glob->regex
  "Compile a glob pattern into a JS RegExp.
   Subset: `**` matches anything including slashes; `*` matches a single
   path segment; `?` matches one char. `/**/` collapses to `(?:/.+)?/`
   so a middle directory is optional (e.g. src/**/*.ts matches src/a.ts)."
  [glob]
  (let [;; Sentinel control bytes (unlikely in any path):
        ;;   \x01 = /**/ middle    -> optional intermediate dirs
        ;;   \x02 = leading **/    -> optional dir prefix
        ;;   \x03 = bare **        -> match anything incl. /
        ;; Inserted BEFORE single-* expansion so the introduced
        ;; regex `.*` is not re-rewritten by `* -> [^/]*`.
        s (-> glob
              (.replace (js/RegExp. "^\\*\\*/" "") "")
              (.replace (js/RegExp. "/\\*\\*/" "g") "")
              (.replace (js/RegExp. "\\*\\*" "g") ""))
        escaped (-> s
                    (.replace (js/RegExp. "[.+^$(){}|\\[\\]\\\\]" "g") "\\$&")
                    (.replace (js/RegExp. "\\*" "g") "[^/]*")
                    (.replace (js/RegExp. "\\?" "g") ".")
                    (.replace (js/RegExp. "" "g") ".*")
                    (.replace (js/RegExp. "" "g") "(?:.+/)?")
                    (.replace (js/RegExp. "" "g") "(?:/.+)?/"))]
    (js/RegExp. (str "^" escaped "$"))))

(defn path-matches-skill?
  "Returns true if at least one of the skill's `paths` globs matches `file-path`.
   Skills with no `paths` field are considered always-applicable (returns true)."
  [skill file-path]
  (let [globs (:paths skill)]
    (if (or (nil? globs) (empty? globs))
      true
      (boolean
       (some (fn [g]
               (.test (glob->regex g) file-path))
             globs)))))

;;; ─── Trigger phrase matching ───────────────────────────────────

(defn- trigger-pattern
  "A trigger matched as a bare substring fired far too easily: a skill
   declaring `the` matched almost every prompt, and inside words like
   `theme`. Require a non-alphanumeric boundary on each side instead."
  [t]
  (let [lit (-> (str/lower-case (str t))
                (.replace (js/RegExp. "[.*+?^${}()|\\[\\]\\\\]" "g") "\\$&"))]
    (js/RegExp. (str "(^|[^a-z0-9])" lit "([^a-z0-9]|$)"))))

(defn matching-triggers
  "Given a user prompt and a map of skills, return the names of skills
   whose `triggers` (phrases) appear as substrings in the prompt
   (case-insensitive). Skills with no triggers do not auto-activate."
  [skills prompt]
  (let [lc (some-> prompt str .toLowerCase)]
    (when (and lc (not (str/blank? lc)))
      (->> skills
           (keep (fn [[name skill]]
                   (let [trigs (:triggers skill)]
                     (when (and (seq trigs)
                                (some #(.test (trigger-pattern %) lc) trigs))
                       name))))
           vec))))


(def activation-counts
  "skill name → {:explicit n :auto n} for this session. Session-scoped and
   deliberately not persisted: the question it answers is \"is this skill
   earning the tokens it costs me right now\". Read by `/skills doctor`."
  (atom {}))

(defn count-activation!
  [name kind]
  (swap! activation-counts update name
         (fn [m] (update (or m {:explicit 0 :auto 0}) kind (fnil inc 0)))))

(defn doctor-rows
  "One row per skill: what it costs, where it came from, what it is allowed to
   do, and how often it actually fired. `budget` is the per-description cap
   the system-prompt listing applies."
  [skills & [budget]]
  (let [limit (or budget default-description-budget)]
    (->> skills
         (map (fn [[sname skill]]
                (let [desc  (str (or (:description skill) ""))
                      shown (budget-description desc limit)
                      cnt   (get @activation-counts sname {:explicit 0 :auto 0})]
                  {:name        sname
                   :scope       (if (project-skill? skill) "project" "global")
                   :source      (:source skill)
                   :desc-chars  (count desc)
                   :desc-tokens (estimate-tokens shown)
                   :truncated?  (not= desc shown)
                   :hidden?     (boolean (:disable-model-invocation skill))
                   :has-tools   (boolean (:has-tools skill))
                   ;; A project skill's tools never load and its allowed-tools
                   ;; is ignored, so say so here rather than in a doc nobody
                   ;; reads at the moment they wonder why.
                   :tools-gated (boolean (and (:has-tools skill) (project-skill? skill)))
                   :grants      (vec (or (:allowed-tools skill) []))
                   :grants-gated (boolean (and (seq (:allowed-tools skill)) (project-skill? skill)))
                   :references  (count (reference-files skill))
                   :license     (:license skill)
                   :compatibility (:compatibility skill)
                   :metadata    (:metadata skill)
                   :findings    (vec (or (:findings skill) []))
                   :explicit    (:explicit cnt)
                   :auto        (:auto cnt)})))
         (sort-by :name)
         vec)))

;;; ─── Auto-activation: triggers and paths ────────────────────────
;;; `triggers` and `paths` were parsed since the skills format landed and read
;;; by nothing (roadmap: "parsed but unwired"). A skill's description is always
;;; listed (level 1); its BODY (level 2) is injected for one turn when the
;;; user's prompt contains a trigger phrase, or a file the session has touched
;;; matches one of its path globs. Injected as `volatile-additions`, so it sits
;;; after the cache boundary and a turn without a match costs nothing.

(def max-auto-activated
  "How many skill bodies one turn may auto-inject."
  3)

(def max-touched-paths
  "How many distinct paths the session remembers for path-scoped activation.
   The set only ever grew, so a path touched once kept a skill's whole body in
   every later turn for the rest of the session."
  200)

(defn auto-activated
  "Names of skills to activate this turn: trigger matches on `prompt` plus
   path-scoped skills matching any of `touched` paths. Hidden skills
   (`disable-model-invocation`) never auto-activate. Pure."
  [skills prompt touched & [active]]
  (let [visible (remove (fn [[_ s]] (:disable-model-invocation s)) skills)
        ;; A project skill may auto-inject on a PATH match: that means you are
        ;; working in a file it claims, which is the case auto-activation was
        ;; built for. It may NOT auto-inject on a trigger match — those phrases
        ;; are chosen by the skill's author and tested against words you wrote,
        ;; so a cloned repo could otherwise put its own instructions in front
        ;; of the model on every turn without you or the model choosing it.
        triggerable (remove (fn [[_ s]] (project-skill? s)) visible)
        by-trig (set (matching-triggers (into {} triggerable) prompt))
        by-path (set (keep (fn [[n s]]
                             (when (and (seq (:paths s))
                                        (some #(path-matches-skill? s %) touched))
                               n))
                           visible))
        ;; A skill activated explicitly already has its body in context as a
        ;; message; injecting it again per turn is pure duplication.
        chosen  (remove (fn [n] (contains? (set (or active #{})) n))
                        (into by-trig by-path))]
    ;; Cap the per-turn total. Bodies land after the cache boundary, so every
    ;; one of them is re-sent on every turn it matches.
    (vec (take max-auto-activated (sort chosen)))))

(defn activation-block
  "The prompt text for auto-activated skill bodies, or nil."
  [skills names]
  (when (seq names)
    (str/join "\n\n"
              (map (fn [n]
                     (let [s (get skills n)]
                       (str "## Skill (auto-activated): " n "\n\n" (or (:body s) (:markdown s) ""))))
                   names))))

(defn- prompt-text [user-message]
  (let [c (when user-message (or (:content user-message) (.-content user-message)))]
    (cond
      (string? c) c
      (js/Array.isArray c) (str/join " " (keep #(or (.-text %) (get % "text")) c))
      :else "")))

(def ^:private activation-handlers (atom nil))

(defn register-skill-activation!
  "Subscribe the auto-activation hooks on `agent` for `skills`, replacing any
   earlier registration (called again on /reload). Tracks paths from
   `tool_call` inputs so path-scoped skills follow what the session touches."
  [agent skills]
  (let [events  (:events agent)
        touched (atom #{})]
    (when-let [[on-call on-start] @activation-handlers]
      ((:off events) "tool_call" on-call)
      ((:off events) "before_agent_start" on-start))
    (let [on-call  (fn [d]
                     (when-let [p (some-> d .-input .-path)]
                       (swap! touched (fn [ps]
                                        (if (>= (count ps) max-touched-paths)
                                          ps
                                          (conj ps (str p)))))))
          on-start (fn [d]
                     (let [active (:active-skills @(:state agent))
                           names  (auto-activated skills (prompt-text (.-userMessage d))
                                                  @touched active)]
                       (when-let [block (activation-block skills names)]
                         (doseq [n names] (count-activation! n :auto))
                         #js {"volatile-additions" #js [block]})))]
      ((:on events) "tool_call" on-call)
      ((:on events) "before_agent_start" on-start)
      (reset! activation-handlers [on-call on-start])
      nil)))
