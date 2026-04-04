(ns tool-system-v2.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tool-registry :refer [create-registry]]
            [agent.schema.typebox-adapter :refer [json-schema->zod typebox-tool->zod]]))

;; ── Tool override with original preservation ─────────────────

(defn test-override-preserves-original []
  (let [original #js {:execute (fn [_] "original")}
        override #js {:execute (fn [_] "override")}
        reg      (create-registry {"test" original})]
    ;; Override
    ((:register reg) "test" override)
    (-> (expect (.-execute (get ((:all reg)) "test")))
        (.toBe (.-execute override)))
    ;; Unregister restores original
    ((:unregister reg) "test")
    (-> (expect (.-execute (get ((:all reg)) "test")))
        (.toBe (.-execute original)))))

(defn test-unregister-without-override-removes []
  (let [tool #js {:execute (fn [_] "tool")}
        reg  (create-registry {})]
    ((:register reg) "new-tool" tool)
    (-> (expect (some? (get ((:all reg)) "new-tool"))) (.toBe true))
    ((:unregister reg) "new-tool")
    (-> (expect (get ((:all reg)) "new-tool")) (.toBeUndefined))))

;; ── TypeBox → Zod adapter (upgraded to safeParse validation) ──

(defn test-typebox-string []
  (let [schema #js {:type "string" :description "A name"}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod "hello"))) (.toBe true))
    (-> (expect (.-success (.safeParse zod 123))) (.toBe false))))

(defn test-typebox-number []
  (let [schema #js {:type "number"}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod 42))) (.toBe true))
    (-> (expect (.-success (.safeParse zod "x"))) (.toBe false))))

(defn test-typebox-object []
  (let [schema #js {:type "object"
                    :properties #js {:name #js {:type "string" :description "Name"}
                                     :age  #js {:type "integer"}}
                    :required #js ["name"]}
        zod    (json-schema->zod schema)]
    ;; Valid: has required "name" and optional "age"
    (-> (expect (.-success (.safeParse zod #js {:name "Alice" :age 30}))) (.toBe true))
    ;; Invalid: missing required "name"
    (-> (expect (.-success (.safeParse zod #js {:age 30}))) (.toBe false))))

(defn test-typebox-array []
  (let [schema #js {:type "array" :items #js {:type "string"}}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod #js ["a" "b"]))) (.toBe true))
    (-> (expect (.-success (.safeParse zod "not-array"))) (.toBe false))))

(defn test-typebox-boolean []
  (let [schema #js {:type "boolean"}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod true))) (.toBe true))
    (-> (expect (.-success (.safeParse zod "yes"))) (.toBe false))))

;; ── New test cases ───────────────────────────────────────────

(defn test-typebox-string-enum []
  (let [schema #js {:type "string" :enum #js ["json" "text"]}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod "json"))) (.toBe true))
    (-> (expect (.-success (.safeParse zod "text"))) (.toBe true))
    (-> (expect (.-success (.safeParse zod "xml"))) (.toBe false))))

(defn test-typebox-number-enum []
  (let [schema #js {:type "integer" :enum #js [1 2 3]}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod 1))) (.toBe true))
    (-> (expect (.-success (.safeParse zod 3))) (.toBe true))
    (-> (expect (.-success (.safeParse zod 5))) (.toBe false))))

(defn test-typebox-integer []
  (let [schema #js {:type "integer"}
        zod    (json-schema->zod schema)]
    (-> (expect (.-success (.safeParse zod 3))) (.toBe true))
    ;; .int() rejects non-integer numbers
    (-> (expect (.-success (.safeParse zod 3.5))) (.toBe false))))

(defn test-typebox-optional-field []
  (let [schema #js {:type "object"
                    :properties #js {:name #js {:type "string"}
                                     :age  #js {:type "number"}}
                    :required #js ["name"]}
        zod    (json-schema->zod schema)]
    ;; age is not required — omit it
    (-> (expect (.-success (.safeParse zod #js {:name "x"}))) (.toBe true))))

(defn test-typebox-default-value []
  (let [schema #js {:type "string" :default "hello"}
        zod    (json-schema->zod schema)]
    ;; Default means undefined input resolves to "hello"
    (let [result (.safeParse zod js/undefined)]
      (-> (expect (.-success result)) (.toBe true))
      (-> (expect (.-data result)) (.toBe "hello")))))

(defn test-typebox-tool-conversion []
  (let [tool-def #js {:description "Search"
                      :parameters #js {:type "object"
                                       :properties #js {:q #js {:type "string"}}
                                       :required #js ["q"]}}
        converted (typebox-tool->zod tool-def)
        params    (.-parameters converted)]
    ;; Parameters should be a valid Zod schema
    (-> (expect (.-success (.safeParse params #js {:q "test"}))) (.toBe true))
    (-> (expect (.-success (.safeParse params #js {}))) (.toBe false))
    ;; Description preserved
    (-> (expect (.-description converted)) (.toBe "Search"))))

(defn test-typebox-fallback []
  (let [schema #js {:type "foobar"}
        zod    (json-schema->zod schema)]
    ;; Fallback to z.any() — accepts anything
    (-> (expect (.-success (.safeParse zod "anything"))) (.toBe true))
    (-> (expect (.-success (.safeParse zod 42))) (.toBe true))
    (-> (expect (.-success (.safeParse zod nil))) (.toBe true))))

;; ── describe blocks ──────────────────────────────────────────

(describe "tool override" (fn []
  (it "preserves original and restores on unregister" test-override-preserves-original)
  (it "removes tool when no original exists" test-unregister-without-override-removes)))

(describe "typebox-to-zod adapter" (fn []
  (it "converts string schema and validates correctly" test-typebox-string)
  (it "converts number schema and validates correctly" test-typebox-number)
  (it "converts object schema with required fields" test-typebox-object)
  (it "converts array schema and validates correctly" test-typebox-array)
  (it "converts boolean schema and validates correctly" test-typebox-boolean)
  (it "converts string enum and rejects non-members" test-typebox-string-enum)
  (it "converts number enum and rejects non-members" test-typebox-number-enum)
  (it "converts integer type with .int() validation" test-typebox-integer)
  (it "makes non-required object fields optional" test-typebox-optional-field)
  (it "applies default values on undefined input" test-typebox-default-value)
  (it "typebox-tool->zod converts full tool definition" test-typebox-tool-conversion)
  (it "falls back to z.any() for unknown types" test-typebox-fallback)))
