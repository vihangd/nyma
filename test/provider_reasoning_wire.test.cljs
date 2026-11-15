(ns provider-reasoning-wire.test
  "Assert the JSON that actually leaves the process.

   `small_model/thinking_budget` has spent its whole life writing
   `providerOptions.thinkingBudget`, a key nothing reads, and no test noticed
   because the tests checked a function's return value rather than the request.
   These drive each provider's real request-rewriter and read the body string it
   produces.

   Every dialect asserted here was measured against the live API first — see the
   per-provider comments in `agent.utils.reasoning-request`."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-openrouter.index :as openrouter]
            [agent.extensions.custom-provider-groq.index :as groq]
            [agent.extensions.custom-provider-minimax.index :as minimax]
            [agent.extensions.custom-provider-kimi.index :as kimi]
            [agent.extensions.custom-provider-relay.index :as relay]
            [agent.extensions.custom-provider-deepseek.index :as deepseek]
            [agent.extensions.custom-provider-opencode-zen.index :as zen]
            [agent.extensions.custom-provider-local.index :as local]))

(def ^:private base-body
  (js/JSON.stringify #js {:model "m" :messages #js [#js {:role "user" :content "hi"}]}))

(defn- rewrite [rw body]
  (js/JSON.parse (rw (or body base-body) nil)))

;; ── OpenRouter ────────────────────────────────────────────────────
(describe "wire/openrouter"
          (fn []
            (it "sends reasoning.effort at the level that is set"
                (fn []
                  (let [b (rewrite (openrouter/make-request-rewriter nil (fn [] "medium")) nil)]
                    (-> (expect (.-effort (.-reasoning b))) (.toBe "medium")))))

            (it "sends NO reasoning key when thinking is off — today's behaviour is the floor"
                (fn []
                  (let [rw (openrouter/make-request-rewriter nil (fn [] "off"))]
                    (-> (expect (.-reasoning (rewrite rw nil))) (.toBeUndefined)))))

            (it "reads the level per request, not once at construction"
        ;; /thinking can change at any time; the rewriter is built once
                (fn []
                  (let [lvl (atom "off")
                        rw  (openrouter/make-request-rewriter nil (fn [] @lvl))]
                    (-> (expect (.-reasoning (rewrite rw nil))) (.toBeUndefined))
                    (reset! lvl "high")
                    (-> (expect (.-effort (.-reasoning (rewrite rw nil)))) (.toBe "high")))))

            (it "carries the backend routing block alongside reasoning"
                (fn []
                  (let [b (rewrite (openrouter/make-request-rewriter {:only ["parasail"]}
                                                                     (fn [] "low")) nil)]
                    (-> (expect (first (.-only (.-provider b)))) (.toBe "parasail"))
                    (-> (expect (.-effort (.-reasoning b))) (.toBe "low")))))

            (it "never overrides what the caller already set"
                (fn []
                  (let [pre (js/JSON.stringify #js {:model "m" :reasoning #js {:effort "xhigh"}})
                        b   (rewrite (openrouter/make-request-rewriter nil (fn [] "low")) pre)]
                    (-> (expect (.-effort (.-reasoning b))) (.toBe "xhigh")))))))

;; ── Groq ──────────────────────────────────────────────────────────
(describe "wire/groq"
          (fn []
            (it "sends reasoning_effort + parsed format for a model Groq lists"
                (fn []
                  (reset! groq/level-fn-atom (fn [] "low"))
                  (let [b (rewrite (groq/make-request-rewriter "openai/gpt-oss-20b") nil)]
                    (-> (expect (.-reasoning_effort b)) (.toBe "low"))
                    (-> (expect (.-reasoning_format b)) (.toBe "parsed")))))

            (it "sends nothing for a model that does not accept it"
        ;; on Groq an unsupported reasoning param is an error, not an ignored key
                (fn []
                  (reset! groq/level-fn-atom (fn [] "high"))
                  (let [b (rewrite (groq/make-request-rewriter "llama-3.1-8b-instant") nil)]
                    (-> (expect (.-reasoning_effort b)) (.toBeUndefined)))))

            (it "sends nothing when thinking is off"
                (fn []
                  (reset! groq/level-fn-atom (fn [] "off"))
                  (let [b (rewrite (groq/make-request-rewriter "openai/gpt-oss-20b") nil)]
                    (-> (expect (.-reasoning_effort b)) (.toBeUndefined)))))))

;; ── MiniMax ───────────────────────────────────────────────────────
(describe "wire/minimax"
          (fn []
            (it "does NOT send a thinking field — measured as unwired on purpose"
        ;; the string form is rejected on both models; {type:"enabled"} is
        ;; rejected on M3 (allowed: adaptive, disabled); {type:"disabled"} is
        ;; accepted but not honoured. Nothing to gain, a role to break.
                (fn []
                  (let [b (js/JSON.parse (minimax/splice-reasoning-split base-body))]
                    (-> (expect (.-thinking b)) (.toBeUndefined))
                    (-> (expect (.-reasoning_split b)) (.toBe true)))))))

;; ── Kimi ──────────────────────────────────────────────────────────
(describe "wire/kimi"
          (fn []
            (it "off actually turns Moonshot thinking off — it used to be hardcoded true"
                (fn []
                  (reset! kimi/level-fn-atom (fn [] "off"))
                  (let [b (rewrite (kimi/make-request-rewriter "kimi-k2.6") nil)]
                    (-> (expect (.-thinking (.-chat_template_kwargs b))) (.toBe false)))))

            (it "sends no temperature to a thinking model, which refuses nyma's"
                (fn []
                  (let [body (js/JSON.stringify #js {:model "kimi-k2.6" :temperature 0.2 :messages #js []})
                        b    (rewrite (kimi/make-request-rewriter "kimi-k2.6") body)]
                    (-> (expect (.-temperature b)) (.toBeUndefined)))))

            (it "on for a thinking model when a level is set"
                (fn []
                  (reset! kimi/level-fn-atom (fn [] "medium"))
                  (let [b (rewrite (kimi/make-request-rewriter "kimi-k2.6") nil)]
                    (-> (expect (.-thinking (.-chat_template_kwargs b))) (.toBe true)))))))

;; ── Relay (yunwu and other New-API gateways) ──────────────────────
(describe "wire/relay"
          (fn []
            (it "sends reasoning_effort on the chat path"
        ;; measured: yunwu deepseek-v4-pro returns no reasoning by default and
        ;; answers 17*23 as 403; with reasoning_effort it answers 391
                (fn []
                  (reset! relay/level-fn-atom (fn [] "medium"))
                  (let [b (rewrite (relay/chat-request-rewriter) nil)]
                    (-> (expect (.-reasoning_effort b)) (.toBe "medium")))))

            (it "clamps to the OpenAI scale the relay speaks"
                (fn []
                  (reset! relay/level-fn-atom (fn [] "xhigh"))
                  (-> (expect (.-reasoning_effort (rewrite (relay/chat-request-rewriter) nil)))
                      (.toBe "high"))
                  (reset! relay/level-fn-atom (fn [] "minimal"))
                  (-> (expect (.-reasoning_effort (rewrite (relay/chat-request-rewriter) nil)))
                      (.toBe "low"))))

            (it "sends nothing when off, and still lifts <think> as before"
                (fn []
                  (reset! relay/level-fn-atom (fn [] "off"))
                  (let [body (js/JSON.stringify
                              #js {:model "m"
                                   :messages #js [#js {:role "assistant"
                                                       :content "<think>plan</think>done"}]})
                        b (rewrite (relay/chat-request-rewriter) body)]
                    (-> (expect (.-reasoning_effort b)) (.toBeUndefined))
                    ;; the pre-existing lift must survive the addition
                    (-> (expect (.-reasoning_content (first (.-messages b)))) (.toBe "plan")))))))

(defn- body-for [model]
  (js/JSON.stringify #js {:model model :messages #js [#js {:role "user" :content "hi"}]}))

;; ── DeepSeek V4 / GLM: thinking + low/high/max ────────────────────
(describe "wire/deepseek"
          (fn []
            (it "maps every level onto thinking + low/high/max"
        ;; measured: with no parameter V4 decides per request (152 0 0 0 chars)
                (fn []
                  (doseq [[lvl effort] [["minimal" "low"] ["low" "low"] ["medium" "high"]
                                        ["high" "max"] ["xhigh" "max"]]]
                    (reset! deepseek/level-fn-atom (fn [] lvl))
                    (let [b (rewrite deepseek/request-rewriter nil)]
                      (-> (expect (.-type (.-thinking b))) (.toBe "enabled"))
                      (-> (expect (.-reasoning_effort b)) (.toBe effort))))))

            (it "off turns thinking off rather than sending nothing"
                (fn []
                  (reset! deepseek/level-fn-atom (fn [] "off"))
                  (let [b (rewrite deepseek/request-rewriter nil)]
                    (-> (expect (.-type (.-thinking b))) (.toBe "disabled"))
                    (-> (expect (.-reasoning_effort b)) (.toBeUndefined)))))

            (it "keeps lifting <think> for replay, and never overrides the caller"
                (fn []
                  (reset! deepseek/level-fn-atom (fn [] "high"))
                  (let [body (js/JSON.stringify
                              #js {:model "deepseek-v4-pro" :reasoning_effort "low"
                                   :messages #js [#js {:role "assistant"
                                                       :content "<think>plan</think>done"}]})
                        b    (rewrite deepseek/request-rewriter body)]
                    (-> (expect (.-reasoning_effort b)) (.toBe "low"))
                    (-> (expect (.-reasoning_content (first (.-messages b)))) (.toBe "plan")))))))

