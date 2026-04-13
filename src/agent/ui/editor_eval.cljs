(ns agent.ui.editor-eval
  "Editor eval mode — evaluates `$expr` and `$$expr` typed directly
   into the prompt editor via a one-shot `bb -e <expr>` subprocess.

   Prefix semantics, mirroring oh-my-pi's bash mode:
     $expr   → eval, show, include in LLM context for future turns
     $$expr  → eval, show, HIDE from LLM context (:local-only message)

   Unlike editor-bash, this path does NOT route through the
   bash_suite middleware chain. Rationale:

     - Babashka is a language runtime, not a shell. env_filter,
       cwd_manager, and shell-grammar classification don't apply.
     - A bb form can still shell out via clojure.java.shell/sh —
       that path will eventually hit bash_suite when we wire the
       user_bash event. Today it does not.

   SECURITY GAP (documented so a future phase can catch it):
     $(clojure.java.io/delete-file \"/\") runs ungated. If you add
     an eval-mode security extension, emit before_tool_call with
     name \"bb\" in run-eval! and honor {:block true ...} the
     same way editor-bash does. A ~10 line change.

   Separated from app.cljs so the pure pieces (parse, format, and
   the bb-availability probe) are unit-testable without mounting
   the ink app."
  (:require [agent.ui.editor-exec-util :as exec]
            [clojure.string :as str]))

;;; ─── Pure: parsing the editor text ──────────────────────

(def ^:private form-start-chars
  "Characters that can legitimately open a Clojure form. Whitespace
   is allowed so `$ (+ 1 2)` still fires — the eval branch trims it
   out before calling bb. Stored as a string for O(1) membership
   via .includes."
  "([{#`'~@^ \t\n")

(defn- form-start-char?
  "True if `ch` (a single-character string) can open a Clojure form.
   Used by parse-eval-input to distinguish `$(+ 1 2)` (eval) from
   bare `$PATH` (which looks like shell-variable leakage and should
   be left alone)."
  [ch]
  (and (string? ch)
       (= 1 (count ch))
       (>= (.indexOf form-start-chars ch) 0)))

(defn- valid-eval-body?
  "True when `body` (text after the sigil) should be handled as a
   Clojure expression. Empty string → false. First character must
   be a form-start char. After whitespace trim the result must be
   non-empty — this rejects `$ ` and `$  ` as :not-eval."
  [body]
  (and (seq body)
       (form-start-char? (.charAt body 0))
       (seq (str/trim body))))

(defn parse-eval-input
  "Classify `text` into one of:
     {:kind :eval         :expr \"(+ 1 2)\"}
     {:kind :eval-hidden  :expr \"(+ 1 2)\"}
     {:kind :not-eval}

   Leading whitespace is stripped first (same contract as
   detect-mode). `$$` is checked before `$` — order matters.

   A bare `$` or `$$` with no expression is :not-eval. A `$` followed
   by a non-form-start character (e.g. `$PATH`, `$VAR`) is also
   :not-eval so shell-variable lookalikes don't accidentally fire
   eval mode."
  [text]
  (let [s (when text (.trimStart (str text)))]
    (cond
      (or (nil? s) (= s "")) {:kind :not-eval}

      (.startsWith s "$$")
      (let [body (.slice s 2)]
        (if (valid-eval-body? body)
          {:kind :eval-hidden :expr (str/trim body)}
          {:kind :not-eval}))

      (.startsWith s "$")
      (let [body (.slice s 1)]
        (if (valid-eval-body? body)
          {:kind :eval :expr (str/trim body)}
          {:kind :not-eval}))

      :else {:kind :not-eval})))

;;; ─── Impure: detect whether `bb` is on PATH ─────────────

(def ^:private bb-probe-state
  "Process-wide memo for the bb --version probe. nil = not probed
   yet; :present / :missing once resolved. Plain atom (not a
   promise) so the happy path is a synchronous lookup after the
   first call."
  (atom nil))

(defn reset-bb-probe!
  "Clear the cached bb-availability result. Test helper — lets a
   suite verify the memoization path without leaking cache state
   across tests."
  []
  (reset! bb-probe-state nil))

(defn ^:async bb-available?
  "Return true iff `bb --version` runs successfully. Memoized for
   the lifetime of the process — the spawn cost (~50ms) is paid
   once on first use, and every call after that is a map lookup.

   Swallows every error class — a missing binary, a permission
   error, and a non-zero exit all return false."
  []
  (case @bb-probe-state
    :present true
    :missing false
    (let [result (try
                   (let [proc (js/Bun.spawn #js ["bb" "--version"]
                                            #js {:stdout "pipe"
                                                 :stderr "pipe"})
                         code (js-await (.-exited proc))]
                     (zero? code))
                   (catch :default _ false))]
      (reset! bb-probe-state (if result :present :missing))
      result)))

;;; ─── Impure: spawn bb and capture output ────────────────

(def ^:private install-hint
  "Multi-line install instructions shown when bb is not on PATH.
   Platform-agnostic: we list the two most common install paths and
   let the user pick — no platform sniffing."
  (str "Babashka (`bb`) is not installed or not on PATH.\n"
       "Install options:\n"
       "  brew install borkdude/brew/babashka\n"
       "  bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)"))

(defn ^:async run-eval!
  "Evaluate a Clojure expression via `bb -e <expr>` and return a
   result map:
     {:unavailable? false :expr expr
      :stdout str :stderr str :exit-code int}
   — or —
     {:unavailable? true :expr expr
      :install-hint \"...\" :stdout \"\" :stderr \"\" :exit-code -1}
   when bb is missing. Never throws — any internal failure surfaces
   in :stderr so the caller can render it like any other eval error."
  [expr]
  (if-not (js-await (bb-available?))
    {:unavailable? true
     :expr         expr
     :install-hint install-hint
     :stdout       ""
     :stderr       ""
     :exit-code    -1}
    (try
      (let [proc   (js/Bun.spawn #js ["bb" "-e" expr]
                                 #js {:timeout 30000
                                      :stdout  "pipe"
                                      :stderr  "pipe"})
            stdout (js-await (.text (js/Response. (.-stdout proc))))
            stderr (js-await (.text (js/Response. (.-stderr proc))))
            code   (js-await (.-exited proc))]
        {:unavailable? false
         :expr         expr
         :stdout       (or stdout "")
         :stderr       (or stderr "")
         :exit-code    (or code 0)})
      (catch :default e
        {:unavailable? false
         :expr         expr
         :stdout       ""
         :stderr       (str "bb spawn failed: " (.-message e))
         :exit-code    -1}))))

;;; ─── Pure: formatting the rendered message ──────────────

(defn format-eval-output
  "Build a human-readable text block for a run-eval! result. The
   styled renderer in chat_view layers a λ header, a primary-color
   accent, and an exit footer over this; plain text here is the
   fallback for :role assistant rendering and for the LLM context
   on $expr (non-hidden) turns."
  [{:keys [expr unavailable? install-hint stdout stderr exit-code]}]
  (cond
    unavailable?
    (str "λ " expr "\n" (or install-hint "bb not available"))

    :else
    (let [out (exec/truncate-output stdout)
          err (exec/truncate-output stderr)
          body (cond
                 (and (seq out) (seq err))
                 (str out "\nstderr:\n" err)

                 (seq out) out
                 (seq err) (str "stderr:\n" err)
                 :else     "")
          footer (when (and exit-code (not (zero? exit-code)))
                   (str "\nexit " exit-code))]
      (str "λ " expr
           (when (seq body) (str "\n" body))
           footer))))
