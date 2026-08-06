(ns agent.pricing)

;; Model pricing: [input-rate-per-1M-tokens, output-rate-per-1M-tokens] in USD
(def token-costs
  (atom {"claude-opus-5"                 [5.0 25.0]
         "claude-opus-4-8"              [15.0 75.0]
         "claude-sonnet-5"              [3.0 15.0]
         "claude-haiku-4-5-20251001"    [1.0 5.0]
         "claude-sonnet-4-20250514"     [3.0 15.0]
         "claude-opus-4-20250514"       [15.0 75.0]
         "claude-haiku-3-20240307"      [0.25 1.25]
         "gpt-4o"                       [2.5 10.0]
         "gpt-4o-mini"                  [0.15 0.6]
         "gpt-4-turbo"                  [10.0 30.0]
         "gemini-2.0-flash"             [0.1 0.4]
         "gemini-1.5-pro"               [1.25 5.0]}))

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

   `config.model` holds the RESOLVED AI SDK model object once the provider
   registry resolved it, not a spec string — so `(str …)` on it yields
   \"[object Object]\" and every lookup missed, silently reporting $0 for every
   registry-resolved model. Falls back to the bare id, then to the raw value
   for the unknown-provider path where setModel leaves the spec string in place."
  [config]
  (let [model    (when config (aget config "model"))
        provider (when config (aget config "active-provider-name"))
        id       (when (and model (not (string? model))) (.-modelId model))]
    (cond
      (and (seq (str provider)) (seq (str id))) (str provider "/" id)
      (seq (str id))                            (str id)
      (string? model)                           model
      :else                                     "")))

(defn calculate-cost
  "Calculate USD cost for a given model and token counts.
   Returns 0 if model pricing is unknown."
  [model-id input-tokens output-tokens]
  (if-let [[input-rate output-rate] (lookup-cost model-id)]
    (+ (* (/ input-tokens 1000000) input-rate)
       (* (/ output-tokens 1000000) output-rate))
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
