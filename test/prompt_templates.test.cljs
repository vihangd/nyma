(ns prompt-templates.test
  "A `.md` in a prompts dir becomes `/<name>`: described by its frontmatter,
   arguments substituted, submitted as a user turn; never shadowing a
   command that already exists."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.debug :as d]
            [agent.resources.loader :refer [discover-prompts]]
            [agent.commands.builtins :refer [register-builtins register-prompt-commands!]]))

(def ^:dynamic *dir* nil)

(defn- write-prompt! [name content]
  (fs/writeFileSync (path/join *dir* (str name ".md")) content))

(defn- agent-with-prompts []
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})]
    (register-builtins agent {} {:skills {} :prompts (discover-prompts *dir*)})
    agent))

(describe "prompt templates as slash commands"
          (fn []
            (beforeEach (fn []
                          (set! *dir* (fs/mkdtempSync (path/join (os/tmpdir) "nyma-prompts-")))))
            (afterEach (fn []
                         (fs/rmSync *dir* #js {:recursive true :force true})))

            (it "registers /<name> with the frontmatter description and argument hint"
                (fn []
                  (write-prompt! "review" (str "---\n"
                                               "description: Review a file\n"
                                               "argument-hint: <path>\n"
                                               "---\n"
                                               "Review $1 carefully."))
                  (let [cmd (get @(:commands (agent-with-prompts)) "review")]
                    (-> (expect (some? cmd)) (.toBe true))
                    (-> (expect (:description cmd)) (.toBe "Review a file — <path>"))
                    (-> (expect (:group cmd)) (.toBe :prompts)))))

            (it "substitutes arguments and submits the text as a turn"
                (fn []
                  (write-prompt! "fix" "Fix $1 then run $ARGUMENTS")
                  (let [agent (agent-with-prompts)
                        sent  (atom nil)]
                    ((:on (:events agent)) "turn_request" (fn [d] (reset! sent d)))
                    ((:handler (get @(:commands agent) "fix")) ["tests" "again"] nil)
                    (-> (expect (.-text @sent)) (.toBe "Fix tests then run tests again"))
                    (-> (expect (.-echo @sent)) (.toBe true)))))

            (it "with no arguments sends the prompt with $ARGUMENTS emptied, never literally"
                (fn []
                  ;; The empty-args passthrough is for skill bodies quoting shell
                  ;; snippets; a prompt template with it sent `$ARGUMENTS` to
                  ;; the model as text.
                  (write-prompt! "review" "Review $ARGUMENTS carefully")
                  (let [agent (agent-with-prompts)
                        sent  (atom nil)]
                    ((:on (:events agent)) "turn_request" (fn [d] (reset! sent d)))
                    ((:handler (get @(:commands agent) "review")) [] nil)
                    (-> (expect (.-text @sent)) (.toBe "Review  carefully")))))

            (it "queues a follow-up when nothing can start a turn"
                (fn []
                  (write-prompt! "hi" "Hello $1")
                  (let [agent (agent-with-prompts)]
                    ((:handler (get @(:commands agent) "hi")) ["there"] nil)
                    (-> (expect (mapv :content @(:follow-queue agent))) (.toEqual ["Hello there"])))))

            (it "skips a name that belongs to an existing command, with a warning"
                (fn []
                  (write-prompt! "help" "Not the help command")
                  (let [logged (atom [])]
                    (d/configure-logger! (fn [line] (swap! logged conj line)))
                    (let [agent (agent-with-prompts)
                          help  (get @(:commands agent) "help")]
                      (-> (expect (:aliases help)) (.toEqual ["?"]))
                      (-> (expect (some #(.includes % "/help skipped") @logged)) (.toBe true))))))

            (it "re-registering drops a template whose file went away"
                (fn []
                  (write-prompt! "gone" "Bye")
                  (let [agent (agent-with-prompts)]
                    (-> (expect (contains? @(:commands agent) "gone")) (.toBe true))
                    (fs/rmSync (path/join *dir* "gone.md"))
                    (register-prompt-commands! agent (discover-prompts *dir*))
                    (-> (expect (contains? @(:commands agent) "gone")) (.toBe false)))))))
