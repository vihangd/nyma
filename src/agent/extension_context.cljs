(ns agent.extension-context
  (:require [agent.sessions.compaction :refer [compact]]))

(defn create-extension-context
  "Build the context object passed to event handlers and tool execute.
   Mirrors pi's ExtensionContext:
   ctx.ui, ctx.hasUI, ctx.cwd, ctx.signal, ctx.sessionManager,
   ctx.modelRegistry, ctx.model, ctx.isIdle, ctx.abort, ctx.hasPendingMessages,
   ctx.shutdown, ctx.getContextUsage, ctx.compact, ctx.getSystemPrompt."
  [agent]
  (let [ui (when-let [api (.-extension-api agent)] (.-ui api))]
    #js {:ui             (or ui #js {:available false})
         :hasUI          (boolean (and ui (.-available ui)))
         :cwd            (js/process.cwd)
         :signal         (when-let [ctrl-atom (:abort-controller agent)]
                           (.-signal @ctrl-atom))

         ;; Session manager
         :sessionManager (when-let [s @(:session agent)]
                           #js {:getEntries (fn [] ((:get-entries s)))
                                :getBranch  (fn [] ((:get-branch s)))
                                :getLeafId  (fn [] ((:get-leaf-id s)))})

         ;; Model access
         :modelRegistry  (when-let [pr (:provider-registry agent)]
                           #js {:list    (fn [] (clj->js ((:list pr))))
                                :get     (fn [name] (clj->js ((:get pr) name)))
                                :current (fn [] (:model (:config agent)))})
         :model          (:model (:config agent))

         ;; Agent lifecycle
         :isIdle             (fn [] (empty? (:active-executions @(:state agent))))
         :abort              (fn []
                               (when-let [ctrl-atom (:abort-controller agent)]
                                 (.abort @ctrl-atom)))
         :hasPendingMessages (fn []
                               (or (seq @(:steer-queue agent))
                                   (seq @(:follow-queue agent))))
         :shutdown           (fn [] (js/process.exit 0))

         ;; Context usage
         :getContextUsage    (fn []
                               (let [s @(:state agent)]
                                 #js {:inputTokens  (or (:total-input-tokens s) 0)
                                      :outputTokens (or (:total-output-tokens s) 0)
                                      :cost         (or (:total-cost s) 0)
                                      :turns        (or (:turn-count s) 0)}))

         ;; Compaction
         :compact            (fn [opts]
                               (when-let [session @(:session agent)]
                                 (compact session (:model (:config agent)) (:events agent)
                                   (when opts (js->clj opts :keywordize-keys true)))))

         ;; System prompt
         :getSystemPrompt    (fn [] (:system-prompt (:config agent)))

         ;; Session directory
         :getSessionDirectory (fn [] (str (.. js/process -env -HOME) "/.nyma/sessions"))}))

(defn create-command-context
  "Extended context for command handlers. Includes waitForIdle, newSession,
   fork, navigateTree, reload — methods only available in command scope."
  [agent]
  (let [base (create-extension-context agent)]
    ;; Extend with command-only methods
    (aset base "waitForIdle"
      (fn []
        (js/Promise.
          (fn [resolve]
            (let [check (fn check []
                          (if (empty? (:active-executions @(:state agent)))
                            (resolve)
                            (js/setTimeout check 100)))]
              (check))))))

    (aset base "newSession"
      (fn [_opts]
        ((:dispatch! (:store agent)) :messages-cleared {})
        nil))

    (aset base "fork"
      (fn [entry-id]
        (when-let [session @(:session agent)]
          ((:branch session) entry-id))))

    (aset base "navigateTree"
      (fn [target-id _opts]
        (when-let [session @(:session agent)]
          ((:branch session) target-id))))

    (aset base "reload"
      (fn []
        ((:emit (:events agent)) "reload" {})))

    base))
