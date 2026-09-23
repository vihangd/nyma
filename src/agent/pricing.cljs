(ns agent.pricing
  "Token cost table + `calculate-turn-cost` for all supported models."
  (:require [agent.model-info :as model-info]))

;; Model pricing in USD per 1M tokens:
;;   [input output]                              — no cache rates known
;;   [input output cache-read cache-write]       — cached tokens priced separately
;;
;; Both shapes are valid; the 2-element form keeps every existing entry and
;; caller working. Cache rates are only ever applied when a model declares them,
;; because the ratios are not universal — Anthropic reads at 0.1x and writes at
;; 1.25x, OpenAI reads at roughly 0.1x with no separate write charge, Gemini
;; differs again. Guessing a ratio would replace a known-wrong number with an
;; unknown-wrong one.
(def token-costs
  (atom {"claude-opus-5"                 [5.0 25.0 0.5 6.25]
         "claude-opus-4-8"              [15.0 75.0 1.5 18.75]
         "claude-sonnet-5"              [3.0 15.0 0.3 3.75]
         "claude-haiku-4-5-20251001"    [1.0 5.0 0.1 1.25]
         "claude-sonnet-4-20250514"     [3.0 15.0 0.3 3.75]
         "claude-opus-4-20250514"       [15.0 75.0 1.5 18.75]
         "claude-haiku-3-20240307"      [0.25 1.25 0.03 0.30]
         "gpt-4o"                       [2.5 10.0 1.25 nil]
         "gpt-4o-mini"                  [0.15 0.6 0.075 nil]
         "gpt-4-turbo"                  [10.0 30.0]
         "gemini-2.0-flash"             [0.1 0.4 0.025 nil]
         "gemini-1.5-pro"               [1.25 5.0 0.3125 nil]}))

(def unpriced-providers
  "Provider names whose per-token rates we don't know.

   Relays and gateways carry other vendors' models under those vendors' own
   ids, so a bare-id fallback would report the first-party rate for a relayed
   model — a number the user is not being charged. Registering here means
   `lookup-cost` reports nothing rather than reporting the wrong thing."
  (atom #{}))

;; Provider/model splitting mirrors registry/split-model-spec: FIRST slash only,
;; since model ids carry slashes of their own (meta-llama/llama-3.3-70b).
;; Duplicated rather than required to keep this namespace free of the AI SDK.
(defn- spec-provider [k]
  (let [s (str k) i (.indexOf s "/")]
    (when-not (neg? i) (.slice s 0 i))))

(defn- spec-bare-id [k]
  (let [s (str k) i (.indexOf s "/")]
    (if (neg? i) s (.slice s (inc i)))))

(defn lookup-cost
  "[input-rate output-rate] for a model key, or nil.

   Tries the qualified `provider/id` key first, then the bare id — except for
   providers marked unpriced, where the bare fallback would surface some other
   vendor's price."
  [model-key]
  (let [costs @token-costs]
    (or (get costs (str model-key))
        (when-not (contains? @unpriced-providers (spec-provider model-key))
          (get costs (spec-bare-id model-key))))))

(defn model-cost-key
  "Pricing key for the model currently on `config`, as `provider/id`.

   Delegates to `model-info/config-model-key`: pricing and context windows need
   the same key, and having two hand-rolled versions is how they drifted apart.
   See that fn for why a bare id (or the raw model object) is not usable here."
  [config]
  (or (model-info/config-model-key config) ""))

(defn calculate-turn-cost
  "USD cost for one turn, pricing cached input separately when the model
   declares cache rates. Returns 0 when the model has no pricing at all.

   `usage` keys: :input-tokens :output-tokens :cache-read-tokens
   :cache-write-tokens.

   `:input-tokens` INCLUDES the cached tokens — measured on a live turn,
   cacheRead 5478 + cacheWrite 1286 + noCache 1 == inputTokens 6765 — so the
   fresh portion is the remainder, and charging the whole of it at the input
   rate overstated a cache-heavy Opus turn by 3.1x.

   A model with no declared cache rates is priced exactly as before: everything
   at the input rate. That keeps an unknown model's number unchanged rather than
   swapping one wrong answer for another.

   A usage map carrying only :input-tokens and :output-tokens prices the
   whole input at the input rate."
  [model-id usage]
  (if-let [rates (lookup-cost model-id)]
    (let [[in-rate out-rate cr-rate cw-rate] rates
          input  (or (:input-tokens usage) 0)
          output (or (:output-tokens usage) 0)
          cr     (or (:cache-read-tokens usage) 0)
          cw     (or (:cache-write-tokens usage) 0)
          per-1m (fn [tokens rate] (/ (* tokens rate) 1000000))]
      (if-not (or (number? cr-rate) (number? cw-rate))
        ;; No cache rates for this model — previous behaviour, unchanged.
        (+ (per-1m input in-rate) (per-1m output out-rate))
        ;; A provider may price reads but not writes (OpenAI); an undeclared
        ;; side falls back to the plain input rate rather than to free.
        (let [fresh (max 0 (- input cr cw))]
          (+ (per-1m fresh in-rate)
             (per-1m cr (or cr-rate in-rate))
             (per-1m cw (or cw-rate in-rate))
             (per-1m output out-rate)))))
    0))

(defn format-cost
  "Format USD cost as a human-readable string."
  [usd]
  (cond
    (zero? usd)   "$0.00"
    (< usd 0.01)  (str "$" (.toFixed usd 4))
    (< usd 1)     (str "$" (.toFixed usd 3))
    :else          (str "$" (.toFixed usd 2))))

(defn format-tokens
  "Format token count as human-readable (e.g., '1.2k', '45.6k', '1.2M')."
  [n]
  (cond
    (< n 1000)     (str n)
    (< n 1000000)  (let [v (/ n 1000)]   (str (.toFixed v 1) "k"))
    :else           (let [v (/ n 1000000)] (str (.toFixed v 1) "M"))))
