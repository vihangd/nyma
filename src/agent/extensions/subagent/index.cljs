(ns agent.extensions.subagent.index
  "Subagent extension — context-isolated delegation built on roles.

   Evidence boundary (researched: fan-out vs RLM vs tiered vs single):
   for coding agents the consensus is single-threaded EDITS + isolated
   READ-ONLY exploration/verification. So subagent roles are read-only by
   default; the editing `worker` role is opt-in. Recursion is capped at
   depth 1 structurally — children get no `subagent` tool.

   A subagent is the dual of the `advisor` tool:
     advisor  = no tools, PARENT transcript, stronger model, generateText
     subagent = tools,     FRESH  transcript, role  model,   loop/run

   Isolation guarantees (verified against agent.core/create-agent):
     - child gets its own event bus  → parent extensions never fire on it
     - child :session is its own file → no writes to parent JSONL/SQLite
     - only the final summary returns → child tool calls stay in the child"
  (:require [agent.utils.data :as data]
            [agent.schema :as schema]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run steer]]
            [agent.pricing :as pricing]
            [agent.extensions.model-roles.policy :as policy]
            [agent.providers.registry :as registry]
            [agent.tool-metadata :as tool-metadata]
            [agent.settings.manager :refer [create-settings-manager defaults]]
            [agent.sessions.manager :refer [create-session-manager attach-session-persistence!]]
            [agent.extensions.subagent.concurrency :as conc]
            [agent.extensions.subagent.agents-md :as agents-md]
            [agent.utils.worktree :as wt]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]))

(def ^:private MAX-PARALLEL 8)
(def ^:private MAX-CONCURRENCY 4)

