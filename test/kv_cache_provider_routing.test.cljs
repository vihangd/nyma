(ns kv-cache-provider-routing.test
  "kv_cache must route cache_control on the model's PROVIDER, not its id.

   These assert on the REQUEST BODY THAT REACHES THE WIRE, via a fetch
   interceptor, rather than on the mutated config. That distinction is the whole
   point: the old routing produced a correctly-annotated config for a
   `claude-*` model served over an OpenAI-compatible endpoint, and @ai-sdk/openai
   then dropped every one of those annotations. Asserting on internal state
   showed success while the feature was silently doing nothing — and burning
   breakpoints against Anthropic's budget of four."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["ai" :refer [streamText]]
            [agent.core :refer [create-agent]]
            [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions :refer [create-extension-api]]))

(defn- reset-stats! []
  (swap! shared/suite-stats assoc :kv-cache
         {:turns 0 :cache-hits 0 :cached-tokens 0}))

(beforeEach reset-stats!)

;; Long enough to clear the 500-token :min-system-tokens floor, and carrying a
;; `## ` separator so split-at-stable-boundary finds a stable/dynamic split.
(def ^:private long-system
  (str (.repeat "You are a careful assistant working in a large repository. " 120)
       "\n## Current task\n"
       (.repeat "Answer briefly. " 20)))

(defn- capture []
  (let [box (atom nil)]
    {:box   box
     :fetch (fn [_url init]
              (reset! box (js/JSON.parse (.-body init)))
              ;; 500 is retryable, so cap retries at the call site instead.
              (js/Response. "" #js {:status 400}))}))

(defn- anthropic-model [f]
  ((createAnthropic #js {:apiKey "k" :baseURL "https://relay.test/v1" :fetch f})
   "claude-opus-5"))

(defn- openai-model [f]
  (.chat (createOpenAI #js {:apiKey "k" :baseURL "https://relay.test/v1"
                            :compatibility "compatible" :fetch f})
         "claude-opus-5"))

(defn ^:async annotate!
  "Run kv_cache's before_provider_request over a config carrying `model`.
   Returns the mutated config."
  [model]
  (let [agent  (create-agent {:model model :system-prompt long-system})
        api    (create-extension-api agent)
        _deact (kv-cache/activate api)
        config #js {:model           model
                    :system          long-system
                    :messages        #js [#js {:role "user" :content "q1"}
                                          #js {:role "assistant" :content "a1 complete"}
                                          #js {:role "user" :content "q2"}]
                    :tools           #js {}
                    :providerOptions #js {}}]
    (js-await ((:emit-collect (:events agent)) "before_provider_request" config))
    config))

(defn ^:async wire-body
  "Annotate, then actually send through the AI SDK and return the request body
   the provider produced."
  [make-model]
  (let [{:keys [box fetch]} (capture)
        model  (make-model fetch)
        config (js-await (annotate! model))]
    (try
      (let [res (streamText #js {:model      model
                                 :system     (.-system config)
                                 :messages   (.-messages config)
                                 :maxRetries 0})]
        (js-await (.-text res)))
      (catch :default _ nil))
    @box))

(defn- count-cache-control [body]
  ;; The `g` flag is load-bearing: without it String.match returns only the
  ;; first match, so every count reads as 1.
  (let [s (js/JSON.stringify (or body #js {}))]
    (count (or (.match s (js/RegExp. "cache_control" "g")) #js []))))

(defn ^:async test-anthropic-path-sends-breakpoints []
  (let [body (js-await (wire-body anthropic-model))]
    (-> (expect body) (.not.toBeNil))
    ;; Layer 1 (system stable block) + layer 2 (assistant anchor).
    (-> (expect (count-cache-control body)) (.toBe 2))))

(defn ^:async test-openai-path-sends-none []
  (let [body (js-await (wire-body openai-model))]
    (-> (expect body) (.not.toBeNil))
    ;; Same model id, same annotations computed — but nothing survives the
    ;; OpenAI wire format, so kv_cache must not spend breakpoints here.
    (-> (expect (count-cache-control body)) (.toBe 0))))

(defn ^:async test-openai-path-not-annotated []
  ;; The config itself must be left alone: an annotation that cannot reach the
  ;; wire still counts against Anthropic's 4-breakpoint budget on the next
  ;; provider switch, and makes the cache-hit accounting lie.
  (let [{:keys [fetch]} (capture)
        config (js-await (annotate! (openai-model fetch)))
        msgs   (.-messages config)]
    (-> (expect (js/Array.isArray (.-system config))) (.toBe false))
    (-> (expect (.-providerOptions (aget msgs 1))) (.toBeUndefined))))

(defn ^:async test-anthropic-path-annotated []
  (let [{:keys [fetch]} (capture)
        config (js-await (annotate! (anthropic-model fetch)))]
    (-> (expect (js/Array.isArray (.-system config))) (.toBe true))))

(describe "kv-cache routes on provider, not model id" (fn []
                                                        (it "anthropic provider: breakpoints reach the wire"
                                                            test-anthropic-path-sends-breakpoints)
                                                        (it "openai-compatible provider with a claude id: zero breakpoints on the wire"
                                                            test-openai-path-sends-none)
                                                        (it "openai-compatible provider with a claude id: config left unannotated"
                                                            test-openai-path-not-annotated)
                                                        (it "anthropic provider: system prompt is split for caching"
                                                            test-anthropic-path-annotated)))

;; ── Unit-level routing ───────────────────────────────────────

(describe "shared/detect-cache-provider-for-model" (fn []
                                                     (it "routes anthropic.messages to :anthropic"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:provider "anthropic.messages" :modelId "claude-opus-5"}))
                                                               (.toBe :anthropic))))

                                                     (it "routes the hand-rolled claude-native provider to :anthropic"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:provider "claude-native" :modelId "claude-opus-5"}))
                                                               (.toBe :anthropic))))

                                                     (it "routes google.generative-ai to :google"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:provider "google.generative-ai" :modelId "gemini-2.0-flash"}))
                                                               (.toBe :google))))

                                                     (it "returns nil for openai.chat even when the id says claude"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:provider "openai.chat" :modelId "claude-opus-5"}))
                                                               (.toBeNil))))

                                                     (it "still honours settings extras over an openai-shaped SDK"
                                                         (fn []
        ;; minimax's Anthropic-compat endpoint is reached via createOpenAI, so
        ;; no provider tag can express it — the escape hatch must survive.
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:provider "openai.chat" :modelId "minimax-m2.5"}
                                                                        {:anthropic ["minimax"]}))
                                                               (.toBe :anthropic))))

                                                     (it "does not let extras resurrect the claude-id heuristic"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:provider "openai.chat" :modelId "claude-opus-5"}
                                                                        {:anthropic ["minimax"]}))
                                                               (.toBeNil))))

                                                     (it "falls back to the id heuristic when no provider tag is present"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model
                                                                        #js {:modelId "claude-opus-5"}))
                                                               (.toBe :anthropic))))

                                                     (it "accepts a bare string model"
                                                         (fn []
                                                           (-> (expect (shared/detect-cache-provider-for-model "gemini-2.0-flash"))
                                                               (.toBe :google))))))
