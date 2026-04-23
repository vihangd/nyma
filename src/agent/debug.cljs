(ns agent.debug
  "Unified debug logger for nyma. Replaces the previous pair of
   `agent.debug/dbg` (file-only, NYMA_DEBUG_USERMSG-gated) and
   `agent.utils.debug/{debug,info,warn,error}` (stderr-only,
   NYMA_DEBUG/DEBUG-gated) with a single namespace that honours every
   legacy env var and defaults to the safer file sink.

   Sink (default):
     Writes to ~/.nyma/debug.log so Ink's TUI cursor tracking is never
     disturbed by stderr output mid-render. Opt into stderr for piping
     to `jq`/`tail -f` by setting NYMA_DEBUG_STDERR=1.

   Gate (any of these is enough):
     - NYMA_DEBUG_USERMSG=<anything>   (legacy: previously file-only)
     - NYMA_DEBUG=<anything>            (legacy: previously stderr-only)
     - DEBUG=*                          (debug-npm convention: everything)
     - DEBUG=nyma                       (debug-npm convention: nyma-scoped)
     - DEBUG=<tag>                      (debug-npm convention: per-tag)
     - (set-enabled! true)              (runtime override, e.g. --debug)

   warn and error are ALWAYS emitted regardless of gate — real warnings
   must never be swallowed by a debug toggle.

   API:
     (debug msg)                 => tagged \"nyma\" at debug level
     (debug tag msg)             => explicit tag
     (debug tag msg extras)      => extras is a CLJS map, JSON-appended
     (info / warn / error ...)    => same overloads, different levels

   Tests: configure-logger! / reset-logger! swap the sink without
   touching globals. set-enabled! overrides env (both directions)."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

;;; ─── Paths ─────────────────────────────────────────────

(defn- log-path []
  (path/join (os/homedir) ".nyma" "debug.log"))

(defn- ensure-dir []
  (let [dir (path/join (os/homedir) ".nyma")]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))))

;;; ─── Sinks ─────────────────────────────────────────────

(defn- default-file-sink
  "Append one line to ~/.nyma/debug.log. Idempotent mkdir on each call
   so the log survives a freshly-cleaned home directory."
  [line]
  (ensure-dir)
  (fs/appendFileSync (log-path) (str line "\n") "utf8"))

(defn- default-stderr-sink
  "Write to process.stderr. Used when NYMA_DEBUG_STDERR=1 is set."
  [line]
  (.write (.-stderr js/process) (str line "\n")))

(defn- pick-default-sink
  "Choose the initial sink based on env. Caller-injected sinks via
   `configure-logger!` override this regardless of env."
  []
  (if-let [e js/process.env]
    (if (aget e "NYMA_DEBUG_STDERR")
      default-stderr-sink
      default-file-sink)
    default-file-sink))

;;; ─── State ─────────────────────────────────────────────

(def ^:private forced-enabled?
  "Explicit runtime override. Callers flip this via `set-enabled!`
   to enable logging without an env var (e.g. from a --debug CLI flag
   resolved at startup). nil means 'consult the environment'."
  (atom nil))

(def ^:private sink
  "Current log sink — (fn [line-string]). Default picked from env at
   module load; swap via configure-logger! for tests."
  (atom (pick-default-sink)))

;;; ─── Env gate ──────────────────────────────────────────

(defn- env [k]
  (when-let [e js/process.env]
    (aget e k)))

(defn- debug-env-enables? [tag]
  (let [usermsg   (env "NYMA_DEBUG_USERMSG")
        nyma-flag (env "NYMA_DEBUG")
        debug-val (env "DEBUG")]
    (or (and (string? usermsg) (seq usermsg))
        (and (string? nyma-flag) (seq nyma-flag))
        (and (string? debug-val)
             (or (= debug-val "*")
                 (str/includes? debug-val "nyma")
                 (and tag (str/includes? debug-val tag)))))))

(defn enabled?
  "Is debug logging currently on for this tag? Returns a strict
   boolean. Checks the explicit runtime override first (nil means
   'consult env'; true / false are respected verbatim), then any of the
   three env vars. Pure query — no side effects.

   Note: we can't use `if-let` here because `false` is a valid override
   value (e.g. --quiet CLI flag with env NYMA_DEBUG=1 should return
   false) and `if-let [x false]` takes the else branch."
  ([] (enabled? nil))
  ([tag]
   (let [override @forced-enabled?]
     (if (nil? override)
       (boolean (debug-env-enables? tag))
       (boolean override)))))

;;; ─── Public API ────────────────────────────────────────

(defn set-enabled!
  "Force debug logging on (true) or off (false). Passing nil returns
   to 'consult the environment' mode. Useful for a --debug CLI flag
   resolved at startup, and for tests that need deterministic gates."
  [on?]
  (reset! forced-enabled? on?))

(defn configure-logger!
  "Swap the log sink. sink-fn receives one already-formatted line per
   call. Used by tests to capture output:

     (let [captured (atom [])]
       (configure-logger! (fn [line] (swap! captured conj line)))
       (do-work)
       @captured)"
  [sink-fn]
  (reset! sink sink-fn))

(defn reset-logger!
  "Restore the default sink (re-picked from env). Pair with
   configure-logger! in test cleanup."
  []
  (reset! sink (pick-default-sink)))

(defn- timestamp []
  (.toISOString (js/Date.)))

(defn- format-line [tag level msg extras]
  (let [ts    (timestamp)
        tag   (or tag "nyma")
        parts (cond-> [(str "[" ts "]") (str "[" level "]") (str "[" tag "]") msg]
                (seq extras) (conj (js/JSON.stringify (clj->js extras))))]
    (str/join " " parts)))

(defn log
  "General log. level is a free-form string — 'debug', 'info', 'warn',
   'error' are the conventional values. Honours the enabled? gate for
   all levels EXCEPT 'warn' and 'error', which are always emitted so
   user-facing warnings never get swallowed by a gate toggle."
  ([level msg] (log level nil msg nil))
  ([level tag msg] (log level tag msg nil))
  ([level tag msg extras]
   (when (or (contains? #{"warn" "error"} level)
             (enabled? tag))
     (@sink (format-line tag level msg extras)))))

(defn debug
  "Shortcut for (log \"debug\" tag msg extras). Gated on enabled?."
  ([msg] (log "debug" nil msg nil))
  ([tag msg] (log "debug" tag msg nil))
  ([tag msg extras] (log "debug" tag msg extras)))

(defn info
  "Shortcut for (log \"info\" tag msg extras). Gated on enabled?."
  ([msg] (log "info" nil msg nil))
  ([tag msg] (log "info" tag msg nil))
  ([tag msg extras] (log "info" tag msg extras)))

(defn warn
  "Shortcut for (log \"warn\" tag msg extras). Always emitted."
  ([msg] (log "warn" nil msg nil))
  ([tag msg] (log "warn" tag msg nil))
  ([tag msg extras] (log "warn" tag msg extras)))

(defn error
  "Shortcut for (log \"error\" tag msg extras). Always emitted."
  ([msg] (log "error" nil msg nil))
  ([tag msg] (log "error" tag msg nil))
  ([tag msg extras] (log "error" tag msg extras)))