(describe "wire/relay model families"
          (fn []
            (it "GLM gets thinking + max at high, not OpenAI's high (less than its default)"
                (fn []
                  (reset! relay/level-fn-atom (fn [] "high"))
                  (let [b (rewrite (relay/chat-request-rewriter) (body-for "glm-5.2"))]
                    (-> (expect (.-type (.-thinking b))) (.toBe "enabled"))
                    (-> (expect (.-reasoning_effort b)) (.toBe "max")))))

            (it "GLM-5.3 cannot turn thinking off: off is the lightest effort"
        ;; measured: thinking disabled → 400 code 1210
                (fn []
                  (reset! relay/level-fn-atom (fn [] "off"))
                  (let [b (rewrite (relay/chat-request-rewriter) (body-for "glm-5.3-flash"))]
                    (-> (expect (.-thinking b)) (.toBeUndefined))
                    (-> (expect (.-reasoning_effort b)) (.toBe "low")))
                  (let [b (rewrite (relay/chat-request-rewriter) (body-for "glm-5.2"))]
                    (-> (expect (.-type (.-thinking b))) (.toBe "disabled")))))

            (it "a vendor-prefixed DeepSeek V4 id is recognised"
                (fn []
                  (reset! relay/level-fn-atom (fn [] "medium"))
                  (let [b (rewrite (relay/chat-request-rewriter) (body-for "deepseek/deepseek-v4-pro"))]
                    (-> (expect (.-reasoning_effort b)) (.toBe "high"))
                    (-> (expect (.-type (.-thinking b))) (.toBe "enabled")))))

            (it "velona: off sends reasoning.enabled false, any other level nothing"
        ;; measured: velona ignores every effort knob; only this turns it off
                (fn []
                  (reset! relay/level-fn-atom (fn [] "off"))
                  (let [b (rewrite (relay/chat-request-rewriter "velona") (body-for "deepseek/deepseek-v4-pro"))]
                    (-> (expect (.-enabled (.-reasoning b))) (.toBe false)))
                  (reset! relay/level-fn-atom (fn [] "high"))
                  (let [b (rewrite (relay/chat-request-rewriter "velona") (body-for "deepseek/deepseek-v4-pro"))]
                    (-> (expect (.-reasoning b)) (.toBeUndefined))
                    (-> (expect (.-reasoning_effort b)) (.toBeUndefined)))))))

