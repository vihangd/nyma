(ns agent.cli
  (:require [agent.debug :as d]
            [clojure.string :as str]
            ["node:util" :refer [parseArgs]]
            ["node:fs" :as fs]
            ["node:path" :as npath]
            ["node:readline" :as readline]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.resources.loader :refer [discover]]
            [agent.resources.skills :as skills]
            [agent.sessions.manager :refer [create-session-manager session->seed-messages attach-session-persistence! new-session-path]]
            [agent.sessions.partial :as session-partial]
            [agent.sessions.listing :refer [list-sessions scope-to-project format-row]]
            [agent.sessions.archive :as archive]
            [agent.sessions.storage :as storage]
            [agent.version :refer [version]]
            [agent.settings.manager :refer [create-settings-manager inert-warning]]
            [agent.extensions :as ext :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.keybindings :refer [load-keybindings apply-keybindings rebuild-registry!]]
            [agent.providers.registry :as registry-utils]
            [agent.providers.oauth :as oauth]
            [agent.file-access :as file-access]
            [agent.extensions.model-roles.policy :as perm-policy]
            ["./modes/interactive.mjs" :as interactive]
            [agent.modes.print :as print-mode]
            [agent.modes.rpc :as rpc]
            [agent.modes.pi-rpc :as pi-rpc]))

(defn agent-stats
  "Snapshot of agent stats used by the session_end_summary / session_end events."
  [agent]
  (let [s @(:state agent)]
    #js {:totalCost    (or (:total-cost s) 0)
         :turnCount    (or (:turn-count s) 0)
         :inputTokens  (or (:total-input-tokens s) 0)
         :outputTokens (or (:total-output-tokens s) 0)
         :messageCount (count (:messages s))}))

(defn ^:async emit-session-shutdown!
  "Synchronous half of the shutdown sequence (used by the 'exit' handler):
   fires session_shutdown, session_end_summary, then session_end. The
   `:reason` payload propagates to handlers that care."
  [agent reason]
  (let [stats  (agent-stats agent)
        events (:events agent)]
    ((:emit events) "session_shutdown" {:reason reason})
    ((:emit events) "session_end_summary" stats)
    ((:emit events) "session_end" stats)))

(defn ^:async emit-session-shutdown-async!
  "Async half of the shutdown sequence (used by the SIGINT handler so
   extensions awaiting cleanup get a chance to finish). Same event order
   as `emit-session-shutdown!` but uses `emit-async` for the two
   summary/end events."
  [agent reason]
  (let [stats  (agent-stats agent)
        events (:events agent)]
    ((:emit events) "session_shutdown" {:reason reason})
    (js-await ((:emit-async events) "session_end_summary" stats))
    (js-await ((:emit-async events) "session_end" stats))))

(defn ^:async finish-one-shot!
  "Shut down and exit after a one-shot mode (-p / --mode json).

   `main` used to just return here. Nothing else ran: the `exit` handler that
   performs cleanup only fires when the process is already exiting, and the
   process never exited, because a loaded extension still held the event loop
   open (LSP clients, file watchers, MCP sockets). So `nyma -p` printed its
   answer and then sat there forever — fine interactively, fatal for a script
   or a CI step, which is exactly what one-shot mode is for.

   Only the SUCCESS path was affected, which is why no test caught it: the
   error paths throw and the process dies on its own.

   Mirrors the SIGINT sequence — async shutdown so extensions awaiting cleanup
   get a chance to finish, then deactivate, then exit."
  [agent extensions-atom shutdown-done?]
  (when-not @shutdown-done?
    (reset! shutdown-done? true)
    (js-await (-> (emit-session-shutdown-async! agent "exit")
                  (.catch (fn [_] nil))))
    (try (js-await (deactivate-all @extensions-atom)) (catch :default _ nil)))
  ;; One macrotask so buffered stdout drains before the hard exit — console.log
  ;; to a pipe is not guaranteed synchronous, and truncating the answer would be
  ;; a worse bug than the one being fixed.
  (js-await (js/Promise. (fn [res] (js/setTimeout res 0))))
  (js/process.exit (or (.-exitCode js/process) 0)))

(def ^:private sigint-window-ms
  "How long the first Ctrl-C's 'press again to exit' offer stands."
  2000)

(defn sigint-action
  "What Ctrl-C means right now: `:interrupt` or `:shutdown`.

   Ctrl-C used to exit, full stop. Every other agent CLI treats the first one
   during a response as 'stop generating' — so the reflex for abandoning a bad
   answer killed the session, losing the context that made the next prompt
   worth writing. A second press inside the window, or a press while idle,
   still exits: the escape hatch has to stay one keystroke away.

   Pure so the decision is testable without a signal or a terminal."
  [{:keys [streaming? last-sigint-ms now]}]
  (if (and streaming?
           (or (nil? last-sigint-ms)
               (> (- now last-sigint-ms) sigint-window-ms)))
    :interrupt
    :shutdown))

(defn ^:async shutdown!
  "The one way out: session_shutdown + the two end events, every extension's
   deactivate, then exit.

   `/exit` used to call `process.exit` itself, which runs the synchronous
   `exit` handler and nothing else — so MCP sockets and LSP clients were killed
   rather than closed, and any extension awaiting cleanup never got to finish.
   It now routes here through the bus, so there is exactly one shutdown
   sequence and `shutdown-done?` covers all of its callers."
  [agent extensions-atom shutdown-done? reason]
  (when-not @shutdown-done?
    (reset! shutdown-done? true)
    (try (js-await (emit-session-shutdown-async! agent reason))
         (catch :default _ nil))
    (try (js-await (deactivate-all @extensions-atom))
         (catch :default _ nil)))
  (js/process.exit (or (.-exitCode js/process) 0)))

