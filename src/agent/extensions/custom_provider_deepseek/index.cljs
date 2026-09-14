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
            [agent.utils.credentials :as credentials]
            [agent.utils.reasoning-stream :as rs]))

(def ^:private provider-name "deepseek")
(def ^:private default-base-url "https://api.deepseek.com/v1")

;; DeepSeek model catalog, verified against GET /models 2026-09-07.
;; - deepseek-v4-pro   : flagship, ~671B MoE (~37B active), thinking-capable
;; - deepseek-v4-flash : faster/cheaper, also thinking-capable
;; - deepseek-v4-flash-vision-exp : flash plus image input
;;
;; Context is 1M on all three (docs: "1M context / 384K max output"), not the
;; 131072 this declared. That was not cosmetic: the window drives
;; `compaction-point`, so nyma compacted at ~111k on a model that accepts 1M —
;; roughly 8x too early, and premature compaction on a re-injection-heavy loop
;; is exactly how context rot starts. The repo already assumed the truth
;; elsewhere (compaction.test.cljs reasons about deepseek-v4-pro's 1,048,576
;; window, from the openlux seed). 1000000 rather than 1048576 because the docs
;; say "1M" and under-declaring a window is safe where over-declaring is not.
;;
;; Legacy aliases deepseek-chat / deepseek-reasoner sunset 2026-07-24 and are
;; now GONE from GET /models — keeping them offered the picker two ids that
;; 404. Thinking is a request parameter on the v4 models, not a separate id.
(def ^:private models
  [{:id "deepseek-v4-pro"   :name "DeepSeek V4 Pro"            :ctx 1000000}
   {:id "deepseek-v4-flash" :name "DeepSeek V4 Flash"          :ctx 1000000}
   {:id "deepseek-v4-flash-vision-exp"
    :name "DeepSeek V4 Flash Vision (exp)"                     :ctx 1000000}])

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx})

(defn resolve-base-url []
  (or (aget js/process.env "DEEPSEEK_BASE_URL") default-base-url))

(defn resolve-api-key []
  (or (aget js/process.env "DEEPSEEK_API_KEY")
      (credentials/read-credential provider-name)))

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
