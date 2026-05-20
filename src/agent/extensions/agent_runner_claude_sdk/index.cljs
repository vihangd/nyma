(ns agent.extensions.agent-runner-claude-sdk.index
  "In-process Claude Agent SDK runner for nyma.
   Registers :claude-sdk using @anthropic-ai/claude-agent-sdk (bundled binary).
   conn-map shape matches the ACP path so all UI plumbing works unchanged."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.agents.registry :as registry]
            [agent.extensions.agent-shell.acp.pool :as pool]))

;;; ─── Agent def ──────────────────────────────────────────────

(def ^:private claude-sdk-def
  {:name        "Claude Agent SDK"
   :in-process? true
   :features    #{:sessions :thinking :subagents :mcp :cost}
   :modes       {:default   "default"
                 :auto-edit "acceptEdits"
                 :yolo      "bypassPermissions"}})

;;; ─── SDK options builder ────────────────────────────────────

(defn- build-sdk-options [agent-key session-id]
  (let [config    (shared/load-config)
        ag-config (get-in config [:agents agent-key])
        mcp       @shared/mcp-servers
        base      {:includePartialMessages true
                   :permissionMode         (or (:permission-mode ag-config) "acceptEdits")
                   :settingSources         (or (:setting-sources ag-config) ["user" "project"])}
        with-resume (if session-id (assoc base :resume session-id) base)
        with-tools  (if (:allowed-tools ag-config)
                      (assoc with-resume :allowedTools (vec (:allowed-tools ag-config)))
                      with-resume)]
    (if (seq mcp)
      (assoc with-tools :mcpServers
             (reduce (fn [acc s]
                       (let [srv (cond-> {:command (:command s) :args (or (:args s) [])}
                                   (seq (:env s)) (assoc :env (:env s)))]
                         (assoc acc (:name s) srv)))
                     {} mcp))
      with-tools)))

;;; ─── Message processor ──────────────────────────────────────

