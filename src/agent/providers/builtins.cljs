(ns agent.providers.builtins
  (:require ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["@ai-sdk/google" :refer [createGoogleGenerativeAI]]))

(def builtin-providers
  "Built-in LLM providers registered in the provider registry at startup."
  {"anthropic" {:create-model (fn [id] ((createAnthropic) id))}
   "openai"    {:create-model (fn [id] ((createOpenAI) id))}
   "google"    {:create-model (fn [id] ((createGoogleGenerativeAI) id))}})
