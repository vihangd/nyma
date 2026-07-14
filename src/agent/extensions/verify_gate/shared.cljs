(ns agent.extensions.verify-gate.shared
  "Pure helpers for the verify-before-done gate.

   Loop-engineering SOTA (2026): the loop needs something deterministic that
   can say no — a test suite, a typecheck — before the agent declares done.
   The gate runs a configured quality command after any turn that edited
   files; a failure is fed back as a follow-up so the agent fixes it before
   stopping.")

(def edit-tools #{"write" "edit" "multi_edit"})

(def default-config
  {:cmd nil :max-attempts 2 :timeout-ms 120000})

(defn config
  "Read the `verify` settings section. Gate is off unless `cmd` is set:
   {\"verify\": {\"cmd\": \"bun test\", \"max-attempts\": 2, \"timeout-ms\": 120000}}"
  [settings]
  (let [v (when settings (aget settings "verify"))]
    (merge default-config
           (when v
             (cond-> {}
               (aget v "cmd")          (assoc :cmd (aget v "cmd"))
               (aget v "max-attempts") (assoc :max-attempts (aget v "max-attempts"))
               (aget v "timeout-ms")   (assoc :timeout-ms (aget v "timeout-ms")))))))

(defn edit-tool? [tool-name]
  (contains? edit-tools (str tool-name)))

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
       "Fix the failures before finishing. Do not weaken or delete tests to make them pass."))
