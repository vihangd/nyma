(ns agent.cli
  (:require [clojure.string :as str]
            ["node:util" :refer [parseArgs]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.resources.loader :refer [discover]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.keybindings :refer [load-keybindings apply-keybindings rebuild-registry!]]
            [agent.providers.oauth :as oauth]
            [agent.file-access :as file-access]
            [agent.extensions.model-roles.policy :as perm-policy]
            ["./modes/interactive.mjs" :as interactive]
            [agent.modes.print :as print-mode]
            [agent.modes.rpc :as rpc]))

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

(defn ^:async emit-session-ready!
  "Fires session_ready (emit-async) with the standard payload — called once
   per CLI startup after extensions are loaded and the model is resolved."
  [agent model extensions-count]
  (js-await ((:emit-async (:events agent)) "session_ready"
                                           #js {:cwd        (js/process.cwd)
                                                :model      (str (or model "unknown"))
                                                :extensions extensions-count})))

(defn- resolve-model-via-registry
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
        ;; Parse provider/model slash syntax when no explicit --provider is given
        ;; --model provider/model slash syntax overrides the settings-default provider.
        ;; Only an explicit --provider CLI flag takes precedence over it.
        cli-provider (:provider values)
        [provider model-id]
        (if (and (not cli-provider) (str/includes? raw-model "/"))
          (let [slash (.indexOf raw-model "/")]
            [(.slice raw-model 0 slash) (.slice raw-model (inc slash))])
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
              (js/console.warn (str "OAuth refresh failed for " provider ": " (.-message e))))))))
    {:model    ((:resolve provider-registry) provider model-id)
     :provider provider
     :model-id model-id}))

(defn- resolve-tools
  "Returns an explicit tool restriction list, or nil if all builtins should be active.
   Only restricts when --tools CLI flag or settings 'tools' key is set."
  [values merged]
  (or (when-let [t (:tools values)]
        (-> t (.split ",") vec))
      (:tools merged)))

(defn resolve-ext-flags
  "Resolve --ext-* CLI flags against registered extension flags.
   Scans process.argv for --ext-flagname=value or --ext-flagname (boolean),
   matches against registered flags, coerces by type, and sets :value."
  [agent]
  (let [argv  (.slice js/process.argv 2)
        flags @(:flags agent)]
    (doseq [arg argv]
      (when (.startsWith arg "--ext-")
        (let [rest-arg  (.slice arg 6)
              eq-idx    (.indexOf rest-arg "=")
              flag-name (if (>= eq-idx 0) (.slice rest-arg 0 eq-idx) rest-arg)
              raw-value (when (>= eq-idx 0) (.slice rest-arg (inc eq-idx)))]
          (doseq [[full-name flag-config] flags]
            (let [short-name (last (.split full-name "/"))]
              (when (= short-name flag-name)
                (let [coerced (case (:type flag-config)
                                "boolean" (if (nil? raw-value) true (not= raw-value "false"))
                                "number"  (js/Number raw-value)
                                "string"  (or raw-value "")
                                raw-value)]
                  (swap! (:flags agent) assoc-in [full-name :value] coerced))))))))))

(def ^:private help-text
  "Usage: nyma [options] [prompt]

Modes (default: interactive):
  -p, --print            Run a single prompt and print the result, then exit.
                         The prompt is the first positional argument.
      --output-format <f> With -p: 'text' (default) or 'json' — a single
                         claude-style result object {result, is_error,
                         session_id, total_cost_usd, usage, duration_ms} for
                         headless orchestrators (e.g. cw).
      --mode <mode>      Explicit mode: interactive | print | json | rpc.
      --permission-mode <m>  Initial permission mode: default | accept-edits |
                         plan | full-auto. Interactive defaults to 'default'
                         (asks before write/shell); headless defaults to
                         'full-auto'. Switch at runtime with /mode.

Model selection:
      --provider <name>  Provider id (anthropic, openai, google, or any
                         extension-registered provider). Default: anthropic.
  -m, --model <id>       Model id. Default: claude-sonnet-4-20250514.

Session:
      --session <path>   Use a specific session file (jsonl).
      --no-session       Don't read or write any session file.

Tools:
      --tools <list>     Comma-separated allowlist of built-in tools.
                         Omit to enable all built-ins.

Extensions:
      --ext-<name>[=v]   Set a flag registered by an extension. Boolean
                         flags accept --ext-foo or --ext-foo=false.

Other:
  -h, --help             Show this help and exit.

Examples:
  nyma                                  Start the interactive UI
  nyma -p \"explain this repo\"           One-shot print mode
  nyma -m claude-opus-4-20250514        Pick a model
  nyma --provider openai -m gpt-5       Pick provider + model
")

(defn- print-help! []
  (js/process.stdout.write help-text))

(defn- temp-session-path []
  (str "/tmp/nyma-session-" (js/Date.now) ".jsonl"))

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
  (let [pos     (first positionals)
        pos?    (and (string? pos) (pos? (count pos)))
        piped?  (not (.-isTTY (.-stdin js/process)))
        stdin   (when piped?
                  (let [s (js-await (read-stdin-string))
                        t (when s (.trim s))]
                    (when (and t (pos? (count t))) t)))]
    (cond
      (and pos? stdin) (str pos "\n\n" stdin)
      pos?             pos
      stdin            stdin
      :else            nil)))

(defn- die-no-prompt! [mode-label]
  (.write (.-stderr js/process)
          (str "nyma " mode-label
               ": no prompt — pass a positional or pipe text on stdin.\n"))
  (js/process.exit 2))

