(ns agent.extensions.custom-provider-relay.index
  "Register any remote OpenAI- or Anthropic-compatible gateway as a provider,
   without writing code.

   A gateway (relay, proxy, LLM gateway) fronts OTHER vendors' models under
   those vendors' own ids. That is the difference from `custom_provider_local`,
   which this deliberately does not extend:

     - a missing key is a real error here, not something to paper over with a
       placeholder — a remote gateway answers a dummy key with an opaque 401;
     - its prices are its own, so its models must not inherit the first-party
       rate for the same model id (see agent.pricing/unpriced-providers);
     - its catalogue is large and changes without notice, so the model list is
       discovered from `/v1/models` rather than hardcoded.

   Configuration in settings.json:
     {
       \"providers\": [
         {
           \"name\":       \"yunwu\",                  // /model yunwu/<id>
           \"baseUrl\":    \"https://yunwu.ai/v1\",
           \"apiKeyEnv\":  \"YUNWU_API_KEY\",          // or /login yunwu
           \"api\":        \"openai-compatible\",      // or \"anthropic\"
           \"include\":    [\"claude\", \"/^gpt-5/\"],   // allow-list, optional
           \"exclude\":    [\"preview\"],              // subtracted, optional
           \"discover\":   true,                      // GET /v1/models
           \"models\":     [{\"id\": \"...\", \"contextWindow\": 200000}]
         }
       ]
     }

   Presets ship for yunwu (https://yunwu.ai), a New API relay, in both
   protocols. Prompt caching for Claude models works only on the `anthropic`
   variant: the OpenAI-compatible path cannot carry cache_control breakpoints —
   @ai-sdk/openai drops them, and New API's OpenAI→Claude request converter
   discards any that survive."
  (:require ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            [agent.debug :as d]
            [agent.providers.model-fetch :as model-fetch]
            [agent.utils.credentials :as credentials]
            [agent.utils.reasoning-stream :as rs]
            [agent.utils.reasoning-request :as rr]))

;; ── Presets ──────────────────────────────────────────────────
;; Both point at the same base URL. `createAnthropic` appends /messages and
;; `createOpenAI` appends /chat/completions, and New API serves both.
;;
;; The seed lists are deliberately tiny — enough that `/model` is useful before
;; the first discovery call, not an attempt to mirror the catalogue.

(def ^:private presets
  [{:name      "yunwu"
    :base-url  "https://yunwu.ai/v1"
    :api-key-env "YUNWU_API_KEY"
    :api       "openai-compatible"
    :discover  true
    ;; yunwu reports `supported_endpoint_types` per model, so we ask for the
    ;; protocols we speak instead of guessing from names. Its catalogue also
    ;; carries image ("mj动作", "wan视频生成"), audio ("语音转文字") and
    ;; retrieval ("rerank") endpoints, plus many entries that declare NO
    ;; endpoint at all and are simply unusable.
    ;;
    ;; Both OpenAI protocols are admitted; create-model-fn picks per model,
    ;; because much of the GPT-5.x line is /responses-only.
    :endpoint-types ["openai" "openai-response"]
    ;; Claude ids are served over both `anthropic` and `openai` here, so they
    ;; would otherwise appear under both providers. Keep them on yunwu-claude:
    ;; that is the only variant where prompt caching works.
    :exclude   ["claude"]
    :models    [{:id "deepseek-v3.2"}
                ;; Also declares the `anthropic` endpoint — the only DeepSeek id
                ;; here that does — and it genuinely works. Kept on the OpenAI
                ;; side anyway: probing showed cache_control is accepted and
                ;; IGNORED (cache_creation_input_tokens was 0 on every call)
                ;; because caching here is DeepSeek's own automatic prefix cache,
                ;; and `thinking` returns no thinking blocks. Tagging it
                ;; anthropic would only make kv_cache spend breakpoints and
                ;; thinking.cljs attach a parameter, both for nothing.
                ;;
                ;; Caching still works — a cold prefix read 0, the identical
                ;; request next read 7133 with no cache_control sent at all.
                {:id "deepseek-v4-pro" :context-window 1048576}
                {:id "glm-4.7"}
                {:id "gemini-2.5-flash"}
                {:id "gpt-5.2"}]}
   {:name      "yunwu-claude"
    :base-url  "https://yunwu.ai/v1"
    :api-key-env "YUNWU_API_KEY"
    ;; Same account as `yunwu`, so one /login covers both.
    :credential-name "yunwu"
    :api       "anthropic"
    :discover  true
    ;; Confirmed against a live catalogue: Claude ids there declare
    ;; [anthropic, openai], so /v1/messages is genuinely served.
    :endpoint-types ["anthropic"]
    :models    [{:id "claude-opus-5"}
                {:id "claude-sonnet-5"}
                {:id "claude-haiku-4-5-20251001"}]}])

