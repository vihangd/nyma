(ns agent.providers.registry
  "LLM provider registry (register/resolve by name)."
  (:require ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            [agent.providers.oauth :as oauth]
            [agent.utils.credentials :as credentials]
            [agent.utils.data :as data]))

(def ^:private config-keys
  "Kebab-case keys a provider config may spell in camelCase. Sub-map keys
   are listed under their parent."
  {:top   [:create-model :base-url :api-key-env :max-tokens :overhead-tokens]
   :oauth [:get-api-key :refresh-token]})

(defn- normalize-keys
  "Every key in `ks` present under any spelling, written back kebab-case.
   The kebab spelling wins when both are present."
  [m ks]
  (reduce (fn [m k]
            (let [v (data/conf-get m k)]
              (if (some? v) (assoc m k v) m)))
          m ks))

(defn- normalize-config
  "Normalize JS camelCase keys to kebab-case CLJ keys."
  [config]
  (let [base (normalize-keys config (:top config-keys))]
    (if-let [oauth (:oauth base)]
      (assoc base :oauth (normalize-keys oauth (:oauth config-keys)))
      base)))

(defn resolve-api-key
  "Resolve API key/token for a provider config.
   Returns {:key string :oauth? bool} or nil.

   Order: OAuth → env var → `~/.nyma/credentials.json`. The last step is what
   makes `/login <provider>` work for providers registered declaratively (no
   :create-model of their own) — without it the file is written and never read."
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
       {:key val :oauth? false}))
    ;; Finally: a key saved by `/login <provider>`
   (when-let [saved (credentials/read-credential provider-name)]
     {:key saved :oauth? false})))

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

(defn split-model-spec
  "Split \"provider/model-id\" on the FIRST slash only — HuggingFace-style
   model ids (org/model, e.g. poolside/Laguna-S-2.1-NVFP4) contain slashes of
   their own. Returns [provider model-id], or [nil spec] when no slash.
   Shared by the CLI --model path and the runtime setModel path so the two
   parsers can't drift."
  [spec]
  (let [s     (str spec)
        slash (.indexOf s "/")]
    (if (neg? slash)
      [nil s]
      [(.slice s 0 slash) (.slice s (inc slash))])))
