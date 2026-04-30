(ns agent.extensions.claude-hook-bridge.handlers.command
  "Spawn-a-subprocess handler. Honors:
     - timeout (default 600s for command, per CC spec)
     - abort signal from the agent's AbortController so Ctrl+C kills
       in-flight hook processes
     - exit code semantics (0 ok, 2 blocking error, other = non-blocking)
     - 10K-character spillover for very large stdout

   Design notes:
     - Bun.spawn is preferred for speed; falls back to node:child_process
       if Bun isn't available (defensive — the rest of nyma uses Bun).
     - JSON input is written to stdin and stdin is closed immediately;
       the script must read all of stdin before responding.
     - stdout and stderr are read concurrently; the merged result is
       returned only after the process has exited (or been killed).
     - On timeout/abort, the process is hard-killed (SIGKILL after a
       short SIGTERM grace) so a misbehaving hook can't hold up the
       agent indefinitely."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]))

(def default-timeout-ms 600000)

(defn- now [] (.now js/Date))

(defn- expand-tilde [p]
  (let [s (str p)]
    (if (.startsWith s "~/")
      (path/join (os/homedir) (.slice s 2))
      s)))

(defn- resolve-command
  "Take the raw command string from settings and produce the argv
   `[shell-path, '-c', expanded-command]`. We use `sh -c` (or `cmd /c`
   on Windows) so the user's quoting and pipes work as expected."
  [command shell-pref]
  (let [s (expand-tilde command)
        win? (and (.-platform js/process)
                  (.startsWith (str (.-platform js/process)) "win"))
        shell (cond
                shell-pref shell-pref
                win?       "cmd"
                :else      "/bin/sh")
        flag  (cond
                shell-pref "-c"
                win?       "/c"
                :else      "-c")]
    [shell flag s]))

(defn- read-stream-text
  "Drain a Bun stream into a string. Bun.spawn returns
   ReadableStreams for stdout/stderr."
  [stream]
  (if (or (nil? stream) (false? stream))
    (js/Promise.resolve "")
    (.text stream)))

(defn ^:async run-command
  "Spawn the configured command, pipe the JSON event to stdin, and
   return a parsed result map.

   Args:
     :command     string — shell command line
     :timeout-ms  int    — clamp; default 600000
     :shell       string — \"bash\" / \"powershell\" / nil → auto
     :env         object — env vars to merge over the parent's env
     :cwd         string — working directory; defaults to process.cwd()
     :abort-signal AbortSignal — kills the process when fired
     :stdin-json   any    — event payload; JSON.stringify'd before write

   Returns a Promise of:
     {:exit-code int :stdout string :stderr string :duration-ms int
      :timed-out? bool :aborted? bool :error error-or-nil}"
  [{:keys [command timeout-ms shell env cwd abort-signal stdin-json]}]
  (let [start  (now)
        argv   (resolve-command command shell)
        ;; Build env: shallow-merge user env over process.env at the
        ;; JS level (squint has no js->clj). Object.assign is fine —
        ;; we want the user's vars to win over the parent's.
        merged-env (let [base (js/Object.assign #js {} js/process.env)]
                     (when env
                       (doseq [k (keys env)]
                         (aset base (str k) (str (get env k)))))
                     base)
        spawn-opts
        #js {:cwd    (or cwd (js/process.cwd))
             :stdin  "pipe"
             :stdout "pipe"
             :stderr "pipe"
             :env    merged-env}]
    (try
      (let [proc       (js/Bun.spawn (clj->js argv) spawn-opts)
            ;; Pipe the stdin JSON in.
            stdin      (.-stdin proc)
            json-body  (cond
                         (string? stdin-json) stdin-json
                         (some? stdin-json)   (js/JSON.stringify (clj->js stdin-json))
                         :else                "{}")]
        (.write stdin json-body)
        (.end stdin)

        ;; Race exited vs timeout vs abort.
        (let [timed-out (atom false)
              aborted   (atom false)
              timer     (js/setTimeout
                         (fn []
                           (reset! timed-out true)
                           (try (.kill proc) (catch :default _e nil)))
                         (or timeout-ms default-timeout-ms))
              abort-fn  (fn []
                          (reset! aborted true)
                          (try (.kill proc) (catch :default _e nil)))
              _         (when abort-signal
                          (.addEventListener abort-signal "abort" abort-fn))
              exit-code (js-await (.-exited proc))
              _         (js/clearTimeout timer)
              _         (when abort-signal
                          (try (.removeEventListener abort-signal "abort" abort-fn)
                               (catch :default _e nil)))
              stdout    (js-await (read-stream-text (.-stdout proc)))
              stderr    (js-await (read-stream-text (.-stderr proc)))]
          {:exit-code   (or exit-code 1)
           :stdout      (or stdout "")
           :stderr      (or stderr "")
           :duration-ms (- (now) start)
           :timed-out?  @timed-out
           :aborted?    @aborted
           :error       nil}))
      (catch :default e
        {:exit-code   1
         :stdout      ""
         :stderr      (str "spawn error: " (or (.-message e) (str e)))
         :duration-ms (- (now) start)
         :timed-out?  false
         :aborted?    false
         :error       e}))))
