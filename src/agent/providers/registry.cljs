(ns agent.providers.registry
  (:require ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            [agent.providers.oauth :as oauth]))

(defn- normalize-config
  "Normalize JS camelCase keys to kebab-case CLJ keys."
  [config]
  (let [base config
        base (if (and (not (:create-model base)) (:createModel base))
               (assoc base :create-model (:createModel base))
               base)
        base (if (and (not (:base-url base)) (:baseUrl base))
               (assoc base :base-url (:baseUrl base))
               base)
        base (if (and (not (:api-key-env base)) (:apiKeyEnv base))
               (assoc base :api-key-env (:apiKeyEnv base))
               base)
        base (if (and (not (:max-tokens base)) (:maxTokens base))
               (assoc base :max-tokens (:maxTokens base))
               base)
        ;; Normalize OAuth sub-keys
        base (if-let [oauth (:oauth base)]
               (let [oauth (if (and (not (:get-api-key oauth)) (:getApiKey oauth))
                             (assoc oauth :get-api-key (:getApiKey oauth))
                             oauth)
                     oauth (if (and (not (:refresh-token oauth)) (:refreshToken oauth))
                             (assoc oauth :refresh-token (:refreshToken oauth))
                             oauth)]
                 (assoc base :oauth oauth))
               base)]
    base))

(defn- resolve-api-key
  "Resolve API key/token for a provider config.
   Returns {:key string :oauth? bool} or nil."
  [provider-name config]
  (or
    ;; Try OAuth credentials first
    (when-let [oauth-cfg (:oauth config)]
      (when-let [creds (oauth/load-credentials provider-name)]
        (when-not (oauth/needs-refresh? creds)
          (let [token ((:get-api-key oauth-cfg) #js {"access"     (:access creds)
                                                     "refresh"    (:refresh creds)
                                                     "expires-at" (:expires-at creds)})]
            (when token {:key token :oauth? true})))))
    ;; Fall back to environment variable
    (when-let [env-var (:api-key-env config)]
      (when-let [val (aget js/process.env env-var)]
        {:key val :oauth? false}))))

(defn build-provider-entry
  "Convert enriched provider config to internal registry entry.
   Auto-generates :create-model from :api + :base-url when :create-model is absent.
   For OAuth providers using the anthropic API, uses authToken instead of apiKey."
  [provider-name config]
  (let [config (normalize-config config)]
    (if (:create-model config)
      config
      (assoc config
        :create-model
        (fn [model-id]
          (let [resolved (resolve-api-key provider-name config)]
            (when-not resolved
              (throw (js/Error.
                       (str "No credentials for provider '" provider-name
                            "'. Run /login " provider-name " or set "
                            (or (:api-key-env config) "the API key env var") "."))))
            (let [{:keys [key oauth?]} resolved]
              (case (:api config)
                "anthropic"
                (if oauth?
                  ;; OAuth: use authToken + required beta headers
                  ((createAnthropic
                     #js {:authToken key
                          :baseURL   (:base-url config)
                          :headers   #js {"anthropic-beta"
                                          "oauth-2025-04-20,interleaved-thinking-2025-05-14"}})
                   model-id)
                  ;; Standard API key
                  ((createAnthropic
                     #js {:apiKey  key
                          :baseURL (:base-url config)})
                   model-id))
                "openai-compatible"
                ((createOpenAI #js {:apiKey        key
                                    :baseURL       (:base-url config)
                                    :compatibility "compatible"})
                 model-id)
                ;; Default: try openai-compatible
                ((createOpenAI #js {:apiKey        key
                                    :baseURL       (:base-url config)
                                    :compatibility "compatible"})
                 model-id)))))))))

(defn create-provider-registry
  "Create a provider registry for managing LLM providers.
   Initial providers is a map of {name → {:create-model (fn [model-id] → model-obj), ...}}.
   Returns a map with register/unregister/get/list/resolve functions."
  [initial-providers]
  (let [providers (atom (or initial-providers {}))]
    {:register   (fn [name config]
                   (swap! providers assoc name config))
     :unregister (fn [name]
                   (swap! providers dissoc name))
     :get        (fn [name]
                   (get @providers name))
     :list       (fn []
                   @providers)
     :resolve    (fn [provider-name model-id]
                   (if-let [p (get @providers provider-name)]
                     ((:create-model p) model-id)
                     (throw (js/Error. (str "Unknown provider: " provider-name)))))}))
