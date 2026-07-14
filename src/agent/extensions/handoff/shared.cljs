(ns agent.extensions.handoff.shared
  "Pure helpers for /handoff — session-to-session brief passing.

   Amp's alternative to /compact: instead of summarizing in place, write a
   purpose-built brief (goal, state, decisions, next steps, key files) and
   seed the NEXT session with it. Preserves intent better than mechanical
   compaction."
  (:require ["node:path" :as path]
            [clojure.string :as str]))

(def brief-system-prompt
  (str "You are writing a handoff brief for the next engineering session on this task. "
       "From the transcript, produce a concise markdown brief with exactly these sections:\n"
       "## Goal — what the user is trying to achieve\n"
       "## State — what has been done and verified so far\n"
       "## Decisions — choices made and why (include rejected alternatives)\n"
       "## Next steps — concrete ordered actions\n"
       "## Key files — paths that matter, with one-line notes\n"
       "Be specific (file paths, command names, error messages). No preamble."))

(defn handoff-path []
  (path/join (js/process.cwd) ".nyma" "handoff.md"))

(defn format-transcript
  "Flatten agent messages to role-tagged text, tool noise truncated."
  [msgs]
  (->> msgs
       (keep (fn [m]
               (let [role    (str (:role m))
                     content (str (:content m))]
                 (when (seq content)
                   (str role ": "
                        (if (contains? #{"tool_call" "tool_result"} role)
                          (.slice content 0 500)
                          (.slice content 0 4000)))))))
       (str/join "\n")))

(defn injection-block [brief]
  (str "# Handoff brief (from the previous session)\n\n" brief
       "\n\n(The above was written by the previous session. Continue from Next steps.)"))
