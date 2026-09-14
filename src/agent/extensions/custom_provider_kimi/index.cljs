(ns agent.extensions.custom-provider-kimi.index
  "Moonshot/Kimi provider with thinking-model passthrough.

   The K2 thinking models stream chain-of-thought through Moonshot-specific
   delta fields (`reasoning_content` for the older line, `reasoning` for
   K2.6) that the AI SDK's openai chat-completions adapter doesn't
   recognize. The shared `agent.utils.reasoning-stream` helper handles
   the response side; here we add the request-side rewrites that are
   Moonshot-specific:

   - Walk the messages array; for any assistant message whose content
     carries <think> blocks, lift the inner text into a
     `reasoning_content` field on the message — Moonshot rejects
     thinking-mode tool-call replays that lack it.
   - Inject `chat_template_kwargs.thinking=true` and
     `preserve_thinking=true` so the server keeps thinking on across
     turns."
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            [agent.utils.credentials :as credentials]
            [agent.utils.reasoning-stream :as rs]
            [agent.utils.reasoning-request :as rr]))

(def ^:private provider-name "kimi")
(def ^:private default-base-url "https://api.moonshot.ai/v1")

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

(defn- thinking-model? [id]
  (or (= id "kimi-k2.6")
      (= id "kimi-k2.5")
      (.includes (str id) "thinking")))

(defn- ->js-model [{:keys [id name ctx]}]
  #js {:id            id
       :name          name
       :contextWindow ctx})

(defn resolve-base-url []
  (or (aget js/process.env "KIMI_BASE_URL") default-base-url))

(defn resolve-api-key []
  (or (aget js/process.env "MOONSHOT_API_KEY")
      (aget js/process.env "KIMI_API_KEY")
      (credentials/read-credential provider-name)))

;;; ─── Request rewrite ──────────────────────────────────────────────────
;;; Lifting <think> back to reasoning_content lives in
;;; `agent.utils.reasoning-stream`; here we only add Moonshot's
;;; `chat_template_kwargs.thinking` / `preserve_thinking` injection.

(def level-fn-atom (atom nil))

;; Unlike the other dialects this one never returns nil: Moonshot's flag is a
;; boolean, and `off` has to send `false` to turn thinking off. The extension
;; used to hardcode `true`, so `/thinking off` never reached the server.
(defmethod rr/reasoning-body provider-name [_ level _model-id]
  {:thinking (boolean (rr/active-level level))})

(defn make-request-rewriter [model-id]
  (fn [body-str init]
    (let [lifted (rs/lift-think-request-rewriter body-str init)]
      (if-not (thinking-model? model-id)
        lifted
        (try
          (let [body   (js/JSON.parse lifted)
                kwargs (or (.-chat_template_kwargs body) #js {})
                ;; Was hardcoded `true`, so `/thinking off` left Moonshot
                ;; thinking anyway — the level now decides.
                want   (:thinking (rr/reasoning-body
                                   provider-name
                                   (when-let [f @level-fn-atom] (f))
                                   model-id))]
            (when (nil? (.-thinking kwargs))
              (aset kwargs "thinking" want))
            (aset body "chat_template_kwargs" kwargs)
            (when (nil? (.-preserve_thinking body))
              (aset body "preserve_thinking" true))
            (js/JSON.stringify body))
          (catch :default _ lifted))))))

(defn- create-kimi-model [id]
  (let [key (resolve-api-key)]
    (when-not key
      (throw (js/Error.
              (str "No Kimi credentials found. Set the MOONSHOT_API_KEY "
                   "env var or run /login kimi to save a key. "
                   "Get a key at https://platform.moonshot.ai/"))))
    (.chat (createOpenAI #js {:apiKey  key
                              :baseURL (resolve-base-url)
                              :fetch   (rs/make-fetch (make-request-rewriter id))})
           id)))

(defn ^:export default [api]
  (reset! level-fn-atom (when (.-getThinkingLevel api)
                          (fn [] (try (.getThinkingLevel api) (catch :default _e nil)))))
  (.registerProvider api provider-name
                     #js {:createModel create-kimi-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "MOONSHOT_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
