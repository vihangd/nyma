(ns agent.extensions.verify-gate.shared
  "Pure helpers for the verify-before-done gate.

   Loop-engineering SOTA (2026): the loop needs something deterministic that
   can say no — a test suite, a typecheck — before the agent declares done.
   The gate runs a configured quality command after any turn that edited
   files; a failure is fed back as a follow-up so the agent fixes it before
   stopping."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.tool-metadata :as tool-metadata]
            [agent.utils.git-files :as git]))

(def default-config
  {:cmd nil :critic nil :max-attempts 2 :timeout-ms 120000})

(defn config
  "Read the `verify` settings section. Gate is off unless `cmd` is set, critic
   unless `critic` names a role:
   {\"verify\": {\"cmd\": \"bun test\", \"critic\": \"reviewer\",
               \"max-attempts\": 2, \"timeout-ms\": 120000}}"
  [settings]
  (let [v (when settings (aget settings "verify"))]
    (merge default-config
           (when v
             (cond-> {}
               (aget v "cmd")          (assoc :cmd (aget v "cmd"))
               (aget v "critic")       (assoc :critic (aget v "critic"))
               (aget v "max-attempts") (assoc :max-attempts (aget v "max-attempts"))
               (aget v "timeout-ms")   (assoc :timeout-ms (aget v "timeout-ms")))))))

;; ── "you tried to configure this and it did not take" ────────────────────
;; The gate cannot warn merely because `verify.cmd` is unset: extension.json
;; declares the whole section, the loader registers it as defaults, so
;; `(.settings api "verify")` is non-empty for EVERY user and an
;; existence check would nag everyone who never wanted the gate.
;;
;; An unrecognised key in the section is the signal that someone did try —
;; `"comand"`, `"command"`, `"maxAttempts"` — and got silence for it.

(def known-keys ["cmd" "critic" "max-attempts" "timeout-ms"])

(defn unknown-keys
  "Keys in the `verify` settings section that no reader looks at. Pure."
  [section]
  (if-not section
    []
    (vec (sort (filterv (fn [k] (not (.includes (clj->js known-keys) k)))
                        (vec (js/Object.keys section)))))))

(defn off-hint
  "The one-time notice for a `verify` section that was written but does
   nothing. Names the key that works and the ones that do not. Pure."
  [unknown]
  (str "verify_gate loaded but off — set verify.cmd (e.g. \"bun test\"). "
       "Unrecognised key"
       (when (> (count unknown) 1) "s")
       " in the `verify` settings section: "
       (.join (clj->js unknown) ", ")))

(defn edit-tool? [tool-name]
  (tool-metadata/file-editing? tool-name))

(defn tampered-paths
  "Paths from `paths` that look like graded checks (test files or the
   settings holding the verify config). A green gate after editing these
   deserves human review — verifiers get satisfied instead of the request."
  [paths]
  (filterv (fn [p]
             (let [s (str p)]
               (or (re-find #"(?i)(^|/)(tests?|__tests__)(/|\.|_|-)" s)
                   (.includes s ".test.")
                   (.includes s "_test.")
                   (.includes s ".spec.")
                   ;; Only the settings file that holds the verify config —
                   ;; flagging every settings.json (.vscode/…) is noise.
                   (.includes s ".nyma/settings.json"))))
           (vec paths)))

(defn tail-lines
  "Last n lines of s — failures live at the end of test output."
  [s n]
  (let [lines (.split (str s) "\n")]
    (.join (.slice lines (max 0 (- (.-length lines) n))) "\n")))

(defn failure-message
  "Follow-up injected when the gate fails."
  [cmd exit-code output attempt max-attempts]
  (str "Verification gate failed (attempt " attempt "/" max-attempts "): `"
       cmd "` exited " exit-code ".\n\n"
       "```\n" (tail-lines output 40) "\n```\n\n"
       "Fix the failures before finishing. Do not weaken or delete tests to make them pass. "
       "If a test is itself wrong, or conflicts with the request, stop and say so instead of changing it."))

;; ── critic ───────────────────────────────────────────────────────────────
;; One read-only review of a run's change, in a fresh context. Review earns its
;; keep from a fresh context and a stronger model, and loses it to noise and
;; repeat rounds: so one round, few findings, each with a way to check it, and
;; the transcript stays out. Reasoning it is handed would be reasoning it agrees with.

(def max-findings 3)
(def max-diff-chars 60000)
(def max-task-chars 4000)

(defn head
  "The commit a run starts from, so work it commits still counts as its change."
  [cwd]
  (not-empty (git/run-git-args ["rev-parse" "HEAD"] cwd)))

(defn- repo-path
  "`p` relative to `root`, nil outside it: git refuses the whole command for one
   such path."
  [root cwd p]
  (let [abs (path/resolve cwd (str p))
        abs (try (fs/realpathSync abs)
                 (catch :default _
                   (try (path/join (fs/realpathSync (path/dirname abs)) (path/basename abs))
                        (catch :default _ abs))))
        rel (path/relative root abs)]
    (when-not (or (empty? rel) (str/starts-with? rel "..") (path/isAbsolute rel))
      rel)))

(defn run-change
  "The diff since `base` (default HEAD) and new files for the paths a run edited,
   and only those: the user's own uncommitted work is not this run's to answer
   for."
  [paths cwd & [base]]
  (let [root (git/run-git-args ["rev-parse" "--show-toplevel"] cwd)
        ps   (when (seq root) (vec (keep #(repo-path root cwd %) paths)))]
    (if (empty? ps)
      {:diff "" :new []}
      {:diff (git/run-git-args (into ["diff" (or base "HEAD") "--"] ps) root)
       :new  (remove str/blank? (str/split-lines
                                 (git/run-git-args (into ["ls-files" "--others" "--exclude-standard" "--"] ps) root)))})))

(defn critic-task
  "What the critic is told. Pure."
  [task {:keys [diff new]}]
  (str "Review a change another agent just made, for defects its tests would not catch: "
       "wrong logic, missed edge cases, data loss, races, broken error handling, or not doing "
       "what was asked. Ignore style. Read the code around the change where the diff alone "
       "cannot settle it. Report only what you can show: a concrete input or state that "
       "gives a wrong result.\n\n"
       "Output at most " max-findings " findings, most severe first, one per line, exactly as:\n"
       "path:line — problem — input or state that shows it\n"
       "No preamble, no praise. If nothing qualifies, output exactly: No findings.\n\n"
       "What was asked:\n" (subs (str task) 0 (min (count (str task)) max-task-chars)) "\n"
       (when (seq new) (str "\nNew files (not in the diff; read them):\n  " (str/join "\n  " new) "\n"))
       "\nDiff" (when (> (count diff) max-diff-chars) " (truncated)") ":\n"
       (subs diff 0 (min (count diff) max-diff-chars))))

(defn critic-findings
  "The lines of a critic's reply to act on, at most `max-findings`. Pure."
  ;; ponytail: trusts one finding per line, as the prompt asks
  [text]
  (vec (take max-findings (remove str/blank? (str/split-lines (str text))))))

(defn critic-message
  "Follow-up carrying the findings. A reviewer's claim, not a fact. Pure."
  [findings]
  (str "An independent review of this change raised the following. Reviewers are often "
       "wrong: verify each against the code, fix the real ones, and say which you rejected "
       "and why.\n\n" (str/join "\n" findings)))
