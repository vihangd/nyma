(ns tool-ctx-fixture
  "Shared test fixtures for tool execution contexts and extension API
   mocks. Borrowed from cc-kit's `makeCtx` helper in
   tests/tools.test.ts:20-35 — the idea of one canonical factory that
   fills every field with a sensible default so test sites only name
   the fields they actually care about.

   Two factories:

     `mk-tool-ctx`   — builds the `ext-ctx` object that nyma's
                       middleware enriches before calling a tool's
                       execute fn. Includes toolCallId, abortSignal,
                       onUpdate, and the same no-op stubs for every
                       field of a real extension context. Use this
                       when testing an extension tool that reads
                       fields off the second arg.

     `mk-api-mock`   — builds the top-level `api` object passed to an
                       extension's default/activate fn. Exposes a
                       :ui slot with :notify, plus
                       registerCommand/unregisterCommand with an
                       :_commands atom the test can inspect.

   Both are plain JS objects so extension code can `(.-foo api)` them
   directly. Every callback defaults to a no-op so an extension that
   fires and forgets won't error on a missing method."
  (:require ["node:path" :as path]))

;;; ─── Helpers ──────────────────────────────────────────

(defn- noop [] nil)
(defn- noop1 [_] nil)
(defn- noop2 [_ _] nil)

;;; ─── Tool execution context ──────────────────────────

(defn mk-tool-ctx
  "Build a mock extension context for a tool's execute(args, ctx)
   second argument. Every field of `create-extension-context` has a
   default, enriched with the middleware-set fields
   (toolCallId, abortSignal, onUpdate).

   Options (all optional):
     :cwd              working directory (default: process.cwd())
     :ui               pre-built ui object (otherwise we build a
                       default with :available true + :notify no-op)
     :abort-controller pass a real AbortController if you want to
                       drive cancellation from the test. Otherwise
                       we create a fresh one so abortSignal is
                       always valid.
     :tool-call-id     string id for the in-flight call
     :updates          atom to capture onUpdate calls (caller can
                       deref to inspect)
     :model            model config map / object
     :cwd              working directory override
     :extras           a map merged onto the returned JS object last,
                       for per-test overrides we don't have flags
                       for.

   The returned object is a plain JS object so tests using JS interop
   (.-field access) work naturally."
  [& [{:keys [cwd ui abort-controller tool-call-id updates model extras]
       :or   {cwd          (js/process.cwd)
              tool-call-id "test-tool-call-id"}}]]
  (let [ctrl   (or abort-controller (js/AbortController.))
        upd    (or updates (atom []))
        ui-obj (or ui
                   #js {:available true
                        :notify    (fn [msg _level] nil)
                        :showOverlay noop1
                        :setTitle    noop1})
        base   #js {;; Middleware-enriched fields (set by execute-tool-fn)
                    :toolCallId  tool-call-id
                    :abortSignal (.-signal ctrl)
                    :onUpdate    (fn [data] (swap! upd conj data))
                    ;; G18: active model ID string (set by execute-tool-fn from agent config)
                    :modelId     (str (or (and model (.-modelId model)) model "unknown"))

                    ;; create-extension-context fields
                    :ui    ui-obj
                    :hasUI (boolean (.-available ui-obj))
                    :cwd   cwd
                    :signal (.-signal ctrl)

                    :sessionManager #js {:getEntries (fn [] #js [])
                                         :getBranch  (fn [] #js [])
                                         :getLeafId  (fn [] nil)}

                    :modelRegistry  #js {:list    (fn [] #js [])
                                         :get     (fn [_] nil)
                                         :current (fn [] model)}
                    :model          model

                    :isIdle              (fn [] true)
                    :abort               (fn [] (.abort ctrl))
                    :hasPendingMessages  (fn [] false)
                    :shutdown            noop

                    :getContextUsage     (fn []
                                           #js {:inputTokens  0
                                                :outputTokens 0
                                                :cost         0
                                                :turns        0})

                    :compact             noop1
                    :getSystemPrompt     (fn [] "test system prompt")
                    :getSessionDirectory (fn [] (path/join cwd ".nyma" "sessions"))

                    :getTokenBudget      (fn []
                                           #js {:contextWindow   100000
                                                :inputBudget     70000
                                                :tokensUsed      0
                                                :tokensRemaining 70000
                                                :model           "test-model"})
                    :getModelInfo        (fn [& _] #js {:context-window 100000})
                    :estimateTokens      (fn [text] (count (or text "")))
                    :getContextProviders (fn [] #js [])

                    ;; Escape hatches the test can peek at
                    :_abortController    ctrl
                    :_updates            upd}]
    (if (seq extras)
      (do
        ;; clj->js handles the keyword → string conversion so we
        ;; don't need to call `name` (which isn't auto-imported by
        ;; Squint in compiled modules).
        (js/Object.assign base (clj->js extras))
        base)
      base)))

;;; ─── Extension API mock ───────────────────────────────

