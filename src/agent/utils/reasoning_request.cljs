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
   each publishes its own reasoning parameter.

   This namespace used to hold those mappings, one function per dialect, which
   meant core carried a hardcoded list of five provider dialects and a sixth
   provider could not join without editing this file. What is left here is the
   OPEN half: the `reasoning-body` multimethod and the one pure level helper
   every dialect needs. Each provider extension installs its own
   `(defmethod rr/reasoning-body \"<provider>\" ...)` next to the request
   rewriter that consumes it, so the measured evidence for a dialect and the
   code that sends it live in the same file.

   Everything here is pure. `off` (and any level nyma does not recognise)
   returns nil from `active-level`, which every dialect treats as 'send
   nothing' — so the floor is exactly today's behaviour, and the `:default`
   method makes an unknown provider send nothing too."
  (:require [agent.thinking :as thinking]))

(defn active-level
  "A level worth sending: recognised, and not `off` — else nil.

   Public because every provider's `defmethod` lives in its own extension and
   needs this clamp."
  [level]
  (let [l (str (or level "off"))]
    (and (thinking/valid-level? l) (not= l "off") l)))

(defmulti reasoning-body
  "provider-name → the fields to splice into the raw request body, or nil.

   Dispatches on the provider name string. Methods are installed by the
   provider extensions themselves (`custom_provider_groq/index.cljs` and
   friends); squint compiles `defmethod` to a mutation of the shared
   multimethod object imported from here, so a method registers as soon as its
   module is imported — which is exactly when the provider exists.

   Arity is uniform `[provider level model-id]` so a caller never has to know
   whether a given dialect happens to care about the model."
  (fn [provider _level _model-id] provider))

(defmethod reasoning-body :default [_ _ _] nil)
