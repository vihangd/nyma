(ns skills-budget.test
  "Skill descriptions ride in the system prompt on EVERY request, and used to
   ride a second time inside the `skill` tool's own description. Neither was
   capped. And progressive disclosure stopped at the body: a skill's bundled
   `references/` were named in the docs and read by no code, so authors had to
   inline everything."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.resources.skills :as skills]))

(def ^:private dirs (atom []))

(defn- tmp-skill!
  "A real skill directory, optionally with reference files."
  [name & [refs]]
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-skillbudget-"))
        d    (path/join root name)]
    (swap! dirs conj root)
    (fs/mkdirSync d #js {:recursive true})
    (fs/writeFileSync (path/join d "SKILL.md")
                      (str "---\nname: " name "\ndescription: d\n---\nBody."))
    (when (seq refs)
      (fs/mkdirSync (path/join d "references") #js {:recursive true})
      (doseq [r refs] (fs/writeFileSync (path/join d "references" r) "INLINED-CONTENT")))
    d))

(describe "skills:description budget" (fn []
                                        (afterEach (fn []
                                                     (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
                                                     (reset! dirs [])
                                                     nil))

                                        (it "a short description is untouched"
                                            (fn []
                                              (-> (expect (skills/budget-description "short one" 500)) (.toBe "short one"))))

                                        (it "a long one is cut and says so"
                                            (fn []
                                              (let [out (skills/budget-description (.repeat "x" 900) 100)]
                                                (-> (expect (.startsWith out (.repeat "x" 100))) (.toBe true))
                                                (-> (expect (.includes out "truncated; 900 chars")) (.toBe true)))))

                                        (it "the cap is exactly the limit, not off by one"
                                            (fn []
                                              (-> (expect (skills/budget-description (.repeat "x" 100) 100)) (.toBe (.repeat "x" 100)))
                                              (-> (expect (.includes (skills/budget-description (.repeat "x" 101) 100) "truncated")) (.toBe true))))

                                        (it "a missing or nonsense limit falls back to the default"
                                            (fn []
                                              (-> (expect (skills/budget-description "abc")) (.toBe "abc"))
                                              (-> (expect (skills/budget-description "abc" nil)) (.toBe "abc"))
                                              (-> (expect (skills/budget-description "abc" 0)) (.toBe "abc"))))

                                        (it "nil description does not blow up"
                                            (fn []
                                              (-> (expect (skills/budget-description nil 10)) (.toBe ""))))

                                        (it "the skill tool's description carries names, not descriptions"
                                            (fn []
                                              (let [desc (skills/skill-tool-description
                                                          {"deploy" {:name "deploy" :description "Ship the service to production"}})]
                                                (-> (expect (.includes desc "deploy")) (.toBe true))
                                                (-> (expect (.includes desc "Ship the service")) (.toBe false)))))))

(describe "skills:reference files" (fn []
                                     (afterEach (fn []
                                                  (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
                                                  (reset! dirs [])
                                                  nil))

                                     (it "lists a skill's references/ files, sorted"
                                         (fn []
                                           (let [d (tmp-skill! "docs" ["b.md" "a.md"])]
                                             (-> (expect (skills/reference-files {:dir d}))
                                                 (.toEqual (clj->js ["references/a.md" "references/b.md"]))))))

                                     (it "no references/ directory means no files and no block"
                                         (fn []
                                           (let [d (tmp-skill! "plain")]
                                             (-> (expect (skills/reference-files {:dir d})) (.toEqual (clj->js [])))
                                             (-> (expect (.includes (:content (skills/skill-message {:name "plain" :dir d :body "B"} nil))
                                                                    "Reference files"))
                                                 (.toBe false)))))

                                     (it "an activated skill names its references so the model can read them"
                                         (fn []
                                           (let [d (tmp-skill! "docs" ["REFERENCE.md"])
                                                 c (:content (skills/skill-message {:name "docs" :dir d :body "B"} nil))]
                                             (-> (expect (.includes c "references/REFERENCE.md")) (.toBe true))
          ;; named, never inlined — that is the point of the level
                                             (-> (expect (.includes c "INLINED-CONTENT")) (.toBe false)))))

                                     (it "angle brackets in a reference file name are escaped"
                                         (fn []
            ;; escape-attr guarded the NAME; the reference block interpolated
            ;; the directory and every file name raw into the same wrapper. A
            ;; file name cannot hold a slash, so it cannot spell `</skill>` on
            ;; its own — but it can open a tag, and the directory path is
            ;; attacker-shaped too, so both are escaped rather than reasoned
            ;; about.
                                           (let [d (tmp-skill! "docs" ["weird<tag>.md"])
                                                 c (:content (skills/skill-message {:name "docs" :dir d :body "B"} nil))]
                                             (-> (expect (.includes c "weird<tag>")) (.toBe false))
                                             (-> (expect (.includes c "weird&lt;tag&gt;")) (.toBe true))
              ;; exactly one closing tag, at the end where it belongs
                                             (-> (expect (count (.split c "</skill>"))) (.toBe 2)))))

                                     (it "a directory path with angle brackets is escaped too"
                                         (fn []
                                           (let [c (:content (skills/skill-message
                                                              {:name "d" :dir "/tmp/a</skill>b" :body "B"} nil))]
              ;; no references/ dir here, so nothing is appended at all
                                             (-> (expect (count (.split c "</skill>"))) (.toBe 2)))))

                                     (it "a record with no :dir is handled"
                                         (fn []
                                           (-> (expect (skills/reference-files {})) (.toEqual (clj->js [])))))))
