(ns loop-tools-preserve-schema-symbol.test
  "Regression: verify the loop's tool wrappers do NOT strip Symbol-keyed
   properties from tool defs.

   Background — `wrap-tools-with-before-hook` and
   `wrap-tools-with-middleware` originally called `clj->js` on the tools
   map. Squint's `clj->js` recursively rebuilds JS objects with only
   string-keyed enumerable own properties, dropping Symbol keys. That
   stripped the AI-SDK `Symbol.for(\"vercel.ai.schema\")` marker from
   jsonSchema-wrapped inputSchemas, making `asSchema` fail
   `isSchema(...)` and call `schema()` on a plain object, yielding
   `schema is not a function. (In 'schema()', 'schema' is an instance
   of Object)` on every LLM turn that had any tool registered.

   Test: build a minimal tools map with a jsonSchema-wrapped
   inputSchema, run it through both wrappers, assert the schemaSymbol
   survives AND the wrap is still recognized as a Schema by asSchema."
  (:require ["bun:test" :refer [describe it expect]]
            ["ai" :refer [jsonSchema]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [agent.tool-registry :refer [create-registry]]
            [agent.loop :refer [wrap-tools-with-before-hook]]
            [agent.middleware :refer [wrap-tools-with-middleware]]))

(defn- has-schema-symbol? [s]
  (boolean
   (some #(= (.toString %) "Symbol(vercel.ai.schema)")
         (js/Object.getOwnPropertySymbols s))))

(defn- make-tools []
  {"foo" #js {:description "f"
              :inputSchema (jsonSchema #js {:type "object" :properties #js {}})
              :execute (fn [_] "ok")}})

(defn- fake-events []
  {:emit (fn [_e _d] nil)})

(defn- fake-pipeline []
  {:execute (fn [_n _t _a]
              (js/Promise.resolve {:result "ok"}))})

(describe "loop-tools-preserve-schema-symbol"
          (fn []
            (it "wrap-tools-with-before-hook preserves schemaSymbol on inputSchema"
                (fn []
                  (let [tools   (make-tools)
                        wrapped (wrap-tools-with-before-hook tools (fake-events))
                        t       (get wrapped "foo")
                        sch     (.-inputSchema t)]
                    (-> (expect (has-schema-symbol? sch)) (.toBe true))
                    ;; Stronger: asSchema must accept it (full real check).
                    (-> (expect (try (asSchema sch) true
                                     (catch :default _e false)))
                        (.toBe true)))))

            (it "wrap-tools-with-middleware preserves schemaSymbol on inputSchema"
                (fn []
                  (let [tools   (make-tools)
                        wrapped (wrap-tools-with-middleware tools (fake-pipeline) (fake-events))
                        t       (get wrapped "foo")
                        sch     (.-inputSchema t)]
                    (-> (expect (has-schema-symbol? sch)) (.toBe true))
                    (-> (expect (try (asSchema sch) true
                                     (catch :default _e false)))
                        (.toBe true)))))

            (it "tool-registry register preserves schemaSymbol on already-wrapped inputSchema"
                (fn []
                  ;; Guard against normalize-tool! re-wrapping or otherwise
                  ;; mangling a schema that's already a Schema. The fix path
                  ;; relies on raw-json-schema? returning false for wrapped
                  ;; objects so the symbol stays intact.
                  (let [reg (create-registry {})
                        td  #js {:description "f"
                                 :inputSchema (jsonSchema #js {:type "object"
                                                               :properties #js {}})
                                 :execute (fn [_] "ok")}]
                    ((:register reg) "wrapped-foo" td)
                    (let [t (get ((:all reg)) "wrapped-foo")
                          s (.-inputSchema t)]
                      (-> (expect (has-schema-symbol? s)) (.toBe true))
                      (-> (expect (try (asSchema s) true
                                       (catch :default _e false)))
                          (.toBe true))))))

            (it "tool-registry register wraps :parameters and result has schemaSymbol"
                (fn []
                  ;; Old AI-SDK key. Registry must migrate to inputSchema
                  ;; AND wrap with jsonSchema so the result is a real Schema.
                  (let [reg (create-registry {})
                        td  #js {:description "f"
                                 :parameters #js {:type "object"
                                                  :properties #js {}}
                                 :execute (fn [_] "ok")}]
                    ((:register reg) "raw-foo" td)
                    (let [t (get ((:all reg)) "raw-foo")
                          s (.-inputSchema t)]
                      (-> (expect (some? s)) (.toBe true))
                      (-> (expect (has-schema-symbol? s)) (.toBe true))
                      (-> (expect (try (asSchema s) true
                                       (catch :default _e false)))
                          (.toBe true))))))))
