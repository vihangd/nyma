(ns agent.thinking
  "Turn the session's thinking level into provider request options.

   nyma tracked a thinking level in three places — the `--thinking` CLI flag, the
   settings `:thinking` key, and the `thinking-level` atom that `/status` prints
   and the RPC mode cycles — and no code ever turned any of them into a request
   parameter. Extended thinking was therefore never requested from any provider,
   and the reasoning stream handlers in `agent.loop` had nothing to render.

   Measured against a live Claude Opus 5: with no `thinking` field the response
   carries no reasoning parts at all. (A comment in custom_provider_claude_native
   claimed Opus 5 thinks by default without configuration; it does not, at least
   over the raw Messages API.)

   Routing is on the model's `provider` tag rather than its id, for the same
   reason `token-suite/shared` does it: which reasoning parameter a request may
   carry is a property of the endpoint, not of the model's name. A Claude model
   reached over an OpenAI-compatible relay must not be sent Anthropic's
   `thinking` block."
  (:require [clojure.string :as str]))

(def levels
  "Valid levels, weakest first. `off` sends nothing at all."
  ["off" "minimal" "low" "medium" "high" "xhigh"])

(defn valid-level? [level]
  (boolean (some #{(str level)} levels)))

;; Anthropic requires budget_tokens >= 1024, and max_tokens strictly greater
;; than the budget. The AI SDK's default max_tokens is far above the top of this
;; ladder for every current Claude model.
(def ^:private anthropic-budgets
  {"minimal" 1024
   "low"     4000
   "medium"  10000
   "high"    24000
   "xhigh"   32000})

;; OpenAI's /responses reasoning effort has no `xhigh`.
(def ^:private openai-efforts
  {"minimal" "minimal"
   "low"     "low"
   "medium"  "medium"
   "high"    "high"
   "xhigh"   "high"})

;; Gemini 2.5 Flash accepts 0–24576; 2.5 Pro goes higher. Clamp to the lower
;; bound so one ladder is safe across the line.
(def ^:private gemini-max-budget 24576)

(defn gemini-thinking-model?
  "Whether this Gemini model accepts `thinkingConfig` at all.

   Unlike the provider-tag routing elsewhere, support here genuinely varies per
   MODEL within one endpoint: 2.0 Flash rejects the field, 2.5 requires it to
   think. Conservative allowlist — an unrecognised Gemini gets no reasoning
   parameter rather than a failed request."
  [model]
  (let [id (str (cond
                  (nil? model)    ""
                  (string? model) model
                  :else           (or (.-modelId model) "")))]
    (boolean (or (re-find #"gemini-(2\.5|3)" id)
                 (str/includes? id "thinking")))))

(defn- model-id [model]
  (str (cond
         (nil? model)    ""
         (string? model) model
         :else           (or (.-modelId model) ""))))

(defn adaptive-model?
  "Whether this Claude model takes ADAPTIVE thinking (`effort`), not a token
   budget. Opus/Sonnet 4.6 and every later Claude, Fable and Mythos.

   nyma sent `{type enabled, budgetTokens}` to every Claude. Opus 5, Opus
   4.8/4.7 and Sonnet 5 reject `budget_tokens` with a 400; for Opus 5.5,
   Sonnet 5.5 and Fable the AI SDK quietly swaps in adaptive thinking but
   drops the level, so `/thinking high` and `/thinking low` behaved the same."
  [model]
  (boolean (re-find #"claude-(fable|mythos)-|claude-(opus|sonnet)-(4-[6-9]|[5-9])" (model-id model))))

(defn- always-thinks?
  "Models that reject disabling thinking outright (a 400): `off` can only
   lower effort."
  [model]
  (boolean (re-find #"claude-(fable|mythos)-|claude-(opus|sonnet)-5-5" (model-id model))))

;; Adaptive effort per level. 4.6 has no `xhigh`.
(def ^:private anthropic-efforts
  {"minimal" "low" "low" "low" "medium" "medium" "high" "high" "xhigh" "xhigh"})

(defn- anthropic-options [level model]
  (cond
    (and (= "off" level) (always-thinks? model))
    #js {:anthropic #js {:effort "low"}}

    ;; Opus 5 / Sonnet 5 think when `thinking` is omitted; they accept an
    ;; explicit disable, so `off` must say so.
    (and (= "off" level) (re-find #"claude-(opus|sonnet)-5" (model-id model)))
    #js {:anthropic #js {:thinking #js {:type "disabled"}}}

    (= "off" level) nil

    (adaptive-model? model)
    (let [effort (get anthropic-efforts level)
          effort (if (and (= effort "xhigh") (re-find #"-4-6" (model-id model))) "high" effort)]
      ;; `summarized`: current models default to `omitted` — thinking blocks
      ;; with empty text — and the reasoning view would show nothing.
      #js {:anthropic #js {:thinking #js {:type "adaptive" :display "summarized"}
                           :effort   effort}})

    :else
    (when-let [budget (get anthropic-budgets level)]
      #js {:anthropic #js {:thinking #js {:type "enabled" :budgetTokens budget}}})))

(defn- provider-tag [model]
  (cond
    (nil? model)     ""
    (string? model)  ""
    :else            (str (or (.-provider model) ""))))

(defn provider-kind
  "Which reasoning dialect `model` accepts, or nil.

   Deliberately conservative. `openai.chat` is excluded: that endpoint carries
   everything a gateway relays — DeepSeek, GLM, Qwen — and most of those reject
   `reasoning_effort` outright. Sending a parameter that 400s would be worse
   than not thinking."
  [model]
  (let [t (provider-tag model)]
    (cond
      (or (str/starts-with? t "anthropic")
          (str/starts-with? t "claude-native")) :anthropic
      (str/starts-with? t "google")             :google
      (= t "openai.responses")                  :openai-responses
      :else                                     nil)))

(defn budget-for-level
  "Reasoning token budget for `level`, or 0 for off/unknown.

   Callers that set an explicit output cap need this: Anthropic counts thinking
   against `max_tokens`, so a request with a 24k budget and a 2k cap is
   rejected. The main loop doesn't hit this because it sets no cap."
  [level]
  (or (get anthropic-budgets (str level)) 0))

(defn level->provider-options
  "providerOptions for `level` on `model`, or nil when nothing should be sent.

   Returns a plain JS object ready to hand to streamText."
  [level model]
  (let [level (str (or level "off"))]
    (when (valid-level? level)
      (case (provider-kind model)
        :anthropic
        (anthropic-options level model)

        :google
        (when (not= "off" level)
        ;; Only the thinking-capable Gemini lines, and clamped: `thinkingConfig`
        ;; is rejected outright by models that don't support it (the only
        ;; built-in Gemini, gemini-2.0-flash, is one), and 2.5 Flash caps the
        ;; budget at 24576 — either way the request 400s rather than degrading.
        ;; Same principle as excluding openai.chat: a parameter that fails is
        ;; worse than no reasoning.
        (when (gemini-thinking-model? model)
          (when-let [budget (get anthropic-budgets level)]
            #js {:google #js {:thinkingConfig
                              #js {:thinkingBudget  (min budget gemini-max-budget)
                                   :includeThoughts true}}})))

        :openai-responses
        (when-let [effort (get openai-efforts level)]
          #js {:openai #js {:reasoningEffort effort}})

        nil))))

(defn merge-provider-options
  "Merge `extra` into `base` without discarding either. Neither argument is
   mutated; nil for both sides is handled."
  [base extra]
  (let [out #js {}]
    (doseq [src [base extra]]
      (when src
        (doseq [k (js/Object.keys src)]
          (let [existing (aget out k)
                incoming (aget src k)]
            (if (and existing incoming
                     (object? existing) (object? incoming))
              (aset out k (js/Object.assign #js {} existing incoming))
              (aset out k incoming))))))
    out))
