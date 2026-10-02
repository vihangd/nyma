(ns tool-name-literal-lint-test
  "No source file may compare a LIVE tool name against a literal set.

   The name on an event is the REGISTERED name, and the scoped `registerTool`
   prefixes an extension's tool with its namespace — so the multi-edit tool is
   `token-suite__multi_edit` on the wire and matches no bare literal. Eight
   sites got this wrong and each silently excluded that tool:

     file_access.cljs              .nymaignore enforcement
     spec_driven/phases.cljs       the plan phase's write gate
     spec_driven/index.cljs        tasks.md checkbox diffing
     token_suite/repo_map.cljs     reindex-on-edit
     token_suite/smart_compaction  edit tracking
     token_suite/structured_context hot-file window
     lsp_suite/index.cljs          didChange / diagnostics
     openwiki/events.cljs          status line

   Demonstrated for the first before fixing: with `*.secret` in `.nymaignore`,
   `edit`, `write` and `multi_edit` were blocked and `token-suite__multi_edit`
   was not. A ninth site, `priority_assembly`, was fixed earlier the same way.

   The fix everywhere is `tool-metadata/matches-any-tool-name?`, which matches
   the `__` boundary. This lint stops the tenth."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.tool-metadata :as tm]))

(def ^:private allowed
  "Files whose literal sets are legitimate.

   `tool_metadata` DEFINES the matcher. `profiles.cljs` feeds its sets straight
   into `matches-tool-name?`/`offered-names`, so the literal is an input to a
   correct matcher rather than a comparison. `agent_shell/shared.cljs` holds ACP
   operation names (`edit`/`delete`/`move`) that are not tool names at all.
   Each was checked individually rather than inferred from the grep."
  #{"tool_metadata.cljs" "profiles.cljs" "shared.cljs"})

(defn- cljs-files [dir]
  (->> (fs/readdirSync dir #js {:withFileTypes true})
       (mapcat (fn [e]
                 (let [p (path/join dir (.-name e))]
                   (cond (.isDirectory e)              (cljs-files p)
                         (.endsWith (.-name e) ".cljs") [p]
                         :else                         []))))
       vec))

(def ^:private tool-names
  "Native tool names distinctive enough that a set literal containing two or
   more of them is almost certainly a tool-name comparison."
  #{"read" "write" "edit" "multi_edit" "bash" "glob" "grep"})

(defn- strip-comments
  "Drop `;` comments. A detector that reads prose is a detector that can be
   talked out of firing — the first version of this lint cleared a file because
   a COMMENT I had added mentioned the matcher by name, and the planted
   violation sailed through."
  [src]
  (->> (str/split-lines (str src))
       (map (fn [ln] (let [i (.indexOf ln ";")] (if (neg? i) ln (.slice ln 0 i)))))
       (str/join "\n")))

(defn literal-comparisons
  "Occurrences of a tool-name set literal used AS A COMPARISON. Pure.

   Matches the two forms that compare — `(contains? #{…} x)` and the set
   called as a predicate, `(#{…} x)` — rather than asking whether the file
   mentions the matcher anywhere. Routing through the matcher puts the set in
   the LAST position (`(matches-any-tool-name? x #{…})`), which neither
   pattern matches, so correct code is invisible to this and a comment cannot
   excuse a real comparison."
  [src]
  (let [code (strip-comments src)
        tool-set? (fn [inner]
                    (let [names (set (map (fn [q] (str/replace q "\"" ""))
                                          (str/split (str inner) #"\s+")))]
                      (>= (count (filter tool-names names)) 2)))
        ;; Names bound to a tool-name set IN THIS FILE. Needed because the
        ;; real sites mostly use a named def — `(contains? write-tools tool)`
        ;; — and a literal-only regex cleared every one of them. Found by
        ;; planting the violation in its named form and watching the lint stay
        ;; green.
        set-defs  (->> (re-seq #"\(def(?:\s+\^:private)?\s+([a-z][a-z0-9-]*)[\s\S]{0,400}?#\{(\"[a-z_]+\"(?:\s+\"[a-z_]+\")*)\}"
                               code)
                       (keep (fn [[_ nm inner]] (when (tool-set? inner) nm)))
                       set)
        ;; …and names bound in a `let`, which is how spec_driven/index does it.
        let-defs  (->> (re-seq #"\b([a-z][a-z0-9-]*)\s+#\{(\"[a-z_]+\"(?:\s+\"[a-z_]+\")*)\}" code)
                       (keep (fn [[_ nm inner]] (when (tool-set? inner) nm)))
                       set)
        named     (into set-defs let-defs)]
    (->> (concat
          ;; inline literal, both comparison forms
          (keep (fn [[_ inner]] (when (tool-set? inner) (str "#{" inner "}")))
                (re-seq #"\(contains\?\s+#\{(\"[a-z_]+\"(?:\s+\"[a-z_]+\")*)\}" code))
          (keep (fn [[_ inner]] (when (tool-set? inner) (str "#{" inner "}")))
                (re-seq #"\(#\{(\"[a-z_]+\"(?:\s+\"[a-z_]+\")*)\}\s" code))
          ;; a named tool-name set used as a comparison
          (keep (fn [[_ nm]] (when (contains? named nm) (str "(contains? " nm " …)")))
                (re-seq #"\(contains\?\s+([a-z][a-z0-9-]*)\s" code))
          (keep (fn [[_ nm]] (when (contains? named nm) (str "(" nm " …)")))
                (re-seq #"\((([a-z][a-z0-9-]*))\s+tool(?:-name)?\)" code)))
         vec)))

(describe "the detector itself"
  (fn []
    (it "fires on (contains? #{…} x)"
      (fn []
        (-> (expect (literal-comparisons "(contains? #{\"edit\" \"write\"} t)"))
            (.toEqual (clj->js ["#{\"edit\" \"write\"}"])))))

    (it "fires on a set called as a predicate"
      (fn []
        (-> (expect (count (literal-comparisons "(#{\"edit\" \"multi_edit\"} name)")))
            (.toBe 1))))

    (it "clears a set passed to the matcher — the set is the LAST arg there"
      (fn []
        (-> (expect (literal-comparisons
                     "(tm/matches-any-tool-name? tool #{\"edit\" \"multi_edit\"})"))
            (.toEqual (clj->js [])))))

    (it "a comment mentioning the matcher cannot excuse a real comparison"
      (fn []
        ;; The first version of this lint failed exactly here.
        (-> (expect (count (literal-comparisons
                            ";; matches-any-tool-name? is the right way\n(contains? #{\"edit\" \"write\"} t)")))
            (.toBe 1))))

    (it "fires on a NAMED tool-name set used as a comparison"
      (fn []
        ;; The shape the real sites actually use. A literal-only regex cleared
        ;; every one of them; found by planting the violation in its named form
        ;; and watching the lint stay green.
        (-> (expect (count (literal-comparisons
                            "(def ^:private write-tools #{\"write\" \"edit\" \"multi_edit\"})\n(contains? write-tools tool)")))
            (.toBeGreaterThan 0))))

    (it "clears a named set that IS routed through the matcher"
      (fn []
        (-> (expect (literal-comparisons
                     "(def ^:private write-tools #{\"write\" \"edit\" \"multi_edit\"})\n(tm/matches-any-tool-name? tool write-tools)"))
            (.toEqual (clj->js [])))))

    (it "ignores a one-name set and unrelated strings"
      (fn []
        (-> (expect (literal-comparisons "(contains? #{\"edit\"} t)")) (.toEqual (clj->js [])))
        (-> (expect (literal-comparisons "(contains? #{\"alpha\" \"beta\"} t)"))
            (.toEqual (clj->js [])))))))

(describe "src/ is clean"
  (fn []
    (it "scans a believable number of files"
      (fn []
        ;; Guard the guard: a glob matching nothing makes the next assertion
        ;; vacuous, which is how a lint comes to report green while checking
        ;; nothing.
        (-> (expect (count (cljs-files (path/join (js/process.cwd) "src"))))
            (.toBeGreaterThan 200))))

    (it "no file compares a live tool name against a literal set"
      (fn []
        (let [offenders
              (->> (cljs-files (path/join (js/process.cwd) "src"))
                   (remove (fn [p] (contains? allowed (path/basename p))))
                   (keep (fn [p]
                           (when (seq (literal-comparisons (fs/readFileSync p "utf8")))
                             (path/relative (js/process.cwd) p))))
                   vec)]
          (-> (expect (clj->js offenders)) (.toEqual (clj->js []))))))))

(describe "matches-any-tool-name?"
          (fn []
            (it "matches a prefixed extension tool by its bare name"
                (fn []
                  (-> (expect (tm/matches-any-tool-name? "token-suite__multi_edit"
                                                         #{"edit" "write" "multi_edit"}))
                      (.toBe true))))

            (it "still matches a native name"
                (fn []
                  (-> (expect (tm/matches-any-tool-name? "edit" #{"edit" "write"})) (.toBe true))))

            (it "respects the __ boundary — my_multi_edit is a different tool"
                (fn []
                  (-> (expect (tm/matches-any-tool-name? "my_multi_edit" #{"multi_edit"}))
                      (.toBe false))))

            (it "is false for an unrelated tool"
                (fn []
                  (-> (expect (tm/matches-any-tool-name? "bash" #{"edit" "write"})) (.toBe false))))))
