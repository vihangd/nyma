(ns agent.extensions.custom-provider-claude-native.index
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.custom-provider-claude-native.provider :as provider]))

(def ^:private provider-name "claude-native")
(def ^:private default-base-url "https://api.anthropic.com/v1")

;; Anthropic context windows (tokens)
(def ^:private models
  [{:id "claude-haiku-4-5-20251001" :name "Claude Haiku 4.5"  :ctx 200000}
   {:id "claude-sonnet-4-6"         :name "Claude Sonnet 4.6" :ctx 200000}
   {:id "claude-opus-4-6"           :name "Claude Opus 4.6"   :ctx 200000}
   {:id "claude-opus-4-7"           :name "Claude Opus 4.7"   :ctx 200000}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx
       :reasoning     false})

(defn- read-credentials-file []
  (let [home      (.. js/process -env -HOME)
        cred-path (path/join home ".nyma" "credentials.json")]
    (when (and home (fs/existsSync cred-path))
      (try
        (let [raw    (fs/readFileSync cred-path "utf8")
              parsed (js/JSON.parse raw)]
          ;; Stored under "anthropic" key — same as /login anthropic would write
          (or (aget parsed "anthropic") (aget parsed provider-name)))
        (catch :default _ nil)))))

(defn resolve-api-key []
  (or (aget js/process.env "ANTHROPIC_API_KEY")
      (read-credentials-file)))

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
