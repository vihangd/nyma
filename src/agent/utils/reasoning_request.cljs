(ns agent.utils.reasoning-request
  "Translate nyma's thinking level into each provider's REQUEST dialect.

   `agent.thinking` covers the three dialects the Vercel AI SDK models natively —
   Anthropic, Google, and OpenAI's /responses endpoint — and deliberately sends
   nothing for `openai.chat`, because a bare relay will 400 on a parameter it
   does not know (`thinking.cljs:72-79`).

   The consequence was that `/thinking` did nothing at all for kimi, local,
   relay-chat, deepseek, minimax, groq, opencode-zen-chat and openrouter: every
   one of them builds its model with `createOpenAI(...).chat(...)`, so every one
   reports the tag `openai.chat`. Those providers are not bare relays, though —
   each publishes its own reasoning parameter. This namespace holds those
   mappings, one function per dialect, so a provider extension can inject the
   right field into the raw request body.

   Everything here is pure. `off` (and any level nyma does not recognise)
   returns nil, which every caller treats as 'send nothing' — so the floor is
   exactly today's behaviour."
  (:require [agent.thinking :as thinking]))

(defn- active?
  "A level worth sending: recognised, and not `off`."
  [level]
  (let [l (str (or level "off"))]
    (and (thinking/valid-level? l) (not= l "off") l)))

;; ── OpenRouter ────────────────────────────────────────────────────
;; https://openrouter.ai/docs/use-cases/reasoning-tokens
;; effort ∈ none|minimal|low|medium|high|xhigh|max — nyma's levels are
;; minimal|low|medium|high|xhigh, so this is a 1:1 pass-through. 284 of
;; OpenRouter's 415 models accept it, and OpenRouter drops parameters a backend
;; does not support rather than erroring.

(defn openrouter
  "Level → the `reasoning` object, or nil."
  [level]
  (when-let [l (active? level)]
    {:effort l}))

;; ── Groq ──────────────────────────────────────────────────────────
;; `reasoning_effort` is accepted only by the models Groq documents for it;
;; sending it to the others is an error, so the caller passes the model id.
;; `reasoning_format: "parsed"` puts the chain in its own field instead of
;; inline <think>, which is what reasoning_stream would otherwise have to strip.

(def ^:private groq-reasoning-models
  #{"qwen/qwen3-32b" "qwen/qwen3.6-27b" "openai/gpt-oss-20b" "openai/gpt-oss-120b"
    "openai/gpt-oss-safeguard-20b"})

(defn groq-reasoning-model?
  "Pure: does this Groq model accept reasoning parameters?"
  [model-id]
  (contains? groq-reasoning-models (str model-id)))

(defn groq
  "Level + model → {:reasoning_effort … :reasoning_format \"parsed\"}, or nil.
   Groq's scale tops out at `high`, so xhigh clamps."
  [level model-id]
  (when-let [l (active? level)]
    (when (groq-reasoning-model? model-id)
      {:reasoning_effort (if (= l "xhigh") "high" l)
       :reasoning_format "parsed"})))

;; ── MiniMax ───────────────────────────────────────────────────────
;; Measured against MiniMax-M2.5 rather than taken from the docs, and the docs
;; would have produced a broken request:
;;
;;   thinking: "disabled"          → REJECTED, status 2013: "Mismatch type
;;                                   open_platform_oai.ThinkingConfig with value
;;                                   string" — it is an OBJECT, not a string
;;   thinking: {type: "enabled"}   → honoured: 498 → 1514 reasoning chars
;;   thinking: {type: "disabled"}  → accepted but NOT honoured on M2.5
;;                                   (560 chars vs 498 baseline)
;;
;; So `off` sends nothing rather than a field the provider ignores — claiming to
;; disable something we cannot disable is how the rest of this codebase ended up
;; with features that quietly do nothing.

(defn minimax
  "Level → {:thinking {:type \"enabled\"}}, or nil for off."
  [level]
  (when (active? level)
    {:thinking {:type "enabled"}}))

;; ── Relay gateways (yunwu and other New-API style relays) ─────────
;; Measured against yunwu:
;;   deepseek-v4-pro, no param        → 0 reasoning chars, and answers
;;                                      "17*23" as 403 (wrong)
;;   deepseek-v4-pro, reasoning_effort → 73-90 reasoning chars, answers 391
;;                                      (right)
;;   deepseek-v4-flash                → already reasons by default; the param is
;;                                      accepted and changes little
;; The relay speaks the OpenAI dialect, which tops out at `high`.

(defn relay
  "Level → {:reasoning_effort …}, or nil for off."
  [level]
  (when-let [l (active? level)]
    {:reasoning_effort (case l
                         "minimal" "low"
                         "xhigh"   "high"
                         l)}))

;; ── Kimi / Moonshot ───────────────────────────────────────────────
;; Injected as chat_template_kwargs.thinking. The extension hardcoded `true`
;; for its thinking models, so `off` never turned it off.

(defn kimi
  "Level → {:thinking bool}, for chat_template_kwargs."
  [level]
  {:thinking (boolean (active? level))})