;; ── opencode-zen (chat path) ──────────────────────────────────────
(describe "wire/opencode-zen"
          (fn []
            (it "GLM and DeepSeek get thinking + low/high/max; off disables"
                (fn []
                  (reset! zen/level-fn-atom (fn [] "high"))
                  (let [b (rewrite zen/request-rewriter (body-for "glm-5.3-flash"))]
                    (-> (expect (.-reasoning_effort b)) (.toBe "max")))
                  (reset! zen/level-fn-atom (fn [] "off"))
                  ;; measured: Zen honours disabled even for glm-5.3
                  (let [b (rewrite zen/request-rewriter (body-for "deepseek-v4-pro"))]
                    (-> (expect (.-type (.-thinking b))) (.toBe "disabled")))))

            (it "Qwen gets OpenAI's scale with thinking (max 400s there)"
                (fn []
                  (reset! zen/level-fn-atom (fn [] "xhigh"))
                  (let [b (rewrite zen/request-rewriter (body-for "qwen3.6-plus"))]
                    (-> (expect (.-reasoning_effort b)) (.toBe "high"))
                    (-> (expect (.-type (.-thinking b))) (.toBe "enabled")))))

            (it "Gemini/Grok never get a thinking key (Zen 400s on it)"
                (fn []
                  (reset! zen/level-fn-atom (fn [] "medium"))
                  (let [b (rewrite zen/request-rewriter (body-for "gemini-3.5-flash-lite"))]
                    (-> (expect (.-thinking b)) (.toBeUndefined))
                    (-> (expect (.-reasoning_effort b)) (.toBe "medium")))
                  (reset! zen/level-fn-atom (fn [] "off"))
                  (let [b (rewrite zen/request-rewriter (body-for "grok-4.5"))]
                    (-> (expect (.-thinking b)) (.toBeUndefined))
                    (-> (expect (.-reasoning_effort b)) (.toBeUndefined)))))))

