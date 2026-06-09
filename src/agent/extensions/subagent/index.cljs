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
     - child :session stays nil       → no writes to parent JSONL/SQLite
     - only the final summary returns → child tool calls stay in the child"
  (:require [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions.subagent.concurrency :as conc]
            [agent.extensions.subagent.agents-md :as agents-md]
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

;; ---------------------------------------------------------------------------
;; Role / agent resolution
;; ---------------------------------------------------------------------------

;; Built-in subagent roles owned by THIS extension. settings.json merges
;; its :roles SHALLOWLY (a user roles map REPLACES the defaults), so we
;; cannot rely on settings/manager defaults for these — define them here
;; and merge defaults < settings.roles < markdown so they always exist
;; while remaining user-overridable. Read-only by default (evidence
;; boundary: single-threaded edits + isolated read-only exploration).
(def ^:private default-subagent-roles
  {:scout      {:provider "anthropic" :model "claude-haiku-4-20250901"
                :allowed-tools ["read" "glob" "grep" "ls"]
                :description "Fast read-only codebase recon. Returns a compressed summary."
                :system-prompt "You are a scout. Investigate the codebase read-only and return a compressed, structured summary (files, symbols, where things live). Do not propose edits."}
   :planner    {:provider "anthropic" :model "claude-opus-4-20250514"
                :allowed-tools ["read" "glob" "grep" "ls" "web_search" "web_fetch"]
                :description "Read-only implementation planner. Returns a numbered plan."
                :system-prompt "You are a planner. Analyze read-only and return a detailed numbered plan under a 'Plan:' header. Do not modify files."}
   :reviewer   {:provider "anthropic" :model "claude-sonnet-4-20250514"
                :allowed-tools ["read" "glob" "grep" "ls"]
                :description "Read-only code reviewer. Returns findings, one per line."
                :system-prompt "You are a reviewer. Examine the code read-only and return concise findings (path:line — problem — fix). Do not modify files."}
   :researcher {:provider "anthropic" :model "claude-sonnet-4-20250514"
                :allowed-tools ["read" "web_search" "web_fetch"]
                :description "Web/docs research. Returns sourced findings."
                :system-prompt "You are a researcher. Gather information from docs/web and return a sourced summary with URLs. Do not modify files."}})

(defn- all-agents
  "Merge built-in subagent roles < settings.roles < markdown agents
   (.nyma/agents). Later sources override earlier. Returns
   {keyword-or-string -> role-config}."
  [settings-mgr]
  (let [settings ((:get settings-mgr))
        roles    (or (:roles settings) {})
        md       (agents-md/load-agents)]
    (merge default-subagent-roles roles md)))

(defn- lookup-agent
  "Find a role-config by name. Squint: keywords ARE strings, so a single
   lookup covers keyword (settings roles) and string (markdown) keys."
  [agents nm]
  (or (get agents nm) (get agents (str nm))))

(defn- cfg-get
  "Read a key from a role-config, tolerating CLJS keyword (== string in
   Squint) and JS-object string keys. `kw` is the keyword, `kstr` the
   plain string form (avoid (name kw) — bare `name` is undefined here)."
  [cfg kw kstr]
  (or (get cfg kw) (get cfg kstr)))

