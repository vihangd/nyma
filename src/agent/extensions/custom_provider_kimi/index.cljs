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
   - Send `thinking: {type: enabled|disabled}` from the thinking level
     (Moonshot ignores `chat_template_kwargs.thinking`, measured), and
     `preserve_thinking=true` so the server keeps thinking on across
     turns."
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            [agent.utils.credentials :as credentials]
            [agent.utils.reasoning-stream :as rs]
            [agent.utils.reasoning-request :as rr]))

(def ^:private provider-name "kimi")
(def ^:private default-base-url "https://api.moonshot.ai/v1")

;; The models this key lists (GET /v1/models, 2026-11-15). Every one thinks;
;; the K2 previews and moonshot-v1-* are gone.
(def ^:private models
  [{:id "kimi-k3.1-pro"             :name "Kimi K3.1 Pro"             :ctx 1048576}
   {:id "kimi-k3.1-pro-highspeed"   :name "Kimi K3.1 Pro Highspeed"   :ctx 1048576}
   {:id "kimi-k3"                   :name "Kimi K3"                   :ctx 1048576}
   {:id "kimi-k2.7-code"            :name "Kimi K2.7 Code"            :ctx 262144}
   {:id "kimi-k2.7-code-highspeed"  :name "Kimi K2.7 Code Highspeed"  :ctx 262144}
   {:id "kimi-k2.6"                 :name "Kimi K2.6"                 :ctx 262144}])

(defn- thinking-model? [id]
  (boolean (re-find #"^kimi-k(2\.[5-9]|[3-9])|thinking" (str id))))

;; 400 on disabled: "invalid thinking: only type=enabled is allowed for this
;; model". ponytail: name list; retry without on that 400 if more appear.
(defn- always-thinks? [id]
  (boolean (re-find #"kimi-k2\.7-code" (str id))))

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
;;; `thinking` / `preserve_thinking` injection.

(def level-fn-atom (atom nil))

;; `off` has to send `disabled`: Moonshot thinks by default. Nil only for a
;; model that refuses `disabled`. (Was chat_template_kwargs.thinking, which
;; Moonshot ignores: off still reasoned.)
(defmethod rr/reasoning-body provider-name [_ level model-id]
  (cond
    (rr/active-level level)   {:thinking {:type "enabled"}}
    (always-thinks? model-id) nil
    :else                     {:thinking {:type "disabled"}}))

(defn make-request-rewriter [model-id]
  (fn [body-str init]
    (let [lifted (rs/lift-think-request-rewriter body-str init)]
      (if-not (thinking-model? model-id)
        lifted
        (try
          (let [body (js/JSON.parse lifted)
                want (:thinking (rr/reasoning-body
                                 provider-name
                                 (when-let [f @level-fn-atom] (f))
                                 model-id))]
            (when (and want (nil? (.-thinking body)))
              (aset body "thinking" (clj->js want)))
            (when (nil? (.-preserve_thinking body))
              (aset body "preserve_thinking" true))
            ;; Refused otherwise: "invalid temperature: only 1 is allowed for
            ;; this model". Its value depends on the thinking mode, so the
            ;; server picks.
            (js-delete body "temperature")
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
  (reset! level-fn-atom (rr/level-reader (when (.-getThinkingLevel api) #(.getThinkingLevel api))))
  (.registerProvider api provider-name
                     #js {:createModel create-kimi-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "MOONSHOT_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
