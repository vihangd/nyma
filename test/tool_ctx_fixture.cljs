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
  "Build a mock extension API — the object passed to an extension's
   default/activate fn. Mirrors the shape nyma's effort_switcher /
   model_switcher / clear_session tests build inline.

   Provides a notify-capturing :ui, registerCommand/unregisterCommand
   backed by an atom the test can inspect via :_commands, plus
   matching :_notifications and :_events atoms for on/off/emit
   capture.

   Options (all optional):
     :ui-overrides  map of extra :ui fields (merged onto defaults)
     :extras        map of extra top-level api fields"
  [& [{:keys [ui-overrides extras]}]]
  (let [commands      (atom {})
        notifications (atom [])
        event-handlers (atom {})
        ui-base       {:available true
                       :notify    (fn [msg & [level]]
                                    (swap! notifications conj
                                           {:message msg :level (or level "info")}))
                       :showOverlay noop1
                       :setTitle    noop1}
        ui-map        (merge ui-base (or ui-overrides {}))
        ui-js         (clj->js ui-map)
        base          #js {:ui ui-js

                           :registerCommand
                           (fn [name opts]
                             (swap! commands assoc name opts))

                           :unregisterCommand
                           (fn [name]
                             (swap! commands dissoc name))

                           :on
                           (fn [event handler & _]
                             (swap! event-handlers update event (fnil conj []) handler))

                           :off
                           (fn [event handler]
                             (swap! event-handlers update event
                                    (fn [hs] (filterv #(not= % handler) hs))))

                           :emit
                           (fn [event data]
                             (doseq [h (get @event-handlers event [])]
                               (h data)))

                           ;; Escape hatches the test can peek at
                           :_commands       commands
                           :_notifications  notifications
                           :_event-handlers event-handlers}]
    (when (seq extras)
      (js/Object.assign base (clj->js extras)))
    base))
