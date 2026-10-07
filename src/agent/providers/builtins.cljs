(ns agent.providers.builtins
  "Default Anthropic/OpenAI/Google provider factories."
  (:require ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["@ai-sdk/google" :refer [createGoogleGenerativeAI]]
            [agent.utils.credentials :as credentials]))

(def key-env
  "The env var each built-in provider reads its key from."
  {"anthropic" "ANTHROPIC_API_KEY"
   "openai"    "OPENAI_API_KEY"
   "google"    "GOOGLE_GENERATIVE_AI_API_KEY"})

(defn- api-key
  "Env var first, then a key saved by `/login <provider>`. The built-ins read
   only the env var, so `/login anthropic` wrote a key nothing read — while
   the failure message told the user to run `/login`."
  [provider]
  (let [v (aget js/process.env (get key-env provider))]
    (if (seq (str (or v ""))) v (credentials/read-credential provider))))

(defn- builtin-model [provider create]
  (fn [id]
    (if-let [k (api-key provider)]
      ((create #js {:apiKey k}) id)
      (throw (js/Error. (str "No " provider " API key. Set " (get key-env provider)
                             " or run /login " provider "."))))))

;; Model lists carry ids + display names only. Context windows come from
;; `agent.model-info` and pricing from `agent.pricing`, so those numbers keep a
;; single source of truth — `agent.providers.catalog` joins them at read time.
;; Extension providers already declare `:models` this way (see
;; custom_provider_openrouter); declaring them here too means the catalog is
;; simply "walk providers, read :models" with no per-provider special-casing.
(def ^:private anthropic-models
  [{:id "claude-sonnet-5-5"          :name "Claude Sonnet 5.5"}
   {:id "claude-opus-5-5"            :name "Claude Opus 5.5"}
   {:id "claude-fable-5-1"           :name "Claude Fable 5.1"}
   {:id "claude-haiku-4-5"           :name "Claude Haiku 4.5"}
   {:id "claude-opus-5"              :name "Claude Opus 5"}
   {:id "claude-sonnet-5"            :name "Claude Sonnet 5"}
   {:id "claude-opus-4-8"            :name "Claude Opus 4.8"}])

(def ^:private openai-models
  [{:id "gpt-4o"      :name "GPT-4o"}
   {:id "gpt-4o-mini" :name "GPT-4o mini"}])

(def ^:private google-models
  [{:id "gemini-2.0-flash" :name "Gemini 2.0 Flash"}])

(def builtin-providers
  "Built-in LLM providers registered in the provider registry at startup."
  {"anthropic" {:create-model (builtin-model "anthropic" createAnthropic)
                :models       anthropic-models}
   "openai"    {:create-model (builtin-model "openai" createOpenAI)
                :models       openai-models}
   "google"    {:create-model (builtin-model "google" createGoogleGenerativeAI)
                :models       google-models}})
