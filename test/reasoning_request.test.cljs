(ns reasoning-request.test
  "`/thinking` reached none of the OpenAI-compatible providers: they all report
   the tag `openai.chat`, which `thinking/provider-kind` maps to nil. These are
   the per-provider dialects that fix it.

   The floor that matters: `off` — nyma's default — must send nothing anywhere,
   so wiring these cannot change today's behaviour until a level is set."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.reasoning-request :as rr]))

(describe "reasoning-request/openrouter"
          (fn []
            (it "passes nyma's levels straight through as effort"
                (fn []
                  (-> (expect (:effort (rr/openrouter "medium"))) (.toBe "medium"))
                  (-> (expect (:effort (rr/openrouter "xhigh"))) (.toBe "xhigh"))
                  (-> (expect (:effort (rr/openrouter "minimal"))) (.toBe "minimal"))))

            (it "sends nothing for off or an unrecognised level"
                (fn []
                  (-> (expect (rr/openrouter "off")) (.toBeFalsy))
                  (-> (expect (rr/openrouter nil)) (.toBeFalsy))
                  (-> (expect (rr/openrouter "turbo")) (.toBeFalsy))))))

(describe "reasoning-request/groq"
          (fn []
            (it "only sends to models Groq documents for it"
        ;; sending reasoning_effort to a model that doesn't take it is an error,
        ;; not an ignored field
                (fn []
                  (-> (expect (rr/groq "high" "openai/gpt-oss-20b")) (.toBeTruthy))
                  (-> (expect (rr/groq "high" "llama-3.1-8b-instant")) (.toBeFalsy))))

            (it "clamps xhigh, which Groq's scale does not have"
                (fn []
                  (-> (expect (:reasoning_effort (rr/groq "xhigh" "openai/gpt-oss-20b")))
                      (.toBe "high"))))

            (it "asks for the chain in its own field rather than inline <think>"
                (fn []
                  (-> (expect (:reasoning_format (rr/groq "low" "openai/gpt-oss-120b")))
                      (.toBe "parsed"))))

            (it "sends nothing when off"
                (fn []
                  (-> (expect (rr/groq "off" "openai/gpt-oss-20b")) (.toBeFalsy))))))

(describe "reasoning-request/minimax"
          (fn []
            (it "sends the OBJECT form — the string form is rejected outright"
        ;; measured: thinking:"disabled" → status 2013 "Mismatch type
        ;; ThinkingConfig with value string". The docs read as a string.
                (fn []
                  (-> (expect (:type (:thinking (rr/minimax "high")))) (.toBe "enabled"))))

            (it "sends nothing for off rather than a field M2.5 ignores"
        ;; measured: thinking:{type:"disabled"} gave 560 reasoning chars against
        ;; a 498 baseline — accepted, not honoured. Sending it would be theatre.
                (fn []
                  (-> (expect (rr/minimax "off")) (.toBeFalsy))
                  (-> (expect (rr/minimax nil)) (.toBeFalsy))))))

(describe "reasoning-request/kimi"
          (fn []
            (it "off means off — it was hardcoded true for thinking models"
                (fn []
                  (-> (expect (:thinking (rr/kimi "off"))) (.toBe false))
                  (-> (expect (:thinking (rr/kimi "high"))) (.toBe true))))))