(defn- resolve-session
  "Pick a session file. Explicit --session always wins. Otherwise:
   - --no-session         → ephemeral (nil path)
   - print/json one-shots → ephemeral by default; persisting would
                            pollute the interactive default session
                            with every scripted invocation
   - interactive          → ~/.nyma/sessions/default.jsonl"
  [values mode]
  (let [one-shot?    (contains? #{"print" "json"} mode)
        session-path (or (:session values)
                         (when-not (or (:no-session values) one-shot?)
                           (str (.. js/process -env -HOME) "/.nyma/sessions/default.jsonl")))]
    (create-session-manager session-path)))

(defn ^:async main []
  (let [{:keys [values positionals]}
        (parseArgs
         #js {:args    (clj->js (remove #(.startsWith % "--ext-")
                                        (.slice js/process.argv 2)))
              :options #js {:help         #js {:type "boolean" :short "h"}
                            :provider     #js {:type "string"}
                            :model        #js {:type "string" :short "m"}
                            :mode         #js {:type "string"}
                            :print        #js {:type "boolean" :short "p"}
                            :continue     #js {:type "boolean" :short "c"}
                            :resume       #js {:type "boolean" :short "r"}
                            :tools        #js {:type "string"}
                            :thinking     #js {:type "string"}
                            :session      #js {:type "string"}
                            :fork         #js {:type "string"}
                            :no-session   #js {:type "boolean"}
                            :output-format #js {:type "string"}
                            :permission-mode #js {:type "string"}}
              :allowPositionals true})

        _ (when (:help values)
            (print-help!)
            (js/process.exit 0))

        mode      (or (:mode values)
                      (when (:print values) "print")
                      "interactive")
        settings  (create-settings-manager)
        merged    ((:get settings))
        sessions-dir (str (.. js/process -env -HOME) "/.nyma/sessions")
        resources (-> (js-await (discover))
                      (assoc :settings settings)
                      (assoc :sessions-dir sessions-dir))
        session   (resolve-session values mode)

        active-tools (resolve-tools values merged)
        ;; Create agent first, then resolve model via its provider registry
        agent (create-agent
               {:model         nil
                :system-prompt ((:build-system-prompt resources))
                :max-steps     (or (:max-steps merged) 100)
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
            (set! (.-active-provider-name (:config agent)) (or provider "")))
        ;; The configured DEFAULT model spec (-m / settings :model / fallback).
        ;; The "default" role resolves to this — so /role default|reset and
        ;; plan-exit restore the user's chosen model, not a hardcoded sonnet.
        _ (when (and provider (:model-id resolved))
            (swap! (:state agent) assoc :base-model-spec
                   (str provider "/" (:model-id resolved))))
        api (create-extension-api agent)]

    ;; Filter active tools if --tools flag was used
    (when active-tools
      ((:set-active (:tool-registry agent)) (set active-tools)))

    ;; Attach session to agent so extensions can access it
    (reset! (:session agent) session)

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
        (js/console.warn (str "[nyma] Unknown --permission-mode \"" requested
                              "\"; using " pm ".")))
      (swap! (:state agent) assoc :permission-mode pm))

    ;; Attach extension API to agent so UI can access it
    (set! (.-extension-api agent) api)

    ;; Load all extensions (both .cljs and .ts/.js)
    (let [loaded-extensions (js-await (discover-and-load (:extension-dirs resources) api))
          extensions-atom   (atom loaded-extensions)]

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
            (when-let [m (:model late-resolved)]
              (set! (.-model (:config agent)) m)
              (set! (.-active-provider-name (:config agent))
                    (or (:provider late-resolved) ""))))
          (catch :default e
            (js/console.warn (str "[nyma] " (.-message e))))))

      ;; Resolve --ext-* CLI flags against registered extension flags
      (resolve-ext-flags agent)

      ;; Register .nymaignore file access restrictions
      (file-access/register-access-check (:events agent) (js/process.cwd))

      ;; Register built-in commands with reload support
      (register-builtins agent session resources extensions-atom resolve-ext-flags)

      ;; Load user keybindings and rebuild the action-id registry
      (let [bindings (load-keybindings)]
        (when (seq bindings)
          (apply-keybindings (:shortcuts agent) (:commands agent) bindings))
        (rebuild-registry! (:keybinding-registry agent) bindings))

      ;; Emit session_ready — all extensions loaded, session attached, model resolved
      (js-await (emit-session-ready! agent model (count @extensions-atom)))

      ;; SIGINT: async shutdown so extensions awaiting cleanup get a chance to finish.
      (.on js/process "SIGINT"
           (fn []
             (-> (emit-session-shutdown-async! agent "sigint")
                 (.finally (fn []
                             (deactivate-all @extensions-atom)
                             (js/process.exit 0))))))
      ;; exit: synchronous shutdown — node won't wait on promises here.
      (.on js/process "exit"
           (fn []
             (try
               (emit-session-shutdown! agent "exit")
               (catch :default _ nil))
             (deactivate-all @extensions-atom)))

      ;; Dispatch to mode. Print/json resolve stdin here (not in the mode
      ;; module) so unit tests can call mode/start directly with nil and
      ;; not have it block on a never-closing piped stdin.
      (case mode
        "interactive" (js-await (interactive/start agent session resources))
        "print"       (let [p (js-await (resolve-one-shot-prompt positionals))]
                        (when-not p (die-no-prompt! "-p"))
                        ;; `-p --output-format json` → single claude-style result
                        ;; object (for headless orchestrators); default → text.
                        (if (= (:output-format values) "json")
                          (js-await (print-mode/start-result agent p))
                          (js-await (print-mode/start agent p))))
        "json"        (let [p (js-await (resolve-one-shot-prompt positionals))]
                        (when-not p (die-no-prompt! "--mode json"))
                        (js-await (print-mode/start-json agent p)))
        "rpc"         (js-await (rpc/start agent))))))

(when (.-main js/import.meta) (main))
