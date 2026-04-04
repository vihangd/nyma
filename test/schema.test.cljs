(ns schema.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.schema :refer [compile-type compile-field compile-schema data-tool]]))

(describe "compile-type" (fn []
  (it "compiles :string"
    (fn []
      (let [s (compile-type :string)
            result (.safeParse s "hello")]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "compiles :number"
    (fn []
      (let [s (compile-type :number)
            result (.safeParse s 42)]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "compiles :boolean"
    (fn []
      (let [s (compile-type :boolean)
            result (.safeParse s true)]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "rejects wrong type"
    (fn []
      (let [s (compile-type :string)
            result (.safeParse s 42)]
        (-> (expect (.-success result)) (.toBe false)))))

  (it "compiles [:tuple :number :number]"
    (fn []
      (let [s (compile-type [:tuple :number :number])
            result (.safeParse s #js [1 2])]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "compiles [:enum \"json\" \"text\"]"
    (fn []
      (let [s (compile-type [:enum "json" "text"])
            good (.safeParse s "json")
            bad  (.safeParse s "xml")]
        (-> (expect (.-success good)) (.toBe true))
        (-> (expect (.-success bad)) (.toBe false)))))

  (it "throws on unknown type"
    (fn []
      (-> (expect (fn [] (compile-type :foobar))) (.toThrow "Unknown schema type"))))))

(describe "compile-field" (fn []
  (it "compiles field with description"
    (fn []
      (let [f (compile-field {:type :string :description "A test field"})
            result (.safeParse f "hello")]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "compiles optional field"
    (fn []
      (let [f (compile-field {:type :number :optional true})
            result (.safeParse f js/undefined)]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "compiles field with default"
    (fn []
      (let [f (compile-field {:type :number :optional true :default 42})
            result (.safeParse f js/undefined)]
        (-> (expect (.-success result)) (.toBe true))
        (-> (expect (.-data result)) (.toBe 42)))))))

(describe "compile-schema" (fn []
  (it "compiles schema map to Zod object"
    (fn []
      (let [s (compile-schema {:path {:type :string :description "File path"}
                               :line {:type :number :optional true}})
            good (.safeParse s #js {:path "/foo"})
            bad  (.safeParse s #js {:line 5})]
        (-> (expect (.-success good)) (.toBe true))
        (-> (expect (.-success bad)) (.toBe false)))))

  (it "handles nested object schemas"
    (fn []
      (let [s (compile-schema {:config {:type [:object {:timeout {:type :number}
                                                        :verbose {:type :boolean}}]}})
            result (.safeParse s #js {:config #js {:timeout 5000 :verbose true}})]
        (-> (expect (.-success result)) (.toBe true)))))

  (it "handles shorthand type values"
    (fn []
      (let [s (compile-schema {:name :string :count :number})
            result (.safeParse s #js {:name "test" :count 5})]
        (-> (expect (.-success result)) (.toBe true)))))))

(describe "compile-type - array" (fn []
  (it "compiles [:array :string]"
    (fn []
      (let [s (compile-type [:array :string])
            good (.safeParse s #js ["a" "b"])
            bad  (.safeParse s "not-array")]
        (-> (expect (.-success good)) (.toBe true))
        (-> (expect (.-success bad)) (.toBe false)))))

  (it "compiles [:array :number]"
    (fn []
      (let [s (compile-type [:array :number])
            result (.safeParse s #js [1 2 3])]
        (-> (expect (.-success result)) (.toBe true)))))))

(describe "compile-field - default/optional ordering" (fn []
  (it "default value resolves to concrete type (not T|undefined)"
    (fn []
      (let [f (compile-field {:type :number :default 42})
            result (.safeParse f js/undefined)]
        (-> (expect (.-success result)) (.toBe true))
        (-> (expect (.-data result)) (.toBe 42)))))

  (it "default with optional still resolves default"
    (fn []
      (let [f (compile-field {:type :string :optional true :default "fallback"})
            result (.safeParse f js/undefined)]
        (-> (expect (.-success result)) (.toBe true))
        (-> (expect (.-data result)) (.toBe "fallback")))))))

(describe "data-tool" (fn []
  (it "creates an AI SDK tool from data definition"
    (fn []
      (let [t (data-tool {:name "greet"
                          :description "Say hello"
                          :schema {:name {:type :string :description "Name to greet"}}
                          :execute (fn [{:keys [name]}] (str "Hello " name))})]
        (-> (expect t) (.toBeDefined))
        (-> (expect (.-description t)) (.toBe "Say hello")))))

  (it "has parameters set from compiled schema"
    (fn []
      (let [t (data-tool {:name "greet"
                          :description "Say hello"
                          :schema {:name {:type :string :description "Name"}}
                          :execute (fn [_] "ok")})]
        (-> (expect (.-parameters t)) (.toBeDefined)))))))
