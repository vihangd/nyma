(ns agent.extension-scope
  (:require [agent.permissions :refer [check]]))

(defn- gate
  "Gate a function behind a capability check. Throws if not authorized."
  [capabilities capability f]
  (if (check capabilities capability)
    f
    (fn [& _args]
      (throw (js/Error. (str "Extension missing capability: " (str capability)))))))

(defn create-scoped-api
  "Wrap the base extension API with namespace prefixing and capability gating.
   Tools and commands are prefixed with 'namespace/' to prevent collisions.
   Methods are gated behind the extension's declared capabilities."
  [base-api ns-str capabilities]
  (let [prefix (fn [n] (str ns-str "/" n))]
    #js {:on               (gate capabilities :events (.-on base-api))
         :off              (gate capabilities :events (.-off base-api))
         ;; Tool management
         :registerTool     (gate capabilities :tools
                             (fn [name td] (.registerTool base-api (prefix name) td)))
         :unregisterTool   (gate capabilities :tools
                             (fn [name] (.unregisterTool base-api (prefix name))))
         :getActiveTools   (gate capabilities :tools (.-getActiveTools base-api))
         :getAllTools       (gate capabilities :tools (.-getAllTools base-api))
         :setActiveTools   (gate capabilities :tools (.-setActiveTools base-api))
         ;; Commands
         :registerCommand  (gate capabilities :commands
                             (fn [name opts] (.registerCommand base-api (prefix name) opts)))
         :unregisterCommand (gate capabilities :commands
                              (fn [name] (.unregisterCommand base-api (prefix name))))
         :getCommands      (gate capabilities :commands (.-getCommands base-api))
         ;; Shortcuts
         :registerShortcut (gate capabilities :shortcuts
                             (fn [key handler] (.registerShortcut base-api key handler)))
         :unregisterShortcut (gate capabilities :shortcuts
                               (fn [key] (.unregisterShortcut base-api key)))
         ;; Messaging
         :sendMessage      (gate capabilities :messages (.-sendMessage base-api))
         :sendUserMessage  (gate capabilities :messages (.-sendUserMessage base-api))
         ;; Middleware
         :addMiddleware    (gate capabilities :middleware (.-addMiddleware base-api))
         :removeMiddleware (gate capabilities :middleware (.-removeMiddleware base-api))
         ;; Shell
         :exec             (gate capabilities :exec (.-exec base-api))
         ;; Session
         :appendEntry      (gate capabilities :session (.-appendEntry base-api))
         :setSessionName   (gate capabilities :session (.-setSessionName base-api))
         :getSessionName   (gate capabilities :session (.-getSessionName base-api))
         :setLabel         (gate capabilities :session (.-setLabel base-api))
         ;; Message rendering
         :registerMessageRenderer (gate capabilities :renderers (.-registerMessageRenderer base-api))
         ;; Provider management
         :registerProvider   (gate capabilities :providers (.-registerProvider base-api))
         :unregisterProvider (gate capabilities :providers (.-unregisterProvider base-api))
         ;; Model/thinking control
         :setModel         (gate capabilities :model (.-setModel base-api))
         :getThinkingLevel (gate capabilities :model (.-getThinkingLevel base-api))
         :setThinkingLevel (gate capabilities :model (.-setThinkingLevel base-api))
         ;; Inter-extension events (namespace-prefixed)
         :events           (gate capabilities :events
                             (let [bus (.-events base-api)]
                               #js {:on   (fn [event handler & [priority]]
                                            (.on bus (prefix event) handler priority))
                                    :off  (fn [event handler]
                                            (.off bus (prefix event) handler))
                                    :emit (fn [event data]
                                            (.emit bus (prefix event) data))}))
         ;; Context providers (gated)
         :registerContextProvider   (gate capabilities :context (.-registerContextProvider base-api))
         :unregisterContextProvider (gate capabilities :context (.-unregisterContextProvider base-api))
         :getTokenBudget            (gate capabilities :context (.-getTokenBudget base-api))
         ;; Model info & token estimation (ungated — read-only utilities)
         :getModelInfo      (.-getModelInfo base-api)
         :registerModelInfo (.-registerModelInfo base-api)
         :estimateTokens    (.-estimateTokens base-api)
         ;; Flags (namespace-prefixed)
         :registerFlag     (gate capabilities :flags
                             (fn [name config]
                               (.registerFlag base-api (prefix name) config)))
         :getFlag          (gate capabilities :flags
                             (fn [name]
                               (.getFlag base-api (prefix name))))
         ;; UI
         :ui               (if (check capabilities :ui) (.-ui base-api) #js {:available false})
         :namespace        ns-str}))

(defn derive-namespace
  "Derive an extension namespace from its file path.
   e.g., '/path/to/git-tools.cljs' → 'git-tools'
   Validates that the result is non-empty and contains only safe characters."
  [file-path]
  (let [base        (last (.split file-path "/"))
        without-ext (first (.split base "."))]
    (if (and without-ext (.test #"^[a-zA-Z0-9_-]+$" without-ext))
      without-ext
      (throw (js/Error. (str "Invalid extension namespace derived from: " file-path))))))
