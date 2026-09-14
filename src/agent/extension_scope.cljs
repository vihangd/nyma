(ns agent.extension-scope
  (:require [agent.debug :as d]
            [agent.permissions :refer [check]]
            [agent.extension-state :refer [create-state-api]]))

(defn- gate
  "Gate a function behind a capability check. Throws if not authorized."
  [capabilities capability f]
  (if (check capabilities capability)
    f
    (fn [& _args]
      (throw (js/Error. (str "Extension missing capability: " (str capability)))))))

(def handler-errors
  "namespace → count of event-handler throws swallowed by the error boundary
   below. Surfaced by /extensions; nothing else reads it."
  (atom {}))

(defn dispose-scope!
  "Undo every registration a scoped API recorded, newest first, one try/catch
   each. Runs AFTER the extension's own deactivate, so anything it forgot —
   an inline handler it could never `.off`, a command with no unregister — is
   swept by the namespace that owns it. Clears the list, so a second call is
   a no-op."
  [scoped]
  (when-let [ds (and scoped (aget scoped "__disposers"))]
    (doseq [{:keys [f]} (reverse @ds)]
      (try
        (f)
        (catch :default e
          (try
            (d/warn (str "[" (.-namespace scoped) "] dispose error: " (.-message e)))
            (catch :default _ nil)))))
    (reset! ds [])))

