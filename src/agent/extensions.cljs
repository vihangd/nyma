(ns agent.extensions
  (:require [agent.loop :refer [steer follow-up]]
            [agent.extension-context :refer [create-extension-context]]
            [agent.token-estimation :as te]))

(defn create-extension-api
  "Build the API object that extensions receive.
   Covers pi-mono's ExtensionAPI surface:
   - Event subscription (on/off)
   - Tool management (registerTool, unregisterTool, getActiveTools, getAllTools, setActiveTools)
   - Command registration (registerCommand, unregisterCommand, getCommands)
   - Shortcut registration (registerShortcut, unregisterShortcut)
   - Messaging (sendMessage, sendUserMessage)
   - Middleware (addMiddleware, removeMiddleware)
   - Shell execution (exec)
   - Session entries (appendEntry, setSessionName, getSessionName, setLabel)
   - Message rendering (registerMessageRenderer)
   - Provider management (registerProvider, unregisterProvider)
   - Model/thinking control (setModel, getThinkingLevel, setThinkingLevel)
   - Inter-extension events
   - UI hooks"
  [agent]
  (let [handler-map (atom {})]
  #js {:on                (fn [event handler & [priority]]
                            (let [wrapped (fn [data]
                                            (handler data (create-extension-context agent)))]
                              (swap! handler-map assoc handler wrapped)
                              ((:on (:events agent)) event wrapped priority)))
       :off               (fn [event handler]
                            (let [wrapped (get @handler-map handler handler)]
                              (swap! handler-map dissoc handler)
                              ((:off (:events agent)) event wrapped)))

       ;; ── Tool management ─────────────────────────────────
       :registerTool      (fn [name tool-def]
                            ((:register (:tool-registry agent)) name tool-def))
       :unregisterTool    (fn [name]
                            ((:unregister (:tool-registry agent)) name))
       :getActiveTools    (fn [] (clj->js (keys ((:get-active (:tool-registry agent))))))
       :getAllTools        (fn [] (clj->js (keys ((:all (:tool-registry agent))))))
       :setActiveTools    (fn [names]
                            ((:set-active (:tool-registry agent)) (set (js->clj names))))

       ;; ── Command registration ────────────────────────────
       :registerCommand   (fn [name opts]
                            (swap! (:commands agent) assoc name opts))
       :unregisterCommand (fn [name]
                            (swap! (:commands agent) dissoc name))
       :getCommands       (fn [] @(:commands agent))

       ;; ── Shortcut registration ───────────────────────────
       :registerShortcut  (fn [key handler]
                            (swap! (:shortcuts agent) assoc key handler))
       :unregisterShortcut (fn [key]
                             (swap! (:shortcuts agent) dissoc key))

       ;; ── Messaging ───────────────────────────────────────
       :sendMessage       (fn [msg]
                            (swap! (:state agent) update :messages conj msg))
       :sendUserMessage   (fn [text opts]
                            (let [deliver-as (or (and opts (.-deliverAs opts)) "steer")]
                              (case deliver-as
                                "steer"    (steer agent {:role "user" :content text})
                                "followUp" (follow-up agent {:role "user" :content text}))))

       ;; ── Middleware API ──────────────────────────────────
       :addMiddleware     (fn [interceptor & [opts]]
                            (when-let [pipeline (:middleware agent)]
                              ((:add pipeline) interceptor opts)))
       :removeMiddleware  (fn [interceptor-name]
                            (when-let [pipeline (:middleware agent)]
                              ((:remove pipeline) interceptor-name)))

       ;; ── Shell execution ─────────────────────────────────
       :exec              (fn [cmd args]
                            (let [cmd-args (into [cmd] (or (js->clj args) []))
                                  proc     (js/Bun.spawn (clj->js cmd-args)
                                             #js {:cwd    (js/process.cwd)
                                                  :stdout "pipe"
                                                  :stderr "pipe"})]
                              (.then (.-exited proc)
                                (fn [_]
                                  (-> (js/Promise.all
                                        #js [(.text (.-stdout proc))
                                             (.text (.-stderr proc))])
                                      (.then (fn [results]
                                               #js {:stdout (aget results 0)
                                                    :stderr (aget results 1)})))))))

       ;; ── Session entries (pi-compat: appendEntry) ────────
       :appendEntry       (fn [entry-type data]
                            (when-let [session @(:session agent)]
                              ((:append session)
                                {:role     (str entry-type)
                                 :content  (if (string? data) data (js/JSON.stringify data))
                                 :metadata {:source "extension" :entry-type (str entry-type)}})))

       ;; ── Session naming/labeling ─────────────────────────
       :setSessionName    (fn [name]
                            (when-let [session @(:session agent)]
                              (when-let [f (:set-session-name session)]
                                (f name))))
       :getSessionName    (fn []
                            (when-let [session @(:session agent)]
                              (when-let [f (:get-session-name session)]
                                (f))))
       :setLabel          (fn [entry-id label]
                            (when-let [session @(:session agent)]
                              (when-let [f (:set-label session)]
                                (f entry-id label))))

       ;; ── Message rendering ───────────────────────────────
       :registerMessageRenderer
                          (fn [msg-type renderer]
                            (swap! (:message-renderers agent) assoc (str msg-type) renderer))

       ;; ── Provider management ─────────────────────────────
       :registerProvider  (fn [name config]
                            ((:register (:provider-registry agent)) name config))
       :unregisterProvider (fn [name]
                             ((:unregister (:provider-registry agent)) name))

       ;; ── Model control ───────────────────────────────────
       :setModel          (fn [model-spec]
                            (let [registry (:provider-registry agent)
                                  parts    (.split (str model-spec) "/")
                                  provider (if (> (count parts) 1) (first parts) nil)
                                  model-id (if (> (count parts) 1) (second parts) (str model-spec))]
                              ;; Try to resolve from registry, fall back to direct use
                              (let [model (if (and provider ((:get registry) provider))
                                            ((:resolve registry) provider model-id)
                                            model-spec)]
                                ((:dispatch! (:store agent)) :model-changed {:model model})
                                ;; Update config for the loop to pick up
                                (set! (.-model (:config agent)) model)
                                ;; Emit model_select event
                                ((:emit (:events agent)) "model_select"
                                  {:model model :modelId (str model-spec)}))))

       ;; ── Thinking level ──────────────────────────────────
       :getThinkingLevel  (fn [] @(:thinking-level agent))
       :setThinkingLevel  (fn [level]
                            (when-not (contains? #{"off" "minimal" "low" "medium" "high" "xhigh"} level)
                              (throw (js/Error. (str "Invalid thinking level: " level
                                                     ". Must be one of: off, minimal, low, medium, high, xhigh"))))
                            (reset! (:thinking-level agent) level))

       ;; ── Inter-extension events ──────────────────────────
       :events            (let [bus (:inter-events agent)]
                            #js {:on   (:on bus)
                                 :off  (:off bus)
                                 :emit (:emit bus)})

       ;; ── Flags ──────────────────────────────────────────────
       :registerFlag     (fn [name config]
                            (swap! (:flags agent) assoc name
                              {:description (when config (.-description config))
                               :type        (or (when config (.-type config)) "boolean")
                               :default     (when config (.-default config))
                               :value       nil}))
       :getFlag          (fn [name]
                            (when-let [flag (get @(:flags agent) name)]
                              (if (some? (:value flag))
                                (:value flag)
                                (:default flag))))

       ;; ── Context providers ──────────────────────────────────────
       :registerContextProvider
                          (fn [name config]
                            (swap! (:context-providers agent) assoc name
                              {:priority       (or (when config (.-priority config)) 0)
                               :tokenEstimate  (when config (.-tokenEstimate config))
                               :provide        (when config (.-provide config))}))
       :unregisterContextProvider
                          (fn [name]
                            (swap! (:context-providers agent) dissoc name))

       ;; ── Token budget ───────────────────────────────────────
       :getTokenBudget    (fn []
                            (let [model-id (or (.-modelId (:model (:config agent))) "unknown")
                                  window   ((:context-window (:model-registry agent)) model-id)
                                  state    @(:state agent)
                                  used     (te/estimate-messages-tokens (:messages state))
                                  reserved (js/Math.floor (* window 0.3))]
                              #js {:contextWindow   window
                                   :inputBudget     (- window reserved)
                                   :tokensUsed      used
                                   :tokensRemaining (- window reserved used)
                                   :model           model-id}))

       ;; ── Model info ─────────────────────────────────────────
       :getModelInfo      (fn [& [model-id]]
                            (let [id (or model-id
                                         (.-modelId (:model (:config agent)))
                                         "unknown")]
                              (clj->js ((:get (:model-registry agent)) id))))
       :registerModelInfo (fn [entries]
                            (let [converted (into {}
                                              (map (fn [k]
                                                     [(str k)
                                                      {:context-window
                                                       (.-contextWindow (aget entries k))}])
                                                   (js/Object.keys entries)))]
                              ((:register (:model-registry agent)) converted)))

       ;; ── Token estimation ───────────────────────────────────
       :estimateTokens    (fn [text] (te/estimate-tokens text))

       ;; ── UI hooks — populated when running in interactive mode ──
       :ui                #js {:available   false
                               :showOverlay nil
                               :setWidget   nil
                               :clearWidget nil
                               :confirm     nil
                               :select      nil
                               :input       nil
                               :notify      nil
                               :setStatus   nil
                               :setFooter   nil
                               :setHeader   nil
                               :setTitle    nil
                               :setEditorComponent nil
                               :onTerminalInput nil
                               :custom      nil}}))
