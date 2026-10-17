(ns schema.test
  "One data form → zod, JSON Schema, doc rows. The AI SDK's asSchema is the
   conformance check for the zod side; the JSON side is checked by shape."
  (:require ["bun:test" :refer [describe it expect]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [agent.schema :as schema]))

(def ^:private fields
  {:path  [:string "File path"]
   :range [:array :number {:length 2 :optional true :doc "Line range"}]
   :mode  [:enum ["text" "markdown"] {:optional true :doc "Output format"}]
   :deep  [:boolean {:optional true}]
   :n     [:integer "How many"]})

(describe "schema" (fn []
                     (it "->zod parses valid input and rejects the wrong shape"
                         (fn []
                           (let [zs (schema/->zod fields)]
                             (-> (expect (.-success (.safeParse zs #js {:path "a" :n 2}))) (.toBe true))
                             (-> (expect (.-success (.safeParse zs #js {:path "a" :n 2 :range #js [1 2] :mode "text"}))) (.toBe true))
                             (-> (expect (.-success (.safeParse zs #js {:path "a" :n 2 :range #js [1]}))) (.toBe false))
                             (-> (expect (.-success (.safeParse zs #js {:path "a" :n 2 :mode "html"}))) (.toBe false))
                             (-> (expect (.-success (.safeParse zs #js {:n 2}))) (.toBe false))
          ;; what the AI SDK will do with it
                             (-> (expect (some? (asSchema zs))) (.toBe true)))))

                     (it "->json-schema carries types, enums, array bounds and required"
                         (fn []
                           (let [j (schema/->json-schema fields)]
                             (-> (expect (:type j)) (.toBe "object"))
                             (-> (expect (get-in j [:properties "path" :type])) (.toBe "string"))
                             (-> (expect (get-in j [:properties "path" :description])) (.toBe "File path"))
                             (-> (expect (get-in j [:properties "mode" :enum])) (.toEqual (clj->js ["text" "markdown"])))
                             (-> (expect (get-in j [:properties "range" :minItems])) (.toBe 2))
                             (-> (expect (get-in j [:properties "range" :items :type])) (.toBe "number"))
          ;; keywords are strings in squint: the item type must not be read as the doc
          (-> (expect (get-in j [:properties "range" :description])) (.toBe "Line range"))
          (-> (expect (:doc (schema/parse-field [:array :number "Two numbers"]))) (.toBe "Two numbers"))
                             (-> (expect (sort (:required j))) (.toEqual (clj->js ["n" "path"]))))))

                     (it "->doc-rows renders one bullet per field"
                         (fn []
                           (let [rows (schema/->doc-rows {:path [:string "File path"] :mode [:enum ["a" "b"] {:optional true}]})]
                             (-> (expect rows) (.toContain "- `path` (string): File path"))
                             (-> (expect rows) (.toContain "- `mode` (enum: a | b, optional)")))))))

;;; ─── JSON Schema → signature ───────────────────────────────────

(def ^:private condition
  ;; eM Client's search condition, cut down: a oneOf whose branches each carry
  ;; the same long description — the shape that made one search answer 36 KB.
  (let [long-doc (.repeat "A complete nested email search condition. " 15)]
    (clj->js {:type "object"
              :required ["location"]
              :properties {:location {:type "object" :description "Where to search"
                                      :properties {:account_ids {:type ["array" "null"] :items {:type "string"}}}}
                           :per_page {:type "integer" :description "Page size"}
                           :query {:description long-doc
                                   :oneOf [{:type "object" :description long-doc
                                            :properties {:and {:type "array" :items {:type "object"}}}}
                                           {:type "object" :description long-doc
                                            :properties {:properties {:type "object"
                                                                      :properties {:from_contains {:type ["array" "null"] :items {:type "string"}}
                                                                                   :importance {:enum ["LOW" "HIGH"]}}}}}]}}})))

(describe "schema/signature" (fn []
  (it "keeps real names, marks optional and nullable, renders enums and arrays"
      (fn []
        (-> (expect (schema/signature condition))
            (.toBe (str "{location: {account_ids?: [str]}, per_page?: int, "
                        "query?: {and?: [{}]} | {properties?: {from_contains?: [str], importance?: \"LOW\"|\"HIGH\"}}}")))))

  (it "is a small fraction of the schema it renders"
      (fn []
        (-> (expect (< (* 10 (count (schema/signature condition)))
                       (count (js/JSON.stringify condition))))
            (.toBe true))))

  (it "stops at the depth limit instead of unrolling recursion"
      (fn []
        (let [deep (reduce (fn [inner _] #js {:type "object" :properties #js {:x inner}})
                           #js {:type "string"} (range 10))]
          (-> (expect (.includes (schema/signature deep) "{…}")) (.toBe true)))))

  (it "notes only top-level descriptions, cut to width"
      (fn []
        (let [notes (schema/param-notes condition 20)]
          (-> (expect (.includes notes "location — Where to search")) (.toBe true))
          (-> (expect (.includes notes "query — A complete nested e…")) (.toBe true))
          (-> (expect (count (.split notes "\n"))) (.toBe 3)))))))
