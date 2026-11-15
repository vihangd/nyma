(ns model-info-max-output.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.model-info :as mi]))

(describe "max-output for gateway ids" (fn []
  (it "a vendor-prefixed gateway id gets the vendor's limit, not the 8000 fallback"
    (fn []
      (-> (expect (mi/max-output "openlux/deepseek-v4-pro")) (.toBe (mi/max-output "deepseek/deepseek-v4-pro")))
      (-> (expect (mi/max-output "velona/z-ai/glm-5.3")) (.toBe 131072))
      (-> (expect (mi/max-output "zai/glm-5.3")) (.toBe 131072))))
  (it "an id nobody lists is still unknown"
    (fn []
      (-> (expect (mi/max-output "acme/unknown-model-x")) (.toBeUndefined))))))

(describe "context window for gateway ids" (fn []
  (it "a vendor-prefixed gateway id gets the vendor's window, not the 100000 default"
    (fn []
      (let [cw (:context-window (mi/create-model-registry))]
        (-> (expect (cw "openlux/deepseek-v4-pro")) (.toBe (cw "deepseek/deepseek-v4-pro")))
        (-> (expect (cw "openlux/deepseek-v4-pro")) (.not.toBe 100000))
        (-> (expect (cw "velona/z-ai/glm-5.3")) (.toBe (cw "zai/glm-5.3")))
        (-> (expect (cw "acme/unknown-model-x")) (.toBe 100000)))))))
