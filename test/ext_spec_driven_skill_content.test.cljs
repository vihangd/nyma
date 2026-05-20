(ns ext-spec-driven-skill-content.test
  "Tests for the embedded `spec-driven-dev` SKILL.md content + the
   install-skill! helper. Verifies frontmatter parses cleanly via the
   skills loader and content covers the expected spec-kit conventions."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs"   :as fs]
            ["node:os"   :as os]
            ["node:path" :as path]
            ["./agent/extensions/spec_driven/skill_content.mjs" :as sc]
            ["./agent/resources/skills.mjs" :as skills]))

(defn- mktmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-skill-content-")))

;; ── Embedded content sanity ────────────────────────────────────

(describe "skill-content/skill-md-content"
          (fn []
            (it "starts with YAML frontmatter delimiter"
                (fn []
                  (-> (expect (.startsWith sc/skill-md-content "---\n"))
                      (.toBe true))))

            (it "declares the skill name and description"
                (fn []
                  (-> (expect (.includes sc/skill-md-content "name: spec-driven-dev"))
                      (.toBe true))
                  (-> (expect (.includes sc/skill-md-content "description:"))
                      (.toBe true))))

            (it "uses agentskills.io baseline frontmatter only (no `paths` field)"
                (fn []
                  ;; `paths`/`globs`/`triggers` are NOT standard fields per
                  ;; the agentskills.io spec; vendor-specific globs go under
                  ;; `metadata.nyma.*` as a nested YAML map. Verify the
                  ;; frontmatter doesn't put `paths:` at the top level and
                  ;; that the nyma-specific block parses as a real sub-map
                  ;; (not a single dotted-string key).
                  (let [end (.indexOf sc/skill-md-content "\n---\n" 4)
                        fm  (.slice sc/skill-md-content 4 end)]
                    ;; No top-level `paths:` line
                    (-> (expect (boolean (.match fm
                                                 (js/RegExp. "^paths:" "m"))))
                        (.toBe false))
                    ;; The vendor block is a nested YAML map, not a dotted
                    ;; key. We assert by feeding the frontmatter through
                    ;; the actual skills loader and inspecting the result.
                    (let [parsed (skills/parse-frontmatter sc/skill-md-content)
                          fm-obj (:frontmatter parsed)
                          meta   (aget fm-obj "metadata")
                          nyma   (when meta (aget meta "nyma"))]
                      (-> (expect (some? meta)) (.toBe true))
                      (-> (expect (some? nyma)) (.toBe true))
                      (-> (expect (some? (aget nyma "activation-paths")))
                          (.toBe true))
                      (-> (expect (some? (aget nyma "spec-kit-version")))
                          (.toBe true))))))

            (it "covers all 11 clarify categories"
                (fn []
                  (let [c sc/skill-md-content]
                    (-> (expect (.includes c "Functional Scope"))         (.toBe true))
                    (-> (expect (.includes c "Domain & Data Model"))      (.toBe true))
                    (-> (expect (.includes c "Interaction & UX"))         (.toBe true))
                    (-> (expect (.includes c "Non-Functional"))           (.toBe true))
                    (-> (expect (.includes c "Integration & External"))   (.toBe true))
                    (-> (expect (.includes c "Edge Cases"))               (.toBe true))
                    (-> (expect (.includes c "Constraints & Tradeoffs"))  (.toBe true))
                    (-> (expect (.includes c "Terminology & Consistency")) (.toBe true))
                    (-> (expect (.includes c "Completion Signals"))       (.toBe true))
                    (-> (expect (.includes c "Misc / Placeholders"))      (.toBe true))
                    (-> (expect (.includes c "[NEEDS CLARIFICATION:"))    (.toBe true)))))

            (it "documents all 6 analyze passes"
                (fn []
                  (let [c sc/skill-md-content]
                    (-> (expect (.includes c "A. Duplication"))         (.toBe true))
                    (-> (expect (.includes c "B. Ambiguity"))           (.toBe true))
                    (-> (expect (.includes c "C. Underspecification"))  (.toBe true))
                    (-> (expect (.includes c "D. Constitution"))        (.toBe true))
                    (-> (expect (.includes c "E. Coverage Gaps"))       (.toBe true))
                    (-> (expect (.includes c "F. Inconsistency"))       (.toBe true)))))

            (it "lists the 4-level severity scale"
                (fn []
                  (let [c sc/skill-md-content]
                    (-> (expect (.includes c "CRITICAL")) (.toBe true))
                    (-> (expect (.includes c "HIGH"))     (.toBe true))
                    (-> (expect (.includes c "MEDIUM"))   (.toBe true))
                    (-> (expect (.includes c "LOW"))      (.toBe true)))))

            (it "documents the FR-### / SC-### ID conventions"
                (fn []
                  (let [c sc/skill-md-content]
                    (-> (expect (.includes c "FR-001")) (.toBe true))
                    (-> (expect (.includes c "FR-006")) (.toBe true))
                    (-> (expect (.includes c "SC-001")) (.toBe true)))))

            (it "names the spec-kit version compatibility target"
                (fn []
                  (-> (expect (.includes sc/skill-md-content "0.8.x")) (.toBe true))))))

;; ── parse-frontmatter compatibility ────────────────────────────

(describe "skill-content via skills/parse-frontmatter"
          (fn []
            (it "parses cleanly through the nyma skills loader"
                (fn []
                  (let [parsed (skills/parse-frontmatter sc/skill-md-content)
                        fm     (:frontmatter parsed)]
                    ;; The frontmatter should have parsed
                    (-> (expect (some? fm)) (.toBe true))
                    ;; Required fields present
                    (-> (expect (aget fm "name")) (.toBe "spec-driven-dev"))
                    (-> (expect (some? (aget fm "description"))) (.toBe true))
                    ;; Body content present
                    (-> (expect (.includes (:body parsed) "Spec-Driven Development Workflow"))
                        (.toBe true)))))

            (it "name passes valid-name? per agentskills.io spec"
                (fn []
                  (-> (expect (skills/valid-name? "spec-driven-dev")) (.toBe true))))))

;; ── install-skill! ─────────────────────────────────────────────

(describe "skill-content/install-skill!"
          (fn []
            (it "writes SKILL.md to <home>/.nyma/skills/spec-driven-dev/"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (let [r (sc/install-skill! tmp)]
                        (-> (expect (:ok? r)) (.toBe true))
                        (let [p (path/join tmp ".nyma/skills/spec-driven-dev/SKILL.md")]
                          (-> (expect (fs/existsSync p)) (.toBe true))
                          (-> (expect (.includes (fs/readFileSync p "utf8")
                                                 "spec-driven-dev"))
                              (.toBe true))))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "refuses to clobber by default"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (sc/install-skill! tmp)
                      (let [r (sc/install-skill! tmp)]
                        (-> (expect (:ok? r)) (.toBe false))
                        (-> (expect (.includes (:error r) "Already exists")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "overwrites when clobber? is true"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (sc/install-skill! tmp)
                      ;; tamper with the file then re-install
                      (let [p (path/join tmp ".nyma/skills/spec-driven-dev/SKILL.md")]
                        (fs/writeFileSync p "tampered")
                        (let [r (sc/install-skill! tmp true)]
                          (-> (expect (:ok? r)) (.toBe true))
                          (-> (expect (.includes (fs/readFileSync p "utf8")
                                                 "spec-driven-dev"))
                              (.toBe true))))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "discoverable by the nyma skills loader after install"
                (fn []
                  ;; Install → run discover-skills against the home dir →
                  ;; assert the skill comes back with expected metadata.
                  (let [tmp (mktmp)]
                    (try
                      (sc/install-skill! tmp)
                      (let [m (skills/discover-skills
                               (path/join tmp ".nyma/skills"))
                            s (get m "spec-driven-dev")]
                        (-> (expect (some? s)) (.toBe true))
                        (-> (expect (.includes (.toLowerCase (:description s))
                                               "spec-driven development"))
                            (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))