(defn ^:async emit-session-ready!
  "Fires session_ready (emit-async) with the standard payload — called once
   per CLI startup after extensions are loaded and the model is resolved."
  [agent model extensions-count]
  (js-await ((:emit-async (:events agent)) "session_ready"
                                           #js {:cwd        (js/process.cwd)
                                                :model      (str (or model "unknown"))
                                                :extensions extensions-count})))

(defn resolve-model-via-registry
  "Resolve a model through the agent's provider registry.
   Falls back to the provider name as a direct model ID.
   Handles OAuth credential refresh for providers that need it.

   Supports 'provider/model' slash syntax in --model:
     --model omlx/Qwen3.6-27B-oQ4-mtp  →  provider=omlx, model=Qwen3.6-27B-oQ4-mtp
   Explicit --provider always wins over the slash prefix.

   Returns {:model <resolved-obj> :provider <name>} so the caller can
   stash the user-friendly provider label on the agent config — without
   that, the status line never sees a provider until the user does a
   runtime /model swap (since the startup path doesn't go through
   setModel)."
  [provider-registry values merged]
  (let [raw-model (or (:model values) (:model merged) "claude-sonnet-4-20250514")
        ;; A bare `--model build` is a ROLE name, not a model id. Roles are how
        ;; the rest of nyma talks about models (/role, escalate's chains,
        ;; bench's --model), and one-shot mode was the only place the word was
        ;; taken literally: `--model local` went out as the model id "local" and
        ;; came back "Unknown Model, please check the model code" from whichever
        ;; provider happened to be the default.
        role      (when-not (str/includes? (str raw-model) "/")
                    (get (:roles merged) (keyword (str raw-model))))
        raw-model (if (and role (:model role))
                    (str (:provider role) "/" (:model role))
                    raw-model)
        ;; Parse provider/model slash syntax when no explicit --provider is given
        ;; --model provider/model slash syntax overrides the settings-default provider.
        ;; Only an explicit --provider CLI flag takes precedence over it.
        cli-provider (:provider values)
        [provider model-id]
        (if (and (not cli-provider) (str/includes? raw-model "/"))
          (registry-utils/split-model-spec raw-model)
          [(or cli-provider (:provider merged) "anthropic") raw-model])
        p-config  ((:get provider-registry) provider)]
    ;; Auto-refresh OAuth credentials if needed
    (when-let [oauth-cfg (:oauth p-config)]
      (let [creds (oauth/load-credentials provider)]
        (when (and creds (oauth/needs-refresh? creds))
          (try
            ((:refresh-token oauth-cfg)
             #js {"access"     (:access creds)
                  "refresh"    (:refresh creds)
                  "expires-at" (:expires-at creds)})
            (catch :default e
              (d/warn (str "OAuth refresh failed for " provider ": " (.-message e))))))))
    {:model    (try ((:resolve provider-registry) provider model-id)
                     ;; An unknown provider throws here; the caller still needs
                     ;; to know WHAT was asked for to say so.
                    (catch :default _ nil))
     :provider provider
     :model-id model-id
     ;; Reported rather than warned about: this function runs TWICE, and the
     ;; first pass — before extensions load — is expected to miss every
     ;; extension-registered provider (minimax, zai, qwen-cli, every
     ;; custom_provider_*). Warning there told users their working config was
     ;; broken. Only the late caller, after extensions have registered, can say
     ;; that truthfully.
     :provider-known? (some? p-config)}))

(defn- resolve-tools
  "Returns an explicit tool restriction list, or nil if all builtins should be active.
   Only restricts when --tools CLI flag or settings 'tools' key is set."
  [values merged]
  (or (when-let [t (:tools values)]
        (-> t (.split ",") vec))
      (:tools merged)))

(defn resolve-ext-flags
  "Resolve --ext-* CLI flags against registered extension flags.

   registerFlag already applies the parsed argv value at registration time, so
   this is the late pass for flags registered after startup (e.g. /reload).
   The short-name rule lives in agent.extensions — one copy, because two copies
   of it is how --ext-* silently broke for every extension."
  [agent]
  (let [parsed (ext/parse-ext-flag-argv)]
    (when (seq parsed)
      (doseq [[full-name flag-config] @(:flags agent)]
        (let [raw (get parsed (ext/ext-flag-short-name full-name) :absent)]
          (when (not= raw :absent)
            (swap! (:flags agent) assoc-in [full-name :value]
                   (ext/coerce-flag-value (:type flag-config) raw))))))))

