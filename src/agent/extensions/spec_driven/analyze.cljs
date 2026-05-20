(ns agent.extensions.spec-driven.analyze
  "Implements `/spec analyze <feat>` — cross-artifact consistency
   check across spec/plan/tasks (and the constitution when present).

   Drop-in compatible with github/spec-kit v0.8.x's `/speckit.analyze`:
     - 6 detection passes:
         A. Duplication        (near-duplicate FRs)
         B. Ambiguity          (vague adjectives + unresolved markers)
         C. Underspecification (FRs without measurable outcomes;
                                tasks referencing undefined files)
         D. Constitution       (MUST violations auto-CRITICAL)
         E. Coverage Gaps      (FRs with no tasks; tasks with no FR/story)
         F. Inconsistency      (terminology drift; conflicting stack)
     - 4 severity levels (CRITICAL / HIGH / MEDIUM / LOW)
     - 50 finding cap (soft — overflow rolls into a summary line)
     - Stable IDs (A1, D1, etc.) so re-runs are diffable

   Architecture: synchronous single model call. Handler reads the
   four input files, builds a prompt, calls `generateText`, parses
   the response (Markdown table) into structured findings, prints
   them, and caches the run in `.nyma/spec-state.json` for the
   soft-block on `/spec start`."
  (:require ["node:path" :as path]
            ["node:fs"   :as fs]
            ["node:crypto" :as crypto]
            [clojure.string :as str]
            [agent.extensions.spec-driven.speckit-adapter :as adapter]))

;; ── The analyze prompt template ───────────────────────────────
;;
;; Adapted from `templates/commands/analyze.md` in github/spec-kit
;; v0.8.5 (May 4 2026). Substitutions:
;;   - __SPECKIT_COMMAND_IMPLEMENT__ → /spec start
;;   - prerequisites script → injected file contents in the prompt body

