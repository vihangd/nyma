(ns agent.extensions.model-roles.features.commit
  "/commit — draft a Conventional Commit message for the staged change with the
   `commit` role's model, show it, commit on confirmation. Never pushes.

   The `commit` role was defined in the role defaults and used by nothing but
   spec_driven's ship phase: committing from nyma meant asking the main model
   in conversation, at main-model prices, or leaving for a shell."
  (:require ["ai" :refer [generateText]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.utils.git-files :as git]))

(def max-diff-chars 60000)

(def system-prompt
  (str "You write git commit messages in Conventional Commits form: "
       "`type(scope): imperative subject` (types: feat, fix, refactor, perf, test, docs, build, ci, chore), "
       "subject at most 72 characters, no trailing period. Then a blank line and a short body that says WHY "
       "the change was made — not a list of files. Match the repository's recent style when it is shown. "
       "Output ONLY the message: no preamble, no code fences, no quotes."))

(defn user-prompt
  "What the model sees: recent subjects (for style), the stat and the diff,
   clipped. Pure."
  [{:keys [recent stat diff]}]
  (str (when (seq recent) (str "Recent commits in this repository:\n" recent "\n\n"))
       "Staged change summary:\n" stat "\n\n"
       "Staged diff" (when (> (count diff) max-diff-chars) " (truncated)") ":\n"
       (subs diff 0 (min (count diff) max-diff-chars))))

(defn clean-draft
  "Strip what models wrap messages in anyway: code fences, surrounding quotes,
   a leading `Commit message:` label. Pure."
  [text]
  (-> (str text)
      str/trim
      (str/replace #"^```[a-z]*\n?" "")
      (str/replace #"\n?```$" "")
      (str/replace #"(?i)^commit message:\s*" "")
      (str/replace #"^\"([\s\S]*)\"$" "$1")
      str/trim))

(defn with-subject
  "Replace the first line of `message` with `subject`. Pure."
  [message subject]
  (let [[_ & body] (str/split-lines (str message))]
    (str/join "\n" (cons (str/trim subject) body))))

(defn- notify [ui msg level]
  (when (and ui (.-notify ui)) (.notify ui msg level)))

(defn- commit-model
  "The `commit` role's model, else the current one. A role whose provider has
   no credentials falls back rather than failing the command."
  [api settings]
  (let [role (get-in settings [:roles :commit])]
    (or (when (and role (:provider role) (:model role) (aget api "resolveModel"))
          (try ((aget api "resolveModel") (:provider role) (:model role)) (catch :default _ nil)))
        ;; Current model from the agent state atom, as handoff reads it.
        (when-let [a (aget api "__state_atom")]
          (try (:model @a) (catch :default _ nil))))))

(defn ^:async run!
  "The /commit flow. `gen` is injectable for tests (defaults to generateText)."
  [api ctx & [{:keys [gen cwd]}]]
  (let [ui   (some-> ctx .-ui)
        cwd  (or cwd (js/process.cwd))
        root (git/run-git "rev-parse --show-toplevel" cwd)]
    (if (str/blank? root)
      (do (notify ui "/commit: not a git repository." "error") nil)
      (let [staged   #(remove str/blank? (str/split-lines (git/run-git "diff --cached --name-only" root)))
            tracked  (remove str/blank? (str/split-lines (git/run-git "diff --name-only" root)))
            proceed? (cond
                       (seq (staged)) true
                       (empty? tracked) (do (notify ui "/commit: nothing to commit — no staged or tracked changes." "info") false)
                       ;; Offer, never assume: staging is the user's decision.
                       ;; Untracked files are never added.
                       (not (and ui (.-confirm ui)))
                       (do (notify ui "/commit: nothing staged — `git add` the files first." "info") false)
                       :else
                       (let [ok (js-await (.confirm ui (str "Nothing is staged. Stage these " (count tracked)
                                                            " tracked change(s)?\n  " (str/join "\n  " (take 15 tracked))
                                                            (when (> (count tracked) 15) "\n  …"))))]
                         (when ok (git/run-git "add -u" root))
                         (boolean ok)))]
        (when proceed?
          (let [model (commit-model api (.settings api))]
            (if-not model
              (do (notify ui "/commit: no model to draft with — set settings.roles.commit." "error") nil)
              (let [result (js-await ((or gen generateText)
                                      #js {:model  model
                                           :system system-prompt
                                           :prompt (user-prompt {:recent (git/run-git "log --oneline -10" root)
                                                                 :stat   (git/run-git "diff --cached --stat" root)
                                                                 :diff   (git/run-git "diff --cached" root)})
                                           :maxOutputTokens 1024}))
                    draft  (clean-draft (.-text result))
                    choice (if (and ui (.-select ui))
                             (js-await (.select ui (str "Commit with this message?\n\n" draft)
                                                #js ["Commit" "Edit subject" "Cancel"]))
                             "Cancel")
                    final  (cond
                             (= choice "Commit") draft
                             (= choice "Edit subject")
                             (let [s (js-await (.input ui "New subject line:" (first (str/split-lines draft))))]
                               (when (seq (str/trim (str s))) (with-subject draft s)))
                             :else nil)]
                (if-not final
                  (do (notify ui "/commit: cancelled — nothing committed." "info") nil)
                  ;; -F a temp file: the message may hold quotes, backticks,
                  ;; anything a shell would mangle. Hooks run as usual.
                  (let [f   (path/join (os/tmpdir) (str "nyma-commit-" (js/Date.now) ".txt"))
                        _   (fs/writeFileSync f final)
                        out (git/run-git (str "commit -F '" f "'") root)]
                    (try (fs/unlinkSync f) (catch :default _ nil))
                    (if (str/blank? out)
                      (do (notify ui "/commit: git commit failed (a hook, or nothing to commit) — run git commit to see why." "error") nil)
                      (do (notify ui (first (str/split-lines out)) "info") final))))))))))))
