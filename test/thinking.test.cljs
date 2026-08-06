(ns thinking.test
  "Extended thinking must actually reach the wire.

   nyma tracked a thinking level in three places and sent it nowhere, so these
   assert on the captured request body rather than on internal state — the level
   being set correctly was never the part that was broken."
  (:require ["bun:test" :refer [describe it expect]]
            ["@ai-sdk/anthropic" :refer [createAnthropic]]
            ["@ai-sdk/openai" :refer [createOpenAI]]
            ["@ai-sdk/google" :refer [createGoogleGenerativeAI]]
            ["ai" :refer [streamText]]
            [agent.core :refer [create-agent]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.thinking :as thinking]))

(defn- capture []
  (let [box (atom nil)]
    {:box   box
     :fetch (fn [_url init]
              (reset! box (js/JSON.parse (.-body init)))
              (js/Response. "" #js {:status 400}))}))

(defn- anthropic-model [f]
  ((createAnthropic #js {:apiKey "k" :baseURL "https://relay.test/v1" :fetch f})
   "claude-opus-5"))

(defn- openai-chat-model [f]
  (.chat (createOpenAI #js {:apiKey "k" :baseURL "https://relay.test/v1"
                            :compatibility "compatible" :fetch f})
         "claude-opus-5"))

(defn- openai-responses-model [f]
  (.responses (createOpenAI #js {:apiKey "k" :baseURL "https://relay.test/v1"
                                 :compatibility "compatible" :fetch f})
              "gpt-5.4"))

(defn- google-model [f]
  ((createGoogleGenerativeAI #js {:apiKey "k" :fetch f}) "gemini-2.5-flash"))

(defn ^:async send-with
  "Send one request with `level` applied, return the body that hit the wire."
  [make-model level]
  (let [{:keys [box fetch]} (capture)
        model (make-model fetch)
        opts  (thinking/level->provider-options level model)]
    (try
      (let [res (streamText (cond-> #js {:model      model
                                         :messages   #js [#js {:role "user" :content "hi"}]
                                         :maxRetries 0}
                              opts (doto (aset "providerOptions" opts))))]
        (js-await (.-text res)))
      (catch :default _ nil))
    @box))

;; ── Provider routing ─────────────────────────────────────────

(describe "thinking/provider-kind" (fn []
                                     (it "routes anthropic.messages"
                                         (fn [] (-> (expect (thinking/provider-kind #js {:provider "anthropic.messages"}))
                                                    (.toBe :anthropic))))

                                     (it "routes the hand-rolled claude-native provider"
                                         (fn [] (-> (expect (thinking/provider-kind #js {:provider "claude-native"}))
                                                    (.toBe :anthropic))))

                                     (it "routes google"
                                         (fn [] (-> (expect (thinking/provider-kind #js {:provider "google.generative-ai"}))
                                                    (.toBe :google))))

                                     (it "routes openai.responses"
                                         (fn [] (-> (expect (thinking/provider-kind #js {:provider "openai.responses"}))
                                                    (.toBe :openai-responses))))

                                     (it "declines openai.chat even for a claude id"
                                         (fn []
        ;; That endpoint carries whatever a gateway relays — DeepSeek, GLM,
        ;; Qwen — and most reject reasoning_effort outright.
                                           (-> (expect (thinking/provider-kind #js {:provider "openai.chat"
                                                                                    :modelId "claude-opus-5"}))
                                               (.toBeNil))))

                                     (it "declines a bare string model"
                                         (fn [] (-> (expect (thinking/provider-kind "claude-opus-5")) (.toBeNil))))))

;; ── Option construction ──────────────────────────────────────

(describe "thinking/level->provider-options" (fn []
                                               (it "sends nothing when off"
                                                   (fn []
                                                     (-> (expect (thinking/level->provider-options
                                                                  "off" #js {:provider "anthropic.messages"}))
                                                         (.toBeNil))))

                                               (it "sends nothing for an unknown level"
                                                   (fn []
                                                     (-> (expect (thinking/level->provider-options
                                                                  "bogus" #js {:provider "anthropic.messages"}))
                                                         (.toBeNil))))

                                               (it "sends nothing for a provider with no reasoning dialect"
                                                   (fn []
                                                     (-> (expect (thinking/level->provider-options
                                                                  "high" #js {:provider "openai.chat"}))
                                                         (.toBeNil))))

                                               (it "scales the anthropic budget with the level"
                                                   (fn []
                                                     (let [budget (fn [lvl]
                                                                    (.. (thinking/level->provider-options
                                                                         lvl #js {:provider "anthropic.messages"})
                                                                        -anthropic -thinking -budgetTokens))]
          ;; Anthropic rejects a budget below 1024.
                                                       (-> (expect (budget "minimal")) (.toBeGreaterThanOrEqual 1024))
                                                       (-> (expect (budget "low")) (.toBeGreaterThan (budget "minimal")))
                                                       (-> (expect (budget "medium")) (.toBeGreaterThan (budget "low")))
                                                       (-> (expect (budget "high")) (.toBeGreaterThan (budget "medium")))
                                                       (-> (expect (budget "xhigh")) (.toBeGreaterThan (budget "high"))))))

                                               (it "clamps xhigh to high for openai, which has no xhigh"
                                                   (fn []
                                                     (-> (expect (.. (thinking/level->provider-options
                                                                      "xhigh" #js {:provider "openai.responses"})
                                                                     -openai -reasoningEffort))
                                                         (.toBe "high"))))))

;; ── What reaches the wire ────────────────────────────────────

(defn ^:async test-anthropic-off []
  (let [body (js-await (send-with anthropic-model "off"))]
    ;; Exactly the shape the live probe showed producing zero reasoning.
    (-> (expect (.-thinking body)) (.toBeUndefined))))

(defn ^:async test-anthropic-on []
  (let [body (js-await (send-with anthropic-model "medium"))]
    (-> (expect (.. body -thinking -type)) (.toBe "enabled"))
    (-> (expect (.. body -thinking -budget_tokens)) (.toBeGreaterThanOrEqual 1024))
    ;; Anthropic requires max_tokens strictly greater than the budget.
    (-> (expect (.-max_tokens body)) (.toBeGreaterThan (.. body -thinking -budget_tokens)))))

(defn ^:async test-openai-responses-on []
  (let [body (js-await (send-with openai-responses-model "high"))]
    (-> (expect (.. body -reasoning -effort)) (.toBe "high"))))

(defn ^:async test-openai-chat-untouched []
  (let [body (js-await (send-with openai-chat-model "high"))]
    ;; A claude id over an OpenAI-compatible relay must not be sent either
    ;; dialect: reasoning_effort would 400 on most relayed models.
    (-> (expect (.-reasoning_effort body)) (.toBeUndefined))
    (-> (expect (.-thinking body)) (.toBeUndefined))))

(defn ^:async test-google-on []
  (let [body (js-await (send-with google-model "low"))]
    (-> (expect (.. body -generationConfig -thinkingConfig -thinkingBudget))
        (.toBeGreaterThan 0))))

(describe "thinking reaches the request body" (fn []
                                                (it "anthropic: off sends no thinking field" test-anthropic-off)
                                                (it "anthropic: a level sends an enabled thinking block" test-anthropic-on)
                                                (it "openai /responses: a level sends reasoning effort" test-openai-responses-on)
                                                (it "openai /chat: nothing is sent, even for a claude id" test-openai-chat-untouched)
                                                (it "google: a level sends a thinking budget" test-google-on)))

;; ── Session wiring ───────────────────────────────────────────
;; --thinking and settings :thinking were both parsed and then dropped.

(describe "create-agent seeds the thinking level" (fn []
                                                    (it "takes the configured level"
                                                        (fn []
                                                          (-> (expect @(:thinking-level (create-agent {:model "m" :system-prompt "s"
                                                                                                       :thinking "high"})))
                                                              (.toBe "high"))))

                                                    (it "defaults to off"
                                                        (fn []
                                                          (-> (expect @(:thinking-level (create-agent {:model "m" :system-prompt "s"})))
                                                              (.toBe "off"))))

                                                    (it "falls back to off rather than propagating a bad value"
                                                        (fn []
                                                          (-> (expect @(:thinking-level (create-agent {:model "m" :system-prompt "s"
                                                                                                       :thinking "bogus"})))
                                                              (.toBe "off"))))))

(defn- with-commands
  "An agent with the built-in slash commands registered. `register-builtins`
   closes over session/resources only inside handlers we don't exercise here."
  [opts]
  (let [agent (create-agent opts)]
    (register-builtins agent nil nil)
    agent))

(defn ^:async run-command [agent name args]
  (let [cmd (get @(:commands agent) name)]
    (js-await ((:handler cmd) (clj->js args) #js {}))))

(defn ^:async test-thinking-cmd-sets-level []
  (let [agent (with-commands {:model "m" :system-prompt "s"})]
    (js-await (run-command agent "thinking" ["medium"]))
    (-> (expect @(:thinking-level agent)) (.toBe "medium"))))

(defn ^:async test-thinking-cmd-rejects-invalid []
  (let [agent (with-commands {:model "m" :system-prompt "s" :thinking "high"})]
    (js-await (run-command agent "thinking" ["turbo"]))
    (-> (expect @(:thinking-level agent)) (.toBe "high"))))

(defn ^:async test-thinking-cmd-reports-current []
  (let [agent (with-commands {:model "m" :system-prompt "s" :thinking "low"})]
    (js-await (run-command agent "thinking" []))
    (-> (expect @(:thinking-level agent)) (.toBe "low"))))

(describe "/thinking command" (fn []
                                (it "is registered"
                                    (fn []
                                      (let [agent (with-commands {:model "m" :system-prompt "s"})]
                                        (-> (expect (contains? @(:commands agent) "thinking")) (.toBe true)))))
                                (it "sets a valid level" test-thinking-cmd-sets-level)
                                (it "rejects an invalid level without changing the current one"
                                    test-thinking-cmd-rejects-invalid)
                                (it "reports the current level when called with no argument"
                                    test-thinking-cmd-reports-current)))

;; ── Merging ──────────────────────────────────────────────────

(describe "thinking/merge-provider-options" (fn []
                                              (it "keeps keys from both sides"
                                                  (fn []
                                                    (let [m (thinking/merge-provider-options
                                                             #js {:openai #js {:store true}}
                                                             #js {:anthropic #js {:thinking #js {:type "enabled"}}})]
                                                      (-> (expect (.. m -openai -store)) (.toBe true))
                                                      (-> (expect (.. m -anthropic -thinking -type)) (.toBe "enabled")))))

                                              (it "merges within a shared provider key rather than replacing it"
                                                  (fn []
                                                    (let [m (thinking/merge-provider-options
                                                             #js {:anthropic #js {:cacheControl #js {:type "ephemeral"}}}
                                                             #js {:anthropic #js {:thinking #js {:type "enabled"}}})]
                                                      (-> (expect (.. m -anthropic -cacheControl -type)) (.toBe "ephemeral"))
                                                      (-> (expect (.. m -anthropic -thinking -type)) (.toBe "enabled")))))

                                              (it "tolerates nil on either side"
                                                  (fn []
                                                    (-> (expect (js/Object.keys (thinking/merge-provider-options nil nil)))
                                                        (.toEqual #js []))))))