(defn mk-api-mock
  "A mock of the scoped extension API — the object an extension's activate
   fn receives. Every field an extension can call is present with a capturing
   default, so a test names only what it cares about. Twenty test files used
   to hand-roll this in five shapes.

   Captures (atoms the test can inspect):
     :_commands       {name opts}          :_notifications [{:message :level}]
     :_notes          [msg …] (strings)     :_event-handlers {event [h …]}
     :_global-events  [[event data] …]     :_tools          {name def}
     :_segments       {id cfg}             :_flags          {name cfg}
     :_messages       [msg …] (sendMessage) :_sent          [[text opts] …]
     :_dispatches     [[type data] …]      :_state          (atom state-map)
     :_model          (atom spec)

   Helpers:
     (.fire api \"event\" data)   — call every handler registered for event

   Options (all optional):
     :ui-overrides  map merged onto the default :ui
     :settings      map returned by (.settings api) / section lookups
     :state         initial agent-state map for getState / __state_atom
     :extras        map of extra top-level api fields"
  [& [{:keys [ui-overrides settings state extras]}]]
  (let [commands       (atom {})
        notifications  (atom [])
        notes          (atom [])
        event-handlers (atom {})
        global-events  (atom [])
        tools          (atom {})
        segments       (atom {})
        flags          (atom {})
        messages       (atom [])
        sent           (atom [])
        dispatches     (atom [])
        state-atom     (atom (or state {}))
        model          (atom nil)
        settings-map   (or settings {})
        ui-base        {:available true
                        :notify    (fn [msg & [level]]
                                     (swap! notes conj msg)
                                     (swap! notifications conj
                                            {:message msg :level (or level "info")}))
                        :showOverlay noop1
                        :setTitle    noop1
                        :setWidget   noop1
                        :clearWidget noop1}
        ui-map         (merge ui-base (or ui-overrides {}))
        base           #js {:ui (clj->js ui-map)
                            :namespace "test"

                            :registerCommand   (fn [name opts] (swap! commands assoc name opts))
                            :unregisterCommand (fn [name] (swap! commands dissoc name))
                            :getCommands       (fn [] @commands)

                            :on   (fn [event handler & _]
                                    (swap! event-handlers update event (fnil conj []) handler))
                            :off  (fn [event handler]
                                    (swap! event-handlers update event
                                           (fn [hs] (filterv #(not= % handler) hs))))
                            :emit (fn [event data]
                                    (doseq [h (get @event-handlers event [])] (h data)))
                            :fire (fn [event data]
                                    (doseq [h (get @event-handlers event [])] (h data)))
                            :emitGlobal (fn [event data] (swap! global-events conj [event data]))
                            :events #js {:on   (fn [event handler & _]
                                                 (swap! event-handlers update event (fnil conj []) handler))
                                         :off  (fn [_ _] nil)
                                         :emit (fn [event data] (swap! global-events conj [event data]))}

                            :registerTool   (fn [name td] (swap! tools assoc name td))
                            :unregisterTool (fn [name] (swap! tools dissoc name))
                            :getTool        (fn [name] (get @tools name))

                            :registerStatusSegment   (fn [id cfg] (swap! segments assoc id cfg))
                            :unregisterStatusSegment (fn [id] (swap! segments dissoc id))

                            :registerFlag  (fn [name cfg] (swap! flags assoc name cfg))
                            :getFlag       (fn [name] (let [f (get @flags name)]
                                                        (when f (or (:value f) (.-default f)))))
                            :getGlobalFlag (fn [_] nil)

                            :settings    (fn [& [section]]
                                           (if section (or (get settings-map section) {}) settings-map))
                            :getSettings (fn [] settings-map)

                            :getState      (fn [] @state-atom)
                            :__state_atom  state-atom
                            :dispatch      (fn [t d] (swap! dispatches conj [t d]))
                            :dispatchState (fn [t d]
                                             (swap! dispatches conj [t d])
                                             (when (= "messages-cleared" (str t))
                                               (swap! state-atom assoc :messages [])))
                            :onStateChange (fn [_] (fn [] nil))

                            :sendMessage     (fn [m] (swap! messages conj m))
                            :sendUserMessage (fn [t & [o]] (swap! sent conj [t o]))
                            :setModel        (fn [spec] (reset! model spec))
                            :getActiveModelSpec (fn [] @model)
                            :getThinkingLevel   (fn [] "off")
                            :estimateTokens     (fn [text] (js/Math.ceil (/ (count (str text)) 4)))
                            :exec  (fn [& _] (js/Promise.resolve #js {:stdout "" :stderr ""}))
                            :spawn (fn [& _] nil)

                            ;; Escape hatches the test can peek at
                            :_commands       commands
                            :_notifications  notifications
                            :_notes          notes
                            :_event-handlers event-handlers
                            :_global-events  global-events
                            :_tools          tools
                            :_segments       segments
                            :_flags          flags
                            :_messages       messages
                            :_sent           sent
                            :_dispatches     dispatches
                            :_state          state-atom
                            :_model          model}]
    (when (seq extras)
      (js/Object.assign base (clj->js extras)))
    base))
