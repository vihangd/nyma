(ns agent.extensions.custom-provider-minimax.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private provider-name "minimax")
(def ^:private default-base-url "https://api.minimax.io/v1")

;; All MiniMax M2.x share 204,800-token context and are reasoning-capable
;; via the `reasoning_split` extra_body parameter. Highspeed variants run at
;; 100 tps vs 60 tps for standard variants.
(def ^:private models
  [{:id "MiniMax-M2"             :name "MiniMax M2"             :ctx 204800}
   {:id "MiniMax-M2.1"           :name "MiniMax M2.1"           :ctx 204800}
   {:id "MiniMax-M2.1-highspeed" :name "MiniMax M2.1 Highspeed" :ctx 204800}
   {:id "MiniMax-M2.5"           :name "MiniMax M2.5"           :ctx 204800}
   {:id "MiniMax-M2.5-highspeed" :name "MiniMax M2.5 Highspeed" :ctx 204800}
   {:id "MiniMax-M2.7"           :name "MiniMax M2.7"           :ctx 204800}
   {:id "MiniMax-M2.7-highspeed" :name "MiniMax M2.7 Highspeed" :ctx 204800}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx
       :reasoning     true})

(defn resolve-base-url []
  (or (aget js/process.env "MINIMAX_BASE_URL") default-base-url))

(defn- read-credentials-file
  "Read ~/.nyma/credentials.json and return the stored key for this provider.
   The built-in resolve-api-key in registry.cljs only checks env vars, not
   credentials.json — this makes /login minimax actually work without touching
   core code."
  []
  (let [home      (.. js/process -env -HOME)
        cred-path (path/join home ".nyma" "credentials.json")]
    (when (and home (fs/existsSync cred-path))
      (try
        (let [raw    (fs/readFileSync cred-path "utf8")
              parsed (js/JSON.parse raw)]
          (aget parsed provider-name))
        (catch :default _ nil)))))

(defn resolve-api-key []
  (or (aget js/process.env "MINIMAX_API_KEY")
      (read-credentials-file)))

(defn- create-minimax-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No MiniMax credentials found. Set the MINIMAX_API_KEY "
                   "env var or run /login minimax to save a key. "
                   "Get a key at https://platform.minimax.io/"))))
    ;; .chat() forces the Chat Completions endpoint (/v1/chat/completions).
    ;; Calling the provider directly now routes to the Responses API
    ;; (/v1/responses) which MiniMax does not implement — 404.
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)})
           id)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-minimax-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "MINIMAX_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  ;; Return cleanup function
  (fn []
    (.unregisterProvider api provider-name)))
