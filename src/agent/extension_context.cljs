(ns agent.extension-context
  (:require [agent.utils.js-interop :as ji]
            [agent.model-info :as model-info]
            [agent.sessions.compaction :refer [compact settings->opts resolve-settings]]
            [agent.sessions.manager :as sessions]
            [agent.token-estimation :as te]))

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
                                 ;; The registry was never passed, so compaction
                                 ;; fell back to a hardcoded 100000 and ignored
                                 ;; the model's real window entirely.
                                 (compact session (:model (:config agent)) (:events agent)
                                          (merge (settings->opts (resolve-settings (:settings agent)))
                                                 {:model-registry (:model-registry agent)
                                                   :state-atom     (:state agent)
                                                  :model-key (model-info/config-model-key
                                                              (:config agent))}
                                                 ;; squint has no js->clj — this threw for ANY extension
                                                 ;; calling api.compact(opts).
                                                 (ji/js->clj* opts)))))

         ;; System prompt
         :getSystemPrompt    (fn [] (:system-prompt (:config agent)))

         ;; Session directory
         :getSessionDirectory (fn [] (str (.. js/process -env -HOME) "/.nyma/sessions"))

         ;; Token budget & model info
         :getTokenBudget     (fn []
                               ;; Model may be nil before login or string in tests.
                               (let [m        (:model (:config agent))
                                     model-id (cond
                                                (nil? m)    "unknown"
                                                (string? m) m
                                                :else       (or (.-modelId m) "unknown"))
                                     mr       (:model-registry agent)
                                     ;; The PROVIDER-QUALIFIED key, as the api's
                                     ;; own getTokenBudget uses. Model ids are not
                                     ;; unique across providers, so a bare one
                                     ;; resolved to whichever registered last —
                                     ;; and a gateway registers only qualified
                                     ;; keys, so on a relay this missed entirely
                                     ;; and fell back to the 100000 default.
                                     window   (if mr
                                                ((:context-window mr)
                                                 (model-info/config-model-key (:config agent)))
                                                100000)
                                     state    @(:state agent)
                                     used     (te/estimate-messages-tokens (:messages state))
                                     reserved (js/Math.floor (* window 0.3))]
                                 #js {:contextWindow   window
                                      :inputBudget     (- window reserved)
                                      :tokensUsed      used
                                      :tokensRemaining (- window reserved used)
                                      :model           model-id}))
         :getModelInfo        (fn [& [model-id]]
                                (let [m  (:model (:config agent))
                                      id (or model-id
                                             (cond
                                               (nil? m)    nil
                                               (string? m) m
                                               :else       (.-modelId m))
                                             "unknown")
                                      ;; Same reason: with no explicit id, ask
                                      ;; about the model we are actually running,
                                      ;; by the key it was registered under.
                                      key (if model-id id (model-info/config-model-key (:config agent)))
                                      mr (:model-registry agent)]
                                  (if mr
                                    (clj->js ((:get mr) key))
                                    #js {:context-window 100000})))
         :estimateTokens      (fn [text] (te/estimate-tokens text))}))

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

    ;; Release the tools any active skill registered before the reducer wipes
    ;; the record of them. Done inline rather than via resources.skills, which
    ;; reaches this namespace through agent.extensions and would cycle.
    (let [release-skill-tools!
          (fn []
            (when-let [unregister (:unregister (:tool-registry agent))]
              (doseq [[_ tools] (:skill-tools @(:state agent))
                      t         tools]
                (try (unregister t) (catch :default _e nil)))))

          reseed!
          (fn [session]
            ;; Moving the leaf changes which messages the session tree yields,
            ;; and nothing reseeded the live state from it — so an extension
            ;; navigated to another branch, the interface showed that branch,
            ;; and the model went on seeing the abandoned one. Replay is not a
            ;; new turn, so JSONL re-append is suppressed for its duration.
            (release-skill-tools!)
            (swap! (:state agent) assoc :replaying-session? true)
            (try
              ((:dispatch! (:store agent)) :messages-cleared {})
              (doseq [msg (sessions/session->seed-messages ((:build-context session)))]
                ((:dispatch! (:store agent)) :message-added {:message msg}))
              (finally
                (swap! (:state agent) assoc :replaying-session? false))))]

      (aset base "newSession"
            (fn [_opts]
              (release-skill-tools!)
              ((:dispatch! (:store agent)) :messages-cleared {})
              nil))

      (aset base "__reseed" reseed!))

    (aset base "fork"
          (fn [entry-id]
            (when-let [session @(:session agent)]
              (let [old ((:branch session) entry-id)]
                ((.-__reseed base) session)
                old))))

    (aset base "navigateTree"
          (fn [target-id _opts]
            (when-let [session @(:session agent)]
              (let [old ((:branch session) target-id)]
                ((.-__reseed base) session)
                old))))

    (aset base "reload"
          (fn []
            ((:emit (:events agent)) "reload" {})))

    base))
