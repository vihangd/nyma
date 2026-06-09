(ns agent.extensions.custom-provider-deepseek.index
  "DeepSeek provider — OpenAI-compatible endpoint at api.deepseek.com/v1.

   DeepSeek-V4 thinking models stream chain-of-thought via the
   `reasoning_content` delta field (same shape as Moonshot's older
   line). The AI SDK's openai chat-completions adapter doesn't parse
   it, so we wrap responses through `agent.utils.reasoning-stream`
   which rewrites those deltas into inline `<think>…</think>` content
   that nyma's `agent.ui.think-tag-parser` already handles.

   We do NOT use DeepSeek's Anthropic-compatible endpoint
   (api.deepseek.com/anthropic). That shim is meant for tools that
   hardcode the Anthropic SDK (Claude Code, etc.). For nyma it would
   be a separate code path with a smaller feature surface (no images,
   no MCP, no web_search, no documents) and zero functional gain
   over the OpenAI-compatible endpoint.

   API key resolution order:
     DEEPSEEK_API_KEY env  →  ~/.nyma/credentials.json :deepseek key

   Get a key at https://platform.deepseek.com/."
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.utils.reasoning-stream :as rs]))

(def ^:private provider-name "deepseek")
(def ^:private default-base-url "https://api.deepseek.com/v1")

;; DeepSeek model catalog as of May 2026.
;; - deepseek-v4-pro   : flagship, ~671B MoE (~37B active), thinking-capable
;; - deepseek-v4-flash : faster/cheaper, also thinking-capable
;; - Legacy aliases deepseek-chat / deepseek-reasoner sunset 2026-07-24,
;;   currently routing to v4-flash non-thinking / thinking respectively.
;;   Included so users mid-transition don't break, with a (legacy) tag.
(def ^:private models
  [{:id "deepseek-v4-pro"   :name "DeepSeek V4 Pro"            :ctx 131072}
   {:id "deepseek-v4-flash" :name "DeepSeek V4 Flash"          :ctx 131072}
   {:id "deepseek-chat"     :name "DeepSeek Chat (legacy)"     :ctx 131072}
   {:id "deepseek-reasoner" :name "DeepSeek Reasoner (legacy)" :ctx 131072}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx})

(defn resolve-base-url []
  (or (aget js/process.env "DEEPSEEK_BASE_URL") default-base-url))

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
  (or (aget js/process.env "DEEPSEEK_API_KEY")
      (read-credentials-file)))

(defn- create-deepseek-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No DeepSeek credentials found. Set the DEEPSEEK_API_KEY "
                   "env var or run /login deepseek to save a key. "
                   "Get a key at https://platform.deepseek.com/."))))
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)
                              :fetch   (rs/make-fetch
                                        rs/lift-think-request-rewriter)})
           id)))

(defn ^:export default [api]
  (.registerProvider api provider-name
                     #js {:createModel create-deepseek-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "DEEPSEEK_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
