(ns reasoning-request.test
  "`/thinking` reached none of the OpenAI-compatible providers: they all report
   the tag `openai.chat`, which `thinking/provider-kind` maps to nil. These are
   the per-provider dialects that fix it.

   The floor that matters: `off` — nyma's default — must send nothing anywhere,
   so wiring these cannot change today's behaviour until a level is set.

   The dialects are no longer functions in `agent.utils.reasoning-request`; each
   provider extension installs its own `defmethod` on `rr/reasoning-body`. That
   is why the provider namespaces are required here even though nothing in this
   file names them: importing them is what registers the methods, and a method
   that is never imported is exactly the unknown-provider case the `:default`
   covers below."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.reasoning-request :as rr]
            [agent.extensions.custom-provider-openrouter.index]
            [agent.extensions.custom-provider-groq.index :as groq]
            [agent.extensions.custom-provider-kimi.index]
            [agent.extensions.custom-provider-relay.index]))

(describe "reasoning-request/openrouter"
          (fn []
            (it "passes nyma's level straight through as reasoning.effort"
        ;; OpenRouter's enum is a superset of nyma's, so no clamping
                (fn []
                  (-> (expect (:effort (rr/reasoning-body "openrouter" "medium" nil)))
                      (.toBe "medium"))
                  (-> (expect (:effort (rr/reasoning-body "openrouter" "xhigh" nil)))
                      (.toBe "xhigh"))
                  (-> (expect (:effort (rr/reasoning-body "openrouter" "minimal" nil)))
                      (.toBe "minimal"))))

            (it "sends nothing for off, nil, or a level nyma does not know"
                (fn []
                  (-> (expect (rr/reasoning-body "openrouter" "off" nil)) (.toBeFalsy))
                  (-> (expect (rr/reasoning-body "openrouter" nil nil)) (.toBeFalsy))
                  (-> (expect (rr/reasoning-body "openrouter" "turbo" nil)) (.toBeFalsy))))))

(describe "reasoning-request/groq"
          (fn []
            (it "sends the parameter only to the models Groq documents for it"
        ;; on Groq an unsupported reasoning param is a request error, not an
        ;; ignored key, so the allowlist is load-bearing
                (fn []
                  (-> (expect (groq/reasoning-model? "openai/gpt-oss-20b")) (.toBe true))
                  (-> (expect (groq/reasoning-model? "llama-3.1-8b-instant")) (.toBe false))
                  (-> (expect (rr/reasoning-body "groq" "high" "openai/gpt-oss-20b"))
                      (.toBeTruthy))
                  (-> (expect (rr/reasoning-body "groq" "high" "llama-3.1-8b-instant"))
                      (.toBeFalsy))))

            (it "clamps xhigh to high — Groq's scale stops there"
                (fn []
                  (-> (expect (:reasoning_effort
                               (rr/reasoning-body "groq" "xhigh" "openai/gpt-oss-20b")))
                      (.toBe "high"))))

            (it "asks for the parsed reasoning format, not inline <think>"
                (fn []
                  (-> (expect (:reasoning_format
                               (rr/reasoning-body "groq" "low" "openai/gpt-oss-120b")))
                      (.toBe "parsed"))))

            (it "sends nothing when thinking is off"
                (fn []
                  (-> (expect (rr/reasoning-body "groq" "off" "openai/gpt-oss-20b"))
                      (.toBeFalsy))))))

(describe "reasoning-request/relay"
          (fn []
            (it "clamps to the OpenAI scale a New-API gateway speaks"
                (fn []
                  (-> (expect (:reasoning_effort (rr/reasoning-body "relay" "minimal" nil)))
                      (.toBe "low"))
                  (-> (expect (:reasoning_effort (rr/reasoning-body "relay" "xhigh" nil)))
                      (.toBe "high"))
                  (-> (expect (:reasoning_effort (rr/reasoning-body "relay" "medium" nil)))
                      (.toBe "medium"))))

            (it "sends nothing when thinking is off"
                (fn []
                  (-> (expect (rr/reasoning-body "relay" "off" nil)) (.toBeFalsy))))))

(describe "reasoning-request/kimi"
          (fn []
            (it "off means false, not absent — the flag used to be hardcoded true"
                (fn []
                  (-> (expect (:thinking (rr/reasoning-body "kimi" "off" nil))) (.toBe false))
                  (-> (expect (:thinking (rr/reasoning-body "kimi" "high" nil))) (.toBe true))))))

(describe "reasoning-request/default"
          (fn []
            (it "a provider with no installed dialect sends nothing"
        ;; the whole point of the open dispatch: core does not have to know the
        ;; provider list, and not knowing one is safe rather than fatal
                (fn []
                  (-> (expect (rr/reasoning-body "no-such-provider" "high" "m"))
                      (.toBeFalsy))
                  ;; minimax is a real provider that deliberately installs no
                  ;; method — see splice-reasoning-split's measurements
                  (-> (expect (rr/reasoning-body "minimax" "high" nil)) (.toBeFalsy))))))
