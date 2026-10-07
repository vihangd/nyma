(ns agent.extensions.model-roles.features.review
  "/review [base] — the `deep` role reviews a diff and returns ranked
   findings; on confirmation they go to the agent to fix.

   Reviewing meant installing the pr-review skill or asking /advisor, which
   sees the conversation rather than the change. This reads the change
   itself: `git diff HEAD` (everything uncommitted) by default, or
   `git diff <base>` — committed and uncommitted since base."
  (:require ["ai" :refer [generateText]]
            [clojure.string :as str]
            [agent.utils.git-files :as git]))

(def max-diff-chars 80000)

(def system-prompt
  (str "You are a senior code reviewer. Review the diff for real defects: bugs, wrong logic, "
       "missed edge cases, security problems, data loss, races, broken error handling, and tests "
       "that do not test what they claim. Ignore style and formatting unless it changes meaning. "
       "Output one finding per line, most severe first, exactly as:\n"
       "path:line — high|medium|low — problem — fix\n"
       "No preamble, no praise, no summary. If there is nothing worth fixing, output exactly: No findings."))

(defn user-prompt
  "The stat, untracked files and the clipped diff. Pure."
  [{:keys [label stat untracked diff]}]
  (str "Change under review: " label "\n\n"
       "Summary:\n" stat "\n"
       (when (seq untracked) (str "\nNew untracked files (not in the diff):\n  " (str/join "\n  " untracked) "\n"))
       "\nDiff" (when (> (count diff) max-diff-chars) " (truncated)") ":\n"
       (subs diff 0 (min (count diff) max-diff-chars))))

(defn findings?
  "Did the review find anything? Pure."
  [text]
  (let [t (str/trim (str text))]
    (and (seq t) (not (str/starts-with? (str/lower-case t) "no findings")))))

(defn- notify [ui msg level]
  (when (and ui (.-notify ui)) (.notify ui msg level)))

(defn- review-model
  "The `deep` role's model, else the current one."
  [api settings]
  (let [role (get-in settings [:roles :deep])]
    (or (when (and role (:provider role) (:model role) (aget api "resolveModel"))
          (try ((aget api "resolveModel") (:provider role) (:model role)) (catch :default _ nil)))
        (when-let [a (aget api "__state_atom")]
          (try (:model @a) (catch :default _ nil))))))

(defn ^:async run!
  "The /review flow. `gen` is injectable for tests (defaults to generateText)."
  [api args ctx & [{:keys [gen cwd]}]]
  (let [ui   (some-> ctx .-ui)
        cwd  (or cwd (js/process.cwd))
        root (git/run-git "rev-parse --show-toplevel" cwd)
        base (some-> (first args) str str/trim not-empty)
        ref  (or base "HEAD")]
    (cond
      (str/blank? root)
      (do (notify ui "/review: not a git repository." "error") nil)

      ;; run-git goes through a shell: a revision is never allowed to carry
      ;; anything a shell would read.
      (and base (not (.test (js/RegExp. "^[A-Za-z0-9._/~^@{}-]+$") base)))
      (do (notify ui (str "/review: not a revision: " base) "error") nil)

      (and base (str/blank? (git/run-git (str "rev-parse --verify --quiet '" base "^{commit}'") root)))
      (do (notify ui (str "/review: unknown revision " base) "error") nil)

      :else
      (let [diff      (git/run-git (str "diff '" ref "'") root)
            untracked (remove str/blank? (str/split-lines (git/run-git "ls-files --others --exclude-standard" root)))]
        (if (and (str/blank? diff) (empty? untracked))
          (do (notify ui (str "/review: nothing to review against " ref
                              (when-not base " — try /review <base>, e.g. /review main"))
                      "info")
              nil)
          (if-let [model (review-model api (.settings api))]
            (let [_      (notify ui (str "Reviewing changes against " ref "…") "info")
                  result (js-await ((or gen generateText)
                                    #js {:model  model
                                         :system system-prompt
                                         :prompt (user-prompt {:label     (if base (str "everything since " base) "uncommitted changes")
                                                               :stat      (git/run-git (str "diff --stat '" ref "'") root)
                                                               :untracked untracked
                                                               :diff      diff})
                                         :maxOutputTokens 4096}))
                  text   (str/trim (str (.-text result)))]
              (cond
                (not (findings? text)) (notify ui "/review: no findings." "info")
                (and ui (.-showOverlay ui)) (.showOverlay ui (str "Review against " ref "\n\n" text))
                :else (notify ui text "info"))
              (when (and (findings? text) ui (.-confirm ui) (aget api "sendUserMessage")
                         (js-await (.confirm ui "Ask the agent to fix these findings?")))
                ((aget api "sendUserMessage")
                 (str "Code review findings for " (if base (str "the changes since " base) "the uncommitted changes")
                      ". Verify each against the code and fix the real ones; say which you rejected and why.\n\n" text)))
              text)
            (do (notify ui "/review: no model to review with — set settings.roles.deep." "error") nil)))))))
