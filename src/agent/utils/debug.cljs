(ns agent.utils.debug
  "Environment-gated debug logging with a configurable sink.

   Borrowed from cc-kit:
     packages/shared/src/debug.ts   — env-gate pattern
     packages/shared/src/logger.ts  — configureLogger / pluggable sink

   Two concerns:

   1. Nyma's runtime is a TUI. Calling `console.log` from inside a
      render tick corrupts Ink's cursor tracking (see the comment in
      agent_shell/acp/pool.cljs:59). Debug output must go to stderr,
      not stdout, and must be easy to silence in production.

   2. Tests need to capture log output without touching globals.
      `configure-logger!` lets a test inject a collecting sink and
      then reset it.

   Defaults:
     * Off unless the environment says otherwise. A log is emitted
       when any of these is true:
         - process.env.NYMA_DEBUG is set (any non-empty value)
         - process.env.DEBUG matches 'nyma' or '*' (debug-npm convention)
         - enabled? has been toggled true via `set-enabled!`
     * Sink writes to process.stderr so Ink's stdout rendering is
       left alone.
     * Each line is prefixed with an ISO timestamp and a tag.

   Usage:
     (d/debug \"app\" \"loading extensions\" {:count 12})
     (d/configure-logger! custom-fn)  ; for tests
     (d/set-enabled! true)            ; for runtime enable"
  (:require [clojure.string :as str]))

;;; ─── State ─────────────────────────────────────────────

(def ^:private forced-enabled?
  "Explicit override. Callers can flip this via `set-enabled!` to
   enable logging without setting an env var (e.g. from a --debug
   CLI flag resolved at startup)."
  (atom nil))

(def ^:private sink
  "Current log sink — `(fn [line-string])`. Default is stderr.
   Tests inject their own via `configure-logger!`."
  (atom (fn [line] (.write (.-stderr js/process) (str line "\n")))))

;;; ─── Env detection ─────────────────────────────────────

(defn- env [k]
  (when-let [e js/process.env]
    (aget e k)))

(defn- debug-env-enables? [tag]
  (let [nyma-flag (env "NYMA_DEBUG")
        debug-val (env "DEBUG")]
    (or (and (string? nyma-flag) (seq nyma-flag))
        (and (string? debug-val)
             (or (= debug-val "*")
                 (str/includes? debug-val "nyma")
                 (str/includes? debug-val (or tag "")))))))

(defn enabled?
  "Is debug logging currently on? Checks explicit override first, then
   environment. Pure query — no side effects."
  ([] (enabled? nil))
  ([tag]
   (if-let [override @forced-enabled?]
     override
     (debug-env-enables? tag))))

;;; ─── Public API ────────────────────────────────────────

(defn set-enabled!
  "Force debug logging on (true) or off (false). Passing nil returns
   to 'consult the environment' mode."
  [on?]
  (reset! forced-enabled? on?))

(defn configure-logger!
  "Swap the log sink. `sink-fn` receives a single already-formatted
   string per call. Use this in tests to capture output:

     (let [captured (atom [])]
       (d/configure-logger! (fn [line] (swap! captured conj line)))
       (do-work)
       @captured)"
  [sink-fn]
  (reset! sink sink-fn))

(defn reset-logger!
  "Restore the default stderr sink. Pair with `configure-logger!`
   in test cleanup."
  []
  (reset! sink (fn [line] (.write (.-stderr js/process) (str line "\n")))))

(defn- timestamp []
  (.toISOString (js/Date.)))

(defn- format-line [tag level msg extras]
  (let [ts   (timestamp)
        tag  (or tag "nyma")
        parts (cond-> [(str "[" ts "]") (str "[" level "]") (str "[" tag "]") msg]
                (seq extras) (conj (js/JSON.stringify (clj->js extras))))]
    (str/join " " parts)))

(defn log
  "General log. Level is a free-form string — 'debug', 'info', 'warn',
   'error' are the conventional values. Honors the enabled? gate for
   all levels EXCEPT 'warn' and 'error', which are always emitted so
   user-facing warnings never get swallowed by a DEBUG toggle."
  ([level msg] (log level nil msg nil))
  ([level tag msg] (log level tag msg nil))
  ([level tag msg extras]
   (when (or (contains? #{"warn" "error"} level)
             (enabled? tag))
     (@sink (format-line tag level msg extras)))))

(defn debug
  "Shortcut for (log \"debug\" tag msg extras). Gated on enabled?."
  ([tag msg] (debug tag msg nil))
  ([tag msg extras] (log "debug" tag msg extras)))

(defn info
  "Shortcut for (log \"info\" tag msg extras). Gated on enabled?."
  ([tag msg] (info tag msg nil))
  ([tag msg extras] (log "info" tag msg extras)))

(defn warn
  "Shortcut for (log \"warn\" tag msg extras). Always emitted."
  ([tag msg] (warn tag msg nil))
  ([tag msg extras] (log "warn" tag msg extras)))

(defn error
  "Shortcut for (log \"error\" tag msg extras). Always emitted."
  ([tag msg] (error tag msg nil))
  ([tag msg extras] (log "error" tag msg extras)))
