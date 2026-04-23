(ns agent.extensions.custom-provider-kimi.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private provider-name "kimi")
(def ^:private default-base-url "https://api.moonshot.cn/v1")

;; K2.6 / K2.5 are the current production models (256k context, multimodal).
;; K2 preview series is deprecated as of May 25 2026.
;; moonshot-v1-* are legacy general-purpose models.
(def ^:private models
  [{:id "kimi-k2.6"             :name "Kimi K2.6"              :ctx 262144}
   {:id "kimi-k2.5"             :name "Kimi K2.5"              :ctx 262144}
   {:id "kimi-k2-0905-preview"  :name "Kimi K2 0905 (preview)" :ctx 262144}
   {:id "kimi-k2-turbo-preview" :name "Kimi K2 Turbo (preview)" :ctx 262144}
   {:id "kimi-k2-thinking"      :name "Kimi K2 Thinking"       :ctx 262144}
   {:id "kimi-k2-thinking-turbo" :name "Kimi K2 Thinking Turbo" :ctx 262144}
   {:id "kimi-k2-0711-preview"  :name "Kimi K2 0711 (preview)" :ctx 131072}
   {:id "moonshot-v1-128k"      :name "Moonshot v1 128k"       :ctx 131072}
   {:id "moonshot-v1-32k"       :name "Moonshot v1 32k"        :ctx 32768}
   {:id "moonshot-v1-8k"        :name "Moonshot v1 8k"         :ctx 8192}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx})

(defn resolve-base-url []
  (or (aget js/process.env "KIMI_BASE_URL") default-base-url))

(defn- read-credentials-file []
  (let [home      (.. js/process -env -HOME)
        cred-path (path/join home ".nyma" "credentials.json")]
    (when (and home (fs/existsSync cred-path))
      (try
        (let [raw    (fs/readFileSync cred-path "utf8")
              parsed (js/JSON.parse raw)]
          (aget parsed provider-name))
        (catch :default _ nil)))))

(defn resolve-api-key []
  (or (aget js/process.env "MOONSHOT_API_KEY")
      (aget js/process.env "KIMI_API_KEY")
      (read-credentials-file)))

(defn- create-kimi-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No Kimi credentials found. Set the MOONSHOT_API_KEY "
                   "env var or run /login kimi to save a key. "
                   "Get a key at https://platform.moonshot.cn/"))))
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)})
           id)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-kimi-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "MOONSHOT_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