;; ── Settings ─────────────────────────────────────────────────

(defn- entry-get
  "Read `k` from a settings entry that may be a CLJS map, a JS object with
   camelCase keys, or a JS object with kebab-case keys."
  [e camel kebab]
  (or (get e (keyword kebab))
      (when (object? e) (or (aget e camel) (aget e kebab)))
      (get e camel)
      (get e kebab)))

(defn- ->vec [x]
  (cond
    (nil? x)              []
    (js/Array.isArray x)  (vec x)
    (vector? x)           x
    (seq? x)              (vec x)
    :else                 [x]))

(defn normalize-entry [e]
  {:name        (entry-get e "name" "name")
   :base-url    (entry-get e "baseUrl" "base-url")
   :api-key-env (entry-get e "apiKeyEnv" "api-key-env")
   :credential-name (entry-get e "credentialName" "credential-name")
   :api         (or (entry-get e "api" "api") "openai-compatible")
   :discover    (let [v (entry-get e "discover" "discover")]
                  ;; Absent means "yes" — a gateway's whole point is that we
                  ;; don't know its catalogue.
                  (if (nil? v) true (boolean v)))
   :include     (->vec (entry-get e "include" "include"))
   :exclude     (->vec (entry-get e "exclude" "exclude"))
   :endpoint-types (->vec (entry-get e "endpointTypes" "endpoint-types"))
   :overhead-tokens (let [n (entry-get e "overheadTokens" "overhead-tokens")]
                      (when (and (number? n) (pos? n)) n))
   :models      (mapv (fn [m]
                        {:id             (entry-get m "id" "id")
                         :name           (entry-get m "name" "name")
                         :context-window (or (entry-get m "contextWindow" "context-window")
                                             (entry-get m "ctx" "ctx"))
                         :cost           (entry-get m "cost" "cost")})
                      (->vec (entry-get e "models" "models")))})

(defn load-settings-entries [settings]
  (let [raw (when settings (or (get settings "providers") (get settings :providers)))]
    (mapv normalize-entry (->vec raw))))

(defn merge-entries
  "User entries override presets of the same name."
  [presets user-entries]
  (let [named (set (keep :name user-entries))]
    (vec (concat (remove (fn [p] (contains? named (:name p))) presets)
                 user-entries))))

;; ── Credentials ──────────────────────────────────────────────

(defn credential-name
  "Which `/login` entry this provider's key is stored under.

   Two providers can front the same gateway on different protocols (yunwu and
   yunwu-claude), and they share one account. Without this, `/login yunwu` would
   authenticate only half of it."
  [entry]
  (or (:credential-name entry) (:name entry)))

(defn resolve-key
  "Env var, then a key saved by `/login <credential-name>`. Nil when neither
   exists — callers must fail loudly rather than send a placeholder."
  [entry]
  (let [env-var (:api-key-env entry)
        cred    (credential-name entry)]
    (or (when (seq (str env-var)) (aget js/process.env (str env-var)))
        (credentials/read-credential cred)
        ;; Fall back to the provider's own name when it differs, so a key saved
        ;; against either name works.
        (when (not= cred (:name entry))
          (credentials/read-credential (:name entry))))))

(defn missing-key-message [entry]
  (str "No credentials for '" (:name entry) "'. "
       (if (seq (str (:api-key-env entry)))
         (str "Set the " (:api-key-env entry) " env var or run /login "
              (credential-name entry) ".")
         (str "Run /login " (credential-name entry) " to save a key."))))

;; ── Model creation ───────────────────────────────────────────

