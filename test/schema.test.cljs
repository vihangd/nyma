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
