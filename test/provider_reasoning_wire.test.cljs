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
            [agent.extensions.custom-provider-kimi.index :as kimi]))

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
            (it "sends thinking as an OBJECT — the string form is rejected with 2013"
                (fn []
                  (reset! minimax/level-fn-atom (fn [] "high"))
                  (let [b (js/JSON.parse (minimax/splice-reasoning-split base-body))]
                    (-> (expect (.-type (.-thinking b))) (.toBe "enabled"))
                    (-> (expect (.-reasoning_split b)) (.toBe true)))))

            (it "omits thinking when off, because disabled is not honoured on M2.5"
                (fn []
                  (reset! minimax/level-fn-atom (fn [] "off"))
                  (let [b (js/JSON.parse (minimax/splice-reasoning-split base-body))]
                    (-> (expect (.-thinking b)) (.toBeUndefined))
            ;; reasoning_split is unrelated and stays on
                    (-> (expect (.-reasoning_split b)) (.toBe true)))))))

;; ── Kimi ──────────────────────────────────────────────────────────
(describe "wire/kimi"
          (fn []
            (it "off actually turns Moonshot thinking off — it used to be hardcoded true"
                (fn []
                  (reset! kimi/level-fn-atom (fn [] "off"))
                  (let [b (rewrite (kimi/make-request-rewriter "kimi-k2.6") nil)]
                    (-> (expect (.-thinking (.-chat_template_kwargs b))) (.toBe false)))))

            (it "on for a thinking model when a level is set"
                (fn []
                  (reset! kimi/level-fn-atom (fn [] "medium"))
                  (let [b (rewrite (kimi/make-request-rewriter "kimi-k2.6") nil)]
                    (-> (expect (.-thinking (.-chat_template_kwargs b))) (.toBe true)))))))
