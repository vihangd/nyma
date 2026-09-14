(ns agent.extensions
  (:require [agent.loop :refer [steer follow-up]]
            [agent.extension-context :refer [create-extension-context]]
            [agent.token-estimation :as te]
            [agent.providers.registry :as registry-utils :refer [build-provider-entry]]
            [agent.model-info :as model-info]
            [agent.tool-metadata :as tool-metadata]
            [agent.pricing :as pricing]
            [agent.ui.status-line-segments :as status-segments]
            [agent.debug :as dbg]))

(defn ext-flag-short-name
  "The CLI-visible name for a registered flag. Registration is namespace-scoped
   as `ns__name` (extension_scope uses `__`, not `/`, to satisfy the Anthropic
   tool-name pattern), so the short name has to survive BOTH separators —
   splitting on `/` alone made every extension flag unreachable from the CLI."
  [full-name]
  (last (.split (last (.split (str full-name) "/")) "__")))

(defn parse-ext-flag-argv
  "Parse `--ext-name` / `--ext-name=value` out of argv into
   {short-name raw-value-or-nil}. nil value means the bare boolean form.
   Pure over the argv you pass; defaults to this process's argv."
  ([] (parse-ext-flag-argv (.slice js/process.argv 2)))
  ([argv]
   (reduce (fn [acc arg]
             (if-not (.startsWith (str arg) "--ext-")
               acc
               (let [rest-arg (.slice (str arg) 6)
                     eq-idx   (.indexOf rest-arg "=")]
                 (if (>= eq-idx 0)
                   (assoc acc (.slice rest-arg 0 eq-idx) (.slice rest-arg (inc eq-idx)))
                   (assoc acc rest-arg nil)))))
           {}
           argv)))

(defn coerce-flag-default
  "Coerce a registered flag's `:default` to its declared type.

   `coerce-flag-value` only ever saw CLI argv, where absence is `:absent` and a
   bare flag is nil. A default arrives already-typed from a literal, or
   wrongly-typed from settings JSON. nil means \"no default\" and must stay nil,
   so this cannot reuse coerce-flag-value's nil-means-true boolean rule. Pure."
  [type raw]
  (cond
    (nil? raw)         nil
    (= type "boolean") (cond (boolean? raw) raw
                             (= raw "false") false
                             (= raw "true")  true
                             :else           (boolean raw))
    (= type "number")  (if (number? raw) raw (js/Number raw))
    (= type "string")  (str raw)
    :else              raw))

