(ns skills-doctor.test
  "Conformance findings, CRLF tolerance, activation accounting, and the tool
   cleanup that deactivation never did.

   `valid-name?` existed and was called by nothing but tests; a frontmatter
   name that disagreed with its directory warned only under NYMA_DEBUG; a
   description over the spec's cap was never checked; a malformed YAML block
   failed silently; and `license`/`compatibility`/`metadata` were parsed into
   the record and read by nobody."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.state :as state]
            [agent.resources.skills :as skills]))

(def ^:private dirs (atom []))

(defn- skill-dir!
  "Write <tmp>/<dir-name>/SKILL.md verbatim and discover it."
  [dir-name raw]
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-doctor-"))
        d    (path/join root dir-name)]
    (swap! dirs conj root)
    (fs/mkdirSync d #js {:recursive true})
    (fs/writeFileSync (path/join d "SKILL.md") raw)
    (get (skills/discover-skills root "test") dir-name)))

(defn- findings-of [skill] (vec (or (:findings skill) [])))

(defn- has-finding? [skill needle]
  (boolean (some #(.includes % needle) (findings-of skill))))

(describe "skills:conformance" (fn []
                                 (afterEach (fn []
                                              (reset! skills/activation-counts {})
                                              (doseq [d @dirs] (fs/rmSync d #js {:recursive true :force true}))
                                              (reset! dirs [])
                                              nil))

                                 (it "a conforming skill has no findings"
                                     (fn []
                                       (let [s (skill-dir! "good" "---\nname: good\ndescription: Fine\n---\nBody")]
                                         (-> (expect (findings-of s)) (.toEqual (clj->js []))))))

                                 (it "an invalid directory name is a finding, and the skill still loads"
                                     (fn []
                                       (let [s (skill-dir! "Bad_Name" "---\ndescription: d\n---\nBody")]
                                         (-> (expect (has-finding? s "is not a valid skill name")) (.toBe true))
                                         (-> (expect (:body s)) (.toBe "Body")))))

                                 (it "a frontmatter name that disagrees with the directory is a finding"
                                     (fn []
                                       (let [s (skill-dir! "actual" "---\nname: pretender\ndescription: d\n---\nBody")]
                                         (-> (expect (has-finding? s "does not match directory")) (.toBe true))
          ;; and the DIRECTORY name is the identity
                                         (-> (expect (:name s)) (.toBe "actual"))
                                         (-> (expect (:declared-name s)) (.toBe "pretender")))))

                                 (it "an over-long description is a finding"
                                     (fn []
                                       (let [s (skill-dir! "wordy" (str "---\nname: wordy\ndescription: " (.repeat "x" 1100) "\n---\nB"))]
                                         (-> (expect (has-finding? s "the spec caps it at")) (.toBe true)))))

                                 (it "a malformed YAML block is reported, not swallowed"
                                     (fn []
                                       (let [s (skill-dir! "broken" "---\nname: [unclosed\n  : : :\n---\nBody")]
                                         (-> (expect (has-finding? s "not valid YAML")) (.toBe true)))))

                                 (it "a file with no frontmatter at all is not reported as malformed"
                                     (fn []
                                       (let [s (skill-dir! "plain" "Just a body, no frontmatter\n")]
                                         (-> (expect (has-finding? s "not valid YAML")) (.toBe false)))))

                                 (it "CRLF frontmatter parses, and stays out of the body"
                                     (fn []
                                       (let [raw (.replace "---\nname: winter\ndescription: From Windows\n---\nBody line"
                                                           (js/RegExp. "\n" "g") "\r\n")
                                             s   (skill-dir! "winter" raw)]
                                         (-> (expect (:description s)) (.toBe "From Windows"))
                                         (-> (expect (.includes (:body s) "description:")) (.toBe false))
                                         (-> (expect (.includes (:body s) "Body line")) (.toBe true)))))

                                 (it "an empty or comment-only frontmatter block is valid, not malformed"
                                     (fn []
          ;; Bun.YAML.parse("") returns null, which is not a parse failure
                                       (-> (expect (has-finding? (skill-dir! "empty" "---\n\n---\nBody") "not valid YAML")) (.toBe false))
                                       (-> (expect (has-finding? (skill-dir! "cmt" "---\n# just a comment\n---\nBody") "not valid YAML")) (.toBe false))))

                                 (it "a frontmatter name that no longer resolves points at the directory"
                                     (fn []
                                       (let [s (skill-dir! "my-deploy" "---\nname: deploy\ndescription: d\n---\nB")
                                             hint (skills/alias-hint {"my-deploy" s} "deploy")]
          ;; keying by directory silently renamed these for existing users
                                         (-> (expect (.includes hint "my-deploy")) (.toBe true))
                                         (-> (expect (skills/alias-hint {"my-deploy" s} "nothing-like-it")) (.toBeNil)))))

                                 (it "license, compatibility and metadata survive into the doctor row"
                                     (fn []
                                       (let [s (skill-dir! "meta"
                                                           (str "---\nname: meta\ndescription: d\nlicense: Apache-2.0\n"
                                                                "compatibility: Requires git\nmetadata:\n  author: someone\n---\nB"))
                                             r (first (skills/doctor-rows {"meta" s}))]
                                         (-> (expect (:license r)) (.toBe "Apache-2.0"))
                                         (-> (expect (:compatibility r)) (.toBe "Requires git")))))))

(describe "skills:doctor rows" (fn []
                                 (afterEach (fn [] (reset! skills/activation-counts {}) nil))

                                 (it "reports cost, scope and gating"
                                     (fn []
                                       (let [rows (skills/doctor-rows
                                                   {"p" {:name "p" :description (.repeat "y" 900) :scope :project
                                                         :has-tools true :allowed-tools ["bash"]}
                                                    "g" {:name "g" :description "short" :scope :global}}
                                                   100)
                                             p    (first (filter #(= "p" (:name %)) rows))
                                             g    (first (filter #(= "g" (:name %)) rows))]
                                         (-> (expect (:scope p)) (.toBe "project"))
                                         (-> (expect (:truncated? p)) (.toBe true))
                                         (-> (expect (:desc-chars p)) (.toBe 900))
          ;; a project skill's tools and grants are refused, and the row says so
                                         (-> (expect (:tools-gated p)) (.toBe true))
                                         (-> (expect (:grants-gated p)) (.toBe true))
                                         (-> (expect (:scope g)) (.toBe "global"))
                                         (-> (expect (:truncated? g)) (.toBe false)))))

                                 (it "counts user, model and automatic activations separately"
                                     (fn []
          ;; the model calling the `skill` tool used to be counted as explicit,
          ;; collapsing the one distinction the column exists for
                                       (skills/count-activation! "a" :explicit)
                                       (skills/count-activation! "a" :model)
                                       (skills/count-activation! "a" :auto)
                                       (skills/count-activation! "a" :auto)
                                       (let [r (first (skills/doctor-rows {"a" {:name "a" :description "d"}}))]
                                         (-> (expect (:explicit r)) (.toBe 1))
                                         (-> (expect (:model r)) (.toBe 1))
                                         (-> (expect (:auto r)) (.toBe 2)))))

                                 (it "rows are sorted by name"
                                     (fn []
                                       (-> (expect (mapv :name (skills/doctor-rows {"c" {} "a" {} "b" {}})))
                                           (.toEqual (clj->js ["a" "b" "c"])))))))

(describe "skills:lifecycle holes" (fn []

                                     (it "a grant cannot outlive the instructions it came with"
                                         (fn []
          ;; compaction and pruning replace :messages wholesale, which can drop
          ;; the skill's own message. The skill stayed \"active\" with its
          ;; allowed-tools still lifting permission prompts, and the skill tool
          ;; went on reporting instructions that were no longer there.
                                           (let [st {:messages [{:role "user" :content "hi"}]
                                                     :active-skills #{"deploy"}
                                                     :skill-allowed-tools {"deploy" #{"bash"}}
                                                     :skill-tools {"deploy" #{"t"}}}
                                                 out (state/prune-skill-state st)]
                                             (-> (expect (contains? (:active-skills out) "deploy")) (.toBe false))
                                             (-> (expect (get (:skill-allowed-tools out) "deploy")) (.toBeUndefined)))))

                                     (it "a skill whose message survived is left alone"
                                         (fn []
                                           (let [st {:messages [{:role "system" :skill "deploy" :content "B"}]
                                                     :active-skills #{"deploy"}
                                                     :skill-allowed-tools {"deploy" #{"bash"}}}
                                                 out (state/prune-skill-state st)]
                                             (-> (expect (contains? (:active-skills out) "deploy")) (.toBe true))
                                             (-> (expect (contains? (get (:skill-allowed-tools out) "deploy") "bash")) (.toBe true)))))

                                     (it "clearing the conversation releases the skill's tools"
                                         (fn []
          ;; :messages-cleared wipes the bookkeeping, so a clear that did not
          ;; go through here left the tools registered with the record of them
          ;; already gone — and a later re-activation would store that stale
          ;; object as the \"original\" to restore, so it could never be removed.
                                           (let [agent (create-agent {:model "m" :system-prompt "s"})
                                                 reg   (:tool-registry agent)]
                                             ((:register reg) "skill_tool" #js {:description "x"})
                                             (swap! (:state agent) assoc-in [:skill-tools "s"] #{"skill_tool"})
                                             (swap! (:state agent) update :active-skills conj "s")
                                             (skills/deactivate-all-skills! agent)
                                             (-> (expect (contains? ((:all reg)) "skill_tool")) (.toBe false))
                                             (-> (expect (count (:active-skills @(:state agent)))) (.toBe 0)))))))

(describe "skills:tool cleanup" (fn []
                                  (afterEach (fn [] (reset! skills/activation-counts {}) nil))

                                  (it "deactivating removes the tools the skill registered"
                                      (fn []
                                        (let [agent (create-agent {:model "m" :system-prompt "s"})
                                              reg   (:tool-registry agent)]
          ;; stand in for what a skill's tools.* would have registered
                                          ((:register reg) "skill_tool" #js {:description "x"})
                                          (swap! (:state agent) assoc-in [:skill-tools "s"] #{"skill_tool"})
                                          (swap! (:state agent) update :active-skills conj "s")
                                          (-> (expect (contains? ((:all reg)) "skill_tool")) (.toBe true))
                                          (skills/deactivate-skill "s" agent)
                                          (-> (expect (contains? ((:all reg)) "skill_tool")) (.toBe false))
                                          (-> (expect (get (:skill-tools @(:state agent)) "s")) (.toBeUndefined)))))))
