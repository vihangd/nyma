(ns help-grouping.test
  "/help is grouped, so 'what can I do with sessions?' is one glance rather
   than a read of forty alphabetical lines."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.builtins :refer [help-sections help-for-command
                                             help-group]]
            [clojure.string :as str]
            ["node:fs" :as fs]))

(def ^:private commands
  {"new"                {:group :session :description "Start a FRESH session file"}
   "clear"              {:group :session :description "Reset the context in THIS session file"}
   "cls"                {:group :session :description "Clear the terminal screen only"}
   "model"              {:group :model   :description "Pick a model"}
   "thinking"           {:group :model   :description "Set thinking level"}
   "model-roles__role"  {:description "Switch role"}
   "model-roles__mode"  {:description "Switch mode"}
   "spec__plan"         {:description "Plan a spec"}
   "spec__analyze"      {:description "Analyse a spec"}
   "agent-shell__login" {:description "Log the agent in" :forward-to "agent-shell"}
   "copy"               {:description "Copy the last reply"}
   "hidden-one"         {:hidden? true :description "not shown"}})

(defn- section-of
  "The lines under `title`, up to the next blank line."
  [text title]
  (let [ls   (.split text "\n")
        idx  (.indexOf ls title)]
    (when (>= idx 0)
      (loop [i (inc idx) out []]
        (if (or (>= i (.-length ls)) (= "" (aget ls i)))
          out
          (recur (inc i) (conj out (aget ls i))))))))

(describe
 "/help groups commands into sections"
 (fn []
   (it "puts /new, /clear and /cls under Session"
       (fn []
         (let [lines (str/join "\n" (section-of (help-sections commands) "Session"))]
           (-> (expect (.includes lines "/new")) (.toBe true))
           (-> (expect (.includes lines "/clear")) (.toBe true))
           (-> (expect (.includes lines "/cls")) (.toBe true)))))

   (it "puts /model and /thinking under Model"
       (fn []
         (let [lines (str/join "\n" (section-of (help-sections commands) "Model"))]
           (-> (expect (.includes lines "/model")) (.toBe true))
           (-> (expect (.includes lines "/thinking")) (.toBe true)))))

   (it "files /role and /mode under Modes & roles, not under Extensions"
       (fn []
         (-> (expect (str (help-group "model-roles__role" {}))) (.toBe "modes"))
         (-> (expect (str (help-group "model-roles__mode" {}))) (.toBe "modes"))
         (let [lines (str/join "\n" (section-of (help-sections commands) "Modes & roles"))]
           (-> (expect (.includes lines "/role")) (.toBe true))
           (-> (expect (.includes lines "/mode")) (.toBe true)))))

   (it "gives each extension namespace its own subsection with short names"
       (fn []
         (let [text (help-sections commands)]
           (-> (expect (.includes text "  [spec]")) (.toBe true))
           (-> (expect (.includes text "/plan")) (.toBe true))
           (-> (expect (.includes text "spec__plan")) (.toBe false)))))

   (it "lists agent-forwarded commands under Agent with the // prefix"
       (fn []
         (let [text (help-sections commands)]
           (-> (expect (.includes text "Agent commands (forwarded via //)")) (.toBe true))
           (-> (expect (.includes text "//login")) (.toBe true)))))

   (it "puts an ungrouped builtin under Other rather than losing it"
       (fn []
         (-> (expect (str (help-group "copy" {}))) (.toBe "other"))
         (-> (expect (.includes (str/join "\n" (section-of (help-sections commands) "Other"))
                                "/copy"))
             (.toBe true))))

   (it "never lists a hidden command"
       (fn []
         (-> (expect (.includes (help-sections commands) "hidden-one")) (.toBe false))))

   (it "says how to get one command's details"
       (fn []
         (-> (expect (.includes (help-sections commands) "/help <command>")) (.toBe true))))))

(describe
 "/new, /clear and /cls say what differs"
 (fn []
   ;; Read the real registrations, not a fixture: the bug was that the
   ;; SHIPPED descriptions ("Clear messages and reset agent session",
   ;; "Start a new session") gave no way to tell the three apart.
   (let [src (fs/readFileSync "src/agent/commands/builtins.cljs" "utf8")]

     (it "says /clear resets the context in the same session file"
         (fn []
           (-> (expect (.includes src "Reset the context in THIS session file"))
               (.toBe true))))

     (it "says /new starts a fresh session file"
         (fn []
           (-> (expect (.includes src "Start a FRESH session file")) (.toBe true))))

     (it "says /cls touches the terminal only"
         (fn []
           (-> (expect (.includes src "Clear the terminal screen and scrollback only"))
               (.toBe true))))

     (it "no longer ships the descriptions that could not be told apart"
         (fn []
           (-> (expect (.includes src "\"Clear messages and reset agent session\""))
               (.toBe false))
           (-> (expect (.includes src "\"Start a new session\"")) (.toBe false)))))))

(describe
 "/help <command> prints one command"
 (fn []
   (it "resolves a short name to its namespaced command"
       (fn []
         (let [text (help-for-command commands "role")]
           (-> (expect (.includes text "Switch role")) (.toBe true)))))

   (it "says so when there is no such command"
       (fn []
         (-> (expect (.includes (help-for-command commands "nope") "No such command"))
             (.toBe true))))))
