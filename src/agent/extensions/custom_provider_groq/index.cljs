(ns agent.extensions.custom-provider-groq.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private provider-name "groq")
(def ^:private default-base-url "https://api.groq.com/openai/v1")

;; Current as of 2026-04-22 from console.groq.com/docs/models.
;; Deprecated families excluded: llama-4-maverick (2026-03-09),
;; moonshotai/kimi-k2-instruct-0905 (2026-04-15), llama-guard-4,
;; deepseek-r1-distill-*, gemma*, llama3-*-8192.
;;
;; All Groq-hosted chat models expose a 128K context window.
(def ^:private models
  [;; ── Production ──────────────────────────────────────────────────────────
   {:id "llama-3.3-70b-versatile"
    :name "Llama 3.3 70B Versatile"
    :ctx 131072
    :reasoning false}
   {:id "llama-3.1-8b-instant"
    :name "Llama 3.1 8B Instant"
    :ctx 131072
    :reasoning false}
   {:id "openai/gpt-oss-120b"
    :name "GPT-OSS 120B (Groq)"
    :ctx 131072
    :reasoning true}
   {:id "openai/gpt-oss-20b"
    :name "GPT-OSS 20B (Groq)"
    :ctx 131072
    :reasoning true}
   {:id "groq/compound"
    :name "Groq Compound (agentic)"
    :ctx 131072
    :reasoning false}
   {:id "groq/compound-mini"
    :name "Groq Compound Mini (agentic)"
    :ctx 131072
    :reasoning false}

   ;; ── Preview ─────────────────────────────────────────────────────────────
   {:id "meta-llama/llama-4-scout-17b-16e-instruct"
    :name "Llama 4 Scout 17B-16E (preview)"
    :ctx 131072
    :reasoning false}
   {:id "qwen/qwen3-32b"
    :name "Qwen3 32B (preview)"
    :ctx 131072
    :reasoning true}
   {:id "openai/gpt-oss-safeguard-20b"
    :name "GPT-OSS Safeguard 20B (preview)"
    :ctx 131072
    :reasoning true}])

(defn- ->js-model [{:keys [id name ctx reasoning]}]
  #js {:id            id
       :name          name
       :contextWindow ctx
       :reasoning     reasoning})

(defn resolve-base-url []
  (or (aget js/process.env "GROQ_BASE_URL") default-base-url))

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
  (or (aget js/process.env "GROQ_API_KEY")
      (read-credentials-file)))

(defn- create-groq-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No Groq credentials found. Set the GROQ_API_KEY "
                   "env var or run /login groq to save a key. "
                   "Get a key at https://console.groq.com/keys"))))
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)})
           id)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-groq-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "GROQ_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