(defn coerce-flag-value
  "Coerce a raw CLI string to the flag's declared type. `raw` is :absent when
   the flag was not passed (→ nil, so the default applies), or nil for the bare
   boolean form."
  [type raw]
  (cond
    (= raw :absent) nil
    (= type "boolean") (if (nil? raw) true (not= raw "false"))
    (= type "number")  (js/Number raw)
    (= type "string")  (or raw "")
    :else raw))

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
   - Provider management (registerProvider, unregisterProvider)
   - Model/thinking control (setModel, getThinkingLevel, setThinkingLevel)
   - Inter-extension events
   - UI hooks"
  [agent]
  (let [;; event → Map(handler → wrapped). A JS Map keyed by identity: squint's
        ;; `assoc` stringifies a function key, so two closures with the same
        ;; source collapsed into one entry and `off` removed the wrong one.
        ;; Nested per event so one handler on two events keeps both wrappers.
        handler-map   (js/Map.)
        watch-counter (atom 0)
        ;; provider name → pricing keys it wrote, so unregister can undo them.
        provider-rows (atom {})]
    #js {:on                (fn [event handler & [priority]]
                              (let [wrapped (fn [data]
                                              (handler data (create-extension-context agent)))
                                    m       (or (.get handler-map event)
                                                (let [nm (js/Map.)] (.set handler-map event nm) nm))]
                                (.set m handler wrapped)
                                ((:on (:events agent)) event wrapped priority)))
         :off               (fn [event handler]
                              (let [m       (.get handler-map event)
                                    wrapped (or (and m (.get m handler)) handler)]
                                (when m (.delete m handler))
                                ((:off (:events agent)) event wrapped)))

       ;; ── Tool management ─────────────────────────────────
         :registerTool      (fn [name tool-def]
                              ;; Optional `:safety` on the def feeds the
                              ;; permission gate's category and file-editing?
                              ;; e.g. #js {:destructive? true
                              ;;           :capabilities ["filesystem" "write"]}
                              (when-let [s (and tool-def (aget tool-def "safety"))]
                                (let [caps (aget s "capabilities")]
                                  (tool-metadata/register-metadata!
                                   name
                                   (cond-> {:destructive?           (boolean (aget s "destructive?"))
                                            :network?               (boolean (aget s "network?"))
                                            :read-only?             (boolean (aget s "read-only?"))
                                            :requires-confirmation? (boolean (aget s "requires-confirmation?"))}
                                     caps (assoc :capabilities (set caps))))))
                              ((:register (:tool-registry agent)) name tool-def))
         :unregisterTool    (fn [name]
                              (tool-metadata/unregister-metadata! name)
                              ((:unregister (:tool-registry agent)) name))
         :getActiveTools    (fn [] (clj->js (keys ((:get-active (:tool-registry agent))))))
         :getAllTools        (fn [] (clj->js (keys ((:all (:tool-registry agent))))))
         :getTool           (fn [name] (get ((:all (:tool-registry agent))) name))
         :setActiveTools    (fn [names]
                              ((:set-active (:tool-registry agent)) (set (or names []))))

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
                                (dbg/debug "sendUserMessage"
                                           (str "deliverAs: " deliver-as
                                                " | text: " (.slice (str text) 0 200)
                                                " | stack: " (.-stack (js/Error.))))
                                (case deliver-as
                                  "steer"    (steer agent {:role "user" :content text})
                                  "followUp" (follow-up agent {:role "user" :content text}))))

       ;; ── State access ────────────────────────────────────
       ;; Extensions read from agent state, push events back through the
       ;; bus, and (for those that need direct mutation) reach :__state-atom.
         :getState          (fn [] @(:state agent))
         :dispatch          (fn [event-type data]
                              ((:emit (:events agent))
                               (str event-type)
                               (clj->js data)))
         :onStateChange     (fn [listener]
                              ;; Returns an unsubscribe fn. Keyed by a counter
                              ;; token: squint stores watches in a plain object,
                              ;; so a function key is stringified and two
                              ;; listeners with identical source shared a slot.
                              (let [k (str "ext-watch-" (swap! watch-counter inc))]
                                (add-watch (:state agent) k
                                           (fn [_k _r _o n] (listener n)))
                                (fn [] (remove-watch (:state agent) k))))
         ;; Dot accessor `.-__state-atom` compiles to `.__state_atom` in
         ;; squint (hyphens become underscores), so use the underscore
         ;; spelling here too — otherwise the JS property name and the
         ;; lookup name don't match and consumers get nil.
         :__state_atom      (:state agent)

       ;; ── Middleware API ──────────────────────────────────
         :addMiddleware     (fn [interceptor & [opts]]
                              (when-let [pipeline (:middleware agent)]
                                ((:add pipeline) interceptor opts)))
         :removeMiddleware  (fn [interceptor-name]
                              (when-let [pipeline (:middleware agent)]
                                ((:remove pipeline) interceptor-name)))

       ;; ── Shell execution ─────────────────────────────────
         :exec              (fn [cmd args]
                              (let [cmd-args (into [cmd] (or args []))
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

       ;; ── Long-lived process spawning ────────────────────
         :spawn             (fn [cmd args opts]
                              (let [cmd-args (into [cmd] (or args []))
                                    js-opts  (or opts #js {})
                                    proc     (js/Bun.spawn (clj->js cmd-args)
                                                           #js {:cwd    (or (.-cwd js-opts) (js/process.cwd))
                                                                :stdout "pipe"
                                                                :stderr "pipe"
                                                                :stdin  "pipe"
                                                                :env    (or (.-env js-opts) js/process.env)})]
                                #js {:pid    (.-pid proc)
                                     :stdin  (.-stdin proc)
                                     :stdout (.-stdout proc)
                                     :stderr (.-stderr proc)
                                     :kill   (fn [& [sig]] (.kill proc (or sig "SIGTERM")))
                                     :exited (.-exited proc)
                                     :ref    proc}))

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

       ;; ── Status line segments ────────────────────────────
         :registerStatusSegment
         (fn [id config]
           (status-segments/register-segment (str id)
                                             {:category     (or (.-category config) :extension)
                                              :auto-append? (boolean (.-autoAppend config))
                                              :position     (case (.-position config)
                                                              "left"  :left
                                                              "right" :right
                                                              :right)
                                              :render       (.-render config)}))
         :unregisterStatusSegment
         (fn [id]
           (status-segments/unregister-segment (str id)))

       ;; ── Provider management ─────────────────────────────
         :registerProvider  (fn [name config]
                              (let [;; Convert JS config to CLJ via JSON round-trip
                                    cfg-raw (js/JSON.parse (js/JSON.stringify config))
                                  ;; Extract functions that survive JSON (they won't — handle separately)
                                    create-fn (or (.-createModel config) (aget config "create-model"))
                                    oauth-obj (.-oauth config)
                                    stream-fn (.-streamFn config)
                                  ;; Build CLJ config from JSON-safe fields
                                    cfg {:create-model  create-fn
                                         :base-url      (or (.-baseUrl cfg-raw) (aget cfg-raw "base-url"))
                                         :api-key-env   (or (.-apiKeyEnv cfg-raw) (aget cfg-raw "api-key-env"))
                                         :api           (.-api cfg-raw)
                                       ;; Tokens this provider adds to every
                                       ;; request that nyma cannot see — a
                                       ;; gateway's injected system prompt, say.
                                         :overhead-tokens (or (.-overheadTokens cfg-raw)
                                                              (aget cfg-raw "overhead-tokens"))
                                         :stream-fn     stream-fn
                                         :oauth         (when oauth-obj
                                                          {:name          (.-name oauth-obj)
                                                           :login         (.-login oauth-obj)
                                                           :refresh-token (or (.-refreshToken oauth-obj)
                                                                              (aget oauth-obj "refresh-token"))
                                                           :get-api-key   (or (.-getApiKey oauth-obj)
                                                                              (aget oauth-obj "get-api-key"))})}
                                  ;; Extract model list
                                    models-arr (.-models config)
                                    models (when models-arr
                                             (vec (map (fn [m]
                                                         {:id             (.-id m)
                                                          :name           (.-name m)
                                                          ;; No default: a fabricated context window is
                                                          ;; worse than none, because compaction plans
                                                          ;; against it. Left nil, the model registry
                                                          ;; falls through to the vendor's own entry.
                                                          :context-window (or (.-contextWindow m)
                                                                              (aget m "context-window"))
                                                          :max-tokens     (or (.-maxTokens m) (aget m "max-tokens"))
                                                          :reasoning      (.-reasoning m)
                                                          :input          (when (.-input m) (vec (.-input m)))
                                                          :cost           (when (.-cost m)
                                                                            {:input  (.-input (.-cost m))
                                                                             :output (.-output (.-cost m))
                                                                             ;; Optional: lets a provider price
                                                                             ;; cached input separately. `or` would coerce a declared
                                                                             ;; rate of 0 to the fallback and then to nil, so a provider
                                                                             ;; advertising FREE cache reads looked like one that never
                                                                             ;; declared them — and its cached tokens were billed at the
                                                                             ;; full input rate.
                                                                             :cache-read  (let [a (.-cacheRead (.-cost m))]
                                                                                            (if (some? a) a (aget (.-cost m) "cache_read")))
                                                                             :cache-write (let [a (.-cacheWrite (.-cost m))]
                                                                                            (if (some? a) a (aget (.-cost m) "cache_write")))})})
                                                       models-arr)))
                                    cfg (if models (assoc cfg :models models) cfg)
                                  ;; Remove nil create-model so build-provider-entry can auto-generate
                                    cfg (if (:create-model cfg) cfg (dissoc cfg :create-model))
                                    entry (build-provider-entry name cfg)]
                              ;; Register provider
                                ((:register (:provider-registry agent)) name entry)
                              ;; A gateway/relay carries OTHER vendors' models under
                              ;; those vendors' own ids. Two consequences:
                              ;;   - it must not write bare-id entries, or it silently
                              ;;     overwrites the first-party provider's metadata
                              ;;     (last registration wins);
                              ;;   - its costs must not fall back to the bare id, or
                              ;;     we display a price the user isn't charged.
                                (let [gateway? (boolean (.-unpriced config))
                                      qualify  (fn [id] (str name "/" id))
                                      ;; Only rows THIS registration adds are
                                      ;; recorded for undo — a row already
                                      ;; present belongs to whoever wrote it.
                                      was-unpriced? (contains? @pricing/unpriced-providers name)
                                      price!   (fn [k rates]
                                                 (when-not (contains? @pricing/token-costs k)
                                                   (swap! provider-rows update-in [name :pricing] conj k))
                                                 (swap! pricing/token-costs assoc k rates))]
                                  (if gateway?
                                    (swap! pricing/unpriced-providers conj name)
                                    (swap! pricing/unpriced-providers disj name))
                                ;; Auto-register model metadata. Non-gateway providers
                                ;; register both keys so bare-id callers keep working;
                                ;; the qualified key disambiguates shared model ids.
                                  (when models
                                    ((:register (:model-registry agent))
                                     (into {} (mapcat (fn [m]
                                                        (let [meta {:context-window (:context-window m)}
                                                              q    [[(qualify (:id m)) meta]]]
                                                          (if gateway?
                                                            q
                                                            (cons [(:id m) meta] q))))
                                                      models)))
                                  ;; Auto-register pricing
                                    (swap! provider-rows assoc name {:gateway? (and gateway? (not was-unpriced?))
                                                                     :pricing  []})
                                    (doseq [m models]
                                      (when-let [cost (:cost m)]
                                        ;; 4-element form only when a cache rate
                                        ;; is declared; otherwise keep the plain
                                        ;; [input output] shape so nothing that
                                        ;; reads these pairs has to change.
                                        (let [cr (:cache-read cost)
                                              cw (:cache-write cost)
                                              rates (if (or (number? cr) (number? cw))
                                                      [(or (:input cost) 0)
                                                       (or (:output cost) 0)
                                                       cr cw]
                                                      [(or (:input cost) 0)
                                                       (or (:output cost) 0)])]
                                          (when-not gateway?
                                            (price! (:id m) rates))
                                          (price! (qualify (:id m)) rates))))))))
         :unregisterProvider (fn [name]
                               ((:unregister (:provider-registry agent)) name)
                               ;; Undo the global pricing rows registerProvider
                               ;; wrote, or a reload re-adds them on top.
                               ;; Model-registry entries stay: re-registering
                               ;; identical metadata is idempotent.
                               (when-let [row (get @provider-rows name)]
                                 (swap! pricing/token-costs #(apply dissoc % (:pricing row)))
                                 (when (:gateway? row)
                                   (swap! pricing/unpriced-providers disj name))
                                 (swap! provider-rows dissoc name)))

       ;; ── Model control ───────────────────────────────────
         :setModel          (fn [model-spec]
                              ;; First-slash split (shared with the CLI --model
                              ;; parser): HuggingFace-style ids like
                              ;; poolside/Laguna-S-2.1-NVFP4 contain slashes —
                              ;; the old split-on-every-slash kept only the org
                              ;; segment as the model id.
                              (let [registry (:provider-registry agent)
                                    [provider model-id] (registry-utils/split-model-spec model-spec)]
                              ;; Try to resolve from registry, fall back to direct use
                                (let [model (if (and provider ((:get registry) provider))
                                              ((:resolve registry) provider model-id)
                                              model-spec)]
                                  ((:dispatch! (:store agent)) :model-changed {:model model})
                                ;; Update config for the loop to pick up
                                  (set! (.-model (:config agent)) model)
                                ;; Persist the user-friendly provider name on
                                ;; the config so status-line consumers can
                                ;; render `<provider>/<model>`. The AI SDK's
                                ;; `model.provider` returns underlying-class
                                ;; names like "openai.chat" (uninformative for
                                ;; users using minimax / openrouter / etc.).
                                ;; This way the registry-side label survives.
                                  (aset (:config agent) "active-provider-name"
                                        (or provider ""))
                                ;; Emit model_select event
                                  ((:emit (:events agent)) "model_select"
                                                           {:model    model
                                                            :modelId  (str model-spec)
                                                            :provider (or provider "")}))))

       ;; ── Side-effect-free model resolution ───────────────
       ;; Used by extensions (e.g. advisor) that need to invoke a model
       ;; OTHER than the active one without mutating agent state.
       ;; Accepts either "provider/model" or separate (provider, model-id) args.
         :resolveModel      (fn [provider-or-spec & [model-id]]
                              (let [registry (:provider-registry agent)
                                    [provider mid]
                                    (if model-id
                                      [(str provider-or-spec) (str model-id)]
                                      (let [parts (.split (str provider-or-spec) "/")]
                                        (if (> (count parts) 1)
                                          [(first parts) (.join (.slice parts 1) "/")]
                                          [nil (str provider-or-spec)])))]
                                (when (and provider ((:get registry) provider))
                                  ((:resolve registry) provider mid))))

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

       ;; ── Global event bus emit (main agent bus) ──────────
       ;; Complements api.on/api.off which subscribe to the main bus.
       ;; Use api.emitGlobal to fire events that other extensions
       ;; subscribed to via api.on rather than api.events.on.
         :emitGlobal        (fn [event data]
                              ((:emit (:events agent)) event data))

       ;; ── Flags ──────────────────────────────────────────────
       ;; The pending-argv lookup matters: extensions load (cli.cljs:556)
       ;; BEFORE resolve-ext-flags runs (cli.cljs:601), so an extension that
       ;; reads its own flag during activation would otherwise always see nil.
       ;; Applying the parsed argv value here makes getFlag correct as soon as
       ;; the flag is registered.
         :registerFlag     (fn [name config]
                             (let [type (or (when config (.-type config)) "boolean")]
                               (swap! (:flags agent) assoc name
                                      {:description (when config (.-description config))
                                       :type        type
                                       ;; Coerce the DEFAULT too, not just the
                                       ;; CLI value. desktop_notify passes
                                       ;; `:default (:enabled config)` straight
                                       ;; from settings.json, so
                                       ;; `{"enabled": "false"}` stored the
                                       ;; STRING "false" — truthy — and kept the
                                       ;; feature on. It is the only flag whose
                                       ;; default comes from user JSON, and the
                                       ;; only one that could carry a wrong type.
                                       :default     (coerce-flag-default
                                                     type
                                                     (when config (.-default config)))
                                       :value       (coerce-flag-value
                                                     type
                                                     (get (parse-ext-flag-argv) (ext-flag-short-name name) :absent))})))
         :unregisterFlag   (fn [name]
                             (swap! (:flags agent) dissoc name))
         :getFlag          (fn [name]
                             (when-let [flag (get @(:flags agent) name)]
                               (if (some? (:value flag))
                                 (:value flag)
                                 (:default flag))))
         :getGlobalFlag    (fn [full-name]
                             (when-let [flag (get @(:flags agent) full-name)]
                               (if (some? (:value flag))
                                 (:value flag)
                                 (:default flag))))

       ;; ── Token budget ───────────────────────────────────────
         :getTokenBudget    (fn []
                              ;; Model may be nil before login or string in tests.
                              (let [m        (:model (:config agent))
                                    model-id (cond
                                               (nil? m)    "unknown"
                                               (string? m) m
                                               :else       (or (.-modelId m) "unknown"))
                                    ;; Provider-qualified: a bare id is ambiguous
                                    ;; across providers and read whichever one
                                    ;; registered last, so headroom (which reads
                                    ;; this) could plan against another provider's
                                    ;; window for the same model name.
                                    window   ((:context-window (:model-registry agent))
                                              (model-info/config-model-key (:config agent)))
                                    state    @(:state agent)
                                    msgs-used (te/estimate-messages-tokens (:messages state))
                                    ;; Add a fixed overhead for system prompt + tool schemas.
                                    ;; These are sent on every request but not in state.messages,
                                    ;; so without this adjustment token_suite underestimates
                                    ;; how full the context is — causing it to miss the threshold
                                    ;; and letting the context exceed the provider's actual limit.
                                    ;; 6000 ≈ system-prompt (~1500) + 20 tool schemas (~4500).
                                    overhead 6000
                                    used     (+ msgs-used overhead)
                                    reserved (js/Math.floor (* window 0.3))]
                                #js {:contextWindow   window
                                     :inputBudget     (- window reserved)
                                     :tokensUsed      used
                                     :tokensRemaining (- window reserved used)
                                     :model           model-id}))

       ;; ── Model info ─────────────────────────────────────────
       ;; The active model as "<provider>/<model-id>", read from the agent
       ;; config — the one place BOTH cli resolution paths write it. Extensions
       ;; previously had to dig it out of the state atom, where the CLI records
       ;; it only on the early path (:base-model-spec) and never as :model,
       ;; because it assigns (.-model (:config agent)) directly rather than
       ;; calling setModel. small-model's per-model profiles depended on that
       ;; and went inert on any run that resolved late — editStrategy hid `edit`
       ;; on one task and not another in the same 3-trial run.
         :getActiveModelSpec (fn []
                               (let [cfg  (:config agent)
                                     m    (some-> cfg .-model)
                                     mid  (cond
                                            (nil? m)    nil
                                            (string? m) m
                                            :else       (or (some-> m .-modelId)
                                                            (some-> m .-id)))
                                     prov (aget cfg "active-provider-name")]
                                 (cond
                                   (and (seq (str prov)) (seq (str mid))) (str prov "/" mid)
                                   (seq (str mid)) (str mid)
                                   :else (str (:base-model-spec @(:state agent)) ""))))
         :getModelInfo      (fn [& [model-id]]
                              (let [m  (:model (:config agent))
                                    id (or model-id
                                           (cond
                                             (nil? m)    nil
                                             (string? m) m
                                             :else       (.-modelId m))
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

       ;; ── Settings access ────────────────────────────────────
         :getSettings       (fn []
                              ;; Guarded: `:settings` is not always a full
                              ;; settings manager — test harnesses and the
                              ;; loader smoke build minimal agents — and an
                              ;; extension asking for settings at activation
                              ;; time should get nil, not a TypeError that
                              ;; fails the whole load.
                              (let [sm (:settings agent)]
                                (when (and sm (fn? (:get sm)))
                                  ((:get sm)))))

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
                                 :custom      nil
                                 :setEditorValue nil
                                 :getEditorValue nil}}))
