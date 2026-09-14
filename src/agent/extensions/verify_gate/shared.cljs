(ns agent.extensions.verify-gate.shared
  "Pure helpers for the verify-before-done gate.

   Loop-engineering SOTA (2026): the loop needs something deterministic that
   can say no — a test suite, a typecheck — before the agent declares done.
   The gate runs a configured quality command after any turn that edited
   files; a failure is fed back as a follow-up so the agent fixes it before
   stopping."
  (:require [agent.tool-metadata :as tool-metadata]))

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

;; ── "you tried to configure this and it did not take" ────────────────────
;; The gate cannot warn merely because `verify.cmd` is unset: extension.json
;; declares the whole section, the loader registers it as defaults, so
;; `(.settings api "verify")` is non-empty for EVERY user and an
;; existence check would nag everyone who never wanted the gate.
;;
;; An unrecognised key in the section is the signal that someone did try —
;; `"comand"`, `"command"`, `"maxAttempts"` — and got silence for it.

(def known-keys ["cmd" "max-attempts" "timeout-ms"])

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
       "Fix the failures before finishing. Do not weaken or delete tests to make them pass."))
