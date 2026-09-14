(ns agent.extensions.custom-provider-openrouter.index
  (:require ["@ai-sdk/openai" :refer [createOpenAI]]
            [agent.utils.credentials :as credentials]
            [agent.utils.reasoning-stream :as rs]
            [agent.utils.reasoning-request :as rr]))

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

(defn resolve-api-key []
  (or (aget js/process.env "OPENROUTER_API_KEY")
      (credentials/read-credential provider-name)))

;; ── Backend routing ────────────────────────────────────────────────────────
;;
;; OpenRouter serves one model id from several backends and picks per request.
;; Those backends are not equivalent: `qwen/qwen3.6-35b-a3b` routed to Venice
;; returns finish_reason=stop with NO tool_calls — every agent turn is a no-op —
;; while the same id on Parasail emits tool calls normally. `supported_parameters`
;; advertises "tools" either way, and `require_parameters: true` still routes to
;; the broken one, so capability metadata cannot be trusted to avoid it.
;;
;; So the routing block is exposed instead of guessed at. Passed through to the
;; API verbatim, either for every request or per model id:
;;
;;   {"openrouter": {"provider": {"only": ["parasail"]},
;;                   "model-routing": {"qwen/qwen3.6-35b-a3b": {"only": ["parasail"]}}}}
;;
;; Absent config changes nothing.

(defn routing-for
  "Pure: settings + model id → the OpenRouter `provider` routing object, or nil.
   A per-model entry wins over the global one; neither is merged into the other,
   because a partial merge of routing rules is harder to reason about than a
   replacement. Exposed for tests."
  [settings model-id]
  (let [cfg (:openrouter settings)
        per (:model-routing cfg)
        one (or (get per model-id) (get per (str model-id)))
        all (:provider cfg)]
    (or one all)))

(defn make-request-rewriter
  "Inject the backend routing block and the reasoning effort.

   `level-fn` is read PER REQUEST, not captured: the rewriter is built once when
   the model object is created, but `/thinking` can change at any time.

   Reasoning is safe to send unconditionally — OpenRouter drops parameters a
   backend does not support (the same mechanism that let Venice silently ignore
   `tools`). Measured: `reasoning.exclude` is honoured (reasoning text withheld
   while reasoning_tokens were still spent), and `effort` moves reasoning tokens
   on models whose catalogue lists `reasoning_effort` (108 → 187 on
   qwen3.8-27b); on models listing only `reasoning` it is dropped."
  [routing level-fn]
  (when (or routing level-fn)
    (fn [body-str _init]
      (try
        (let [body (js/JSON.parse body-str)]
          ;; Never override a block the caller already set.
          (when (and routing (nil? (.-provider body)))
            (aset body "provider" (clj->js routing)))
          (when (nil? (.-reasoning body))
            (when-let [r (rr/openrouter (when level-fn (level-fn)))]
              (aset body "reasoning" (clj->js r))))
          (js/JSON.stringify body))
        (catch :default _ body-str)))))

(def ^:private settings-atom (atom nil))
;; The thinking level lives on the agent, and the API that reads it is only
;; available once the extension is activated — so the provider closes over a
;; thunk rather than a value.
(def ^:private level-fn-atom (atom nil))

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
                                            "X-Title"      (resolve-title)}
                              :fetch   (rs/make-fetch
                                        (make-request-rewriter
                                         (routing-for @settings-atom id)
                                         @level-fn-atom))})
           id)))

(defn ^:export default [api]
  (reset! settings-atom (.settings api))
  (reset! level-fn-atom (when (.-getThinkingLevel api)
                          (fn [] (try (.getThinkingLevel api) (catch :default _e nil)))))
  (.registerProvider api provider-name
                     #js {:createModel create-openrouter-model
                          :baseUrl     default-base-url
                          :apiKeyEnv   "OPENROUTER_API_KEY"
                          :api         "openai-compatible"
                          :models      (clj->js (mapv ->js-model models))})

  (fn []
    (.unregisterProvider api provider-name)))
