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
     allowed-tools              - Optional. Tools the skill needs. (Wired into
                                  model_roles permission gating in a follow-up.)
     disable-model-invocation   - Optional bool. If true, skill is hidden from
                                  the model's auto-listing; only `/skill <name>`
                                  triggers it.
     paths                      - Optional list of globs. If present, the skill
                                  description is only injected into the system
                                  prompt when an active file matches.
     triggers                   - Optional list of phrase substrings. If any
                                  match the user's prompt, auto-activate for
                                  that turn.

   Other Claude-Code-extended fields (`context: fork`, `hooks`, `model`,
   `effort`) are preserved on the skill record but not yet acted upon.

   Reference: https://agentskills.io/specification"
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]
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
          (js/console.warn
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
               :model       (get-fm frontmatter "model")
               :effort      (get-fm frontmatter "effort")
               :context     (get-fm frontmatter "context")
               :hooks       (get-fm frontmatter "hooks")
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

(defn ^:async activate-skill
  "Inject skill instructions into context and load skill tools.
   No-op if the skill is already active."
  [skills name agent]
  (when-let [skill (get skills name)]
    (when-not (contains? (:active-skills @(:state agent)) name)
      (swap! (:state agent)
             (fn [s]
               (-> s
                   (update :messages conj {:role "system" :content (:markdown skill)})
                   (update :active-skills conj name))))
      (when (:has-tools skill)
        (let [tool-file (or (let [p (path/join (:dir skill) "tools.cljs")]
                              (when (fs/existsSync p) p))
                            (let [p (path/join (:dir skill) "tools.ts")]
                              (when (fs/existsSync p) p)))]
          (when tool-file
            (let [ext-fn (js-await (load-extension tool-file))]
              (ext-fn (create-extension-api agent)))))))))

(defn deactivate-skill
  "Remove a skill from active-skills tracking.
   Cannot un-inject the system message, but prevents re-activation."
  [name agent]
  (swap! (:state agent) update :active-skills disj name))

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