(defn final-text
  "Extract the last ASSISTANT message's text from a child's messages.
   Filters by role (not just `last`) so a child that ends on a tool step
   returns its summary, not a trailing tool result."
  [messages]
  (let [a (last (filter #(= "assistant" (or (:role %) (get % "role"))) messages))
        c (when a (or (:content a) (get a "content")))]
    (cond
      (nil? a) ""
      (string? c) c
      (and (js/Array.isArray c) (pos? (count c)))
      (let [parts (filter #(= "text" (or (.-type %) (get % "type"))) c)]
        (str/join "\n" (map #(or (.-text %) (get % "text")) parts)))
      :else (str c))))

;; ---------------------------------------------------------------------------
;; Core: run one isolated child agent and return its summary + usage
;; ---------------------------------------------------------------------------

(defn ^:async run-isolated-agent
  "Spawn one isolated child agent for `task` under role `agent-name`.
   opts: {:api :settings-mgr :agents :agent-name :task :model-override :max-steps}
   Returns {:agent :ok :text :usage} (or {:ok false :text <error>})."
  [opts]
  (let [{:keys [api settings-mgr agents agent-name task model-override max-steps]} opts
        cfg (lookup-agent agents agent-name)]
    (cond
      (nil? cfg)
      {:agent agent-name :ok false
       :text (str "Unknown subagent role: " agent-name)}

      ;; Enforce :enabled false (e.g. the editing `worker` role, off by
      ;; default). A disabled role is only runnable if the user explicitly
      ;; sets :enabled true in settings.roles — keeps edits on the main thread.
      (false? (cfg-get cfg :enabled "enabled"))
      {:agent agent-name :ok false
       :text (str "Subagent role \"" agent-name "\" is disabled (:enabled false). "
                  "Enable it in settings.roles to use it.")}

      :else
      (let [provider   (cfg-get cfg :provider "provider")
            model-id   (or model-override (cfg-get cfg :model "model"))
            sys        (or (cfg-get cfg :system-prompt "system-prompt") default-subagent-prompt)
            allowed    (cfg-get cfg :allowed-tools "allowed-tools")
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
              ;; Restrict to the role's read-only (by default) tool set.
              ;; Children never receive the `subagent` tool → depth capped at 1.
              (when (seq allowed)
                ((:set-active (:tool-registry child)) (set allowed)))
              (js-await (run child task))
              (let [st   @(:state child)
                    msgs (:messages st)]
                {:agent agent-name :ok true
                 :text  (final-text msgs)
                 :usage {:input  (or (:total-input-tokens st) 0)
                         :output (or (:total-output-tokens st) 0)
                         :cost   (or (:total-cost st) 0.0)}}))
            (catch :default e
              {:agent agent-name :ok false
               :text (str "Subagent " agent-name " failed: " (.-message e))})))))))

;; ---------------------------------------------------------------------------
;; Result formatting
;; ---------------------------------------------------------------------------

(defn- format-result [r]
  (str "### " (:agent r) (when-not (:ok r) " (FAILED)") "\n" (:text r)))

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

(defn ^:async run-parallel-item
  "Run one fan-out task. Top-level (Squint: no inline async fns)."
  [base t _i]
  (js-await (run-isolated-agent
             (assoc base
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
                          (assoc base :agent-name a :task t)))]
      (if (:ok r)
        (js-await (run-chain-step base items (inc i) (:text r) (conj acc r)))
        (conj acc r)))))

(defn ^:async run-chain [base steps]
  (js-await (run-chain-step base (vec steps) 0 "" [])))

;; ---------------------------------------------------------------------------
;; Async job handling (MVP): run in background, deliver result as follow-up
;; ---------------------------------------------------------------------------

(defn ^:async start-async-job [api run-thunk label]
  (let [id (str "job-" (swap! job-counter inc))]
    (swap! jobs assoc id {:status "running" :label label :result nil})
    ;; fire-and-forget; deliver completion via follow-up message
    (-> (run-thunk)
        (.then (fn [results]
                 (let [text (format-results results)]
                   (swap! jobs assoc id {:status "done" :label label :result text})
                   (when-let [send (aget api "sendUserMessage")]
                     (send (str "[subagent " id " complete] " label "\n\n" text)
                           #js {:deliverAs "followUp"})))))
        (.catch (fn [e]
                  (swap! jobs assoc id {:status "error" :label label
                                        :result (str (.-message e))}))))
    (str "Started background subagent " id " (" label "). "
         "Use the subagent tool with action=\"status\" to check on it.")))

(defn- jobs-status []
  (let [js @jobs]
    (if (empty? js)
      "No subagent jobs."
      (str/join "\n\n"
                (map (fn [[id j]]
                       (str id ": " (:status j) " — " (:label j)
                            ;; Surface the result so `status` can actually
                            ;; recover output if the follow-up was missed.
                            (when-let [r (:result j)]
                              (str "\n" (if (> (count r) 4000) (str (subs r 0 4000) " …") r)))))
                     js)))))

;; ---------------------------------------------------------------------------
;; Tool
;; ---------------------------------------------------------------------------

(def ^:private tool-parameters
  (clj->js
   {:type "object"
    :properties
    {:agent  {:type "string" :description "Subagent role name for single mode (e.g. scout, planner, reviewer, researcher)."}
     :task   {:type "string" :description "Task to delegate (single mode)."}
     :tasks  {:type "array"
              :description "Parallel fan-out: array of {agent, task}. Use for INDEPENDENT read-only work."
              :items {:type "object"
                      :properties {:agent {:type "string"} :task {:type "string"}}}}
     :chain  {:type "array"
              :description "Sequential: array of {agent, task}; '{previous}' in a task is replaced by the prior step's output."
              :items {:type "object"
                      :properties {:agent {:type "string"} :task {:type "string"}}}}
     :action {:type "string" :enum ["run" "status"]
              :description "run (default) or status (list background jobs)."}
     :async  {:type "boolean" :description "Run in background; result is delivered as a follow-up message."}
     :model  {:type "string" :description "Optional model id override (single mode)."}}
    :required []}))

(defn ^:async tool-execute [api args]
  ;; action=status is a pure in-memory read — short-circuit BEFORE touching
  ;; the settings manager / agent .md files (synchronous disk IO), so a
  ;; status poll loop doesn't re-read settings + scan agent dirs each call.
  (if (= (.-action args) "status")
    (jobs-status)
    (let [settings-mgr (create-settings-manager)
          agents       (all-agents settings-mgr)
          base         {:api api :settings-mgr settings-mgr :agents agents}
          async?       (let [s    ((:get settings-mgr))
                             sub  (or (:subagent s) (get s "subagent"))
                             dflt (boolean (or (:async-by-default sub)
                                               (and sub (get sub "async-by-default"))))]
                         (if (nil? (.-async args)) dflt (boolean (.-async args))))]
      (cond
      ;; parallel fan-out
      (.-tasks args)
      (let [thunk (fn [] (run-parallel base (.-tasks args)))]
        (if async?
          (js-await (start-async-job api thunk "parallel"))
          (format-results (js-await (thunk)))))

      ;; sequential chain
      (.-chain args)
      (let [thunk (fn [] (run-chain base (.-chain args)))]
        (if async?
          (js-await (start-async-job api thunk "chain"))
          (format-results (js-await (thunk)))))

      ;; single
      (and (.-agent args) (.-task args))
      (let [thunk (fn [] (run-single-list base (.-agent args) (.-task args) (.-model args)))]
        (if async?
          (js-await (start-async-job api thunk (.-agent args)))
          (format-results (js-await (thunk)))))

      :else
      "subagent: provide {agent, task}, or tasks:[...], or chain:[...], or action:\"status\"."))))

;; ---------------------------------------------------------------------------
;; /agents command
;; ---------------------------------------------------------------------------

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
  (.registerTool
   api "subagent"
   #js {:description
        (str "Delegate to context-isolated subagents (built on roles). "
             "Default subagent roles are READ-ONLY (scout/planner/reviewer/researcher) "
             "— use for exploration, planning, review, research. Keep code EDITS on the "
             "main thread (editing subagents fragment shared state). "
             "Modes: single {agent, task}; parallel {tasks:[{agent,task}]} for INDEPENDENT "
             "read-only work; chain {chain:[{agent,task}]} ('{previous}' = prior output). "
             "Only the subagent's final summary returns; its intermediate tool calls stay "
             "isolated. async:true runs in the background. action:\"status\" lists jobs.")
        :parameters tool-parameters
        :execute (fn [args] (tool-execute api args))})

  (.registerCommand
   api "agents"
   #js {:description "List available subagent roles"
        :handler (fn [_args ctx]
                   (let [sm (create-settings-manager)
                         agents (all-agents sm)]
                     (.notify (.-ui ctx)
                              (str "Available subagents:\n" (format-agent-list agents))
                              "info")))})

  ;; deactivate
  (fn []
    (.unregisterTool api "subagent")
    (.unregisterCommand api "agents")))
