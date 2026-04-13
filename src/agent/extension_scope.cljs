(ns agent.extension-scope
  (:require [agent.permissions :refer [check]]
            [agent.extension-state :refer [create-state-api]]))

(defn- gate
  "Gate a function behind a capability check. Throws if not authorized."
  [capabilities capability f]
  (if (check capabilities capability)
    f
    (fn [& _args]
      (throw (js/Error. (str "Extension missing capability: " (str capability)))))))

(defn create-scoped-api
  "Wrap the base extension API with namespace prefixing and capability gating.
   Tools and commands are prefixed with 'namespace__' to prevent collisions.
   Uses '__' separator (not '/') to comply with Anthropic API tool name pattern
   ^[a-zA-Z0-9_-]{1,128}$."
  [base-api ns-str capabilities]
  (let [prefix      (fn [n] (str ns-str "__" n))
        ;; Error boundary: wrap event handlers so one broken extension can't crash others
        handler-map (js/WeakMap.)
        safe-on     (fn [event handler & [priority]]
                      (let [safe-handler (fn [data ctx]
                                           (try
                                             (handler data ctx)
                                             (catch :default e
                                               (try
                                                 (js/console.warn
                                                  (str "[" ns-str "] Error in " event " handler: " (.-message e)))
                                                 (catch :default _ nil))
                                               nil)))]
                        (.set handler-map handler safe-handler)
                        (.on base-api event safe-handler priority)))
        safe-off    (fn [event handler]
                      (let [safe (or (.get handler-map handler) handler)]
                        (.off base-api event safe)))
        scoped #js {:on               (gate capabilities :events safe-on)
                    :off              (gate capabilities :events safe-off)
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
                    :spawn            (gate capabilities :spawn (.-spawn base-api))
                    ;; Session
                    :appendEntry      (gate capabilities :session (.-appendEntry base-api))
                    :setSessionName   (gate capabilities :session (.-setSessionName base-api))
                    :getSessionName   (gate capabilities :session (.-getSessionName base-api))
                    :setLabel         (gate capabilities :session (.-setLabel base-api))
                    ;; Block renderers (streaming markdown)
                    :registerBlockRenderer   (gate capabilities :renderers (.-registerBlockRenderer base-api))
                    :unregisterBlockRenderer (gate capabilities :renderers (.-unregisterBlockRenderer base-api))
                    ;; Tool renderers (per-tool display in chat view)
                    :registerToolRenderer    (gate capabilities :renderers (.-registerToolRenderer base-api))
                    :unregisterToolRenderer  (gate capabilities :renderers (.-unregisterToolRenderer base-api))
                    ;; Status line segments — extensions can contribute segments
                    ;; that appear in the status line above the editor.
                    :registerStatusSegment   (gate capabilities :ui (.-registerStatusSegment base-api))
                    :unregisterStatusSegment (gate capabilities :ui (.-unregisterStatusSegment base-api))
                    ;; Autocomplete providers (slash/at/path completion)
                    :registerCompletionProvider   (gate capabilities :ui (.-registerCompletionProvider base-api))
                    :unregisterCompletionProvider (gate capabilities :ui (.-unregisterCompletionProvider base-api))
                    ;; Mention providers (@-mention system)
                    :registerMentionProvider   (gate capabilities :ui (.-registerMentionProvider base-api))
                    :unregisterMentionProvider (gate capabilities :ui (.-unregisterMentionProvider base-api))
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
                    ;; Global flag reading (ungated — read-only, no namespace prefix)
                    :getGlobalFlag    (.-getGlobalFlag base-api)
                    ;; Persistent state (namespace-scoped)
                    :state            (if (check capabilities :state)
                                        (create-state-api ns-str)
                                        #js {:get    (fn [_] (throw (js/Error. "Extension missing capability: :state")))
                                             :set    (fn [_ _] (throw (js/Error. "Extension missing capability: :state")))
                                             :delete (fn [_] (throw (js/Error. "Extension missing capability: :state")))
                                             :keys   (fn [] (throw (js/Error. "Extension missing capability: :state")))
                                             :clear  (fn [] (throw (js/Error. "Extension missing capability: :state")))})
                    :namespace        ns-str}]
    ;; Define .ui as a GETTER so it reads the latest value from base-api.
    ;; base-api.ui is set by useEffect AFTER extensions activate — a static
    ;; snapshot would capture undefined/stale value.
    (js/Object.defineProperty scoped "ui"
                              #js {:get         (fn [] (if (check capabilities :ui)
                                                         (or (.-ui base-api) #js {:available false})
                                                         #js {:available false}))
                                   :enumerable  true
                                   :configurable true})
    scoped))

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
