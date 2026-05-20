(ns agent.extensions.spec-driven.import
  "Implements `/spec import <name> <path>` — LLM-driven decomposition of
   a monolithic design doc into the spec-driven feature folder shape
   (spec.md / plan.md / tasks.md, plus research.md for leftovers).

   Architecture mirrors `/spec clarify`: this is a SEED-AND-YIELD
   operation. The slash handler scaffolds the three template files,
   composes a seed prompt that points the model at the source path,
   and enqueues it on `agent.loop/follow-up`. The next agent turn runs
   the decomposition under the model's normal control with full Read /
   Edit / Write tool access.

   The source content is NOT inlined into the prompt — only a TOC. For
   the 21K–76K design docs in real-world use, inlining would burn the
   context budget for no benefit; the model can Read the file when it's
   ready to write each section."
  (:require [clojure.string :as str]
            [agent.extensions.spec-driven.speckit-adapter :as adapter]))

(def import-prompt-template
  "You're decomposing a monolithic design doc into a spec-driven feature
folder.

Source:    %s
Target:    %s
Date:      %s
Spec name: %s

Source TOC (heading + line):
%s

## Steps

1. Read the source via the Read tool.
2. Decompose into three files (scaffolds already exist with title
   headers preserved):
     - **spec.md**  ← Goals, Functional Requirements (FR-### IDs),
                      Non-Goals, Acceptance criteria, Success metrics
                      (SC-### IDs), Constraints
     - **plan.md**  ← Architecture, Components, Data model,
                      Implementation approach, Files to create/modify
     - **tasks.md** ← Actionable breakdown derived from plan, as
                      `- [ ]` checklist, ordered top-to-bottom
3. Use the Edit tool on each scaffold. Preserve the existing title
   header line of each file.
4. Anything that doesn't fit (research notes, alternatives considered,
   addenda, ADRs, glossary, references) → write to `<target>/research.md`
   using the Write tool.

## Decomposition rules

- **Faithful transcription**. Don't paraphrase requirements. Don't
  drop content. If the source has 17 functional requirements, the
  resulting spec.md should have 17 FR-### entries.
- Where the source is ambiguous or unstated, insert a
  `[NEEDS CLARIFICATION: <one-line question>]` marker — do **not**
  invent answers. These are the priority-1 input to the next phase.
- Number requirements (`FR-001`, `FR-002`, …) and success criteria
  (`SC-001`, `SC-002`, …) for cross-reference.
- Tasks should be concrete enough that `/spec next` can pick them up
  individually. Avoid mega-tasks like \"Implement the system.\"

## When done

Print a one-line summary:

  ✓ Imported %s. Files populated: spec.md, plan.md, tasks.md (+ research.md if used).
    [N] [NEEDS CLARIFICATION] markers inserted. Run `/spec clarify` next.

Then stop. Do not start implementation work — the user runs `/spec
clarify` and `/spec analyze` before `/spec start`.
")

(defn today-iso
  "Return today's date in ISO YYYY-MM-DD form (UTC)."
  []
  (.slice (.toISOString (js/Date.)) 0 10))

(def ^:private heading-re
  ;; H1, H2, or H3. Anchored to line start.
  (js/RegExp. "^(#{1,3})\\s+(.+)$"))

(defn extract-toc
  "Walk the source markdown, return a TOC string with heading + 1-based
   line number. Caps at `max-entries` (default 50) and appends `…` if
   truncated. Returns `(no headings)` if the doc has none."
  ([source] (extract-toc source 50))
  ([source max-entries]
   (let [lines    (.split (str source) "\n")
         entries  (atom [])
         truncated? (atom false)]
     (dotimes [i (.-length lines)]
       (when (< (count @entries) max-entries)
         (let [line (aget lines i)
               m    (.exec heading-re line)]
           (when m
             (let [hashes (aget m 1)
                   text   (aget m 2)
                   level  (str "H" (count hashes))]
               (swap! entries conj
                      (str level "  " text "  (line " (inc i) ")")))))))
     (when (and (= (count @entries) max-entries)
                (some (fn [i] (.exec heading-re (aget lines i)))
                      (range max-entries (.-length lines))))
       (reset! truncated? true))
     (cond
       (empty? @entries) "(no headings detected in source)"
       @truncated?       (str (str/join "\n" @entries) "\n…")
       :else             (str/join "\n" @entries)))))

(defn compose-import-seed
  "Pure: produce the user-message text that seeds an import session.
   Caller enqueues this via `agent.loop/follow-up`; the next turn runs
   the decomposition under the model's normal tool loop."
  [{:keys [spec-name source-path target-dir source-content]}]
  (let [date (today-iso)
        toc  (extract-toc source-content)]
    (adapter/interpolate-template
     import-prompt-template
     [source-path target-dir date spec-name toc spec-name])))
