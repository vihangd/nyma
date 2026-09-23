(ns skills-agentskills.test
  "agentskills.io compliance: `$ARGUMENTS` substitution, `/skill:<name>`,
   body-only injection with a removable message, `allowed-tools` lifting
   the permission prompt, and the model-callable `skill` tool."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [permission-check-enter]]
            [agent.commands.resolver :refer [split-skill-invocation]]
            [agent.utils.template-args :refer [substitute]]
            [agent.resources.skills :as skills]))

;;; ─── helpers ───────────────────────────────────────────────────

(def ^:private raw-skill
  (str "---\n"
       "name: deploy\n"
       "description: Ship it\n"
       "allowed-tools: bash read\n"
       "---\n"
       "# Deploy\nDeploy $1 to ${2:-staging}. All: $ARGUMENTS"))

(defn- record [name raw & [extra]]
  (let [{:keys [frontmatter body]} (skills/parse-frontmatter raw)]
    (merge {:dir (str "/tmp/" name) :name name :markdown raw :body body
            :description (aget frontmatter "description")
            :allowed-tools (when-let [t (aget frontmatter "allowed-tools")] (.split t " "))
            :has-tools false}
           extra)))

(def ^:private test-skills
  {"deploy" (record "deploy" raw-skill)
   "hidden" (record "hidden" "---\ndescription: Not for the model\n---\nSecret"
                    {:disable-model-invocation true})})

(defn- fresh-agent [] (create-agent {:model "test-model" :system-prompt "test"}))

