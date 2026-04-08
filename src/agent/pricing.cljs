(ns agent.pricing)

;; Model pricing: [input-rate-per-1M-tokens, output-rate-per-1M-tokens] in USD
(def token-costs
  (atom {"claude-sonnet-4-20250514"     [3.0 15.0]
         "claude-opus-4-20250514"       [15.0 75.0]
         "claude-haiku-3-20240307"      [0.25 1.25]
         "gpt-4o"                       [2.5 10.0]
         "gpt-4o-mini"                  [0.15 0.6]
         "gpt-4-turbo"                  [10.0 30.0]
         "gemini-2.0-flash"             [0.1 0.4]
         "gemini-1.5-pro"               [1.25 5.0]}))

(defn calculate-cost
  "Calculate USD cost for a given model and token counts.
   Returns 0 if model pricing is unknown."
  [model-id input-tokens output-tokens]
  (if-let [[input-rate output-rate] (get @token-costs model-id)]
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