(defn pick-protocol
  "Which wire protocol to speak for one model.

   A gateway does not serve every model over every endpoint. On yunwu the
   GPT-5.x line splits: some ids are `[openai, openai-response]`, but many
   flagships — gpt-5.4, gpt-5-pro, gpt-5-codex, gpt-5.1-codex-max — are
   `[openai-response]` ONLY and 404 on /v1/chat/completions. Dispatching per
   model (the pattern custom_provider_opencode_zen already uses) is the
   difference between those models working and not being usable at all.

   `endpoints` is the model's declared supported_endpoint_types, or nil for a
   gateway that doesn't report them — in which case the entry's own `api` wins."
  [entry endpoints]
  (let [eps (set (map str (or endpoints [])))]
    (cond
      (= "anthropic" (:api entry))         :anthropic
      (= "openai-responses" (:api entry))  :responses
      ;; Only reachable over /responses.
      (and (seq eps)
           (contains? eps "openai-response")
           (not (contains? eps "openai")))  :responses
      :else                                 :chat)))

(def level-fn-atom (atom nil))

(defn chat-request-rewriter
  "Lift <think> back into reasoning_content (as before), then add the relay's
   reasoning effort.

   Measured on yunwu: `deepseek-v4-pro` returns NO reasoning by default and got
   17*23 wrong (403); with `reasoning_effort` it reasons and answers 391. The
   level is read per request, since /thinking can change at any time.

   Only the OpenAI-compatible chat path uses this — the Anthropic path already
   gets thinking through `agent.thinking`, which recognises its provider tag."
  []
  (fn [body-str init]
    (let [lifted (rs/lift-think-request-rewriter body-str init)]
      (try
        (if-let [r (rr/relay (when-let [f @level-fn-atom] (f)))]
          (let [body (js/JSON.parse lifted)]
            (if (nil? (.-reasoning_effort body))
              (do (aset body "reasoning_effort" (:reasoning_effort r))
                  (js/JSON.stringify body))
              lifted))
          lifted)
        (catch :default _ lifted)))))

(defn create-model-fn
  "`endpoints-of` maps a model id to its declared endpoint types. It's a
   function rather than a value because discovery re-registers the provider,
   and models created later must see the refreshed list."
  [entry endpoints-of]
  (fn [model-id]
    (let [key (resolve-key entry)]
      (when-not key
        (throw (js/Error. (missing-key-message entry))))
      (case (pick-protocol entry (when endpoints-of (endpoints-of model-id)))
        ;; Native Messages API. This is the only path on which prompt caching
        ;; works: cache_control breakpoints survive it, and are dropped by the
        ;; OpenAI-compatible one.
        :anthropic
        ((createAnthropic #js {:apiKey  key
                               :baseURL (:base-url entry)})
         model-id)

        ;; /responses has native reasoning handling, so no think-lifting shim.
        :responses
        (.responses (createOpenAI #js {:apiKey        key
                                       :baseURL       (:base-url entry)
                                       :compatibility "compatible"})
                    model-id)

        ;; Gateways relay DeepSeek/GLM/Qwen, which stream reasoning through
        ;; `reasoning_content` and need it replayed on later turns.
        (.chat (createOpenAI #js {:apiKey        key
                                  :baseURL       (:base-url entry)
                                  :compatibility "compatible"
                                  :fetch         (rs/make-fetch (chat-request-rewriter))})
               model-id)))))

(defn- ->js-model [m]
  (let [o #js {:id (str (:id m)) :name (str (or (:name m) (:id m)))}]
    (when (:context-window m) (aset o "contextWindow" (:context-window m)))
    (when-let [c (:cost m)]
      (aset o "cost" #js {:input  (or (get c "input") (:input c) 0)
                          :output (or (get c "output") (:output c) 0)}))
    o))

