(ns agent.providers.builtins
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

(def builtin-providers
  "Built-in LLM providers registered in the provider registry at startup."
  {"anthropic" {:create-model create-anthropic-model}
   "openai"    {:create-model (fn [id] ((createOpenAI) id))}
   "google"    {:create-model (fn [id] ((createGoogleGenerativeAI) id))}})
