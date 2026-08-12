(ns agent.extensions.small-model.self-tune
  "Self-tune — ACE-style online playbook for small/local worker models.

   When the worker (small model) emits a *failure signal* — hallucinated
   tool, exact-repeat call, empty turn (from quality_monitor), or a
   verify-fail (from verify_gate) — the lead (advisor) reflects on the
   transcript and distills ONE generalizable rule. The rule is appended as
   an incremental *delta* to a per-project playbook that is injected into the
   worker's system prompt on subsequent turns, so it stops repeating the
   mistake.

   SOTA basis:
     • ACE (arXiv 2510.04618) — context as an evolving playbook updated by
       incremental deltas; append+dedup (NOT block-rewrite) is what avoids
       the paper's 'context collapse / brevity bias' failure mode.
     • TT-D (arXiv 2510.07841) — a stronger model distilling lessons to the
       weak student; nyma's advisor→worker split is exactly this.
     • Reflexion / Self-Refine — reflect-into-memory foundation.

   Off by default. No-op on frontier models (none of the small-model failure
   signals fire). Reflection is bounded (min-failures, max-reflections) to
   avoid the supervisor's 'context ceiling'.

   Reuses:
     supervisor/call-advisor-tool — advisor invocation (getTool 'advisor')
     before_agent_start           — playbook injection (as memory does)

   Hooks used:
     small-model/quality-signal — internal bus (quality_monitor)
     small-model/verify-fail    — internal bus (verify_gate)
     before_agent_start         — inject the playbook block"
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.debug :as d]
            [agent.extensions.small-model.supervisor :as supervisor]))

;; ── Playbook store (append + dedup + cap; ACE anti-collapse) ─────────
;;
;; ponytail: dir hardcoded to the memory extension's default ("memory").
;; If a user overrides settings.memory.dir the playbook still lives under
;; "memory" — harmless (separate file), upgrade to reading memory.shared/config
;; only if that override turns out to matter.

(defn- lessons-file [dir]
  (path/join (js/process.cwd) ".nyma" dir "PLAYBOOK.md"))

(defn- normalize [s]
  (-> (str s) str/lower-case str/trim (.replace (js/RegExp. "\\s+" "g") " ")))

(defn- dup?
  "True when `candidate` is a near-duplicate of any existing lesson
   (normalized equality, or one containing the other)."
  [existing candidate]
  (let [nc (normalize candidate)]
    (boolean
     (some (fn [e]
             (let [ne (normalize e)]
               (or (= ne nc)
                   (and (>= (count ne) 12) (str/includes? nc ne))
                   (and (>= (count nc) 12) (str/includes? ne nc)))))
           existing))))

(defn append-lesson
  "Delta-append `candidate` to `existing`, dropping near-dups and capping to
   the last `max-n` (oldest evicted). Returns `existing` unchanged on dup."
  [existing candidate max-n]
  (if (or (str/blank? (str candidate)) (dup? existing candidate))
    (vec existing)
    (vec (take-last (max 1 max-n) (conj (vec existing) candidate)))))

;; `call-advisor-tool` never throws — on a missing tool or a failed/timed-out
;; call it RETURNS a "Supervisor: …" string. That is not a lesson; persisting it
;; would inject advisor plumbing errors into the worker's prompt forever (and
;; since the fallback interpolates the failure reason, each one is distinct
;; enough to dodge dedup and eat a playbook slot).
(def ^:private advisor-failure-re
  (js/RegExp. "^supervisor:" "i"))

(defn advisor-failure? [s]
  (boolean (.exec advisor-failure-re (str/trim (str s)))))

(defn clean-lesson
  "Squeeze advisor output into one short rule: strip a leading bullet/number
   marker, collapse to at most 2 non-blank lines, cap length.
   Returns \"\" for advisor-plumbing failures so they are never persisted."
  [raw]
  (if (advisor-failure? raw)
    ""
    (let [lines (->> (str/split (str/trim (str raw)) "\n")
                     (map str/trim)
                     (remove str/blank?)
                     (take 2))
          ;; Anchor on an actual bullet/number marker — a bare char class would
          ;; turn "3-way merges must…" into "way merges must…".
          s (-> (str/join " " lines)
                (.replace (js/RegExp. "^\\s*(?:[-*•]|\\d+[.)])\\s+") ""))]
      (if (> (count s) 300) (str (subs s 0 297) "…") s))))