(defn register!
  "Register (or re-register) `entry` with `models`. Re-registering is how a
   background refresh reaches the UI: the registry's register is a plain assoc
   and the catalogue reads :models at call time, so /model updates live.

   `endpoints-box` is shared across re-registrations so a model resolved after a
   refresh dispatches on the freshly discovered endpoint types."
  [api entry models endpoints-box]
  (when endpoints-box
    (reset! endpoints-box
            (into {} (keep (fn [m]
                             (when (:endpoints m)
                               [(str (:id m)) (:endpoints m)]))
                           models))))
  (.registerProvider api (:name entry)
                     #js {:createModel (create-model-fn
                                        entry
                                        (fn [id] (get (when endpoints-box @endpoints-box)
                                                      (str id))))
                          ;; Tokens this gateway adds to every request that nyma
                          ;; never sees, so context accounting can allow for them.
                          :overheadTokens (or (:overhead-tokens entry) 0)
                          :baseUrl     (:base-url entry)
                          :apiKeyEnv   (or (:api-key-env entry) "")
                          :api         (:api entry)
                          ;; This is a gateway: its rates are its own, and its
                          ;; ids collide with the vendors it relays.
                          :unpriced    true
                          :models      (clj->js (mapv ->js-model models))}))

;; ── Entry point ──────────────────────────────────────────────

(defn- declared-by-id [entry]
  (into {} (map (fn [m] [(str (:id m)) m]) (:models entry))))

(defn- merge-declared
  "Discovered models carry only an id and a display name. Overlay any
   contextWindow/cost the settings entry declared for that id — for a relay
   whose /v1/models says nothing, that is the only way to get real numbers."
  [discovered declared]
  (mapv (fn [m] (merge m (dissoc (get declared (str (:id m))) :name))) discovered))

(defn discovery-disabled?
  "NYMA_NO_MODEL_DISCOVERY=1 suppresses every network call this extension makes.

   Needed because discovery is on by default and reads the key from the ambient
   environment: without it, `bun test` on a machine that happens to export
   YUNWU_API_KEY would make live calls to a third party."
  []
  (let [v (aget js/process.env "NYMA_NO_MODEL_DISCOVERY")]
    (and (seq (str (or v ""))) (not= "0" (str v)) (not= "false" (str v)))))

(defn ^:async discover!
  "Register the cached list immediately, then refresh in the background when
   stale. Never blocks startup and never throws.

   `alive?` guards the late re-registration: a refresh in flight when the
   extension is deactivated would otherwise resurrect a provider that
   unregisterProvider has already removed."
  [api entry alive? endpoints-box]
  (let [pred     (model-fetch/make-filter entry)
        declared (declared-by-id entry)
        cached   (model-fetch/cached-models (:name entry) pred)]
    (when (and (alive?) (seq (:models cached)))
      (register! api entry (merge-declared (:models cached) declared) endpoints-box))
    (when (and (not (:fresh? cached)) (not (discovery-disabled?)))
      (when-let [key (resolve-key entry)]
        (when-let [fresh (js-await (model-fetch/refresh!
                                    (:name entry) (:base-url entry) key pred))]
          (when (alive?)
            (register! api entry (merge-declared fresh declared) endpoints-box)))))))

(defn ^:export default [api]
  (reset! level-fn-atom (when (.-getThinkingLevel api)
                          (fn [] (try (.getThinkingLevel api) (catch :default _e nil)))))
  (let [settings   (try (when (.-getSettings api) (.getSettings api))
                        (catch :default _ nil))
        user       (try (load-settings-entries settings)
                        (catch :default e
                          (d/warn "relay-provider" (str "bad `providers` setting: " (.-message e)))
                          []))
        entries    (merge-entries presets user)
        registered (atom [])
        disposed?  (atom false)
        alive?     (fn [] (not @disposed?))]

    (doseq [entry entries]
      (when (and (seq (str (:name entry))) (seq (str (:base-url entry))))
        (try
          ;; Register the seed list synchronously so the provider resolves even
          ;; if discovery never completes.
          (let [endpoints-box (atom {})]
            (register! api entry (:models entry) endpoints-box)
            (swap! registered conj (:name entry))
            (when (:discover entry)
              (-> (discover! api entry alive? endpoints-box)
                  (.catch (fn [e]
                            (d/warn "relay-provider" (str "discovery failed for " (:name entry) ": " (.-message e))))))))
          (catch :default e
            (d/warn "relay-provider" (str "failed to register " (:name entry) ": " (.-message e)))))))

    (fn []
      (reset! disposed? true)
      (doseq [name @registered]
        (try (.unregisterProvider api name)
             (catch :default _ nil))))))