(def analyze-prompt-template
  "You are running a spec-kit `analyze` pass on a feature spec. Output
a structured Markdown report. **You MUST NOT modify any files.**

## Inputs

You have full access to four artifacts (provided below). They are
authoritative — base every finding on what they actually say.

  1. **spec.md** — Functional Requirements (FR-###), Success Criteria
     (SC-###), User Stories, Edge Cases, Overview, Clarifications.
  2. **plan.md** — Architecture / stack, Data Model, Phases, Constraints.
  3. **tasks.md** — Task IDs, descriptions, phase grouping, [P] parallel
     markers, file refs.
  4. **constitution.md** — MUST/SHOULD principles. Treat MUST violations
     as auto-CRITICAL. (Optional — may be empty.)

## Six detection passes

Run all six. Stop at 50 findings total — if you would emit more,
collapse the overflow into a single summary line:
`(N additional findings of category X suppressed.)`

  **A. Duplication.** Functional requirements that overlap in scope
     and could be merged or that contradict each other. Near-duplicates
     count.

  **B. Ambiguity.** Vague adjectives without measurable criteria
     ('fast', 'scalable', 'secure', 'robust', 'intuitive', 'reliable',
     'performant'); unresolved placeholders (`TODO`, `TKTK`, `???`,
     `<placeholder>`, `[NEEDS CLARIFICATION: …]`).

  **C. Underspecification.** FRs missing an object or measurable
     outcome. Tasks referencing files or modules that aren't in
     plan.md or that the project structure can't accommodate.

  **D. Constitution Alignment.** Any conflict with a MUST principle
     in constitution.md → auto-CRITICAL. SHOULD violations are HIGH.
     If constitution.md is empty, skip this pass and note that.

  **E. Coverage Gaps.** Functional Requirements that map to zero
     tasks. Tasks that reference no FR-### or User Story.

  **F. Inconsistency.** Terminology drift across artifacts (the same
     concept named differently). Stack/tech conflicts (plan says
     PostgreSQL, tasks reference Redis without explanation). Task
     ordering contradictions (a task depends on output of a later one).

## Severity scale

  - **CRITICAL** — constitution violation, missing artifact, zero
                   coverage of baseline functionality
  - **HIGH**     — likely-blocking issue, terminology drift on
                   user-facing concepts, ambiguity in success criteria
  - **MEDIUM**   — readability/maintainability, missing back-references
  - **LOW**      — style, polish

## Stable IDs

Prefix each finding with the category initial and a per-category
counter: A1, A2, … (Ambiguity), D1 (Duplication), U1 (Underspec), C1
(Constitution), G1 (Gaps), I1 (Inconsistency). Same content → same
ID across runs so the user can `diff` re-runs.

## Output format

Render exactly:

  ## Specification Analysis Report

  | ID | Category | Severity | Location | Summary | Recommendation |
  |----|----------|----------|----------|---------|----------------|
  | A1 | Ambiguity | HIGH | spec.md:24 | … | … |
  | …  | …         | …    | …          | … | … |

  ## Coverage Summary

  | Requirement | Has Task? | Task IDs | Notes |
  |-------------|-----------|----------|-------|
  | FR-001      | yes       | T1, T3   | …     |
  | FR-002      | NO        | —        | gap E1 |

  ## Constitution Alignment Issues

  - …  *(or:)*  None.

  ## Unmapped Tasks

  - …  *(or:)*  None.

  ## Metrics

  - Total Requirements: N
  - Total Tasks: M
  - Coverage: X%
  - Ambiguity count: K
  - Duplication count: D
  - **Critical issues**: C

If there are 0 findings, render:
  `## Specification Analysis Report\\n\\n✓ No issues detected.`
followed by the Coverage Summary and Metrics tables (those still go).

## Closing

If CRITICAL issues exist, append a one-line recommendation:
  `Recommend resolving CRITICAL findings before /spec start.`
Don't enforce — nyma's `/spec start` will warn but allow with --force.

---

## spec.md

%s

---

## plan.md

%s

---

## tasks.md

%s

---

## constitution.md

%s

---

Now produce the report.
")

;; ── Pure helpers ──────────────────────────────────────────────

(defn compose-analyze-prompt
  "Pure: produce the system-message text for the analyze pass.
   Substitutes the four artifact contents into the template via
   adapter/interpolate-template — single-pass, so user-authored `%s`
   literals in any artifact can't consume downstream placeholders."
  [{:keys [spec-content plan-content tasks-content constitution-content]}]
  (adapter/interpolate-template
   analyze-prompt-template
   [(or spec-content         "(empty)")
    (or plan-content         "(empty)")
    (or tasks-content        "(empty)")
    (or constitution-content "(no constitution.md present)")]))

(defn parse-analyze-output
  "Parse the model's analyze report into structured findings.
   Returns {:findings [{:id :category :severity :location :summary
   :recommendation}]
            :critical-count <int>
            :raw <full-markdown>}.

   Tolerant of header-row variations and minor formatting drift; we
   only require the fence markers `## Specification Analysis Report`
   and `| ID | Category | …` near the top."
  [report-md]
  (if-not (string? report-md)
    {:findings [] :critical-count 0 :raw ""}
    (let [;; Find the findings table — lines after the second pipe-row
          ;; (which is the |---|---| separator) until the next blank
          ;; line or non-table line.
          lines (str/split-lines report-md)
          ;; Index of header row '| ID | Category | ...'
          hdr (loop [i 0]
                (cond
                  (>= i (count lines)) nil
                  (let [ln (get lines i)]
                    (and (.startsWith ln "|")
                         (.includes ln "ID")
                         (.includes ln "Category")
                         (.includes ln "Severity")))
                  i
                  :else (recur (inc i))))
          findings
          (if-not hdr
            []
            (let [start (+ hdr 2)  ; skip header + separator
                  rows  (loop [i start acc []]
                          (cond
                            (>= i (count lines)) acc
                            (let [ln (get lines i)]
                              (and (.startsWith ln "|")
                                   (not (.includes ln "---"))))
                            (recur (inc i) (conj acc (get lines i)))
                            :else acc))]
              (->> rows
                   (map (fn [row]
                          (let [;; Split by '|', trim each, drop empty
                                ;; first/last from the leading/trailing
                                ;; pipes.
                                parts (->> (str/split row #"\|")
                                           (map str/trim)
                                           vec)
                                cells (cond
                                        (and (>= (count parts) 1)
                                             (str/blank? (first parts)))
                                        (subvec parts 1 (count parts))
                                        :else parts)
                                cells (cond-> cells
                                        (and (seq cells)
                                             (str/blank? (last cells)))
                                        (subvec 0 (dec (count cells))))]
                            (when (>= (count cells) 6)
                              {:id            (nth cells 0)
                               :category      (nth cells 1)
                               :severity      (nth cells 2)
                               :location      (nth cells 3)
                               :summary       (nth cells 4)
                               :recommendation (nth cells 5)}))))
                   (remove nil?)
                   vec)))
          critical-count (count (filter #(= (:severity %) "CRITICAL") findings))]
      {:findings       findings
       :critical-count critical-count
       :raw            report-md})))

(defn compute-content-hash
  "Pure: deterministic SHA-256 over the concatenation of the four
   artifact contents. Used to detect drift since the last analyze run
   for the soft-block on `/spec start`."
  [{:keys [spec-content plan-content tasks-content constitution-content]}]
  (let [combined (str (or spec-content "") "\n--\n"
                      (or plan-content "") "\n--\n"
                      (or tasks-content "") "\n--\n"
                      (or constitution-content ""))
        h        (.createHash crypto "sha256")]
    (.update h combined)
    (.digest h "hex")))

;; ── State persistence ─────────────────────────────────────────

(def ^:private state-file ".nyma/spec-state.json")

(defn read-spec-state
  "Read .nyma/spec-state.json. Returns a JS object (or empty obj on
   missing/parse-error). Caller treats it as a name → run-record map."
  [cwd]
  (let [p (path/join cwd state-file)]
    (if-not (fs/existsSync p)
      #js {}
      (try
        (js/JSON.parse (fs/readFileSync p "utf8"))
        (catch :default _ #js {})))))

(defn write-analyze-result!
  "Persist the most-recent analyze run for spec `name` so `/spec start`
   can soft-block on stale-or-critical results."
  [cwd name {:keys [content-hash critical-count finding-count]}]
  (let [p     (path/join cwd state-file)
        state (read-spec-state cwd)
        entry #js {:run-at         (.toISOString (js/Date.))
                   :content-hash   content-hash
                   :critical-count critical-count
                   :finding-count  finding-count}]
    (aset state name entry)
    (fs/mkdirSync (path/dirname p) #js {:recursive true})
    (fs/writeFileSync p (js/JSON.stringify state nil 2))))

(defn template-content?
  "Heuristic: return true if the spec content is likely still the
   scaffolded template (placeholder bullets, no real FR/SC IDs, no
   resolved acceptance criteria). Used to avoid soft-blocking brand-new
   specs the user just scaffolded and hasn't filled in yet — running
   /spec analyze on a template adds bureaucracy without value."
  [spec-content tasks-content]
  (let [s (or spec-content "")
        t (or tasks-content "")
        ;; Real specs carry FR-### or SC-### IDs (per spec-template.md).
        no-ids?         (and (not (.test (adapter/make-fr-id-re) s))
                             (not (.test (adapter/make-sc-id-re) s)))
        ;; Templated tasks contain literal \"First task\" / \"Second task\"
        ;; from tasks-template; real tasks.md usually has different text.
        only-templates? (and (.includes t "First task")
                             (.includes t "Second task"))]
    (and no-ids? only-templates?)))

(defn should-warn-on-start?
  "Pure: given the persisted spec-state object, the current content hash,
   and (optionally) the raw artifacts, return nil (proceed silently)
   or a {:reason <kw> :detail <str>} for the caller to render.

   Reasons:
     :no-analyze - never analyzed (suppressed when artifacts are still
                   the unedited template — see template-content?)
     :critical   - last analyze had unresolved CRITICAL findings
     :stale      - spec content changed since last analyze"
  ([state name current-content-hash]
   ;; Backwards-compat overload — when artifacts aren't supplied we
   ;; can't run the template-content? heuristic, so we err on the side
   ;; of warning. New callers should use the 5-arg form.
   (should-warn-on-start? state name current-content-hash nil nil))
  ([state name current-content-hash spec-content tasks-content]
   (let [entry    (and state (aget state name))
         critical (when entry (or (aget entry "critical-count") 0))
         hash     (when entry (aget entry "content-hash"))
         run-at   (when entry (aget entry "run-at"))]
     (cond
       (nil? entry)
       (when-not (and spec-content
                      (template-content? spec-content tasks-content))
         {:reason :no-analyze
          :detail "No /spec analyze has been run for this spec."})

       (pos? critical)
       {:reason :critical
        :detail (str critical " unresolved CRITICAL finding(s) from " run-at)}

       (and hash (not= hash current-content-hash))
       {:reason :stale
        :detail "Spec content has changed since the last /spec analyze."}

       :else nil))))