(defn create-scoped-api
  "Wrap the base extension API with namespace prefixing and capability gating.
   Tools and commands are prefixed with 'namespace__' to prevent collisions.
   Uses '__' separator (not '/') to comply with Anthropic API tool name pattern
   ^[a-zA-Z0-9_-]{1,128}$.

   Every registration made through this object is recorded on a per-scope
   disposer list (see `dispose-scope!`). The extension keeps its own inverse
   calls for early teardown; the list is the safety net at unload/reload.
   Not recorded, because they register nothing: sendMessage, dispatch,
   emitGlobal, setModel, setThinkingLevel, registerModelInfo, exec, spawn,
   session setters. Not swept on purpose: `state` — it is the extension's
   on-disk data keyed by namespace and must survive a /reload."
  [base-api ns-str capabilities]
  (let [prefix      (fn [n] (str ns-str "__" n))
        ;; {:f undo-fn, :event e, :h wrapper} — event/h let `off` drop the entry.
        disposers   (atom [])
        track!      (fn [f & [event h]]
                      (swap! disposers conj {:f f :event event :h h})
                      f)
        untrack!    (fn [event h]
                      (swap! disposers
                             (fn [ds] (vec (remove #(and (= (:event %) event)
                                                         (identical? (:h %) h))
                                                   ds)))))
        ;; Error boundary: wrap event handlers so one broken extension can't
        ;; crash others. event → WeakMap(handler → safe-handler); nested per
        ;; event because one handler subscribed to two events must keep two
        ;; wrappers, or `off` on the first event unsubscribes the second's.
        handler-map (js/Map.)
        wrapper-for (fn [event handler]
                      (let [m (.get handler-map event)]
                        (and m (.get m handler))))
        safe-on     (fn [event handler & [priority]]
                      (let [safe-handler (fn [data ctx]
                                           (try
                                             (handler data ctx)
                                             (catch :default e
                                               (swap! handler-errors update ns-str (fnil inc 0))
                                               (try
                                                 (d/warn
                                                  (str "[" ns-str "] Error in " event " handler: " (.-message e)))
                                                 ;; Into the transcript too, once per
                                                 ;; extension: a warn over a live TUI frame
                                                 ;; is corrupted output at best. Later
                                                 ;; throws only bump the /extensions count.
                                                 (when (= 1 (get @handler-errors ns-str))
                                                   (let [ui (.-ui base-api)]
                                                     (when (and ui (.-available ui) (.-notify ui))
                                                       (.notify ui (str ns-str ": error in " event " handler — "
                                                                        (.-message e) " (details in /extensions)")
                                                                "error"))))
                                                 (catch :default _ nil))
                                               nil)))
                            m            (or (.get handler-map event)
                                             (let [nm (js/WeakMap.)] (.set handler-map event nm) nm))]
                        (.set m handler safe-handler)
                        (.on base-api event safe-handler priority)
                        ;; Closes over the wrapper itself, so it still works
                        ;; after the extension dropped the map entry.
                        (track! (fn [] (.off base-api event safe-handler)) event safe-handler)
                        nil))
        safe-off    (fn [event handler]
                      (let [safe (or (wrapper-for event handler) handler)]
                        (when-let [m (.get handler-map event)] (.delete m handler))
                        (untrack! event safe)
                        (.off base-api event safe)))
        inter-bus   (.-events base-api)
        scoped #js {:on               (gate capabilities :events safe-on)
                    :off              (gate capabilities :events safe-off)
                    ;; Tool management
                    :registerTool     (gate capabilities :tools
                                            (fn [name td]
                                              (let [full (prefix name)]
                                                (.registerTool base-api full td)
                                                ;; Identity guard: only remove what
                                                ;; this call put there, so a tool the
                                                ;; extension already swapped out is
                                                ;; left alone.
                                                (track! (fn [] (when (identical? (.getTool base-api full) td)
                                                                 (.unregisterTool base-api full))))
                                                nil)))
                    :unregisterTool   (gate capabilities :tools
                                            (fn [name] (.unregisterTool base-api (prefix name))))
                    ;; overrideTool: register a tool under its REAL
                    ;; name (no namespace prefix), used to wrap native
                    ;; tools with delegating execute fns. Powerful —
                    ;; gated on :tools-override capability separately
                    ;; from :tools so adding it requires manifest
                    ;; opt-in.
                    :overrideTool     (gate capabilities :tools-override
                                            (fn [name td]
                                              (let [tag (.registerTool base-api name td)]
                                                ;; :reoverride means someone else
                                                ;; (or an earlier call of ours) holds
                                                ;; the original — their unregister
                                                ;; restores it, ours would delete it.
                                                ;; Guard: only while an override is
                                                ;; still on top. :owner cannot use
                                                ;; identity — a later stub→wrapper
                                                ;; call replaces td, and the restore
                                                ;; must still happen. A restored
                                                ;; native has no __original.
                                                (case tag
                                                  :owner (track! (fn [] (when (some? (.-__original (or (.getTool base-api name) #js {})))
                                                                          (.unregisterTool base-api name))))
                                                  :new   (track! (fn [] (when (identical? (.getTool base-api name) td)
                                                                          (.unregisterTool base-api name))))
                                                  nil)
                                                nil)))
                    :unoverrideTool   (gate capabilities :tools-override
                                            (fn [name] (.unregisterTool base-api name)))
                    :getActiveTools   (gate capabilities :tools (.-getActiveTools base-api))
                    :getAllTools       (gate capabilities :tools (.-getAllTools base-api))
                    :getTool          (gate capabilities :tools (.-getTool base-api))
                    :setActiveTools   (gate capabilities :tools (.-setActiveTools base-api))
                    ;; Commands
                    :registerCommand  (gate capabilities :commands
                                            (fn [name opts]
                                              (.registerCommand base-api (prefix name) opts)
                                              (track! (fn [] (.unregisterCommand base-api (prefix name))))
                                              nil))
                    :unregisterCommand (gate capabilities :commands
                                             (fn [name] (.unregisterCommand base-api (prefix name))))
                    :getCommands      (gate capabilities :commands (.-getCommands base-api))
                    ;; Shortcuts (keys are not namespaced — pre-existing)
                    :registerShortcut (gate capabilities :shortcuts
                                            (fn [key handler]
                                              (.registerShortcut base-api key handler)
                                              (track! (fn [] (.unregisterShortcut base-api key)))
                                              nil))
                    :unregisterShortcut (gate capabilities :shortcuts
                                              (fn [key] (.unregisterShortcut base-api key)))
                    ;; Messaging
                    :sendMessage      (gate capabilities :messages (.-sendMessage base-api))
                    :sendUserMessage  (gate capabilities :messages (.-sendUserMessage base-api))
                    ;; Middleware — removable only by :name; an anonymous
                    ;; interceptor is unremovable by construction, say so once.
                    :addMiddleware    (gate capabilities :middleware
                                            (fn [interceptor & [opts]]
                                              (.addMiddleware base-api interceptor opts)
                                              (if-let [n (and interceptor (:name interceptor))]
                                                (track! (fn [] (.removeMiddleware base-api n)))
                                                (d/warn (str "[" ns-str "] addMiddleware without :name — cannot be removed at unload")))
                                              nil))
                    :removeMiddleware (gate capabilities :middleware (.-removeMiddleware base-api))
                    ;; Shell
                    :exec             (gate capabilities :exec (.-exec base-api))
                    :spawn            (gate capabilities :spawn (.-spawn base-api))
                    ;; Session
                    :appendEntry      (gate capabilities :session (.-appendEntry base-api))
                    :setSessionName   (gate capabilities :session (.-setSessionName base-api))
                    :getSessionName   (gate capabilities :session (.-getSessionName base-api))
                    :setLabel         (gate capabilities :session (.-setLabel base-api))
                    ;; Status line segments — extensions can contribute segments
                    ;; that appear in the status line above the editor.
                    ;; (ids are not namespaced — pre-existing)
                    :registerStatusSegment   (gate capabilities :ui
                                                   (fn [id cfg]
                                                     (.registerStatusSegment base-api id cfg)
                                                     (track! (fn [] (.unregisterStatusSegment base-api id)))
                                                     nil))
                    :unregisterStatusSegment (gate capabilities :ui (.-unregisterStatusSegment base-api))
                    ;; Provider management
                    :registerProvider   (gate capabilities :providers
                                              (fn [name cfg]
                                                (.registerProvider base-api name cfg)
                                                (track! (fn [] (.unregisterProvider base-api name)))
                                                nil))
                    :unregisterProvider (gate capabilities :providers (.-unregisterProvider base-api))
                    ;; Model/thinking control
                    :setModel         (gate capabilities :model (.-setModel base-api))
                    :getActiveModelSpec (gate capabilities :model (.-getActiveModelSpec base-api))
                    :getThinkingLevel (gate capabilities :model (.-getThinkingLevel base-api))
                    :setThinkingLevel (gate capabilities :model (.-setThinkingLevel base-api))
                    ;; Inter-extension events (namespace-prefixed)
                    :events           (gate capabilities :events
                                            #js {:on   (fn [event handler & [priority]]
                                                         (let [full (prefix event)]
                                                           (.on inter-bus full handler priority)
                                                           (track! (fn [] (.off inter-bus full handler)) full handler)
                                                           nil))
                                                 :off  (fn [event handler]
                                                         (let [full (prefix event)]
                                                           (untrack! full handler)
                                                           (.off inter-bus full handler)))
                                                 :emit (fn [event data]
                                                         (.emit inter-bus (prefix event) data))})
                    ;; Context providers (gated)
                    :getTokenBudget            (gate capabilities :context (.-getTokenBudget base-api))
                    ;; Model info & token estimation (ungated — read-only utilities)
                    :getModelInfo      (.-getModelInfo base-api)
                    :registerModelInfo (.-registerModelInfo base-api)
                    :estimateTokens    (.-estimateTokens base-api)
                    :resolveModel      (gate capabilities :model (.-resolveModel base-api))
                    ;; Settings access (ungated — read-only)
                    :getSettings       (.-getSettings base-api)
                    :settings          (.-settings base-api)
                    ;; Emit to main agent event bus (gated — lets extensions
                    ;; broadcast to other extensions that subscribed via api.on)
                    :emitGlobal        (gate capabilities :events (.-emitGlobal base-api))
                    ;; Flags (namespace-prefixed)
                    :registerFlag     (gate capabilities :flags
                                            (fn [name config]
                                              (.registerFlag base-api (prefix name) config)
                                              (track! (fn [] (.unregisterFlag base-api (prefix name))))
                                              nil))
                    :unregisterFlag   (gate capabilities :flags
                                            (fn [name] (.unregisterFlag base-api (prefix name))))
                    :getFlag          (gate capabilities :flags
                                            (fn [name]
                                              (.getFlag base-api (prefix name))))
                    ;; Global flag reading (ungated — read-only, no namespace prefix)
                    :getGlobalFlag    (.-getGlobalFlag base-api)

                    ;; Agent-state read / dispatch — gated on :state.
                    ;; Distinct from the per-extension persistent :state slot
                    ;; below: these touch the running agent's live state.
                    :getState         (gate capabilities :state (.-getState base-api))
                    :dispatch         (gate capabilities :state (.-dispatch base-api))
                    :dispatchState    (gate capabilities :state (.-dispatchState base-api))
                    :onStateChange    (gate capabilities :state
                                            (fn [listener]
                                              (let [unsub (.onStateChange base-api listener)]
                                                (track! unsub)
                                                unsub)))
                    :__state_atom     (when (check capabilities :state)
                                        (.-__state-atom base-api))

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
    ;; Non-enumerable: the parity test walks Object.keys and this is not API.
    (js/Object.defineProperty scoped "__disposers"
                              #js {:value disposers :enumerable false})
    scoped))

(defn derive-namespace
  "Derive an extension namespace from its file path.

   Rules:
     - Single file:           '/path/to/git-tools.cljs'        → 'git-tools'
     - Directory entry point: '/path/to/git_tools/index.cljs'  → 'git-tools'
       (when the basename is 'index.*' we walk one directory up and
       kebab-case the dir name — otherwise every manifest-less directory
       extension would collide on the namespace 'index').

   Validates that the result is non-empty and contains only safe characters."
  [file-path]
  (let [parts       (vec (.split file-path "/"))
        base        (last parts)
        without-ext (first (.split base "."))
        ns-raw      (if (= without-ext "index")
                      ;; Directory ext: take the parent dir name instead
                      (let [parent (last (butlast parts))]
                        (when parent (.replace parent (js/RegExp. "_" "g") "-")))
                      without-ext)]
    (if (and ns-raw (.test #"^[a-zA-Z0-9_-]+$" ns-raw))
      ns-raw
      (throw (js/Error. (str "Invalid extension namespace derived from: " file-path))))))