(defn- process-sdk-message! [msg conn text-buf tool-calls emit]
  (let [t (.-type msg)]
    (cond
      ;; Session init
      (and (= t "system") (= (.-subtype msg) "init"))
      (do
        (reset! (:session-id conn) (.-session_id msg))
        (emit "acp_connect" #js {:agent-key  (:agent-key conn)
                                 :session-id (.-session_id msg)})
        true)

      ;; Streaming events
      (= t "stream_event")
      (let [ev  (.-event msg)
            evt (.-type ev)]
        (cond
          (and (= evt "content_block_delta")
               (= (.-type (.-delta ev)) "text_delta"))
          (let [chunk (.-text (.-delta ev))]
            (swap! text-buf conj chunk)
            (swap! (:prompt-state conn) update :text str chunk)
            (when-let [cb @shared/stream-callback] (cb chunk))
            (emit "acp_message" #js {:agent-key (:agent-key conn) :chunk chunk}))

          (and (= evt "content_block_delta")
               (= (.-type (.-delta ev)) "thinking_delta"))
          (let [thought (.-thinking (.-delta ev))]
            (when-let [cb @shared/thought-callback] (cb thought))
            (emit "acp_thought" #js {:agent-key (:agent-key conn) :thought thought}))

          (and (= evt "content_block_start")
               (= (.-type (.-content_block ev)) "tool_use"))
          (let [blk (.-content_block ev)
                tid (.-id blk)
                tnm (.-name blk)]
            (swap! tool-calls assoc tid {:toolCallId tid :toolName tnm :status "running" :input ""})
            (swap! (:prompt-state conn) update :tool-calls conj
                   {:toolCallId tid :toolName tnm :status "running" :input ""})
            (emit "acp_tool_start" #js {:agent-key (:agent-key conn) :toolCallId tid :toolName tnm})))
        true)

      ;; Final result
      (= t "result")
      (let [usg  (.-usage msg)
            cost (.-total_cost_usd msg)]
        (when usg
          (shared/update-agent-state!
           (:agent-key conn) :turn-usage
           {:input-tokens  (or (.-input_tokens usg) 0)
            :output-tokens (or (.-output_tokens usg) 0)
            :total-tokens  (or (.-total_tokens usg) 0)}))
        (when cost
          (shared/update-agent-state! (:agent-key conn) :usage {:cost {:amount cost}}))
        (doseq [[tid tc] @tool-calls]
          (when (= (:status tc) "running")
            (swap! tool-calls assoc-in [tid :status] "done")
            (emit "acp_tool_update" #js {:agent-key (:agent-key conn) :toolCallId tid :status "done"})))
        false)

      :else true)))

;;; ─── SDK prompt execution ───────────────────────────────────

(defn- send-prompt-sdk [conn sdk-mod prompt-text]
  (js/Promise.
   (fn [resolve reject]
     (let [session-id  @(:session-id conn)
           opts        (clj->js (build-sdk-options (:agent-key conn) session-id))
           text-buf    (atom [])
           tool-calls  (atom {})
           emit        (:emit conn)]
       (letfn [(finish []
                 (resolve {:text        (apply str @text-buf)
                           :stop-reason "end_turn"
                           :usage       (let [tu (shared/get-agent-state (:agent-key conn) :turn-usage)]
                                          {:input-tokens  (or (:input-tokens tu) 0)
                                           :output-tokens (or (:output-tokens tu) 0)
                                           :total-tokens  (or (:total-tokens tu) 0)
                                           :cached-read   0
                                           :thought       0})
                           :tool-calls  (vec (vals @tool-calls))}))
               (step [gen]
                 (-> (.next gen)
                     (.then (fn [res]
                              (if (.-done res)
                                (finish)
                                (let [keep? (process-sdk-message!
                                             (.-value res) conn text-buf tool-calls emit)]
                                  (if keep? (step gen) (finish))))))
                     (.catch reject)))]
         (try
           (let [query-fn (.-query sdk-mod)
                 gen      (.call query-fn sdk-mod #js {:prompt prompt-text :options opts})]
             (step gen))
           (catch :default e (reject e))))))))

;;; ─── Connection factory ──────────────────────────────────────

(defn create-in-process-connection [agent-key _agent-def api sdk-mod]
  (let [emit-fn (fn [event data]
                  (try
                    (when (.-emitGlobal api) (.emitGlobal api event data))
                    (catch :default _ nil)))]
    {:in-process?  true
     :proc         nil
     :stdin        nil
     :stdout       nil
     :stderr       nil
     :project-root (js/process.cwd)
     :agent-key    agent-key
     :state        (atom {:pending {} :terminals {}})
     :prompt-state (atom {:text "" :tool-calls []})
     :id-counter   (atom 0)
     :session-id   (atom nil)
     :sdk-query    (fn [conn text] (send-prompt-sdk conn sdk-mod text))
     :emit         emit-fn}))

;;; ─── Extension entry point ───────────────────────────────────

(defn ^:async ^:export default [api]
  (let [sdk-mod (try
                  (js-await (js/import "@anthropic-ai/claude-agent-sdk"))
                  (catch :default e
                    (js/console.warn "[agent-runner-claude-sdk] SDK unavailable:" (.-message e))
                    nil))]
    (when sdk-mod
      (let [create-fn  (fn [agent-key agent-def api]
                         (js/Promise.resolve
                          (create-in-process-connection agent-key agent-def api sdk-mod)))
            registered (registry/register-agent!
                        "claude-sdk"
                        (assoc claude-sdk-def :create-fn create-fn))]
        (when-not registered
          (js/console.warn "[agent-runner-claude-sdk] Failed to register claude-sdk agent"))
        (fn []
          (when (= @shared/active-agent "claude-sdk")
            (pool/disconnect "claude-sdk"))
          (registry/unregister-agent! "claude-sdk"))))))
