(ns pricing.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.pricing :refer [calculate-turn-cost format-cost
                                   format-tokens token-costs]]))

(describe "calculate-turn-cost — uncached" (fn []
  (it "calculates cost for known model"
    (fn []
      ;; claude-sonnet: $3/M input, $15/M output
      ;; 1000 input tokens = $0.003, 500 output = $0.0075 → $0.0105
      (let [cost (calculate-turn-cost "claude-sonnet-4-20250514" {:input-tokens 1000 :output-tokens 500})]
        (-> (expect cost) (.toBeCloseTo 0.0105 6)))))

  (it "returns 0 for unknown model"
    (fn []
      (-> (expect (calculate-turn-cost "unknown-model-xyz" {:input-tokens 1000 :output-tokens 500})) (.toBe 0))))

  (it "returns 0 for zero tokens"
    (fn []
      (-> (expect (calculate-turn-cost "claude-sonnet-4-20250514" {:input-tokens 0 :output-tokens 0})) (.toBe 0))))

  (it "scales linearly with token count"
    (fn []
      (let [cost1 (calculate-turn-cost "gpt-4o" {:input-tokens 1000 :output-tokens 1000})
            cost2 (calculate-turn-cost "gpt-4o" {:input-tokens 2000 :output-tokens 2000})]
        (-> (expect (js/Math.abs (- (* 2 cost1) cost2))) (.toBeLessThan 0.0001)))))))

(describe "format-cost" (fn []
  (it "formats zero"
    (fn []
      (-> (expect (format-cost 0)) (.toBe "$0.00"))))

  (it "formats small amounts with 4 decimals"
    (fn []
      (-> (expect (format-cost 0.0023)) (.toBe "$0.0023"))))

  (it "formats medium amounts with 3 decimals"
    (fn []
      (-> (expect (format-cost 0.123)) (.toBe "$0.123"))))

  (it "formats large amounts with 2 decimals"
    (fn []
      (-> (expect (format-cost 1.456)) (.toBe "$1.46"))))))

(describe "format-tokens" (fn []
  (it "formats small numbers as-is"
    (fn []
      (-> (expect (format-tokens 42)) (.toBe "42"))))

  (it "formats thousands with k suffix"
    (fn []
      (-> (expect (format-tokens 1234)) (.toBe "1.2k"))))

  (it "formats millions with M suffix"
    (fn []
      (-> (expect (format-tokens 1500000)) (.toBe "1.5M"))))))

(describe "token-costs" (fn []
  (it "has pricing for claude sonnet"
    (fn []
      (-> (expect (get @token-costs "claude-sonnet-4-20250514")) (.toBeDefined))))

  (it "has pricing for gpt-4o"
    (fn []
      (-> (expect (get @token-costs "gpt-4o")) (.toBeDefined))))))

;;; ─── Cached input is not full-price input ───────────────────────
;;; `usage.inputTokens` INCLUDES cached tokens. Measured on a live turn:
;;;   cacheRead 5478 + cacheWrite 1286 + noCache 1 == inputTokens 6765
;;; so charging the whole of it at the input rate overstated a cache-heavy
;;; Opus turn ~2.7x. That matters on a relay that reads ~5.5k cached tokens
;;; every turn.

(defn- usage [in out cr cw]
  {:input-tokens in :output-tokens out
   :cache-read-tokens cr :cache-write-tokens cw})

(describe "calculate-turn-cost — cached tokens"
  (fn []
    (it "prices the measured turn well below the undifferentiated rate"
        (fn []
          (let [priced (calculate-turn-cost "claude-opus-5" (usage 6765 112 5478 1286))
                flat   (calculate-turn-cost "claude-opus-5" {:input-tokens 6765 :output-tokens 112})]
            (-> (expect priced) (.toBeLessThan flat))
            ;; noCache 1 + read 5478@0.5 + write 1286@6.25 + out 112@25
            (-> (expect (js/Math.round (* priced 10000))) (.toBe 136)))))

    (it "does not double count — the parts must sum to inputTokens"
        (fn []
          ;; If the fresh portion were not the remainder, the same tokens would
          ;; be billed twice: once at the input rate and again as cache.
          (let [all-cached (calculate-turn-cost "claude-opus-5" (usage 1000 0 1000 0))
                explicit   (/ (* 1000 0.5) 1000000)]
            (-> (expect all-cached) (.toBeCloseTo explicit 10)))))

    (it "matches the old number when nothing was cached"
        (fn []
          (-> (expect (calculate-turn-cost "claude-opus-5" (usage 6765 112 0 0)))
              (.toBe (calculate-turn-cost "claude-opus-5" {:input-tokens 6765 :output-tokens 112})))))

    (it "leaves a model with no declared cache rates exactly as it was"
        (fn []
          ;; gpt-4-turbo has a 2-element entry. Charging its cached tokens at a
          ;; guessed ratio would swap a known-wrong number for an unknown-wrong
          ;; one, so it must stay unchanged.
          (-> (expect (calculate-turn-cost "gpt-4-turbo" (usage 1000 100 500 200)))
              (.toBe (calculate-turn-cost "gpt-4-turbo" {:input-tokens 1000 :output-tokens 100})))))

    (it "falls back to the input rate for a side the provider doesn't price"
        (fn []
          ;; gpt-4o declares a read rate but no write rate.
          (let [c (calculate-turn-cost "gpt-4o" (usage 1000 0 0 1000))]
            (-> (expect c) (.toBe (/ (* 1000 2.5) 1000000))))))

    (it "never goes negative when cache counts exceed the input total"
        (fn []
          ;; Defensive: a provider reporting inconsistent numbers must not
          ;; produce a negative fresh-token count.
          (-> (expect (calculate-turn-cost "claude-opus-5" (usage 10 0 5000 5000)))
              (.toBeGreaterThan 0))))

    (it "still returns 0 for a model with no pricing at all"
        (fn []
          (-> (expect (calculate-turn-cost "nope/nothing" (usage 1000 100 0 0))) (.toBe 0))))))