(def help-text
  "Usage: nyma [options] [prompt]

Modes (default: interactive):
  -p, --print            Run a single prompt and print the result, then exit.
                         The prompt is the first positional argument.
      --output-format <f> With -p: 'text' (default), 'json' — a single
                         claude-style result object {result, is_error,
                         session_id, total_cost_usd, usage, duration_ms} for
                         headless orchestrators (e.g. cw) — or 'stream-json':
                         one JSON object per line as the run progresses
                         (message_update text, tool_execution_*, usage), then
                         the same result object as the last line. Every format
                         exits 1 when the run fails (is_error true).
      --mode <mode>      Explicit mode: interactive | print | json | rpc |
                         pi-rpc (JSONL protocol for the pi Emacs frontend).
      --approve, --no-approve
                         Accepted for compatibility with the pi frontend and
                         ignored; permissions come from --permission-mode.
      --permission-mode <m>  Initial permission mode: default | accept-edits |
                         plan | full-auto. Interactive defaults to 'default'
                         (asks before write/shell); headless defaults to
                         'full-auto'. Switch at runtime with /mode.

Model selection:
      --provider <name>  Provider id (anthropic, openai, google, or any
                         extension-registered provider). Default: anthropic.
  -m, --model <id>       Model id. Default: claude-sonnet-4-20250514.
      --thinking <level> Extended thinking: off, minimal, low, medium, high,
                         xhigh. Default: off. Anthropic and Google models, and
                         OpenAI models on /responses. Also /thinking at runtime.

Session:
  -c, --continue         Resume the most recent session.
  -r, --resume           Pick a past session to resume (numbered prompt).
                         Scoped to the current project; shows all if none match.
      --all              With -r: list sessions from every project.
      --fork <path>      Branch a copy of an existing session into a new file.
      --session <path>   Use a specific session file (jsonl).
                         (Default: a fresh session file per launch.)
      --no-session       Don't read or write any session file.
      --discover         With -p: fetch provider catalogues at startup. Off by
                         default in one-shot runs, which only need the model
                         named on the command line (saves ~3s per launch).

Tools:
      --tools <list>     Comma-separated allowlist of built-in tools.
                         Omit to enable all built-ins.

Extensions:
      --ext-<name>[=v]   Set a flag registered by an extension. Boolean
                         flags accept --ext-foo or --ext-foo=false.

Other:
      --debug            Debug logging on for this run (same as NYMA_DEBUG=1);
                         lines go to ~/.nyma/debug.log.
  -h, --help             Show this help and exit.
  -v, --version          Print the version and exit.

Environment:
  ANTHROPIC_API_KEY      Credentials for the built-in providers. Also settable
  OPENAI_API_KEY         with /login <provider>, which stores them in
  GOOGLE_GENERATIVE_AI_API_KEY
                         ~/.nyma/credentials.json.
  NYMA_DEBUG=1           Debug logging to ~/.nyma/debug.log (same as --debug).
  NYMA_NO_MODEL_DISCOVERY=1
                         Never fetch provider catalogues at startup.

Examples:
  nyma                                  Start the interactive UI
  nyma -p \"explain this repo\"           One-shot print mode
  nyma -m claude-opus-4-20250514        Pick a model
  nyma --provider openai -m gpt-5       Pick provider + model
")

(defn- print-help! []
  (js/process.stdout.write help-text))

(defn- print-version! []
  ;; Bare, so `nyma --version` is usable in a shell substitution. The version
  ;; is baked in at build time (see agent.version) because a compiled binary
  ;; has no package.json to read.
  (js/process.stdout.write (str version "\n")))

(defn- ^:async read-stdin-string []
  (js/Promise.
   (fn [resolve _reject]
     (let [chunks (atom [])
           stdin  (.-stdin js/process)]
       (.setEncoding stdin "utf8")
       (.on stdin "data"  (fn [c] (swap! chunks conj c)))
       (.on stdin "end"   (fn [] (resolve (.join (clj->js @chunks) ""))))
       (.on stdin "error" (fn [_] (resolve "")))))))

(defn- ^:async resolve-one-shot-prompt
  "For print/json modes: combine the positional prompt with piped stdin.
   - positional only            → positional
   - piped stdin only           → stdin
   - both                       → positional + blank line + stdin
   Returns nil when neither is available so the caller can error out
   with a non-zero exit."
  [positionals]
  ;; NB: do not name a local `pos?`. `let` binds sequentially, so it shadows
  ;; core/pos? for every later binding — the stdin branch below then called a
  ;; boolean and print mode died with "pos_QMARK_2 is not a function" whenever
  ;; stdin was not a TTY, i.e. in every pipe, script and CI invocation.
  (let [pos      (first positionals)
        has-pos? (and (string? pos) (pos? (count pos)))
        piped?   (not (.-isTTY (.-stdin js/process)))
        stdin    (when piped?
                   (let [s (js-await (read-stdin-string))
                         t (when s (.trim s))]
                     (when (and t (pos? (count t))) t)))]
    (cond
      (and has-pos? stdin) (str pos "\n\n" stdin)
      has-pos?             pos
      stdin            stdin
      :else            nil)))

(def output-formats #{"text" "json" "stream-json"})

(defn output-format-error
  "The stderr line for an unaccepted `--output-format`, nil when it is fine
   (absent means text). A typo used to fall through to text silently, so
   `--output-format josn` handed a JSON parser plain prose. Only print mode
   reads the flag, so only print mode is refused over it: `--mode rpc` with
   a typo there exited 2 for a flag it never looks at."
  [f & [mode]]
  (when (and (some? f)
             (or (nil? mode) (= mode "print"))
             (not (contains? output-formats f)))
    "nyma: --output-format must be text, json or stream-json"))

(def modes #{"interactive" "print" "json" "rpc" "pi-rpc"})

(defn mode-error
  "The stderr line for an unaccepted `--mode`, nil when it is fine. A typo
   used to reach the mode dispatch and die there with `No matching clause:
   josn` and exit 1 — a usage error reported as a crash."
  [mode]
  (when-not (contains? modes mode)
    "nyma: --mode must be interactive, print, json, rpc or pi-rpc"))

(defn- die-no-prompt! [mode-label]
  (.write (.-stderr js/process)
          (str "nyma " mode-label
               ": no prompt — pass a positional or pipe text on stdin.\n"))
  (js/process.exit 2))

(defn pick-session
  "Select a session from a mtime-desc `list-sessions` vector by 1-based numeric
   `input`. Blank/nil → most recent (first). Out-of-range/non-numeric → nil.
   Pure so it can be unit-tested without TUI I/O."
  [sessions input]
  (let [t (some-> input str str/trim)]
    (cond
      (empty? sessions)        nil
      (or (nil? t) (= t ""))   (first sessions)
      :else (let [n (js/parseInt t 10)]
              (when (and (js/Number.isInteger n) (>= n 1) (<= n (count sessions)))
                (nth sessions (dec n)))))))

(defn- ^:async prompt-line
  "Ask one line on stderr (so it doesn't pollute stdout), resolve the answer."
  [question]
  (js/Promise.
   (fn [resolve _reject]
     ;; terminal:false — don't toggle stdin raw-mode, so the TUI inits cleanly
     ;; on stdin after we close this one-shot prompt.
     (let [rl (.createInterface readline #js {:input    (.-stdin js/process)
                                              :output   (.-stderr js/process)
                                              :terminal false})]
       (.question rl question (fn [ans] (.close rl) (resolve ans)))))))

(defn resume-listing
  "Which sessions to offer, and how many were held back.

   Returns {:shown [...] :hidden n}. Scoped to the current project unless
   `all?`. Pure apart from `list-sessions`, so the scoping and the hidden count
   are testable without a terminal."
  [sessions-dir all?]
  (let [sessions (list-sessions sessions-dir)]
    (if all?
      {:shown sessions :hidden 0}
      (let [[mine other] (scope-to-project sessions (js/process.cwd))]
        ;; Scoping to a project the user has never run in would otherwise show
        ;; an empty list and look broken. Falling back to everything keeps -r
        ;; useful there, and the footer still explains what is on screen.
        (if (seq mine)
          {:shown mine :hidden (count other)}
          {:shown sessions :hidden 0})))))

(defn- ^:async pick-resume-path
  "Print a numbered session list on stderr, read a choice, return the path
   (or nil when there are no sessions).

   Runs BEFORE the TUI starts (see prompt-line), so this is plain stderr text
   rather than an overlay picker — `two-col-row` is passed in for layout but no
   part of the rendering stack takes over stdin."
  [sessions-dir all?]
  (let [{:keys [shown hidden]} (resume-listing sessions-dir all?)
        width (max 40 (min 100 (or (.-columns (.-stderr js/process)) 80)))]
    (when (seq shown)
      (.write (.-stderr js/process) "\nResume which session?\n\n")
      (doseq [[i s] (map-indexed vector shown)]
        (.write (.-stderr js/process)
                (str "  " (.padStart (str (inc i)) 2) ". "
                     (format-row s (- width 6)) "\n")))
      (when (pos? hidden)
        ;; Never hide work silently: a scoped list that does not say what it
        ;; left out is how a session becomes unfindable.
        (.write (.-stderr js/process)
                (str "\n  … " hidden " session" (when (> hidden 1) "s")
                     " from other projects — rerun with --all\n")))
      (.write (.-stderr js/process) "\n")
      (let [ans (js-await (prompt-line "Number [blank = most recent]: "))]
        (:path (pick-session shown ans))))))

(defn ^:async resolve-session
  "Pick a session file and return its manager. Precedence:
   - --session <path>     → that path (always wins)
   - --no-session         → ephemeral (nil path)
   - print/json one-shots → ephemeral (scripted invocations shouldn't persist)
   - --fork <path>        → copy the source JSONL into a fresh file, use the copy
   - --continue / -c      → most recent session in sessions-dir (interactive only)
   - --resume  / -r       → numbered pre-TUI picker over sessions-dir (interactive)
   - interactive default  → a fresh ~/.nyma/sessions/<ts>.jsonl per launch"
  [values mode sessions-dir]
  (let [one-shot?    (contains? #{"print" "json"} mode)
        interactive? (= mode "interactive")
        ;; -c/-r/--fork were accepted and then dropped on the floor in one-shot
        ;; mode: the cond's `one-shot?` clause wins before any of them is read.
        ;; A flag that parses, prints nothing and does nothing is the worst of
        ;; the three possible behaviours — `nyma -p -c "and then?"` looked like
        ;; it was continuing a conversation and was starting a fresh one.
        _ (when (and one-shot?
                     (or (:continue values) (:resume values) (:fork values))
                     (not (:session values)))
            (.write (.-stderr js/process)
                    "nyma: -c/-r/--fork are ignored in print mode; use --session\n"))
        ;; Said once, here, rather than by each branch: an empty picker and a
        ;; -c with nothing to continue both used to print NOTHING and silently
        ;; start fresh, which reads as "my session is gone".
        no-sessions! (fn []
                       (.write (.-stderr js/process)
                               "nyma: no sessions for this project — starting fresh\n")
                       (new-session-path sessions-dir))
        path
        (cond
          (:session values)    (:session values)
          (:no-session values) nil
          one-shot?            nil
          (:fork values)
          (let [src (:fork values)
                dst (new-session-path sessions-dir)]
            (if (fs/existsSync src)
              (do (fs/mkdirSync sessions-dir #js {:recursive true})
                  (fs/copyFileSync src dst))
              (.write (.-stderr js/process)
                      (str "warning: --fork source not found: " src "; starting fresh\n")))
            dst)
          (and (:resume values) interactive?)
          (or (js-await (pick-resume-path sessions-dir (:all values)))
              ;; nil means either "nothing to offer" or "that number was not on
              ;; the list". Only the first is a missing-sessions story, and
              ;; saying so when a list was just printed would be a lie.
              (if (seq (:shown (resume-listing sessions-dir (:all values))))
                (new-session-path sessions-dir)
                (no-sessions!)))
          (and (:continue values) interactive?)
          (or (:path (first (list-sessions sessions-dir)))
              (no-sessions!))
          interactive?         (new-session-path sessions-dir)
          :else                nil)]
    (when path
      (fs/mkdirSync (npath/dirname path) #js {:recursive true})
      ;; A resumed session may be sitting on disk compressed. Appending to a zstd
      ;; frame is not a thing, so it becomes a plain JSONL again BEFORE the
      ;; manager opens it — reads would have worked either way, writes would not.
      (archive/restore! path))
    (let [session (create-session-manager path)]
      ;; Stamp the project on a session the first time its file is created, so
      ;; `-r` can scope by it later. Sessions written before this existed have
      ;; no marker and fall back to inference (sessions/project.cljs).
      ;;
      ;; Role "session-meta" is invisible to the model: both context builders
      ;; filter to #{user assistant tool_call tool_result} (context.cljs,
      ;; manager.cljs), so this never reaches a prompt or a replayed
      ;; transcript.
      (when (and path (not (fs/existsSync path)))
        ((:append session) {:role "session-meta" :metadata {:cwd (js/process.cwd)}}))
      session)))

(defn parse-args-error
  "One line for a `parseArgs` throw, or nil when it is not one we can phrase.

   node's own message is `Unknown option '--x'. To specify an option argument,
   use = …` — a sentence about parser internals, addressed to nobody. The user
   needs the flag they typed and where the list of real ones is."
  [e]
  (let [code (and e (.-code e))
        msg  (str (and e (or (.-message e) e)))]
    (cond
      (= code "ERR_PARSE_ARGS_UNKNOWN_OPTION")
      (let [flag (second (re-find #"'(-[^']+)'" msg))]
        (str "nyma: unknown option '" (or flag "?") "' (see --help)"))
      (and code (.startsWith (str code) "ERR_PARSE_ARGS"))
      (str "nyma: " (first (.split msg ". ")) " (see --help)")
      :else nil)))

(defn error-line
  "What a thrown Error should say on the way out: `nyma: <message>`.

   Mode dispatch used to let anything escape to node's default handler, which
   prints the class name and a 30-frame stack through squint's compiled output
   — for `ENOENT: no such file or directory, open '.nyma/x'` as readily as for
   a real bug. The stack is still there under NYMA_DEBUG, where it is worth
   something."
  [e]
  (str "nyma: " (or (and e (.-message e)) (str e))))

(defn interactive-resources
  "What interactive mode receives, with the positional prompt folded in.

   `Usage: nyma [options] [prompt]` is what --help has always said, and
   interactive mode dropped the positional on the floor: `nyma \"fix the
   build\"` opened an empty session and the prompt was gone. The TUI reads
   `seed-prompt` off this map and submits it after mount — joining here rather
   than there because argv splitting is the CLI's business."
  [resources positionals]
  (assoc resources :seed-prompt (str/join " " (or positionals []))))

(def cli-options
  "Every flag nyma parses. Kept beside `help-text`, which must list all of them."
  #js {:help         #js {:type "boolean" :short "h"}
       :version      #js {:type "boolean" :short "v"}
       :debug        #js {:type "boolean"}
       :provider     #js {:type "string"}
       :model        #js {:type "string" :short "m"}
       :mode         #js {:type "string"}
       :print        #js {:type "boolean" :short "p"}
       :continue     #js {:type "boolean" :short "c"}
       :resume       #js {:type "boolean" :short "r"}
       ;; Widen -r past the current project.
       :all          #js {:type "boolean"}
       :tools        #js {:type "string"}
       :thinking     #js {:type "string"}
       :session      #js {:type "string"}
       :fork         #js {:type "string"}
       :no-session   #js {:type "boolean"}
       ;; One-shot runs skip catalogue discovery; this opts back in
       ;; for an id that exists only in a live catalogue.
       :discover     #js {:type "boolean"}
       :output-format #js {:type "string"}
       :permission-mode #js {:type "string"}
       ;; Accepted for compat with the pi Emacs frontend's trust policy
       ;; (it always appends one of these). Declared so strict parseArgs
       ;; doesn't crash; pi-rpc keeps its ask-baseline regardless.
       :approve      #js {:type "boolean"}
       :no-approve   #js {:type "boolean"}})

(defn parse-cli-args!
  "argv → {:values :positionals}, exiting 2 with one line on a bad flag.

   An unrecognised flag used to escape as an unhandled Error: node printed its
   class, its `code`, and a stack through squint's compiled output, and the
   process died on the default handler's exit code rather than the 2 a shell
   expects for a usage error."
  []
  (try
    (parseArgs
     #js {:args    (clj->js (remove #(.startsWith % "--ext-")
                                    (.slice js/process.argv 2)))
          :options cli-options
          :allowPositionals true})
    (catch :default e
      (if-let [line (parse-args-error e)]
        (do (.write (.-stderr js/process) (str line "\n"))
            (js/process.exit 2))
        (throw e)))))

(defn ^:async main []
  ;; FIRST, before anything can reach a model. The AI SDK writes its warnings
  ;; straight to stderr, which lands mid-frame once the TUI owns the screen and
  ;; desynchronises pi-tui's differential renderer. Installed here rather than
  ;; in interactive mode so print/json/rpc get it too.
  (d/install-sdk-warning-bridge!)
  (let [{:keys [values positionals]} (parse-cli-args!)

        _ (when (:help values)
            (print-help!)
            (js/process.exit 0))

        _ (when (:version values)
            (print-version!)
            (js/process.exit 0))

        ;; Same gate NYMA_DEBUG=1 opens; the flag is for the one-off run.
        _ (when (:debug values) (d/set-enabled! true))

        mode      (or (:mode values)
                      (when (:print values) "print")
                      "interactive")

        _ (when-let [msg (or (mode-error mode)
                             (output-format-error (:output-format values) mode))]
            (.write (.-stderr js/process) (str msg "\n"))
            (js/process.exit 2))
        ;; Provider extensions fetch every gateway's full catalogue at launch so
        ;; the picker and /model autocomplete have something to show. A one-shot
        ;; run has neither: it resolves exactly the model named on the command
        ;; line, which the seed lists already carry. Measured, timed against a
        ;; non-existent model so startup runs and generation does not: 5.5s with
        ;; discovery, 2.6s without — and worse per provider holding a stale
        ;; credential, since those 401 on every launch. `nyma --model vllm/...`
        ;; was fetching yunwu's catalogue, which is what surfaced this.
        ;; Env, because that is how NYMA_MODE and NYMA_NO_MODEL_DISCOVERY
        ;; already reach extensions. --discover opts back in.
        _ (when (and (contains? #{"print" "json"} mode)
                     (not (:discover values)))
            (aset js/process.env "NYMA_ONE_SHOT" "1"))
        settings  (create-settings-manager)
        merged    ((:get settings))
        ;; Say so if the settings file contains keys that do nothing. Emitted
        ;; here, before the TUI starts, which is the one point where writing to
        ;; stderr cannot desynchronise pi-tui's renderer. Six keys shipped
        ;; parsed-but-unread; silence made a dead setting indistinguishable from
        ;; a typo.
        _         (when-let [w (inert-warning ((:user-settings settings)))]
                    (d/warn "settings" w))
        sessions-dir (str (.. js/process -env -HOME) "/.nyma/sessions")
        resources (-> (js-await (discover {:context-files (:context-files merged)}))
                      (assoc :settings settings)
                      (assoc :sessions-dir sessions-dir))
        session   (js-await (resolve-session values mode sessions-dir))

        ;; Compress sessions nobody has touched in a while. Runs after the
        ;; current session is resolved so the active file is never a candidate,
        ;; and after settings so it stays off unless asked for. Measured: 0.82MB
        ;; of JSONL becomes 0.11MB in 2ms, so even a large sweep is unnoticeable
        ;; next to the model call that follows.
        _         (let [days (or (:archive-after-days (:sessions merged)) 0)]
                    (when (pos? days)
                      (let [{:keys [archived bytes-saved]}
                            (archive/sweep! sessions-dir days
                                            (when session ((:get-file-path session))))]
                        (when (pos? archived)
                          (d/info "sessions"
                                  (str "archived " archived " session(s), "
                                       (js/Math.round (/ bytes-saved 1024)) "KB saved"))))))

        active-tools (resolve-tools values merged)
        ;; Create agent first, then resolve model via its provider registry
        agent (create-agent
               {:model         nil
                :system-prompt ((:build-system-prompt resources))
                :max-steps     (or (:max-steps merged) 100)
                ;; --thinking beats settings :thinking. Both were parsed and
                ;; then dropped before this.
                :thinking      (or (:thinking values) (:thinking merged))
                :settings      settings})
        resolved (try
                   (resolve-model-via-registry (:provider-registry agent) values merged)
                   (catch :default _e nil))
        model    (:model resolved)
        provider (:provider resolved)
        _ (when model
            (set! (.-model (:config agent)) model)
            ;; Persist the user-friendly provider label so the status
            ;; line shows `<provider>/<model>` from the very first turn,
            ;; not just after a runtime /model swap.
            (aset (:config agent) "active-provider-name" (or provider "")))
        ;; The configured DEFAULT model spec (-m / settings :model / fallback).
        ;; The "default" role resolves to this — so /role default|reset and
        ;; plan-exit restore the user's chosen model, not a hardcoded sonnet.
        ;; Recorded even when resolution FAILED: it is the only record of what
        ;; the user asked for, and the loop's "no model configured" error reads
        ;; it to name the spec instead of sending everyone to /login.
        _ (when (and provider (:model-id resolved))
            (swap! (:state agent) assoc :base-model-spec
                   (str provider "/" (:model-id resolved))))
        api (create-extension-api agent)]

    ;; The `skill` tool needs the discovered skill map and the agent, so it
    ;; cannot sit in `builtin-tools`; registered before the --tools filter so
    ;; the allowlist governs it like any built-in. /reload calls the same
    ;; helper with the rediscovered map.
    (skills/register-skill-tool! agent (:skills resources))

    ;; Filter active tools if --tools flag was used
    (when active-tools
      ((:set-active (:tool-registry agent)) (set active-tools)))

    ;; Attach session to agent so extensions can access it
    (reset! (:session agent) session)

    ;; Resume: load the session file, seed state :messages so the model sees
    ;; prior turns, THEN attach the persistence subscriber (seeding writes the
    ;; atom directly — not via dispatch! — so it never re-appends to the JSONL).
    (when (and session ((:get-file-path session)))
      ((:load session))
      (let [seeded (session->seed-messages ((:build-context session)))
            ;; A sidecar next to the session file means the previous run died
            ;; mid-response. Fold it back in so a resumed session doesn't lose
            ;; the turn that was in flight when it crashed.
            partial-text (session-partial/read-partial ((:get-file-path session)))
            seeded       (if partial-text
                           (let [marked (session-partial/mark-cutoff partial-text)]
                             ;; PERSIST it before dropping the sidecar. Folding
                             ;; it into `:messages` alone put the recovered turn
                             ;; in this session's context and nowhere else — the
                             ;; sidecar was deleted in the same breath, so the
                             ;; next message linked to the pre-crash leaf and the
                             ;; recovered response was gone from disk for good.
                             ;; Any later resume silently lost it.
                             ;;
                             ;; Appending also matches the interrupted-turn path
                             ;; in sessions/manager, which writes the same shape
                             ;; via (:append session). Seeding uses `swap!`
                             ;; rather than `dispatch!`, so the persistence
                             ;; subscriber does not fire and this cannot
                             ;; double-write.
                             ((:append session) {:role "assistant" :content marked})
                             (session-partial/clear-partial! ((:get-file-path session)))
                             (session-partial/append-partial seeded partial-text))
                           seeded)]
        (when (seq seeded)
          (swap! (:state agent) assoc :messages seeded)
          ;; DEFERRED, not emitted here. Extensions load ~30 lines below and the
          ;; bus has no replay (events.cljs) — an emit at this point reaches an
          ;; empty handler list and is simply lost. That silently disabled the
          ;; Claude-Code-compatible SessionStart hook
          ;; (claude_hook_bridge/events/session.cljs) at launch, and mcp_client's
          ;; bring-up, which is why it subscribes to two events hoping one lands.
          (swap! (:state agent) assoc
                 :pending-session-start
                 {:reason (cond (:fork values)     "fork"
                                (:continue values) "continue"
                                (:resume values)   "resume"
                                :else              "default")
                  :sessionFile ((:get-file-path session))
                  :messageCount (count seeded)}))))
    (attach-session-persistence! agent session)

    ;; Initial permission mode (modes-as-roles). Interactive defaults to
    ;; "default" (asks before write/shell/network — there's a UI to prompt).
    ;; Headless print/json/rpc have NO UI, so an "ask" would dead-end on a
    ;; deny; they default to "full-auto" (allow-all, preserving prior headless
    ;; behavior so orchestrators keep working). --permission-mode overrides.
    (let [headless? (contains? #{"print" "json" "rpc"} mode)
          requested (:permission-mode values)
          pm        (perm-policy/resolve-initial-mode requested headless?)]
      ;; Warn on an invalid flag (resolve-initial-mode already fell back to the
      ;; computed default so a typo'd headless flag still gets full-auto).
      (when (and requested (not (perm-policy/mode? requested)))
        (d/warn (str "[nyma] Unknown --permission-mode \"" requested
                     "\"; using " pm ".")))
      (swap! (:state agent) assoc :permission-mode pm))

    ;; Attach extension API to agent so UI can access it
    (set! (.-extension-api agent) api)

    ;; The SQLite store behind prompt_history (Ctrl+R) and /stats. Both read
    ;; `api.__sqlite-store` and both shipped with nothing setting it, so the
    ;; picker was always empty and /stats always said "require SQLite storage".
    ;; Cross-session by design (~/.nyma/nyma.db), so --no-session does not
    ;; disable it. Usage rows come from the store's :usage-updated dispatch.
    (when-not (:no-session values)
      (try
        (let [db-path (npath/join (.. js/process -env -HOME) ".nyma" "nyma.db")
              sqlite  (storage/create-sqlite-store db-path)]
          (aset api "__sqlite-store" sqlite)
          ((:subscribe (:store agent))
           (fn [event-type state data]
             (when (= event-type :usage-updated)
               (try
                 ((:record-usage sqlite)
                  {:session-file (str (or (when-let [s @(:session agent)]
                                            (when (fn? (:session-file s)) ((:session-file s))))
                                          ""))
                   :model        (let [m (:model (:config agent))]
                                   (if (string? m) m (or (and m (.-modelId m)) "")))
                   :input-tokens (:input-tokens data)
                   :output-tokens (:output-tokens data)
                   :cost         (:cost data)})
                 (catch :default e (d/debug "sqlite" (str "usage row: " (.-message e)))))))))
        (catch :default e
          (d/warn "sqlite" (str "store unavailable: " (.-message e))))))

    ;; Load all extensions (both .cljs and .ts/.js). Up to a few seconds with
    ;; 40 builtins, during which the terminal was blank; one line on stderr,
    ;; cleared once the TUI paints over it.
    (when (and (not (contains? #{"print" "json"} mode)) (.-isTTY js/process.stderr))
      (.write js/process.stderr (str "nyma " version ": loading extensions…\r")))
    (let [loaded-extensions (js-await (discover-and-load (:extension-dirs resources) api
                                                         (:builtin-extensions resources)))
          extensions-atom   (atom loaded-extensions)
          ;; Shared by the SIGINT handler, the `exit` handler and the one-shot
          ;; finisher so shutdown runs exactly once.
          shutdown-done?    (atom false)
          ;; When the last Ctrl-C was, so a second one inside the window means
          ;; "I meant it".
          last-sigint-ms    (atom nil)]

      ;; Re-resolve model now that extensions have registered their providers.
      ;; The startup resolution above runs against the built-in registry only
      ;; (anthropic/openai/google) — any user whose default provider is
      ;; extension-registered (minimax, qwen-cli, custom_provider_*) hits an
      ;; "Unknown provider" throw, lands here with config.model = nil, and
      ;; would otherwise see "No model configured" on their first message.
      (when (nil? (.-model (:config agent)))
        (try
          (when-let [late-resolved (resolve-model-via-registry
                                    (:provider-registry agent) values merged)]
            ;; Now it is a real finding: every provider extension has had its
            ;; chance to register. Names the registered set, because a provider
            ;; declared in ~/.nyma/settings.json can be hidden by a project
            ;; settings file replacing the whole `local-models` array — which
            ;; looks identical to a missing credential from the outside.
            (when-not (:provider-known? late-resolved)
              (let [known (vec (sort (keys ((:list (:provider-registry agent))))))]
                (if (:provider values)
                  ;; The user NAMED this provider on the command line and it
                  ;; does not exist. Warning and carrying on left the run to
                  ;; die several seconds later inside the SDK, naming a
                  ;; different model than the one that was asked for — so the
                  ;; error the user saw had nothing to do with their typo.
                  ;; A settings-default provider stays a warning: it may be
                  ;; one the user is not using this run.
                  (do (.write (.-stderr js/process)
                              (str "nyma: unknown provider '" (:provider values)
                                   "'\n  registered: " (str/join ", " known) "\n"))
                      (js/process.exit 2))
                  (d/warn "nyma"
                          (str "Unknown provider '" (:provider late-resolved)
                               "' for model '" (:model-id late-resolved) "'")
                          #js {:registered (clj->js known)}))))
            (when-let [m (:model late-resolved)]
              (set! (.-model (:config agent)) m)
              (aset (:config agent) "active-provider-name"
                    (or (:provider late-resolved) ""))
              ;; The early path sets :base-model-spec; this one did not, so a
              ;; run that resolved late left it nil. Extensions cannot see the
              ;; agent config, and :base-model-spec is the ONLY record of the
              ;; active model in the state atom — so small-model's per-model
              ;; profiles silently went inert on exactly those runs. Measured:
              ;; editStrategy "whole" hid `edit` on one task and not another in
              ;; the same 3-trial run, which is how a half-applied profile
              ;; looks from the outside.
              (when-let [mid (:model-id late-resolved)]
                (swap! (:state agent) assoc :base-model-spec
                       (str (or (:provider late-resolved) "") "/" mid)))))
          (catch :default e
            ;; Say what WAS registered. "Unknown provider: yunwu" alone cannot
            ;; distinguish a missing credential from an extension that never
            ;; loaded — and those need opposite fixes. Chasing that distinction
            ;; by inspection cost an afternoon; the provider list settles it in
            ;; one line. (The answer that time: the bundled single-file binary
            ;; loads 2 extensions and 3 providers, because builtin-extensions-dir
            ;; resolves inside the bundle where the directories do not exist.
            ;; The dist entry point loads 40 and 17.)
            (d/warn "nyma" (.-message e)
                    #js {:registered (clj->js (vec (sort (keys ((:list (:provider-registry agent)))))))
                         :extensions (count loaded-extensions)}))))

      ;; Resolve --ext-* CLI flags against registered extension flags
      (resolve-ext-flags agent)

      ;; Register .nymaignore file access restrictions
      (file-access/register-access-check (:events agent) (js/process.cwd))

      ;; Register built-in commands with reload support
      (register-builtins agent session resources extensions-atom resolve-ext-flags)

      ;; NOW emit the deferred session_start — extensions are loaded and their
      ;; handlers are registered, so subscribers actually exist.
      (when-let [payload (:pending-session-start @(:state agent))]
        (swap! (:state agent) dissoc :pending-session-start)
        ((:emit (:events agent)) "session_start" payload))

      ;; Load user keybindings and rebuild the action-id registry
      (let [bindings (load-keybindings)]
        (when (seq bindings)
          (apply-keybindings (:shortcuts agent) (:commands agent) bindings agent))
        (rebuild-registry! (:keybinding-registry agent) bindings))

      ;; Emit session_ready — all extensions loaded, session attached, model resolved
      (js-await (emit-session-ready! agent model (count @extensions-atom)))

      ;; SIGINT: first press during a turn aborts it; anything else shuts down.
      ;; The TUI writes :streaming? into the state atom for the life of a turn
      ;; (its half of this contract).
      (.on js/process "SIGINT"
           (fn []
             (let [now (js/Date.now)]
               (if (= :interrupt (sigint-action {:streaming? (:streaming? @(:state agent))
                                                 :last-sigint-ms @last-sigint-ms
                                                 :now now}))
                 (do (reset! last-sigint-ms now)
                     (try (.abort @(:abort-controller agent)) (catch :default _ nil))
                     (.write (.-stderr js/process)
                             "nyma: interrupted — press Ctrl-C again to exit\n"))
                 (shutdown! agent extensions-atom shutdown-done? "sigint")))))

      ;; `/exit` emits this rather than calling process.exit itself, so it takes
      ;; the same async path as SIGINT — session_shutdown fires once and MCP/LSP
      ;; get closed instead of killed.
      ((:on (:events agent)) "exit"
                             (fn [_] (shutdown! agent extensions-atom shutdown-done? "exit")))
      ;; exit: synchronous shutdown — node won't wait on promises here.
      ;; Skipped when a one-shot mode already shut down, so extension cleanup
      ;; and the session_end events don't run twice.
      (.on js/process "exit"
           (fn []
             ;; Flush the streaming checkpoint before anything else — this is
             ;; the last synchronous moment the process has.
             (try (session-partial/flush-all!) (catch :default _ nil))
             (when-not @shutdown-done?
               (reset! shutdown-done? true)
               (try
                 (emit-session-shutdown! agent "exit")
                 (catch :default _ nil))
               (deactivate-all @extensions-atom))
             ;; The last thing an interactive session prints: where it went and
             ;; what it cost. Nothing did, so resuming meant guessing the path.
             (when-not (contains? #{"print" "json"} mode)
               (try
                 (let [st   @(:state agent)
                       sess @(:session agent)
                       file (when (and sess (fn? (:session-file sess))) ((:session-file sess)))]
                   (.write js/process.stderr
                           (str "nyma: " (or (:turn-count st) 0) " turns · $"
                                (.toFixed (or (:total-cost st) 0) 4)
                                (when (seq (str file)) (str " · " file " — resume with nyma -c"))
                                "\n")))
                 (catch :default _ nil)))))

      ;; Dispatch to mode. Print/json resolve stdin here (not in the mode
      ;; module) so unit tests can call mode/start directly with nil and
      ;; not have it block on a never-closing piped stdin.
      ;; Anything thrown from here on is reported as one line. The default
      ;; handler printed the error class and a stack through squint's compiled
      ;; output for `ENOENT` and for a real bug alike, and left the exit code to
      ;; node. NYMA_DEBUG keeps the stack, which is the only time it helps.
      (try
        (case mode
          "interactive" (js-await
                         (interactive/start
                          agent session
                          (interactive-resources resources positionals)))
          "print"       (let [p (js-await (resolve-one-shot-prompt positionals))]
                          (when-not p (die-no-prompt! "-p"))
                        ;; `-p --output-format json` → single claude-style result
                        ;; object (for headless orchestrators); stream-json →
                        ;; JSONL progress then that object; default → text.
                          (case (:output-format values)
                            "json"        (js-await (print-mode/start-result agent p))
                            "stream-json" (js-await (print-mode/start-stream-json agent p))
                            (js-await (print-mode/start agent p)))
                          (js-await (finish-one-shot! agent extensions-atom shutdown-done?)))
          "json"        (let [p (js-await (resolve-one-shot-prompt positionals))]
                          (when-not p (die-no-prompt! "--mode json"))
                          (js-await (print-mode/start-json agent p))
                          (js-await (finish-one-shot! agent extensions-atom shutdown-done?)))
          ;; EOF on stdin means the host is gone; without the exit the process
          ;; sat forever on an idle event loop (`nyma --mode rpc </dev/null`).
          "rpc"         (js-await (rpc/start agent {:on-eof (fn [] (js/process.exit 0))}))
          "pi-rpc"      (js-await (pi-rpc/start agent)))
        (catch :default e
          (.write (.-stderr js/process)
                  (if (d/enabled?)
                    (str (or (and e (.-stack e)) (error-line e)) "\n")
                    (str (error-line e) "\n")))
          (js/process.exit 1))))))

(when (.-main js/import.meta) (main))
