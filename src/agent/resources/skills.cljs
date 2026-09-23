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
            [agent.utils.template-args :as template-args]
            [agent.extension-loader :refer [load-extension]]
            [agent.extensions :refer [create-extension-api]]))

;;; ─── Frontmatter parsing ───────────────────────────────────────

(def ^:private name-pattern
  ;; Per agentskills.io: 1-64 chars, lowercase a-z, digits, hyphens.
  ;; No leading/trailing/consecutive hyphens.
  (js/RegExp. "^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$"))

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
    (let [m (.match text (js/RegExp. "^---\\n([\\s\\S]*?)\\n---\\n?([\\s\\S]*)$"))]
      (if (nil? m)
        {:frontmatter {} :body text}
        (let [yaml-text (aget m 1)
              body      (or (aget m 2) "")
              parsed    (try (.parse js/Bun.YAML yaml-text)
                             (catch :default _e nil))]
          {:frontmatter (or parsed #js {})
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

(defn first-skill-line
  "Legacy fallback for skills with no frontmatter description. Returns the
   first non-blank, non-heading line of a body string."
  [markdown]
  (or (->> (str/split-lines (or markdown ""))
           (remove #(or (str/blank? %) (str/starts-with? % "#")))
           first)
      ""))

(defn- build-skill-record
  "Read SKILL.md from skill-dir and return [name skill-record] or nil if
   the skill is invalid (e.g. name mismatch or missing description)."
  [skill-dir dir-name source]
  (let [skill-md-path (path/join skill-dir "SKILL.md")]
    (when (fs/existsSync skill-md-path)
      (let [raw       (fs/readFileSync skill-md-path "utf8")
            {:keys [frontmatter body]} (parse-frontmatter raw)
            fm-name   (get-fm frontmatter "name")
            ;; If frontmatter has no name, accept dir-name. If it has one
            ;; that doesn't match dir-name, that's a spec violation but
            ;; we accept it with a debug warning rather than dropping the
            ;; skill — be liberal in what we accept.
            name      (or fm-name dir-name)
            description (or (get-fm frontmatter "description")
                            (first-skill-line body))
            has-tools (or (fs/existsSync (path/join skill-dir "tools.ts"))
                          (fs/existsSync (path/join skill-dir "tools.cljs")))]
        (when (and fm-name (not= fm-name dir-name)
                   (.-NYMA_DEBUG js/process.env))
          (d/warn
           (str "[skills] frontmatter name '" fm-name
                "' does not match directory '" dir-name
                "' at " skill-dir)))
        [name {:dir         skill-dir
               :name        name
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

(defn skill-message
  "The system message an activated skill contributes. `:skill` tags it so
   deactivate-skill can find it again; the tag never reaches disk — the
   session persister keeps only user/assistant turns, and only their
   :role/:content."
  [skill args]
  {:role    "system"
   :skill   (:name skill)
   :content (str "<skill name=\"" (:name skill) "\">\n"
                 ;; keep-when-empty?: a body read with no args must still show
                 ;; its quoted shell snippets (`echo $1`) as written.
                 (template-args/substitute (skill-body skill) args {:keep-when-empty? true})
                 "\n</skill>")})

(defn ^:async activate-skill
  "Inject skill instructions into context and load skill tools.
   No-op if the skill is already active. `args` fill `$ARGUMENTS`/`$N`
   in the body. Returns the injected body text (nil when nothing was
   injected) so a caller can show the model what it asked for."
  [skills name agent & [args]]
  (when-let [skill (get skills name)]
    (when-not (contains? (:active-skills @(:state agent)) name)
      (let [msg (skill-message (assoc skill :name name) args)]
        (swap! (:state agent)
               (fn [s]
                 (-> s
                     (update :messages conj msg)
                     (update :active-skills conj name)
                     ;; The permission gate reads this rather than the skill
                     ;; records, which live on `resources`, not the agent.
                     (assoc-in [:skill-allowed-tools name]
                               (set (or (:allowed-tools skill) []))))))
      (when (:has-tools skill)
        (let [tool-file (or (let [p (path/join (:dir skill) "tools.cljs")]
                              (when (fs/existsSync p) p))
                            (let [p (path/join (:dir skill) "tools.ts")]
                              (when (fs/existsSync p) p)))]
          (when tool-file
            (let [ext-fn (js-await (load-extension tool-file))]
              (ext-fn (create-extension-api agent))))))
        (:content msg)))))

(defn deactivate-skill
  "Remove a skill: its tracking entry, its tool allowances, and the system
   message it injected."
  [name agent]
  (swap! (:state agent)
         (fn [s]
           (-> s
               (update :active-skills disj name)
               (update :skill-allowed-tools dissoc name)
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
  (str "Activate a skill: its instructions are returned to you and stay in "
       "context for the rest of the session. Available skills:\n"
       (str/join "\n"
                 (map (fn [[sname s]]
                        (str "- " sname (when (seq (:description s)) (str " — " (:description s)))))
                      (model-invocable skills)))))

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
                                (some #(.includes lc (str/lower-case (str %)))
                                      trigs))
                       name))))
           vec))))


;;; ─── Auto-activation: triggers and paths ────────────────────────
;;; `triggers` and `paths` were parsed since the skills format landed and read
;;; by nothing (roadmap: "parsed but unwired"). A skill's description is always
;;; listed (level 1); its BODY (level 2) is injected for one turn when the
;;; user's prompt contains a trigger phrase, or a file the session has touched
;;; matches one of its path globs. Injected as `volatile-additions`, so it sits
;;; after the cache boundary and a turn without a match costs nothing.

(defn auto-activated
  "Names of skills to activate this turn: trigger matches on `prompt` plus
   path-scoped skills matching any of `touched` paths. Hidden skills
   (`disable-model-invocation`) never auto-activate. Pure."
  [skills prompt touched]
  (let [visible (remove (fn [[_ s]] (:disable-model-invocation s)) skills)
        by-trig (set (matching-triggers (into {} visible) prompt))
        by-path (set (keep (fn [[n s]]
                             (when (and (seq (:paths s))
                                        (some #(path-matches-skill? s %) touched))
                               n))
                           visible))]
    (vec (sort (into by-trig by-path)))))

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
                       (swap! touched conj (str p))))
          on-start (fn [d]
                     (let [names (auto-activated skills (prompt-text (.-userMessage d)) @touched)]
                       (when-let [block (activation-block skills names)]
                         #js {"volatile-additions" #js [block]})))]
      ((:on events) "tool_call" on-call)
      ((:on events) "before_agent_start" on-start)
      (reset! activation-handlers [on-call on-start])
      nil)))
