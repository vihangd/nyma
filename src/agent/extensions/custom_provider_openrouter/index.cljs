(ns agent.extensions.custom-provider-openrouter.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private provider-name "openrouter")
(def ^:private default-base-url "https://openrouter.ai/api/v1")

;; Attribution headers — optional for API success but required for OpenRouter's
;; ranking and app-attribution pages. Overridable via env for forks/custom deploys.
(def ^:private default-referer "https://github.com/dpaddy/nyma")
(def ^:private default-title   "nyma")

;; Curated catalog of free OpenRouter models. All priced at $0/1M on both sides.
;; `openrouter/free` is OpenRouter's meta-router that auto-picks a free model
;; supporting the capabilities of the request (tools, vision, structured output).
;;
;; Note: the wire IDs match OpenRouter's published slugs exactly — do not add
;; the `openrouter/` nyma-provider prefix here; that prefix is what users type
;; as `/model openrouter/<wire-id>` and is stripped by the registry.
(def ^:private models
  [{:id "openrouter/free"
    :name "OpenRouter Free Router"
    :ctx 200000}
   {:id "openai/gpt-oss-120b:free"
    :name "GPT-OSS 120B (free)"
    :ctx 131072}
   {:id "openai/gpt-oss-20b:free"
    :name "GPT-OSS 20B (free)"
    :ctx 131072}
   {:id "nvidia/nemotron-3-nano-30b-a3b:free"
    :name "Nemotron 3 Nano 30B A3B (free)"
    :ctx 256000}
   {:id "nvidia/nemotron-3-super-120b-a12b:free"
    :name "Nemotron 3 Super 120B A12B (free)"
    :ctx 262144}
   {:id "qwen/qwen3-coder:free"
    :name "Qwen3 Coder (free)"
    :ctx 262144}
   {:id "meta-llama/llama-3.3-70b-instruct:free"
    :name "Llama 3.3 70B Instruct (free)"
    :ctx 65536}
   {:id "z-ai/glm-4.5-air:free"
    :name "GLM 4.5 Air (free)"
    :ctx 131072}
   {:id "google/gemma-4-31b-it:free"
    :name "Gemma 4 31B IT (free)"
    :ctx 262144}
   {:id "minimax/minimax-m2.5:free"
    :name "MiniMax M2.5 (free)"
    :ctx 204800}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx
       :cost          #js {:input 0 :output 0}})

(defn resolve-base-url []
  (or (aget js/process.env "OPENROUTER_BASE_URL") default-base-url))

(defn- resolve-referer []
  (or (aget js/process.env "OPENROUTER_REFERER") default-referer))

(defn- resolve-title []
  (or (aget js/process.env "OPENROUTER_TITLE") default-title))

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
  (or (aget js/process.env "OPENROUTER_API_KEY")
      (read-credentials-file)))

(defn- create-openrouter-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No OpenRouter credentials found. Set the OPENROUTER_API_KEY "
                   "env var or run /login openrouter to save a key. "
                   "Get a key at https://openrouter.ai/keys"))))
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)
                              :headers #js {"HTTP-Referer" (resolve-referer)
                                            "X-Title"      (resolve-title)}})
           id)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-openrouter-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "OPENROUTER_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