;; ── local servers ─────────────────────────────────────────────────
(describe "wire/local"
          (fn []
            (let [lift (fn [b _] b)]
              (it "off sends both off switches (Ollama /v1, llama.cpp, vLLM)"
                  (fn []
                    (reset! local/level-fn-atom (fn [] "off"))
                    (let [b (rewrite (local/reasoning-rewriter {} lift) nil)]
                      (-> (expect (.-reasoning_effort b)) (.toBe "none"))
                      (-> (expect (.. b -chat_template_kwargs -enable_thinking)) (.toBe false)))))

              (it "a level goes out top-level and to the template"
                  (fn []
                    (reset! local/level-fn-atom (fn [] "xhigh"))
                    (let [b (rewrite (local/reasoning-rewriter {} lift) nil)]
                      (-> (expect (.-reasoning_effort b)) (.toBe "high"))
                      (-> (expect (.. b -chat_template_kwargs -enable_thinking)) (.toBe true))
                      (-> (expect (.. b -chat_template_kwargs -reasoning_effort)) (.toBe "high")))))

              (it "an entry with reasoning none sends nothing"
                  (fn []
                    (reset! local/level-fn-atom (fn [] "high"))
                    (let [b (rewrite (local/reasoning-rewriter {:reasoning "none"} lift) nil)]
                      (-> (expect (.-reasoning_effort b)) (.toBeUndefined))
                      (-> (expect (.-chat_template_kwargs b)) (.toBeUndefined))))))))