(defn- read-lessons [dir]
  (let [f (lessons-file dir)]
    (if (fs/existsSync f)
      (try
        (->> (str/split (fs/readFileSync f "utf8") "\n")
             (map str/trim)
             (filter #(str/starts-with? % "- "))
             (mapv #(str/trim (subs % 2))))
        (catch :default _ []))
      [])))

(defn render-playbook [lessons]
  (str "# Playbook (learned by self-tune)\n\n"
       (str/join "\n" (map #(str "- " %) lessons))
       "\n"))

(defn- write-playbook! [dir lessons]
  (let [f (lessons-file dir)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/writeFileSync f (render-playbook lessons))
    f))

;; ── Reflection ──────────────────────────────────────────────────────

(defn ^:async reflect!
  "Ask the lead (advisor) for one generalizable rule addressing `reason`,
   then delta-append it to the playbook. Advisor sees the transcript itself."
  [api dir max-lessons reason]
  (try
    (let [focus  (str "The small worker model repeatedly hit this failure: " reason
                      ". Review its recent turns and write ONE concise, generalizable "
                      "rule (imperative, at most 2 lines) that would prevent this CLASS "
                      "of mistake in future turns on this project. Output ONLY the rule "
                      "text — no preamble, no numbering.")
          advice (js-await (supervisor/call-advisor-tool api focus))
          lesson (clean-lesson advice)]
      (when (seq lesson)
        (let [existing (read-lessons dir)
              updated  (append-lesson existing lesson max-lessons)]
          (when (not= updated existing)
            (write-playbook! dir updated)
            (d/log "[self-tune] learned:" lesson)))))
    (catch :default e
      (d/warn "[self-tune] reflect failed:" (or (.-message e) (str e))))))

;; ── Activation ──────────────────────────────────────────────────────

(defn activate
  "Wire self-tune. Returns a cleanup fn."
  [api config]
  (let [st-cfg      (:self-tune config)
        ;; Explicit nil checks, not `or`: squint compiles `or` to JS `||`, where
        ;; 0 is falsey — so "max-reflections": 0 (the natural way to disable
        ;; reflection while leaving the module wired) would silently mean 3.
        num-cfg     (fn [k default]
                      (let [v (get st-cfg k)] (if (number? v) v default)))
        max-lessons (num-cfg :max-lessons 20)
        min-fail    (max 1 (num-cfg :min-failures 2))
        max-reflect (num-cfg :max-reflections 3)
        reflect-on  (set (map str (or (:reflect-on st-cfg)
                                      ["quality-signal" "verify-fail"])))
        dir         "memory"
        handlers    (atom [])
        failures    (atom 0)
        reflections (atom 0)

        ;; A failure signal arrived. Count it; once min-failures accumulate
        ;; (and we're under the reflection budget) fire one reflection and
        ;; reset the counter so the next lesson needs another N failures.
        on-failure
        (fn [data _ctx]
          (let [reason (str (or (.-reason data) "quality issue detected"))
                ;; verify_gate carries the actual failure output; the heuristic
                ;; signals carry only a reason. Pass it through when present —
                ;; a rule written against "exit 1" is a guess, one written
                ;; against the assertion that failed is not.
                output (when (and data (.-output data))
                         (str (.-output data)))
                reason (if (seq (str (or output "")))
                         (str reason "\n\nFailure output:\n" output)
                         reason)]
            (swap! failures inc)
            (when (and (>= @failures min-fail)
                       (< @reflections max-reflect))
              (reset! failures 0)
              (swap! reflections inc)
              ;; Fire-and-forget: don't block the bus on the advisor call.
              (reflect! api dir max-lessons reason))))

        ;; Inject the learned playbook into the worker's system prompt.
        on-before-start
        (fn [_data _ctx]
          (let [lessons (read-lessons dir)]
            (when (seq lessons)
              #js {:system-prompt-additions
                   #js [(str "# Learned playbook (self-tune)\n\n"
                             "Rules distilled from your past mistakes on this "
                             "project. Follow them:\n\n"
                             (str/join "\n" (map #(str "- " %) lessons)))]})))]

    (when (contains? reflect-on "quality-signal")
      (.on api "small-model/quality-signal" on-failure)
      (swap! handlers conj ["small-model/quality-signal" on-failure]))

    (when (contains? reflect-on "verify-fail")
      (.on api "small-model/verify-fail" on-failure)
      (swap! handlers conj ["small-model/verify-fail" on-failure]))

    (.on api "before_agent_start" on-before-start)
    (swap! handlers conj ["before_agent_start" on-before-start])

    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler)))))
