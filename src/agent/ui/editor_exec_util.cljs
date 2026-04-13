(ns agent.ui.editor-exec-util
  "Shared primitives between editor-bash and editor-eval. Both modules
   spawn subprocesses, capture stdout/stderr/exit, truncate large
   output, and render the result in the chat view, so the soft-cap
   constant and the JSON-parse helper live here to prevent drift.

   Nothing in this namespace is specific to bash or babashka — if a
   third exec-style mode ever lands (docker run, SSH one-shot, etc.)
   it can require these helpers verbatim.")

(def max-output-bytes
  "Soft cap on stdout/stderr embedded in a single rendered message.
   Anything beyond is truncated with a byte-count note. Matches the
   cc-kit 'head of output, tail elided' pattern."
  8192)

(defn truncate-output
  "Right-truncate `s` to at most `max-output-bytes` and append a
   one-line note describing how many bytes were dropped. Returns the
   empty string for nil input so callers don't need a guard."
  [s]
  (let [s (or s "")]
    (if (> (count s) max-output-bytes)
      (str (.slice s 0 max-output-bytes)
           "\n… (" (- (count s) max-output-bytes) " bytes truncated)")
      s)))

(defn parse-exec-json
  "Turn the `{stdout, stderr, exitCode}` JSON-string shape produced by
   the `bash-execute`-style wrappers into a CLJ map. Never throws —
   malformed JSON degrades to a sentinel error map so callers can
   render it like any other failure."
  [json-str]
  (try
    (let [obj (js/JSON.parse json-str)]
      {:stdout    (or (.-stdout obj) "")
       :stderr    (or (.-stderr obj) "")
       :exit-code (or (.-exitCode obj) 0)})
    (catch :default e
      {:stdout    ""
       :stderr    (str "exec returned unparseable JSON: " (.-message e))
       :exit-code -1})))
