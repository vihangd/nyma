(ns agent.extensions.agent-shell.api
  "Programmatic entrypoint for non-interactive ACP agent invocation.

   The interactive UI drives ACP via the input router, with stream callbacks
   set on global atoms before each prompt. The gateway (and any future
   sub-agent feature) needs to drive ACP without a UI and run multiple prompts
   in parallel — different conversations targeting different projects.

   `run-prompt` resolves the agent + cwd to a pooled connection (spawning a
   fresh subprocess on first hit per `[agent, cwd]` pair), wires per-connection
   callbacks so streams don't clobber each other, sends the prompt, and clears
   callbacks on completion.

   ─── Usage ────────────────────────────────────────────────────────────────

     (run-prompt api
       {:agent       \"claude\"
        :cwd         \"/Users/x/projects/vyom\"
        :prompt      \"list files\"
        :on-stream   (fn [text] ...)})  ;; optional
     ;; → Promise<{:text :usage :tool-calls :stop-reason}>"
  (:require [agent.extensions.agent-shell.acp.pool :as pool]
            [agent.extensions.agent-shell.acp.client :as client]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.shared :as shared]))

(defn- resolve-agent-def
  "Look up an agent definition by key. Accepts strings (\"claude\") or keywords."
  [agent-key]
  (let [normalized (shared/kw-name agent-key)]
    (registry/get-agent normalized)))

(def ^:private lanes
  "Promise-chain serializer per pool-key. ACP sessions support exactly one
   in-flight `session/prompt` at a time, and the per-conn `:callbacks` slot
   is single-valued, so two `run-prompt` calls targeting the same (agent, cwd)
   would race: the second clobbers the first's on-stream callback before its
   prompt completes, and the agent would receive two overlapping prompts on
   one session. We chain each call onto a per-pool-key promise so calls
   complete in arrival order. Different (agent, cwd) pairs have independent
   lanes — cross-project work still runs in parallel."
  (atom {}))

(defn- enqueue-on-lane
  "Chain `f` (a thunk returning a promise) onto the lane for `pool-key`.
   Recovers the lane from any prior rejection so one failed call doesn't
   poison the queue. Returns the promise that resolves when `f` completes."
  [pool-key f]
  (let [new-state (swap! lanes update pool-key
                         (fn [prior]
                           (-> (or prior (js/Promise.resolve nil))
                               (.catch (fn [_] nil))
                               (.then (fn [_] (f))))))]
    (get new-state pool-key)))

(defn run-prompt
  "Non-interactive ACP invocation. Returns a Promise that resolves to
   `{:text :usage :tool-calls :stop-reason}` or rejects on error.

   Required opts:
     :agent   — agent registry key (string or keyword, e.g. \"claude\")
     :prompt  — user prompt text

   Optional opts:
     :cwd        — project directory; defaults to (js/process.cwd)
     :on-stream  — (fn [text-delta]) called for each text chunk
     :on-thought — (fn [text]) called for thinking/reasoning chunks
     :on-plan    — (fn [plan-data]) called when the agent emits a plan
     :on-tool    — (fn [{:type :id :title :status :content}]) tool-call events
     :timeout-ms — per-prompt timeout (default 600s, matches send-prompt)"
  [api opts]
  (let [{:keys [agent prompt cwd on-stream on-thought on-plan on-tool timeout-ms]} opts
        agent-def (resolve-agent-def agent)]
    (cond
      (not agent-def)
      (js/Promise.reject (js/Error. (str "Unknown agent: " agent)))

      (or (not (string? prompt)) (zero? (count prompt)))
      (js/Promise.reject (js/Error. "prompt is required and must be a non-empty string"))

      :else
      (let [resolved-cwd (or cwd (js/process.cwd))
            pool-key     (shared/pool-key (shared/kw-name agent) resolved-cwd)]
        (-> (pool/get-or-create (shared/kw-name agent) agent-def api resolved-cwd)
            (.then
             (fn [conn]
               (enqueue-on-lane
                pool-key
                (fn []
                  (reset! (:callbacks conn)
                          {:on-stream  on-stream
                           :on-thought on-thought
                           :on-plan    on-plan
                           :on-tool    on-tool})
                  (-> (client/send-prompt conn prompt timeout-ms)
                      (.finally (fn [] (reset! (:callbacks conn) nil)))))))))))))

(defn disconnect-project
  "Disconnect a single (agent, cwd) ACP worker. Used by gateway idle eviction.
   No-op if no matching connection exists."
  [agent cwd]
  (pool/disconnect (shared/kw-name agent) cwd))

(defn list-pool
  "Return a snapshot of live pool entries as `[{:agent :cwd :session-id} ...]`.
   Useful for status/admin tools."
  []
  (->> @shared/connections
       (keep (fn [[k v]]
               (when (and (string? k) (map? v))
                 (let [idx (.indexOf k "@")]
                   (when (>= idx 0)
                     {:agent      (subs k 0 idx)
                      :cwd        (subs k (inc idx))
                      :session-id (when-let [sid-atom (:session-id v)] @sid-atom)})))))
       vec))
