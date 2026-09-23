(ns scope-table.test
  "The scoped API's plain passthrough methods are a table, so the seam where
   'scoped API forgot to forward a method' (roadmap §3b) used to hide is now
   checkable: every table entry must exist on the base API, need a real
   capability, and be gated by exactly that capability."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api passthrough-gates ungated-passthroughs]]
            [agent.permissions :refer [all-capabilities]]))

(defn- base [] (create-extension-api (create-agent {:model "test" :system-prompt "x"})))

(describe "scoped api: passthrough table" (fn []
                                            (it "every passthrough exists on the base api"
                                                (fn []
                                                  (let [b (base)]
                                                    (doseq [[m _] passthrough-gates]
                                                      (-> (expect (fn? (aget b (name m)))) (.toBe true)))
                                                    (doseq [m ungated-passthroughs]
                                                      (-> (expect (some? (aget b (name m)))) (.toBe true))))))

                                            (it "every gate names a known capability"
                                                (fn []
                                                  (let [known (set (map str all-capabilities))]
                                                    (doseq [[_ cap] passthrough-gates]
                                                      (-> (expect (contains? known (str cap))) (.toBe true))))))

                                            (it "a scope without the capability throws on call; with it, the base fn runs"
                                                (fn []
                                                  (let [b      (base)
                                                        none   (create-scoped-api b "none" #{})
                                                        tools  (create-scoped-api b "tools" #{:tools})]
                                                    (-> (expect (fn [] (.getAllTools none))) (.toThrow "capability"))
                                                    (-> (expect (js/Array.isArray (.getAllTools tools))) (.toBe true))
          ;; ungated ones work for anyone
                                                    (-> (expect (some? (.-settings none))) (.toBe true)))))))
