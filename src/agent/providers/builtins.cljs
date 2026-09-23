(ns agent.providers.builtins
  "Default Anthropic/OpenAI/Google provider factories."
  (:require ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["@ai-sdk/google" :refer [createGoogleGenerativeAI]]))

(defn- create-anthropic-model
  "Create an Anthropic model using ANTHROPIC_API_KEY env var."
  [id]
  (let [env-key (aget js/process.env "ANTHROPIC_API_KEY")]
    (if env-key
      ((createAnthropic #js {:apiKey env-key}) id)
      (throw (js/Error.
              "No Anthropic credentials found. Set ANTHROPIC_API_KEY env var.")))))

;; Model lists carry ids + display names only. Context windows come from
;; `agent.model-info` and pricing from `agent.pricing`, so those numbers keep a
;; single source of truth — `agent.providers.catalog` joins them at read time.
;; Extension providers already declare `:models` this way (see
;; custom_provider_openrouter); declaring them here too means the catalog is
;; simply "walk providers, read :models" with no per-provider special-casing.
(def ^:private anthropic-models
  [{:id "claude-opus-5"              :name "Claude Opus 5"}
   {:id "claude-sonnet-5"            :name "Claude Sonnet 5"}
   {:id "claude-haiku-4-5-20251001"  :name "Claude Haiku 4.5"}
   {:id "claude-opus-4-8"            :name "Claude Opus 4.8"}])

(def ^:private openai-models
  [{:id "gpt-4o"      :name "GPT-4o"}
   {:id "gpt-4o-mini" :name "GPT-4o mini"}])

(def ^:private google-models
  [{:id "gemini-2.0-flash" :name "Gemini 2.0 Flash"}])

(def builtin-providers
  "Built-in LLM providers registered in the provider registry at startup."
  {"anthropic" {:create-model create-anthropic-model
                :models       anthropic-models}
   "openai"    {:create-model (fn [id] ((createOpenAI) id))
                :models       openai-models}
   "google"    {:create-model (fn [id] ((createGoogleGenerativeAI) id))
                :models       google-models}})
