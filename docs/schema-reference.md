# Schema Reference

Nyma provides a data-driven schema system that compiles Clojure maps to Zod schemas
at runtime, plus a TypeBox adapter for porting pi-mono extensions.

## Data-Driven Schemas (ClojureScript)

### Type Specifiers

| Specifier | Zod Output | Example |
|-----------|------------|---------|
| `:string` | `z.string()` | `{:name :string}` |
| `:number` | `z.number()` | `{:count :number}` |
| `:boolean` | `z.boolean()` | `{:active :boolean}` |
| `[:tuple :string :number]` | `z.tuple([z.string(), z.number()])` | Typed tuples |
| `[:enum "a" "b" "c"]` | `z.enum(["a","b","c"])` | String enums |
| `[:array :string]` | `z.array(z.string())` | Arrays with item type |
| `[:object {...}]` | `z.object({...})` | Nested objects |

### compile-type

Converts a type specifier to a Zod schema:

```clojure
(require '[agent.schema :refer [compile-type]])

(compile-type :string)              ; => z.string()
(compile-type :number)              ; => z.number()
(compile-type [:enum "json" "text"]) ; => z.enum(["json","text"])
(compile-type [:array :number])     ; => z.array(z.number())
```

### compile-field

Converts a field spec map to a Zod schema with metadata:

```clojure
(require '[agent.schema :refer [compile-field]])

(compile-field {:type :string :description "User name"})
; => z.string().describe("User name")

(compile-field {:type :number :optional true})
; => z.number().optional()

(compile-field {:type :string :default "json"})
; => z.string().default("json")
; Note: default implies optional input but concrete return type
```

### compile-schema

Converts a full schema map to a Zod object:

```clojure
(require '[agent.schema :refer [compile-schema]])

(compile-schema
  {:query   {:type :string :description "Search query"}
   :limit   {:type :number :description "Max results" :optional true :default 10}
   :filters {:type [:array :string] :description "Tags"}})
; => z.object({query: z.string().describe("..."), limit: z.number()...})

;; Shorthand — just type keywords:
(compile-schema {:name :string :age :number})
```

### data-tool

Creates an AI SDK tool from a data-driven definition:

```clojure
(require '[agent.schema :refer [data-tool]])

(data-tool
  {:description "Search the database"
   :schema {:query {:type :string :description "Search query"}
            :limit {:type :number :optional true :default 10}}
   :execute (fn [args] (str "Results for: " (:query args)))})
```

### deftool Macro

Compile-time macro that generates tools with Zod schemas:

```clojure
(require-macros '[macros.tool-dsl :refer [deftool]])

(deftool web-search "Search the web"
  {:query [:string "Search query"]
   :limit [:number "Max results" {:optional true}]}
  [{:keys [query limit]}]
  (str "Found " (or limit 10) " results for: " query))
```

## TypeBox Adapter (Pi-Mono Compat)

For porting pi-mono extensions that use TypeBox schemas.

### json-schema->zod

Converts a JSON Schema (as produced by TypeBox) to a Zod schema:

```clojure
(require '[agent.schema.typebox-adapter :refer [json-schema->zod]])

(json-schema->zod #js {:type "string" :description "A name"})
; => z.string().describe("A name")

(json-schema->zod #js {:type "integer"})
; => z.number().int()

(json-schema->zod #js {:type "string" :enum #js ["json" "text"]})
; => z.enum(["json", "text"])

(json-schema->zod #js {:type "object"
                        :properties #js {:name #js {:type "string"}
                                          :age  #js {:type "number"}}
                        :required #js ["name"]})
; => z.object({name: z.string(), age: z.number().optional()})
```

### typebox-tool->zod

Converts a pi-mono tool definition with TypeBox parameters to Zod-based:

```clojure
(require '[agent.schema.typebox-adapter :refer [typebox-tool->zod]])

(let [pi-tool #js {:description "Search"
                    :parameters #js {:type "object"
                                      :properties #js {:q #js {:type "string"}}
                                      :required #js ["q"]}}]
  (typebox-tool->zod pi-tool))
; => tool def with parameters as z.object({q: z.string()})
```

### Supported JSON Schema Types

| JSON Schema | Zod Output |
|-------------|------------|
| `{type: "string"}` | `z.string()` |
| `{type: "number"}` | `z.number()` |
| `{type: "integer"}` | `z.number().int()` |
| `{type: "boolean"}` | `z.boolean()` |
| `{type: "string", enum: [...]}` | `z.enum([...])` |
| `{type: "integer", enum: [...]}` | `z.union([z.literal(...), ...])` |
| `{type: "array", items: {...}}` | `z.array(...)` |
| `{type: "object", properties: {...}}` | `z.object({...})` |
| Unknown type | `z.any()` |

Fields with `default` values get `.default(value)`. Fields not in the `required`
array get `.optional()`.