(defn- system-messages [agent]
  (filter #(= (:role %) "system") (:messages @(:state agent))))

;;; ─── $ARGUMENTS ────────────────────────────────────────────────

(describe "template-args/substitute"
          (fn []
            (doseq [[text args expected]
                    [["$ARGUMENTS"        ["a" "b"]  "a b"]
                     ["$@"                ["a" "b"]  "a b"]
                     ;; No arguments: placeholders become empty (or their
                     ;; default), so a prompt template never sends a literal
                     ;; `$ARGUMENTS` to the model.
                     ["$ARGUMENTS"        []         ""]
                     ["echo $1 \"$@\""    nil        "echo  \"\""]
                     ["${1:-dflt}"        []         "dflt"]
                     ["$1-$2"             ["x" "y"]  "x-y"]
                     ["$1-$2"             ["x"]      "x-"]
                     ["${1:-dflt}"        ["v"]      "v"]
                     ["${2:-b} $1"        ["a"]      "b a"]
                     ["plain $$ text"     ["a"]      "plain $$ text"]
                     ["$1 says $ARGUMENTS" ["$2" "z"] "$2 says $2 z"]
                     [nil                 ["a"]      ""]]]
              (it (str (pr-str text) " with " (pr-str args) " → " (pr-str expected))
                  (fn [] (-> (expect (substitute text args)) (.toBe expected)))))
            ;; Skill activation opts in: a body read with no arguments keeps
            ;; the shell snippet it quotes (`echo $1`, `"$@"`) as written.
            (doseq [[text args expected]
                    [["$ARGUMENTS"        []  "$ARGUMENTS"]
                     ["echo $1 \"$@\""    nil "echo $1 \"$@\""]
                     ["${1:-dflt}"        []  "${1:-dflt}"]
                     ["$1-$2"             ["x"] "x-"]]]
              (it (str "keep-when-empty? " (pr-str text) " with " (pr-str args) " → " (pr-str expected))
                  (fn [] (-> (expect (substitute text args {:keep-when-empty? true}))
                             (.toBe expected)))))))

;;; ─── /skill:<name> ────────────────────────────────────────────

(describe "/skill:<name> resolves to the skill command"
          (fn []
            (it "splits the colon form into cmd + name-prefixed args"
                (fn []
                  (-> (expect (split-skill-invocation "skill:deploy" " api --fast"))
                      (.toEqual ["skill" "deploy  api --fast"]))))
            (it "leaves /skill name args and other commands alone"
                (fn []
                  (-> (expect (split-skill-invocation "skill" " deploy"))
                      (.toEqual ["skill" " deploy"]))
                  (-> (expect (split-skill-invocation "mcp:foo" ""))
                      (.toEqual ["mcp:foo" ""]))
                  (-> (expect (split-skill-invocation "skill:" ""))
                      (.toEqual ["skill:" ""]))))))

;;; ─── activation injects the body, deactivation removes it ─────

(describe "activate-skill injects the body only"
          (fn []
            (it "wraps the substituted body in <skill> and drops the frontmatter"
                (fn []
                  (let [agent (fresh-agent)]
                    (-> (skills/activate-skill test-skills "deploy" agent ["api"])
                        (.then (fn [returned]
                                 (let [msgs (system-messages agent)
                                       c    (:content (first msgs))]
                                   (-> (expect (count msgs)) (.toBe 1))
                                   (-> (expect (.startsWith c "<skill name=\"deploy\">\n")) (.toBe true))
                                   (-> (expect (.endsWith c "\n</skill>")) (.toBe true))
                                   (-> (expect (.includes c "---")) (.toBe false))
                                   (-> (expect (.includes c "allowed-tools")) (.toBe false))
                                   (-> (expect (.includes c "Deploy api to staging. All: api")) (.toBe true))
                                   (-> (expect returned) (.toBe c))
                                   (-> (expect (:skill (first msgs))) (.toBe "deploy")))))))))
            (it "records allowed-tools on the state, and deactivation removes everything"
                (fn []
                  (let [agent (fresh-agent)]
                    (-> (skills/activate-skill test-skills "deploy" agent)
                        (.then (fn [_]
                                 (-> (expect (get-in @(:state agent) [:skill-allowed-tools "deploy"]))
                                     (.toEqual #{"bash" "read"}))
                                 (skills/deactivate-skill "deploy" agent)
                                 (let [st @(:state agent)]
                                   (-> (expect (count (system-messages agent))) (.toBe 0))
                                   (-> (expect (contains? (:active-skills st) "deploy")) (.toBe false))
                                   (-> (expect (contains? (:skill-allowed-tools st) "deploy")) (.toBe false)))))))))))

;;; ─── allowed-tools lifts the prompt ───────────────────────────

(defn- ask-bus []
  (let [events (create-event-bus)]
    ((:on events) "permission_request" (fn [_] #js {:decision "ask"}))
    events))

(defn- gate [events agent tool-name]
  ;; No UI on the agent → an unanswered "ask" must deny.
  (permission-check-enter events nil {:tool-name tool-name :args {} :cancelled false :agent agent}))

(describe "allowed-tools while a skill is active"
          (fn []
            (it "asks without the skill, allows bash with it, still asks for other tools"
                (fn []
                  (let [agent  (fresh-agent)
                        events (ask-bus)]
                    (-> (gate events agent "bash")
                        (.then (fn [ctx]
                                 (-> (expect (:cancelled ctx)) (.toBe true))
                                 (skills/activate-skill test-skills "deploy" agent)))
                        (.then (fn [_] (gate events agent "bash")))
                        (.then (fn [ctx]
                                 (-> (expect (boolean (:cancelled ctx))) (.toBe false))
                                 (gate events agent "write")))
                        (.then (fn [ctx]
                                 (-> (expect (:cancelled ctx)) (.toBe true))))))))
            (it "a deny still wins over the skill's allowance"
                (fn []
                  (let [agent  (fresh-agent)
                        events (create-event-bus)]
                    ((:on events) "permission_request" (fn [_] #js {:decision "deny"}))
                    (-> (skills/activate-skill test-skills "deploy" agent)
                        (.then (fn [_] (gate events agent "bash")))
                        (.then (fn [ctx]
                                 (-> (expect (:cancelled ctx)) (.toBe true))))))))))

;;; ─── the skill tool ───────────────────────────────────────────

(describe "skill tool"
          (fn []
            (it "activates the skill and returns the substituted body"
                (fn []
                  (let [agent (fresh-agent)
                        t     (skills/skill-tool test-skills agent)]
                    (-> (.execute t #js {:name "deploy" :args #js ["web" "prod"]})
                        (.then (fn [out]
                                 (-> (expect (.includes out "Deploy web to prod. All: web prod")) (.toBe true))
                                 (-> (expect (contains? (:active-skills @(:state agent)) "deploy")) (.toBe true))
                                 (-> (expect (count (system-messages agent))) (.toBe 1))))))))
            (it "unknown name errors and names the invocable skills"
                (fn []
                  (let [t (skills/skill-tool test-skills (fresh-agent))]
                    (-> (.execute t #js {:name "nope"})
                        (.then (fn [_] (throw (js/Error. "expected a rejection")))
                               (fn [e]
                                 (-> (expect (.-message e)) (.toContain "Unknown skill \"nope\""))
                                 (-> (expect (.-message e)) (.toContain "deploy"))
                                 (.toContain (.-not (expect (.-message e))) "hidden")))))))
            (it "cannot activate a disable-model-invocation skill"
                (fn []
                  (let [t (skills/skill-tool test-skills (fresh-agent))]
                    (-> (.execute t #js {:name "hidden"})
                        (.then (fn [_] (throw (js/Error. "expected a rejection")))
                               (fn [e] (-> (expect (.-message e)) (.toContain "Unknown skill"))))))))
            (it "description lists only model-invocable skills, by NAME only"
                (fn []
                  (let [desc (skills/skill-tool-description test-skills)]
                    (-> (expect desc) (.toContain "deploy"))
                    ;; The description text itself lives in the system prompt's
                    ;; "Available Skills" block. Repeating it here sent every
                    ;; skill's description twice in every request.
                    (.toContain (.-not (expect desc)) "Ship it")
                    (.toContain (.-not (expect desc)) "hidden"))))

            (it "a skill added after launch is known to the tool once it is registered again (/reload)"
                (fn []
                  (let [agent (fresh-agent)
                        reg   (:tool-registry agent)
                        later (assoc test-skills "newer"
                                     (record "newer" "---\ndescription: Arrived later\n---\nNew body"))]
                    (skills/register-skill-tool! agent test-skills)
                    (skills/register-skill-tool! agent later)
                    (let [t (get ((:all reg)) "skill")]
                      (-> (expect (.-description t)) (.toContain "newer"))
                      (-> (expect (contains? ((:get-active reg)) "skill")) (.toBe true))
                      (-> (.execute t #js {:name "newer"})
                          (.then (fn [out]
                                   (-> (expect out) (.toContain "New body")))))))))

            (it "re-registering keeps the tool inactive when a --tools filter left it out"
                (fn []
                  (let [agent (fresh-agent)
                        reg   (:tool-registry agent)]
                    (skills/register-skill-tool! agent test-skills)
                    ((:set-active reg) #{"read"})
                    (skills/register-skill-tool! agent test-skills)
                    (-> (expect (contains? ((:get-active reg)) "skill")) (.toBe false))
                    (-> (expect (contains? ((:all reg)) "skill")) (.toBe true)))))

            (it "with no skills left the tool is removed"
                (fn []
                  (let [agent (fresh-agent)
                        reg   (:tool-registry agent)]
                    (skills/register-skill-tool! agent test-skills)
                    (skills/register-skill-tool! agent {})
                    (-> (expect (contains? ((:all reg)) "skill")) (.toBe false)))))))
