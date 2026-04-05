(ns token-estimation.test
  (:require [agent.token-estimation :refer [estimate-tokens estimate-messages-tokens]]))

(describe "estimate-tokens" (fn []

  (it "estimates prose tokens within 25% of 3.8 chars/token"
    (fn []
      (let [text "The quick brown fox jumps over the lazy dog and runs away"
            est (estimate-tokens text)
            expected (js/Math.ceil (/ (count text) 3.8))]
        ;; Within 25%
        (-> (expect (js/Math.abs (- est expected))) (.toBeLessThan (* expected 0.25))))))

  (it "estimates code tokens within 25% of 3.2 chars/token"
    (fn []
      (let [text "(defn foo [{:keys [bar baz]}]\n  (+ bar (* baz 2)))"
            est (estimate-tokens text)
            expected (js/Math.ceil (/ (count text) 3.2))]
        (-> (expect (js/Math.abs (- est expected))) (.toBeLessThan (* expected 0.25))))))

  (it "returns 0 for empty string"
    (fn []
      (-> (expect (estimate-tokens "")) (.toBe 0))))

  (it "returns 0 for nil input"
    (fn []
      (-> (expect (estimate-tokens nil)) (.toBe 0))))

  (it "returns integer (not float)"
    (fn []
      (let [est (estimate-tokens "hello world")]
        (-> (expect (js/Number.isInteger est)) (.toBe true)))))))

(describe "estimate-messages-tokens" (fn []

  (it "adds per-message overhead"
    (fn []
      (let [msgs [{:role "user" :content "hi"}
                   {:role "assistant" :content "hello"}]
            est (estimate-messages-tokens msgs)
            ;; Should be more than just content estimates
            content-only (+ (estimate-tokens "hi") (estimate-tokens "hello"))]
        (-> (expect est) (.toBeGreaterThan content-only)))))

  (it "handles mix of CLJ maps and JS objects"
    (fn []
      (let [msgs [#js {:content "from js object"}
                   {:role "user" :content "from clj map"}]
            est (estimate-messages-tokens msgs)]
        (-> (expect est) (.toBeGreaterThan 0)))))))
