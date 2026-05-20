(ns agent.extensions.spec-driven.clarify
  "Implements `/spec clarify <feat>` — the structured-questioning phase
   that resolves ambiguities in a spec before plan/tasks/implement.

   Drop-in compatible with github/spec-kit v0.8.x's `/speckit.clarify`:
     - hard cap of 5 questions per session
     - one question at a time, multiple-choice or ≤5-word free-form
     - writes back into `## Clarifications` / `### Session YYYY-MM-DD`
     - propagates answers into the body, replacing ambiguous text
     - `[NEEDS CLARIFICATION: ...]` inline markers prioritized
     - 11-category taxonomy (functional / data / UX / non-functional /
       integration / edge / constraints / terminology / signals /
       misc / placeholders)

   Architecture: this command is a SEED-AND-YIELD operation. The slash
   handler doesn't run a synchronous Q&A loop — it composes a system
   message describing the workflow + the spec content, appends it to
   the conversation, and notifies the user. The next regular turn
   carries out the multi-turn Q&A under the model's normal control.
   Same pattern `/skill <name>` uses for its activation."
  (:require [clojure.string :as str]
            [agent.extensions.spec-driven.speckit-adapter :as adapter]))

;; ── The seed-prompt template ──────────────────────────────────
;;
;; Adapted from `templates/commands/clarify.md` in github/spec-kit
;; v0.8.5 (May 4 2026). Substitutions made for nyma's vocabulary:
;;   - __SPECKIT_COMMAND_PLAN__   →  /spec start
;;   - external prerequisites script → "use the active spec at <path>"
;;
;; When spec-kit ships an updated clarify.md, replace the body of this
;; constant verbatim and re-run the snapshot test against the fixture.

(def clarify-prompt-template
  "Begin a spec-clarify session. You will identify underspecified areas
in this spec and ask the user up to 5 highly targeted questions, ONE
AT A TIME, encoding answers back into the spec.

## Position in the workflow

This clarification workflow is expected to run (and be completed)
BEFORE invoking `/spec start`. Skipping is allowed but downstream
rework risk increases. If the user says \"skip clarification\" or
\"exploratory spike,\" warn once and stop.

## How to ask each question

For each question, choose the appropriate format:

  A. Multiple choice: 2-5 mutually exclusive options. Render as a
     Markdown table with one row marked **(Recommended)**. The user
     can reply with the option name, the row number, `yes`,
     `recommended`, or `suggested` to accept the recommendation.

  B. Short answer: explicit instruction \"≤ 5 words\". Use only when
     no clean multi-choice partitioning exists.

Stop early if the user says `done`, `good`, or `no more`. Hard cap: 5
questions per session.

## After EACH answer

  1. Append the Q+A as a bullet under `## Clarifications` →
     `### Session <today's ISO date>` in the spec file. Format:
       `- Q: <question> → A: <final answer>`
  2. If the answer resolves an inline `[NEEDS CLARIFICATION: …]`
     marker, REPLACE the marker text in-place (don't leave a duplicate
     ambiguous statement; don't add the answer alongside it).
  3. If the answer changes a Functional Requirement (FR-###), Success
     Criterion (SC-###), edge case, or data-model element, edit that
     section to reflect the resolved decision.
  4. Save (use the `edit` or `write` tool). Spec must be on disk after
     each Q+A so a crash mid-session doesn't lose progress.

## Priority taxonomy

Score each question candidate on (Impact × Uncertainty). Categories:

  1. Functional Scope & Behavior
  2. Domain & Data Model
  3. Interaction & UX Flow
  4. Non-Functional Quality (perf, scale, reliability, observability,
     security, compliance)
  5. Integration & External Dependencies
  6. Edge Cases & Failure Handling
  7. Constraints & Tradeoffs
  8. Terminology & Consistency
  9. Completion Signals (testable acceptance criteria)
 10. Misc / Placeholders (TODOs, vague adjectives like \"robust\",
     \"intuitive\", \"scalable\", \"performant\")
 11. Inline `[NEEDS CLARIFICATION: …]` markers (always priority 1)

## Output format for each question

Render exactly:

  ### Question N of up to 5 — <category>

  <One-sentence question.>

  <If multiple choice: Markdown table with columns Option | Description.
   Mark one row **(Recommended)**.>
  <If free-form: state \"≤ 5 words\".>

After the user answers, you DON'T print the next question until you've
written the spec edit. Confirm with a single line:

  ✓ Recorded. Updating spec…

then issue the edits, then ask the next question.

## Ending the session

Stop on early termination, on the 5th question, or when no high-impact
ambiguities remain. End with a single-line summary:

  ✓ Clarify session complete: <N> questions answered, <M> markers
  resolved.

## Spec under clarification

%s

The spec content follows. Active spec name: %s. File path: %s.
Today's ISO date: %s.

---

%s
")

;; ── Public API ────────────────────────────────────────────────

(defn today-iso
  "Return today's date in ISO YYYY-MM-DD form (UTC)."
  []
  (let [d (js/Date.)]
    (.slice (.toISOString d) 0 10)))

(defn ranked-marker-summary
  "Build a one-line-per-marker summary for the seed prompt so the
   model sees the priority-1 items up front."
  [markers]
  (if (empty? markers)
    "(no [NEEDS CLARIFICATION] markers — start from the taxonomy.)"
    (->> markers
         (map (fn [{:keys [line-idx text]}]
                (str "  • line " line-idx ": " text)))
         (str/join "\n")
         (str "Priority-1 inline markers in this spec:\n"))))

(defn compose-clarify-seed
  "Pure: produce the system-message text that starts a clarify session.
   Caller appends this as `{:role \"system\" :content <text>}` to the
   conversation, then notifies the user. The next turn runs naturally
   under the model's control per the prompt."
  [{:keys [spec-name spec-content spec-path]}]
  (let [date    (today-iso)
        markers (adapter/extract-needs-clarifications spec-content)
        marker-summary (ranked-marker-summary markers)]
    ;; Single-pass substitution via the adapter's interpolate-template.
    ;; The chained `.replace` form previously here had a substitution
    ;; leakage bug: if a user value (e.g. `spec-content`) contained the
    ;; literal text `%s` (printf example, Python format string), the
    ;; next `.replace` would target the user's `%s` instead of the next
    ;; intended placeholder. interpolate-template is single-pass and
    ;; cannot suffer from that.
    (adapter/interpolate-template
     clarify-prompt-template
     [marker-summary spec-name spec-path date spec-content])))