(def ^:private default-subagent-prompt
  "You are a subagent delegated a focused task by a parent agent. Work
within your task only. Return a concise, self-contained summary of your
findings or result — the parent sees ONLY your final message, not your
intermediate tool calls, so put everything that matters in the summary.")

;; Async jobs registry — session-scoped (in-memory). {id {:status :result :task :agent}}
(def ^:private jobs (atom {}))
(def ^:private job-counter (atom 0))
;; Every in-process child, by id, so it can be messaged or read about later.
;; Lost on restart; its transcript is not. {id {:child :drive :label :status :progress}}
(def ^:private children (atom {}))
(def ^:private child-counter (atom 0))
(def ^:private MAX-KEPT-CHILDREN 20)
;; Children started since the user last asked for something.
(def ^:private spawned (atom 0))

;; ---------------------------------------------------------------------------
;; Role / agent resolution
;; ---------------------------------------------------------------------------

;; Built-in subagent roles owned by THIS extension. A user's settings.json
;; :roles map REPLACES the settings defaults, so we cannot rely on those —
;; define them here and merge defaults < settings.roles < markdown, per key
;; (see `all-agents`), so they always exist and an override changes only what
;; it names. Read-only by default (evidence boundary: single-threaded edits +
;; isolated read-only exploration).
(def ^:private default-subagent-roles
  {:scout      {:provider "anthropic" :model "claude-haiku-4-5"
                :allowed-tools ["read" "glob" "grep" "ls"]
                :description "Fast read-only codebase recon. Returns a compressed summary."
                :system-prompt "You are a scout. Investigate the codebase read-only and return a compressed, structured summary (files, symbols, where things live). Do not propose edits."}
   :planner    {:provider "anthropic" :model "claude-opus-5-5"
                :allowed-tools ["read" "glob" "grep" "ls" "web_search" "web_fetch"]
                :description "Read-only implementation planner. Returns a numbered plan."
                :system-prompt "You are a planner. Analyze read-only and return a detailed numbered plan under a 'Plan:' header. Do not modify files."}
   :reviewer   {:provider "anthropic" :model "claude-sonnet-5-5"
                :allowed-tools ["read" "glob" "grep" "ls"]
                :description "Read-only code reviewer. Returns findings, one per line."
                :system-prompt "You are a reviewer. Examine the code read-only and return concise findings (path:line — problem — fix). Do not modify files."}
   :researcher {:provider "anthropic" :model "claude-sonnet-5-5"
                :allowed-tools ["read" "web_search" "web_fetch"]
                :description "Web/docs research. Returns sourced findings."
                :system-prompt "You are a researcher. Gather information from docs/web and return a sourced summary with URLs. Do not modify files."}})

(def ^:private read-only-tools ["read" "glob" "grep" "ls" "retrieve_result"])

(defn- all-tools?
  "`allowed-tools: [\"*\"]`, the one way to ask for every tool."
  [allowed]
  (boolean (some #(= "*" %) allowed)))

(defn child-tools
  "The tools an in-process child may use, nil for all of them. Leaving the list
   out must not mean everything: a role written just to pick a model got bash
   and write. Under a plan-mode parent nothing that changes the tree is offered."
  [allowed & [parent-mode]]
  (let [tools (cond
                (all-tools? allowed) nil
                (seq allowed)        allowed
                :else                read-only-tools)]
    (if (= "plan" parent-mode)
      (filterv tool-metadata/read-only? (or tools read-only-tools))
      tools)))

(defn- gate-child!
  "Answer `child`'s permission_request with the parent's gate, so mode, policy,
   denylist and grants all apply and a prompt names the child. A child's bus has
   no subscribers, and no decision means allow: an api without the gate refuses."
  [child api agent-name]
  ((:on (:events child)) "permission_request"
                         (fn [data]
                           (if-let [check (aget api "checkPermission")]
                             (-> (check (.-tool data) (.-args data) agent-name)
                                 (.then (fn [r] (if (.-allowed r)
                                                  #js {:decision "allow"}
                                                  #js {:decision "deny" :reason (.-reason r)}))))
                             #js {:decision "deny" :reason "No permission gate"}))))

(defn live-settings
  "A settings manager whose `:get` is the session's own view, so `--settings`
   overrides reach subagents. Only the session's manager holds those; a fresh
   one reads the files alone. The rest of the fresh manager stays, because the
   child's permission gate calls `:tool-allowed?` on it."
  [api]
  (let [sm (create-settings-manager)]
    (if (aget api "settings")
      (assoc sm :get (fn [] (.settings api)))
      sm)))

(defn all-agents
  "Merge built-in subagent roles < settings.roles < markdown agents
   (.nyma/agents). Later sources override earlier, key by key: a role naming
   only a model keeps the built-in's `allowed-tools`, which is all that stands
   between a child and every tool. Returns {keyword-or-string -> role-config}."
  [settings-mgr]
  (let [settings ((:get settings-mgr))
        ;; settings.roles also holds the PRIMARY's roles. The model-less ones
        ;; — permission modes, and `lead`, whose whole job is calling this
        ;; tool — cannot be run as a child, so they must not be offered as one.
        roles    (into {} (filter (fn [[_ cfg]] (or (:model cfg) (get cfg "model")))
                                  (or (:roles settings) {})))
        md       (agents-md/load-agents)
        ;; Settings carry the shipped roles too, so being there says nothing:
        ;; a role is the user's once its model differs from the one we ship.
        stock    (merge-with merge default-subagent-roles (:roles defaults))]
    (into {} (map (fn [[k cfg]]
                    [k (assoc cfg :shipped? (and (some? (get stock k))
                                                 (= (cfg-get cfg :model "model")
                                                    (cfg-get (get stock k) :model "model"))))])
                  (merge-with merge default-subagent-roles roles md)))))

(defn inherit-model
  "A role takes the parent's model when it says `inherit` or names none, and when
   it is a shipped role on another provider, which the user may have no key for."
  [cfg api]
  (let [spec (when (and cfg (aget api "getActiveModelSpec")) (.getActiveModelSpec api))
        [prov model] (when spec (registry/split-model-spec spec))
        m    (when cfg (cfg-get cfg :model "model"))]
    (cond
      (nil? model)                      cfg
      (or (nil? m) (= "inherit" m))     (assoc cfg :provider prov :model model)
      :else (policy/inherit-default-model cfg (not (:shipped? cfg)) prov model))))

(defn- lookup-agent
  "Find a role-config by name. Squint: keywords ARE strings, so a single
   lookup covers keyword (settings roles) and string (markdown) keys."
  [agents nm]
  (or (get agents nm) (get agents (str nm))))

(defn- cfg-get
  "Read a key from a role-config in any spelling (`data/conf-get`). The
   second argument is kept for the call sites; it is the same key."
  [cfg kw _kstr]
  (data/conf-get cfg kw))

(defn final-text
  "Extract the last ASSISTANT message's text from a child's messages.
   Filters by role (not just `last`) so a child that ends on a tool step
   returns its summary, not a trailing tool result."
  [messages]
  (let [a (last (filter #(= "assistant" (or (:role %) (get % "role"))) messages))
        c (when a (:content a))]
    (cond
      (nil? a) ""
      (string? c) c
      (and (js/Array.isArray c) (pos? (count c)))
      (let [parts (filter #(= "text" (or (.-type %) (get % "type"))) c)]
        (str/join "\n" (map #(or (.-text %) (get % "text")) parts)))
      :else (str c))))

;; ---------------------------------------------------------------------------
;; Worktree isolation: a child PROCESS in its own checkout
;; ---------------------------------------------------------------------------
;;
;; An in-process child shares process.cwd with the parent, and every tool
;; resolves paths against it, so a child that edits files edits the user's
;; tree. In a worktree the child is a separate `nyma -p` whose cwd is the
;; checkout; its changes come back as a branch.

(defn self-command
  "argv prefix that runs this nyma: the compiled binary is process.execPath
   alone; from dist it is the runtime plus cli.mjs beside this module."
  []
  (let [cli (try (url/fileURLToPath (js/URL. "../../cli.mjs" (.-url js/import.meta)))
                 (catch :default _ nil))]
    (if (and cli (fs/existsSync cli))
      [js/process.execPath cli]
      [js/process.execPath])))

(defn child-permission-mode
  "A worktree isolates the checkout, not the machine: a child's shell reaches
   past it. So a child gets no more than its parent. Edits stay allowed, being
   what a worktree is for; shell and network ask, which `-p` answers with no.
   Only a parent the user put in full-auto passes that on."
  [parent-mode]
  (case (str parent-mode)
    "full-auto" "full-auto"
    "plan"      "plan"
    "accept-edits"))

(defn worktree-args
  "Flags for the child run, from the role config."
  [{:keys [model sys allowed steps mode]}]
  (cond-> ["-p" "--output-format" "stream-json" "--no-session"
           "--permission-mode" (child-permission-mode mode)
           "--max-steps" (str (or steps 20)) "--settings" "{\"max-extensions\":0}" "--model" model
           "--append-system-prompt" sys]
    (and (seq allowed) (not (all-tools? allowed)))
    (into ["--tools" (str/join "," allowed)])))

(def ^:private default-timeout-ms (* 30 60 1000))

(def ^:private child-env "NYMA_SUBAGENT_DEPTH")

(defn ^:async read-lines
  "Call `on-line` with each line of `stream` as it arrives. Resolves the last
   non-blank line and a tail of what came, for a child that printed no result."
  [stream on-line]
  (let [reader  (.getReader stream)
        decoder (js/TextDecoder.)
        buf     (atom "")
        lst     (atom nil)
        tail    (atom "")
        take!   (fn [line]
                  (when-not (str/blank? line)
                    (reset! lst line)
                    (reset! tail (let [t (str @tail line "\n")]
                                   (subs t (max 0 (- (count t) 4000)))))
                    (on-line line)))]
    (loop []
      (let [{:keys [done value]} (let [r (js-await (.read reader))]
                                   {:done (.-done r) :value (.-value r)})]
        (when-not done
          (swap! buf str (.decode decoder value #js {:stream true}))
          (let [parts (.split @buf "\n")]
            (reset! buf (.pop parts))
            (doseq [line parts] (take! line)))
          (recur))))
    (take! @buf)
    {:last @lst :tail @tail}))

(defn ^:async run-in-worktree [opts cfg]
  (let [{:keys [api agent-name task model-override max-steps signal command timeout-ms on-progress]} opts
        provider (cfg-get cfg :provider "provider")
        model-id (or model-override (cfg-get cfg :model "model"))
        allowed  (cfg-get cfg :allowed-tools "allowed-tools")]
    (try
      (let [tree (wt/create! (js/process.cwd) (str agent-name "-" (js/Date.now)))
            argv (into (or command (self-command))   ;; :command — a test seam
                       (conj (worktree-args {:model   (if provider (str provider "/" model-id) model-id)
                                             :sys     (or (cfg-get cfg :system-prompt "system-prompt") default-subagent-prompt)
                                             :allowed allowed
                                             :steps   max-steps
                                             :mode    (when api (:permission-mode (.getState api)))})
                             ;; The model writes this: `- fix the bug` is no flag.
                             "--" task))
            proc (js/Bun.spawn (clj->js argv)
                               #js {:cwd (:path tree) :stdin "ignore" :stdout "pipe" :stderr "pipe"
                                    :env (js/Object.assign #js {} js/process.env
                                                           (doto #js {} (aset child-env "1")))
                                    :timeout (or timeout-ms default-timeout-ms) :killSignal "SIGTERM"})
            ;; Cancelling the parent's turn ends the child too.
            kill (fn [] (try (.kill proc) (catch :default _ nil)))
            _    (some-> signal (.addEventListener "abort" kill #js {:once true}))
            spec (if provider (str provider "/" model-id) model-id)
            live (atom {:input 0 :output 0})
            steps (atom 0)
            ;; Tokens as they land, so a budget sees a child it cannot reach
            ;; any other way. The cost comes with the result, once.
            on-line (fn [line]
                      (when-let [m (try (js/JSON.parse line) (catch :default _ nil))]
                        (case (.-type m)
                          "usage" (let [i (or (.-inputTokens m) 0) o (or (.-outputTokens m) 0)]
                                    (swap! live (fn [t] (-> t (update :input + i) (update :output + o))))
                                    (bill-parent! api {:model spec :input i :output o :cost 0
                                                       :cache-read 0 :cache-write 0}))
                          "tool_execution_start"
                          (when on-progress
                            (on-progress (progress-line agent-name (swap! steps inc)
                                                        {:toolName (.-toolName m) :args (.-args m)})))
                          nil)))
            ;; Both pipes drained AT ONCE: reading stdout to EOF first left a
            ;; child that filled the stderr pipe (64 KB of warnings) blocked on
            ;; the write, stdout never closing, and the parent waiting forever.
            [lines err] (js-await (js/Promise.all #js [(read-lines (.-stdout proc) on-line)
                                                       (.text (js/Response. (.-stderr proc)))
                                                       (.-exited proc)]))
            _    (some-> signal (.removeEventListener "abort" kill))
            out  (:tail lines)
            res  (try (js/JSON.parse (:last lines)) (catch :default _ nil))
            ;; Separately guarded: the child's answer is worth returning even
            ;; when closing its checkout fails — which then stays for the user.
            done (try (wt/finish! tree (str "nyma subagent " agent-name ": " (subs (str task) 0 (min 60 (count (str task))))))
                      (catch :default e {:error (str (.-message e)) :path (:path tree) :branch (:branch tree)}))]
        {:agent agent-name
         :ok    (and (some? res) (not (.-is_error res)))
         :status (cond
                   (some-> signal .-aborted) "cancelled"
                   (nil? res)                "failed"
                   (.-is_error res)          "failed"
                   :else                     "done")
         :text  (str (if res
                       (clip-reply (.-result res) nil)
                       (clip-reply (str "child produced no result: " (str/trim (str err out))) nil))
                     "\n\n"
                     (cond
                       (:error done)
                       (str "Could not close the worktree (" (str/trim (:error done)) "); it is left at "
                            (:path done) " on branch " (:branch done) ".")
                       done
                       (str "Worktree branch `" (:branch done) "` (" (:commits done) " commit(s)):\n" (:stat done)
                            "\nMerge with: git merge " (:branch done))
                       :else "No file changes; the worktree was removed."))
         ;; Its --no-session skips nyma.db, so this is the only record of it.
         ;; What `on-line` billed is not billed again.
         :usage {:model  spec
                 :input  (max 0 (- (or (some-> res .-usage .-input_tokens) 0) (:input @live)))
                 :output (max 0 (- (or (some-> res .-usage .-output_tokens) 0) (:output @live)))
                 :cache-read  (or (some-> res .-usage .-cache_read_input_tokens) 0)
                 :cache-write (or (some-> res .-usage .-cache_creation_input_tokens) 0)
                 :cost   (or (some-> res .-total_cost_usd) 0.0)
                 :cost-unknown? (or (nil? (some-> res .-total_cost_usd))
                                    (boolean (some-> res .-total_cost_partial)))}})
      (catch :default e
        {:agent agent-name :ok false :text (str "Subagent " agent-name " (worktree) failed: " (.-message e))}))))

;; ---------------------------------------------------------------------------
;; Core: run one isolated child agent and return its summary + usage
;; ---------------------------------------------------------------------------

(defn- child-usage
  "What a child on `model` spent. :run-cost holds steps of a run that died before
   its usage was folded into the total."
  [st model]
  {:model  model
   :input  (or (:total-input-tokens st) 0)
   :output (or (:total-output-tokens st) 0)
   :cache-read  (or (:total-cache-read-tokens st) 0)
   :cache-write (or (:total-cache-write-tokens st) 0)
   :cost   (+ (or (:total-cost st) 0.0) (or (:run-cost st) 0))
   :cost-unknown? (boolean (:cost-unknown? st))})

;; Under the tool-result policy's 12,000, so its own cut does not take the footer.
(def ^:private reply-limit 11000)

(defn clip-reply
  "A child's reply, bounded. The whole of it stays in the transcript, when there
   is one; the parent's context only pays for what it can use."
  [text transcript]
  (let [s (str text)]
    (if (<= (count s) reply-limit)
      s
      (str (subs s 0 reply-limit) "\n\n[cut " (- (count s) reply-limit) " chars"
           (when transcript "; the rest is in the transcript") "]"))))

(defn- open-transcript!
  "Save `child` as a real session beside the parent's, under subagents/ so the
   session list stays the user's own. `nyma --session <path>` opens it. Written as
   it goes: a child that dies is the one worth reading. Returns the path, nil
   with no parent file (--no-session, ACP)."
  [child api agent-name]
  (when-let [parent (when (aget api "getSessionFile") (.getSessionFile api))]
    (let [dir  (path/join (path/dirname parent) "subagents")
          file (path/join dir (str (path/basename parent ".jsonl") "-" (wt/safe-name agent-name)
                                   "-" (js/Date.now) ".jsonl"))
          sm   (create-session-manager file)]
      (fs/mkdirSync dir #js {:recursive true})
      ((:append sm) {:role "session-meta" :metadata {:cwd (js/process.cwd)}})
      (reset! (:session child) sm)
      (attach-session-persistence! child sm)
      file)))

(defn- remember-child!
  "Keep `id`, dropping the oldest finished child past the limit: each holds a
   whole conversation."
  [id entry]
  (swap! children assoc id entry)
  (when (> (count @children) MAX-KEPT-CHILDREN)
    (when-let [old (first (remove #(= "running" (:status (get @children %))) (keys @children)))]
      (swap! children dissoc old))))

(defn- progress-line
  "One running child's latest step, in a form that fits a tool row."
  [agent-name n data]
  (let [args (or (:args data) {})
        what (or (get args :path) (get args :command) (get args :pattern) (get args :query))
        s    (str agent-name " · step " n " · " (:toolName data) (when what (str " " what)))]
    (if (> (count s) 100) (str (subs s 0 99) "…") s)))

(defn- count-running! [api delta]
  (when-let [dispatch (aget api "dispatchState")]
    (dispatch "subagent-count" {:delta delta})))

(defn- step-bill
  "What one finished step of a child on `model` spent, as `bill-parent!` takes it.
   `rates` is the child's own cost key, which is not always the role's spelling."
  [step model rates]
  (let [u  (.-usage step)
        in (or (some-> u .-inputTokens) 0)
        ot (or (some-> u .-outputTokens) 0)
        cr (or (some-> u .-inputTokenDetails .-cacheReadTokens) 0)
        cw (or (some-> u .-inputTokenDetails .-cacheWriteTokens) 0)]
    {:model model :input in :output ot :cache-read cr :cache-write cw
     :cost (pricing/calculate-turn-cost rates {:input-tokens in :output-tokens ot
                                               :cache-read-tokens cr :cache-write-tokens cw})
     :cost-unknown? (and (pos? in) (not (pricing/priced? rates)))}))

(defn- bill-parent!
  "A child keeps its own totals, so the parent's cost left out all its work."
  [api usage]
  (when-let [dispatch (and api usage (aget api "dispatchState"))]
    (dispatch "extension-usage"
              {:model              (:model usage)
               :input-tokens       (:input usage)
               :output-tokens      (:output usage)
               :cache-read-tokens  (:cache-read usage)
               :cache-write-tokens (:cache-write usage)
               :cost               (:cost usage)
               :cost-unknown?      (:cost-unknown? usage)})))

(defn ^:async run-child
  "Spawn one isolated child agent for `task` under role `agent-name`.
   opts: {:api :settings-mgr :agents :agent-name :task :model-override :max-steps :signal}
   Returns {:agent :ok :status :text :usage} (or {:ok false :text <error>}).
   :status is how it ended: done, step-capped, empty, failed, cancelled or
   timed-out. Only done and step-capped are :ok. An in-process
   child bills the parent step by step, so a budget can stop it; `:billed?` says
   its `:usage` is already counted."
  [opts]
  (let [{:keys [api settings-mgr agents agent-name task model-override max-steps signal timeout-ms on-progress]} opts
        cfg (inherit-model (lookup-agent agents agent-name) api)]
    (cond
      ;; Queued items of a fan-out reach here after the abort: none may start.
      (some-> signal .-aborted)
      {:agent agent-name :ok false :status "cancelled" :text "cancelled"}

      ;; A model that keeps delegating spends without limit: depth caps the
      ;; tree, not its width over a run.
      (and (:max-children opts) (> (swap! spawned inc) (:max-children opts)))
      {:agent agent-name :ok false
       :text (str "Subagent limit reached for this run (" (:max-children opts)
                  "). Finish with what you have; subagent.max-children raises it.")}

      (nil? cfg)
      {:agent agent-name :ok false
       :text (str "Unknown subagent role: " agent-name)}

      ;; Enforce :enabled false (e.g. the editing `worker` role, off by
      ;; default). A disabled role is only runnable if the user explicitly
      ;; sets :enabled true in settings.roles — keeps edits on the main thread.
      ;; In a worktree the edits cannot reach the main thread's files, which
      ;; is the reason it is off, so isolation runs it.
      (= "worktree" (:isolation opts))
      (js-await (run-in-worktree opts cfg))

      (false? (cfg-get cfg :enabled "enabled"))
      {:agent agent-name :ok false
       :text (str "Subagent role \"" agent-name "\" is disabled (:enabled false). "
                  "Enable it in settings.roles to use it.")}

      :else
      (let [provider   (cfg-get cfg :provider "provider")
            model-id   (or model-override (cfg-get cfg :model "model"))
            sys        (or (cfg-get cfg :system-prompt "system-prompt") default-subagent-prompt)
            allowed    (child-tools (cfg-get cfg :allowed-tools "allowed-tools")
                                    (when (aget api "getState") (:permission-mode (.getState api))))
            resolve-fn (aget api "resolveModel")
            model-obj  (when (and resolve-fn provider model-id)
                         (try (resolve-fn provider model-id) (catch :default _e nil)))]
        (if-not model-obj
          {:agent agent-name :ok false
           :text (str "Could not resolve model " provider "/" model-id
                      " for subagent " agent-name)}
          ;; Wrap the whole spawn (create-agent + set-active + run) so a
          ;; failure anywhere returns a structured {:ok false} rather than
          ;; throwing — important under run-parallel, where a thrown error
          ;; would surface as a nil result and a malformed "### " entry.
          (try
            (let [child (create-agent {:model         model-obj
                                       :system-prompt sys
                                       :max-steps     (or max-steps 20)
                                       :settings      settings-mgr})]
              ;; Rates are keyed provider/model: without this a child's lookup
              ;; missed, and a paid model cost 0.
              (aset (:config child) "active-provider-name" provider)
              (gate-child! child api agent-name)
              ;; Children never receive the `subagent` tool → depth capped at 1.
              (when allowed
                ((:set-active (:tool-registry child)) (set allowed)))
              ;; Caught here, not below: a failed child spent money too. The
              ;; controller is read at abort time: `run` replaces it on entry.
              (if-let [missing (seq (remove (set (keys ((:all (:tool-registry child))))) allowed))]
                ;; A child has builtins only. It would run without these and
                ;; never say so, spending money on the wrong answer.
                {:agent agent-name :ok false
                 :text (str "Subagent " agent-name " cannot have: " (str/join ", " missing)
                            ". A subagent has builtin tools only, not MCP or extension tools.")}
              (let [transcript (open-transcript! child api agent-name)
                    id    (str (wt/safe-name agent-name) "-" (swap! child-counter inc))
                    _     ((:on (:events child)) "turn_end"
                                                 (fn [step _]
                                                   (bill-parent! api (step-bill step (str provider "/" model-id)
                                                                                (pricing/model-cost-key (:config child))))))
                    steps (atom 0)
                    _     ((:on (:events child)) "tool_execution_start"
                                                 (fn [d]
                                                   (let [line (progress-line agent-name (swap! steps inc) d)]
                                                     (swap! children assoc-in [id :progress] line)
                                                     (when on-progress (on-progress line)))))
                    capped? (atom false)
                    _     ((:on (:events child)) "turn_finalize"
                                                 (fn [d] (when (.-stepCapped d) (reset! capped? true)) nil))
                    ;; One run of this child: its task, then each message to it.
                    drive
                    ^:async
                    (fn [prompt sig]
                      (reset! capped? false)
                      (swap! children assoc-in [id :status] "running")
                      (let [stop  (fn [] (.abort @(:abort-controller child) (.-reason sig)))
                            _     (some-> sig (.addEventListener "abort" stop #js {:once true}))
                            late? (atom false)
                            clock (js/setTimeout (fn []
                                                   (reset! late? true)
                                                   (.abort @(:abort-controller child) "timed out"))
                                                 (or timeout-ms default-timeout-ms))
                            _     (count-running! api 1)
                            err   (try (js-await (run child prompt)) nil
                                       (catch :default e e))
                            _     (count-running! api -1)
                            _     (js/clearTimeout clock)
                            _     (some-> sig (.removeEventListener "abort" stop))
                            gone  (some-> sig .-aborted)
                            st    @(:state child)
                            said  (final-text (:messages st))
                            status (cond
                                     @late?            "timed-out"
                                     gone              "cancelled"
                                     err               "failed"
                                     (str/blank? said) "empty"
                                     @capped?          "step-capped"
                                     :else             "done")]
                        (swap! children assoc-in [id :status] status)
                        {:agent agent-name :id id :ok (contains? #{"done" "step-capped"} status)
                         :status status
                         :text  (case status
                                  "cancelled" "cancelled"
                                  "failed"    (str "Subagent " agent-name " failed: " (.-message err))
                                  "empty"     (str "Subagent " agent-name " ended without a reply.")
                                  (clip-reply said transcript))
                         :transcript transcript
                         :usage (child-usage st (str provider "/" model-id))
                         :billed? true}))]
                (remember-child! id {:child child :drive drive :label task})
                (js-await (drive task signal)))))
            (catch :default e
              {:agent agent-name :ok false
               :text (str "Subagent " agent-name " failed: " (.-message e))})))))))

(defn ^:async run-isolated-agent
  "`run-child`, with what it spent added to the parent's totals."
  [opts]
  (let [r (js-await (run-child opts))]
    (when-not (:billed? r) (bill-parent! (:api opts) (:usage r)))
    r))

;; ---------------------------------------------------------------------------
;; Result formatting
;; ---------------------------------------------------------------------------

(defn- format-result [r]
  (str "### " (:agent r) (when-let [id (:id r)] (str " [" id "]"))
       (case (:status r)
         "step-capped" " (STEP CAP: may be unfinished)"
         "timed-out"   " (TIMED OUT: may be unfinished)"
         "cancelled"   " (CANCELLED)"
         "empty"       " (EMPTY)"
         (when-not (:ok r) " (FAILED)"))
       "\n" (:text r)
       (when-let [t (:transcript r)] (str "\n\nTranscript: " t))))

(defn- format-results [results]
  (str/join "\n\n" (map format-result results)))

;; ---------------------------------------------------------------------------
;; Execution modes
;; ---------------------------------------------------------------------------

(defn ^:async run-single [base agent-name task model-override]
  (js-await (run-isolated-agent (assoc base :agent-name agent-name
                                       :task task :model-override model-override))))

(defn ^:async run-single-list
  "run-single wrapped to a one-element vector so format-results is uniform."
  [base agent-name task model-override]
  [(js-await (run-single base agent-name task model-override))])

(defn steps-arg
  "A caller-supplied step budget as a positive integer, else nil (the
   role default applies). Borrowed from apprentice's `turns` argument: the
   parent sizes the budget to the task, and a child out of steps still
   reports what it has (loop step-cap-report)."
  [v & [ceiling]]
  (let [n (js/Number v)]
    (when (and (js/Number.isFinite n) (pos? n))
      (let [n (js/Math.floor n)]
        (if (and (number? ceiling) (pos? ceiling)) (min n ceiling) n)))))

(defn steps-ceiling
  "The most steps a caller may hand a child: `subagent.max-steps` if set, else
   the run's own `max-steps` (default 100). The value comes from the model,
   so it is bounded by something the USER set — a hallucinated or injected
   `steps: 100000` must not buy a child a budget the parent never had."
  [settings]
  (let [sub (:subagent settings)]
    (or (:max-steps sub) (and sub (get sub "max-steps"))
        (:max-steps settings) 100)))

(defn- with-item-steps
  "A per-item `steps` overrides the call-level budget already in `base`."
  [base t]
  (if-let [n (steps-arg (or (.-steps t) (get t "steps")) (:steps-ceiling base))]
    (assoc base :max-steps n)
    base))

(defn ^:async run-parallel-item
  "Run one fan-out task. Top-level (Squint: no inline async fns)."
  [base t _i]
  (js-await (run-isolated-agent
             (assoc (with-item-steps base t)
                    :agent-name (or (.-agent t) (get t "agent"))
                    :task (or (.-task t) (get t "task"))))))

(defn ^:async run-parallel [base tasks]
  (let [items (vec tasks)]
    (if (> (count items) MAX-PARALLEL)
      [{:agent "" :ok false
        :text (str "Too many parallel tasks (" (count items) " > " MAX-PARALLEL ")")}]
      ;; map-with-limit takes a SYNC fn returning a promise.
      (js-await
       (conc/map-with-limit
        items MAX-CONCURRENCY
        (fn [t i] (run-parallel-item base t i)))))))

(defn ^:async run-chain-step
  "Sequential chain via async recursion ('{previous}' = prior output).
   Avoids loop/recur + js-await."
  [base items i prev acc]
  (if (>= i (count items))
    acc
    (let [step (nth items i)
          a    (or (.-agent step) (get step "agent"))
          raw  (or (.-task step) (get step "task"))
          t    (str/replace (str raw) "{previous}" prev)
          r    (js-await (run-isolated-agent
                          (assoc (with-item-steps base step) :agent-name a :task t)))]
      (if (:ok r)
        (js-await (run-chain-step base items (inc i) (:text r) (conj acc r)))
        (conj acc r)))))

(defn ^:async run-chain [base steps]
  (js-await (run-chain-step base (vec steps) 0 "" [])))

;; ---------------------------------------------------------------------------
;; Async job handling (MVP): run in background, deliver result as follow-up
;; ---------------------------------------------------------------------------

(defn ^:async start-async-job
  "`ctrl` is the job's own: it outlives the turn that started it, so the turn's
   abort must not reach it. `cancel-job` does."
  [api run-thunk label ctrl]
  (let [id (str "job-" (swap! job-counter inc))]
    (swap! jobs assoc id {:status "running" :label label :result nil :ctrl ctrl})
    ;; fire-and-forget; deliver completion via follow-up message
    (-> (run-thunk)
        (.then (fn [results]
                 (let [text (format-results results)
                       gone (.. ctrl -signal -aborted)]
                   (swap! jobs assoc id {:status (if gone "cancelled" "done") :label label :result text})
                   (when-let [send (when-not gone (aget api "sendUserMessage"))]
                     (send (str "[subagent " id " complete] " label "\n\n" text)
                           #js {:deliverAs "followUp"})))))
        (.catch (fn [e]
                  (swap! jobs assoc id {:status "error" :label label
                                        :result (str (.-message e))})
                  ;; A parent told only of successes takes silence for work.
                  (when-let [send (aget api "sendUserMessage")]
                    (send (str "[subagent " id " failed] " label "\n\n" (.-message e))
                          #js {:deliverAs "followUp"})))))
    (str "Started background subagent " id " (" label "). "
         "Use the subagent tool with action=\"status\" to check on it.")))

(defn ^:async message-child
  "A running child gets `text` at its next step. A finished one takes it as a new
   task, with everything it already knows: the way to retry or follow up."
  [id text signal]
  (let [e (get @children id)]
    (cond
      (nil? e)                  (str "No subagent " id ".")
      (str/blank? (str text))   "subagent: action=message needs a task."
      (= "running" (:status e)) (do (steer (:child e) {:role "user" :content text})
                                    (str "Sent to " id "."))
      :else (format-results [(js-await ((:drive e) text signal))]))))

(defn- cancel-job [id]
  (let [j (get @jobs id)
        c (get @children id)]
    (cond
      (and c (= "running" (:status c)))
      (do (.abort @(:abort-controller (:child c)) "cancelled") (str "Cancelling " id "."))
      (nil? j) (str "No subagent job " id ".")
      (not= "running" (:status j)) (str id " is already " (:status j) ".")
      :else (do (.abort (:ctrl j)) (str "Cancelling " id ".")))))

(defn- children-status []
  (map (fn [[id c]]
         (str id ": " (:status c) " — " (let [l (str (:label c))] (subs l 0 (min 60 (count l))))
              (when (and (= "running" (:status c)) (:progress c)) (str "\n" (:progress c)))))
       @children))

(defn- jobs-status []
  (let [js @jobs
        kids (children-status)]
    (if (and (empty? js) (empty? kids))
      "No subagent jobs."
      (str/join "\n\n"
                (concat
                 kids
                 (map (fn [[id j]]
                       (str id ": " (:status j) " — " (:label j)
                            ;; Surface the result so `status` can actually
                            ;; recover output if the follow-up was missed.
                            (when-let [r (:result j)]
                              (str "\n" (if (> (count r) 4000) (str (subs r 0 4000) " …") r)))))
                      js))))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(def ^:private task-item
  "One {agent, task, steps?} entry of a fan-out or chain."
  {:type "object"
   :properties {:agent {:type "string"} :task {:type "string"} :steps {:type "integer"}}})

(def tool-fields
  "The subagent tool's parameters as `agent.schema` data (all optional: the
   mode is picked by which keys are present)."
  {:agent  [:string {:optional true :doc "Subagent role name for single mode (e.g. scout, planner, reviewer, researcher)."}]
   :task   [:string {:optional true :doc "Task to delegate (single mode)."}]
   :tasks  [:array {:optional true :items :object
                    :doc "Parallel fan-out: array of {agent, task, steps?}. Use for INDEPENDENT read-only work."}]
   :chain  [:array {:optional true :items :object
                    :doc "Sequential: array of {agent, task, steps?}; '{previous}' in a task is replaced by the prior step's output."}]
   :steps  [:integer {:optional true
                      :doc "Tool-call budget per subagent (default 20). Size it to the task: 3-4 for a lookup or a question about one file, 8-12 for a change spanning several. A subagent out of steps still reports what it has."}]
   :action [:enum ["run" "status" "cancel" "message"] {:optional true :doc "run (default), status (list subagents and background jobs), cancel (stop the one named by `job`), or message (send `task` to the subagent named by `job`)."}]
   :job    [:string {:optional true :doc "For cancel and message: a subagent id from a result header (scout-3) or a background job id (job-1, cancel only)."}]
   :async  [:boolean {:optional true :doc "Run in background; result is delivered as a follow-up message."}]
   :model  [:string {:optional true :doc "Optional model id override (single mode)."}]
   :isolation [:enum ["worktree"] {:optional true :doc "worktree: each subagent works in its own git worktree (a separate checkout on branch nyma/<name>); its changes come back as that branch, never in your files. Use it to let a subagent EDIT (e.g. the worker role), or to run editing subagents in parallel."}]})

(def ^:private tool-parameters
  ;; The item shape of tasks/chain is richer than the data form says
  ;; (nested object properties), so it is patched in after rendering.
  (clj->js (-> (schema/->json-schema tool-fields)
               (assoc-in [:properties "tasks" :items] task-item)
               (assoc-in [:properties "chain" :items] task-item))))

(defn ^:async tool-execute [api args & [signal on-progress raw?]]
  ;; action=status is a pure in-memory read — short-circuit BEFORE touching
  ;; the settings manager / agent .md files (synchronous disk IO), so a
  ;; status poll loop doesn't re-read settings + scan agent dirs each call.
  (case (.-action args)
    "status" (jobs-status)
    "cancel" (cancel-job (.-job args))
    "message" (js-await (message-child (.-job args) (.-task args) signal))
    (let [settings-mgr (live-settings api)
          agents       (all-agents settings-mgr)
          ceiling      (steps-ceiling ((:get settings-mgr)))
          base         (cond-> {:api api :settings-mgr settings-mgr :agents agents
                                :steps-ceiling ceiling :isolation (.-isolation args) :signal signal
                                :timeout-ms (when-let [s (get (:subagent ((:get settings-mgr))) "timeout-seconds")]
                                              (* 1000 s))
                                :max-children (or (get (:subagent ((:get settings-mgr))) "max-children") 16)}
                         (steps-arg (.-steps args) ceiling)
                         (assoc :max-steps (steps-arg (.-steps args) ceiling)))
          async?       (let [s    ((:get settings-mgr))
                             sub  (:subagent s)
                             dflt (boolean (or (:async-by-default sub)
                                               (and sub (get sub "async-by-default"))))]
                         (if (nil? (.-async args)) dflt (boolean (.-async args))))
          ctrl         (when async? (js/AbortController.))
          ;; A background job outlives its tool row.
          base         (cond-> base
                         ctrl (assoc :signal (.-signal ctrl))
                         (and on-progress (not ctrl)) (assoc :on-progress on-progress))]
      (cond
      ;; parallel fan-out
        (.-tasks args)
        (let [thunk (fn [] (run-parallel base (.-tasks args)))]
          (if async?
            (js-await (start-async-job api thunk "parallel" ctrl))
            (format-results (js-await (thunk)))))

      ;; sequential chain
        (.-chain args)
        (let [thunk (fn [] (run-chain base (.-chain args)))]
          (if async?
            (js-await (start-async-job api thunk "chain" ctrl))
            (format-results (js-await (thunk)))))

      ;; single
        (and (.-agent args) (.-task args))
        (let [thunk (fn [] (run-single-list base (.-agent args) (.-task args) (.-model args)))]
          (cond
            async? (js-await (start-async-job api thunk (.-agent args) ctrl))
            raw?   (first (js-await (thunk)))
            :else  (format-results (js-await (thunk)))))

        :else
        "subagent: provide {agent, task}, or tasks:[...], or chain:[...], or action:\"status\"."))))

;; ---------------------------------------------------------------------------
;; /agents command
;; ---------------------------------------------------------------------------

(defn- role-summary
  "The roles that can run, for the tool's description."
  [agents]
  (str/join ", " (keep (fn [[k cfg]]
                         (when-not (false? (cfg-get cfg :enabled "enabled"))
                           (let [d (cfg-get cfg :description "description")]
                             (str k (when (seq d) (str " (" d ")"))))))
                       agents)))

(defn- format-agent-list [agents]
  (str/join "\n"
            (map (fn [[k cfg]]
                   (str "  " (str k) " — " (or (cfg-get cfg :description "description") "")
                        " [" (or (cfg-get cfg :model "model") "?") "]"))
                 agents)))

;; ---------------------------------------------------------------------------
;; Activate
;; ---------------------------------------------------------------------------

(defn ^:export default [api]
  (when (aget api "on")
    (.on api "agent_start" (fn [_ _] (reset! spawned 0) nil)))
  ;; A worktree child is a whole nyma: without this it could delegate too.
  (when-not (aget js/process.env child-env)
  (.registerTool
   api "subagent"
   #js {;; Delegation, not a file or shell action of its own: every tool a
        ;; subagent runs goes through this session's gate.
        :safety #js {:long-running? true :capabilities #js ["execution"] :category "meta"}
        :description
        (str "Delegate to context-isolated subagents (built on roles). "
             "Roles: " (role-summary (all-agents (live-settings api))) ". "
             "A role is READ-ONLY unless it lists other tools; use one for exploration, "
             "planning, review, research. Keep code EDITS on the main thread (editing "
             "subagents fragment shared state). Each subagent costs a whole context: one "
             "for a lookup, parallel only for genuinely independent reads, and do it "
             "yourself when you already know the file. "
             "Modes: single {agent, task}; parallel {tasks:[{agent,task}]} for INDEPENDENT "
             "read-only work; chain {chain:[{agent,task}]} ('{previous}' = prior output). "
             "Only the subagent's final summary returns; its intermediate tool calls stay "
             "isolated. A subagent starts with NO memory of this conversation: make every "
             "task self-contained (absolute paths, exactly what to find or change, any "
             "context it needs). To follow up or retry, use action:\"message\" with job "
             "(the id in its result header) and task; it keeps what it learned. Ask for findings with file paths and line numbers, not "
             "narration. Send independent tasks together, but never give two of them the "
             "same file to edit. `steps` sizes each subagent's tool budget to the task. "
             "async:true runs in the background. action:\"status\" lists jobs; "
             "action:\"cancel\" with job stops one, and message steers one running. "
             "isolation:\"worktree\" gives each subagent its own git checkout so it may edit "
             "safely, even in parallel; its work returns as a branch to merge.")
        :parameters tool-parameters
        ;; ctx.raw is for another extension calling this directly: the result
        ;; map rather than text, so it need not parse a header. The model has
        ;; no way to set it.
        :execute (fn [args ctx] (tool-execute api args (some-> ctx .-abortSignal) (some-> ctx .-onUpdate)
                                              (some-> ctx .-raw)))}))

  (.registerCommand
   api "agents"
   #js {:description "List subagent roles, and the subagents of this session"
        :handler (fn [_args ctx]
                   (let [sm (live-settings api)
                         agents (all-agents sm)]
                     (.notify (.-ui ctx)
                              (str "Available subagents:\n" (format-agent-list agents)
                                   (when (or (seq @children) (seq @jobs))
                                     (str "\n\nThis session:\n" (jobs-status))))
                              "info")))})

  (.registerCommand
   api "worktrees"
   #js {:description "List nyma's git worktrees; /worktrees rm <name> removes one (its branch too, if merged)"
        :handler (fn [args ctx]
                   (let [notify (fn [m l] (some-> ctx .-ui (.notify m l)))
                         [sub nm] (vec args)]
                     (if (= sub "rm")
                       (case (wt/remove! (js/process.cwd) (str nm))
                         "merged" (notify (str "Removed worktree " nm " and its merged branch.") "info")
                         "kept"   (notify (str "Removed worktree " nm "; branch nyma/" (wt/safe-name nm)
                                               " is not merged, so it was kept.") "warning")
                         (notify (str "No worktree named " nm ".") "warning"))
                       (let [all (wt/list-all (js/process.cwd))]
                         (notify (if (seq all)
                                   (str "Worktrees:\n" (str/join "\n" (map #(str "  " (:branch %) "  " (:path %)) all)))
                                   "No nyma worktrees. Start one with nyma --worktree <name>.")
                                 "info")))))})

  ;; deactivate
  (fn []
    (.unregisterTool api "subagent")
    (.unregisterCommand api "agents")
    (.unregisterCommand api "worktrees")))
