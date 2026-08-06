(ns agent.extensions.custom-provider-claude-native.index
  (:require [agent.extensions.custom-provider-claude-native.provider :as provider]
            [agent.utils.credentials :as credentials]))

(def ^:private provider-name "claude-native")
(def ^:private default-base-url "https://api.anthropic.com/v1")

;; Anthropic context windows (tokens)
(def ^:private models
  [{:id "claude-haiku-4-5-20251001" :name "Claude Haiku 4.5"  :ctx 200000}
   {:id "claude-sonnet-4-6"         :name "Claude Sonnet 4.6" :ctx 200000}
   {:id "claude-opus-4-6"           :name "Claude Opus 4.6"   :ctx 200000}
   {:id "claude-opus-4-7"           :name "Claude Opus 4.7"   :ctx 200000}
   {:id "claude-opus-4-8"           :name "Claude Opus 4.8"   :ctx 200000}
   ;; Opus 5 (2026-07-24): 1M context default, 128k output, thinking on by
   ;; default with effort ladder (nyma sends no thinking config → defaults).
   {:id "claude-opus-5"             :name "Claude Opus 5"     :ctx 1000000}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx
       :reasoning     false})

(defn resolve-api-key []
  (or (aget js/process.env "ANTHROPIC_API_KEY")
      ;; "anthropic" first — that's what `/login anthropic` writes.
      (credentials/read-credential "anthropic" provider-name)))

(defn- create-claude-native-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No Anthropic credentials found. Set the ANTHROPIC_API_KEY "
                   "env var or run /login anthropic to save a key."))))
    (provider/create-model id key)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-claude-native-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "ANTHROPIC_API_KEY"
                          :api         "anthropic"
                          :models      (clj->js (mapv ->js-model models))})

  ;; Return cleanup function
  (fn []
    (.unregisterProvider api provider-name)))
