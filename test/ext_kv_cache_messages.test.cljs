(ns ext-kv-cache-messages.test
  "Tests for message-level cache_control breakpoints in kv_cache.

   Tested directly against `before_provider_request` so we can
   pre-seed messages and inspect the mutated st-config."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.core :refer [create-agent]]
            [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions :refer [create-extension-api]]))

(defn- reset-stats! []
  (swap! shared/suite-stats assoc :kv-cache
         {:turns 0 :cache-hits 0 :cached-tokens 0}))

(beforeEach reset-stats!)

(defn- make-agent [model-id]
  (create-agent {:model #js {:modelId model-id}
                 :system-prompt "You are a test agent."}))

(defn- make-config
  "Build a minimal st-config object the way agent.loop builds it."
  [model-id messages]
  #js {:model           #js {:modelId model-id}
       :system          "system prompt"
       :messages        messages
       :tools           #js {}
       :providerOptions #js {}})

(defn ^:async fire-bpr
  "Activate kv-cache on a fresh agent, fire before_provider_request with
   the supplied config. Returns the mutated config."
  [model-id config]
  (let [agent  (make-agent model-id)
        api    (create-extension-api agent)
        _deact (kv-cache/activate api)
        events (:events agent)]
    (js-await ((:emit-collect events) "before_provider_request" config))
    config))

;;; ─── Provider routing ─────────────────────────────────────────────────────

(defn ^:async test-claude-uses-anthropic-key []
  (let [msg #js {:role "assistant" :content "hello world response"}]
    (js-await (fire-bpr "claude-sonnet-4-6"
                        (make-config "claude-sonnet-4-6" #js [msg])))
    (-> (expect (.. msg -providerOptions -anthropic -cacheControl -type))
        (.toBe "ephemeral"))))

(defn ^:async test-gemini-uses-google-key []
  (let [msg #js {:role "assistant" :content "hello"}]
    (js-await (fire-bpr "gemini-2.5-pro"
                        (make-config "gemini-2.5-pro" #js [msg])))
    (-> (expect (.. msg -providerOptions -google -cacheControl -type))
        (.toBe "ephemeral"))))

(defn ^:async test-openai-untouched []
  (let [msg #js {:role "assistant" :content "hello"}]
    (js-await (fire-bpr "gpt-4o" (make-config "gpt-4o" #js [msg])))
    (-> (expect (.-providerOptions msg)) (.toBeUndefined))))

(defn ^:async test-groq-untouched []
  (let [msg #js {:role "assistant" :content "hello"}]
    (js-await (fire-bpr "llama-3.3-70b" (make-config "llama-3.3-70b" #js [msg])))
    (-> (expect (.-providerOptions msg)) (.toBeUndefined))))

(describe "kv-cache/provider routing" (fn []
                                        (it "uses anthropic key for claude models" test-claude-uses-anthropic-key)
                                        (it "uses google key for gemini models" test-gemini-uses-google-key)
                                        (it "leaves openai messages untouched" test-openai-untouched)
                                        (it "leaves groq messages untouched" test-groq-untouched)))

;;; ─── Anchor placement ─────────────────────────────────────────────────────

(defn ^:async test-anchor-on-last-assistant []
  (let [m1 #js {:role "user" :content "q1"}
        m2 #js {:role "assistant" :content "a1"}
        m3 #js {:role "user" :content "q2"}
        m4 #js {:role "assistant" :content "a2"}]
    (js-await (fire-bpr "claude-sonnet-4-6"
                        (make-config "claude-sonnet-4-6" #js [m1 m2 m3 m4])))
    (-> (expect (.. m4 -providerOptions -anthropic -cacheControl -type))
        (.toBe "ephemeral"))
    (-> (expect (.-providerOptions m1)) (.toBeUndefined))
    (-> (expect (.-providerOptions m3)) (.toBeUndefined))))

(defn ^:async test-anchor-skips-empty []
  (let [m1 #js {:role "assistant" :content "complete"}
        m2 #js {:role "user" :content "q"}
        m3 #js {:role "assistant" :content ""}]
    (js-await (fire-bpr "claude-sonnet-4-6"
                        (make-config "claude-sonnet-4-6" #js [m1 m2 m3])))
    (-> (expect (.. m1 -providerOptions -anthropic -cacheControl -type))
        (.toBe "ephemeral"))
    (-> (expect (.-providerOptions m3)) (.toBeUndefined))))

(defn ^:async test-anchor-no-assistant-noop []
  (let [m1 #js {:role "user" :content "q"}]
    (js-await (fire-bpr "claude-sonnet-4-6"
                        (make-config "claude-sonnet-4-6" #js [m1])))
    (-> (expect (.-providerOptions m1)) (.toBeUndefined))))

(describe "kv-cache/anchor" (fn []
                              (it "places cache_control on the last assistant message" test-anchor-on-last-assistant)
                              (it "skips empty/streaming assistant messages when picking the anchor" test-anchor-skips-empty)
                              (it "is a no-op when no assistant messages exist" test-anchor-no-assistant-noop)))

;;; ─── N-back checkpoint ────────────────────────────────────────────────────

(defn ^:async test-n-back-checkpoint []
  ;; default checkpoint-every-turns = 4, max-message-breakpoints = 2
  ;; assistant turns at odd indices: 1, 3, 5, 7, 9
  ;; anchor = 9, then walking back 4 assistant turns lands on index 1
  (let [msgs (clj->js
              (vec (for [i (range 10)]
                     {:role    (if (even? i) "user" "assistant")
                      :content (str "msg-" i)})))]
    (js-await (fire-bpr "claude-sonnet-4-6"
                        (make-config "claude-sonnet-4-6" msgs)))
    (let [m9 (aget msgs 9)
          m1 (aget msgs 1)
          m7 (aget msgs 7)]
      ;; anchor at last assistant turn
      (-> (expect (.. m9 -providerOptions -anthropic -cacheControl -type))
          (.toBe "ephemeral"))
      ;; 4-assistant-turns-back checkpoint at index 1
      (-> (expect (.. m1 -providerOptions -anthropic -cacheControl -type))
          (.toBe "ephemeral"))
      ;; intermediate assistant turn should NOT get a breakpoint (max = 2)
      (-> (expect (.-providerOptions m7)) (.toBeUndefined)))))

(describe "kv-cache/checkpoint" (fn []
                                  (it "places a second breakpoint K assistant turns before the anchor" test-n-back-checkpoint)))

;;; ─── Preserves existing providerOptions ──────────────────────────────────

(defn ^:async test-preserves-existing-options []
  (let [msg #js {:role            "assistant"
                 :content         "hello"
                 :providerOptions #js {:openai #js {:reasoningEffort "high"}}}]
    (js-await (fire-bpr "claude-sonnet-4-6"
                        (make-config "claude-sonnet-4-6" #js [msg])))
    (-> (expect (.. msg -providerOptions -openai -reasoningEffort)) (.toBe "high"))
    (-> (expect (.. msg -providerOptions -anthropic -cacheControl -type)) (.toBe "ephemeral"))))

(describe "kv-cache/preservation" (fn []
                                    (it "merges with existing providerOptions instead of overwriting" test-preserves-existing-options)))
