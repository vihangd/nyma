(ns opencode-zen-models.test
  "Zen's live catalogue carries ids only; known ids keep their metadata."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-opencode-zen.index :as zen]))

(describe "opencode-zen with-known"
          (fn []
            (it "fills a known id's name, window and price, leaves a new id as discovered"
                (fn []
                  (let [[known fresh] (zen/with-known [{:id "glm-5.1" :name "glm-5.1"}
                                                       {:id "glm-5.3" :name "glm-5.3"}])]
                    (-> (expect (:name known)) (.toBe "GLM-5.1"))
                    (-> (expect (:ctx known)) (.toBe 204800))
                    (-> (expect (:name fresh)) (.toBe "glm-5.3"))
                    (-> (expect (:ctx fresh)) (.toBeUndefined)))))))
