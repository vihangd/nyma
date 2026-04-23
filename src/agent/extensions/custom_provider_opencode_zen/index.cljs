(ns agent.extensions.custom-provider-opencode-zen.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private provider-name "opencode-zen")
(def ^:private default-base-url "https://opencode.ai/zen/v1")

;; Each model carries :protocol (:chat or :responses) that drives endpoint
;; dispatch in create-oc-zen-model. The JS shape passed to registerProvider
;; only includes :id/:name/:contextWindow/:cost — :protocol is internal.
;;
;; Free models still require a funded Zen account ($20 minimum top-up at
;; https://opencode.ai/auth) — "free" means zero per-token cost, not zero signup.
;;
;; Context windows are omitted where Zen doesn't publish them; use
;; GET /zen/v1/models at runtime for authoritative values.
(def ^:private models
  [;; ── Free ────────────────────────────────────────────────────────────────
   {:id "gpt-5-nano"           :name "GPT-5 Nano (free)"           :protocol :responses :ctx 400000 :cost {:input 0   :output 0}}
   {:id "minimax-m2.5-free"    :name "MiniMax M2.5 Free"           :protocol :chat      :ctx 204800 :cost {:input 0   :output 0}}
   {:id "big-pickle"           :name "Big Pickle (free)"           :protocol :chat                  :cost {:input 0   :output 0}}
   {:id "ling-2.6-flash"       :name "Ling 2.6 Flash (free)"       :protocol :chat                  :cost {:input 0   :output 0}}
   {:id "nemotron-3-super-free" :name "Nemotron 3 Super Free"      :protocol :chat                  :cost {:input 0   :output 0}}

   ;; ── Paid — /responses (GPT-5.x) ─────────────────────────────────────────
   {:id "gpt-5"                :name "GPT-5"                       :protocol :responses :cost {:input 1.07  :output 8.50}}
   {:id "gpt-5-codex"         :name "GPT-5 Codex"                 :protocol :responses :cost {:input 1.07  :output 8.50}}
   {:id "gpt-5.1"             :name "GPT-5.1"                     :protocol :responses :cost {:input 1.07  :output 8.50}}
   {:id "gpt-5.1-codex"       :name "GPT-5.1 Codex"               :protocol :responses :cost {:input 1.07  :output 8.50}}
   {:id "gpt-5.1-codex-max"   :name "GPT-5.1 Codex Max"           :protocol :responses :cost {:input 1.25  :output 10.00}}
   {:id "gpt-5.1-codex-mini"  :name "GPT-5.1 Codex Mini"          :protocol :responses :cost {:input 0.25  :output 2.00}}
   {:id "gpt-5.2"             :name "GPT-5.2"                     :protocol :responses :cost {:input 1.75  :output 14.00}}
   {:id "gpt-5.2-codex"       :name "GPT-5.2 Codex"               :protocol :responses :cost {:input 1.75  :output 14.00}}
   {:id "gpt-5.3-codex"       :name "GPT-5.3 Codex"               :protocol :responses :cost {:input 1.75  :output 14.00}}
   {:id "gpt-5.3-codex-spark" :name "GPT-5.3 Codex Spark"         :protocol :responses :cost {:input 1.75  :output 14.00}}
   {:id "gpt-5.4"             :name "GPT-5.4"                     :protocol :responses :cost {:input 2.50  :output 15.00}}
   {:id "gpt-5.4-mini"        :name "GPT-5.4 Mini"                :protocol :responses :cost {:input 0.75  :output 4.50}}
   {:id "gpt-5.4-nano"        :name "GPT-5.4 Nano"                :protocol :responses :cost {:input 0.20  :output 1.25}}
   {:id "gpt-5.4-pro"         :name "GPT-5.4 Pro"                 :protocol :responses :cost {:input 30.00 :output 180.00}}

   ;; ── Paid — /chat/completions ─────────────────────────────────────────────
   {:id "minimax-m2.5"        :name "MiniMax M2.5"                :protocol :chat :ctx 204800 :cost {:input 0.30 :output 1.20}}
   {:id "minimax-m2.7"        :name "MiniMax M2.7"                :protocol :chat :ctx 204800 :cost {:input 0.30 :output 1.20}}
   {:id "glm-5"               :name "GLM-5"                       :protocol :chat              :cost {:input 1.00 :output 3.20}}
   {:id "glm-5.1"             :name "GLM-5.1"                     :protocol :chat              :cost {:input 1.40 :output 4.40}}
   {:id "kimi-k2.5"           :name "Kimi K2.5 (via Zen)"         :protocol :chat              :cost {:input 0.60 :output 3.00}}
   {:id "kimi-k2.6"           :name "Kimi K2.6 (via Zen)"         :protocol :chat              :cost {:input 0.95 :output 4.00}}
   {:id "qwen3.5-plus"        :name "Qwen 3.5 Plus"               :protocol :chat              :cost {:input 0.20 :output 1.20}}
   {:id "qwen3.6-plus"        :name "Qwen 3.6 Plus"               :protocol :chat              :cost {:input 0.50 :output 3.00}}])

(def ^:private protocol-by-id
  (into {} (map (juxt :id :protocol) models)))

(defn- ->js-model [{:keys [id name ctx cost]}]
  (let [base #js {:id id :name name}]
    (when ctx (aset base "contextWindow" ctx))
    (when cost (aset base "cost" #js {:input (:input cost) :output (:output cost)}))
    base))

(defn resolve-base-url []
  (or (aget js/process.env "OPENCODE_ZEN_BASE_URL") default-base-url))

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
  (or (aget js/process.env "OPENCODE_ZEN_API_KEY")
      (aget js/process.env "OPENCODE_API_KEY")
      (read-credentials-file)))

(defn- create-oc-zen-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No OpenCode Zen credentials found. Set the OPENCODE_ZEN_API_KEY "
                   "env var or run /login opencode-zen to save a key. "
                   "Sign up at https://opencode.ai/auth"))))
    (let [provider (createOpenAI #js {:apiKey key :baseURL (resolve-base-url)})]
      (case (get protocol-by-id id :chat)
        :responses (.responses provider id)
        :chat      (.chat provider id)))))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-oc-zen-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "OPENCODE_ZEN_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
