(ns pricing.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.pricing :refer [calculate-cost format-cost format-tokens token-costs]]))

(describe "calculate-cost" (fn []
  (it "calculates cost for known model"
    (fn []
      ;; claude-sonnet: $3/M input, $15/M output
      ;; 1000 input tokens = $0.003, 500 output = $0.0075 → $0.0105
      (let [cost (calculate-cost "claude-sonnet-4-20250514" 1000 500)]
        (-> (expect cost) (.toBeCloseTo 0.0105 6)))))

  (it "returns 0 for unknown model"
    (fn []
      (-> (expect (calculate-cost "unknown-model-xyz" 1000 500)) (.toBe 0))))

  (it "returns 0 for zero tokens"
    (fn []
      (-> (expect (calculate-cost "claude-sonnet-4-20250514" 0 0)) (.toBe 0))))

  (it "scales linearly with token count"
    (fn []
      (let [cost1 (calculate-cost "gpt-4o" 1000 1000)
            cost2 (calculate-cost "gpt-4o" 2000 2000)]
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
      (-> (expect (get token-costs "claude-sonnet-4-20250514")) (.toBeDefined))))

  (it "has pricing for gpt-4o"
    (fn []
      (-> (expect (get token-costs "gpt-4o")) (.toBeDefined))))))
