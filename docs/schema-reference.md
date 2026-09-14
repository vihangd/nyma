# Schema Reference

Nyma provides a TypeBox/JSON-Schema adapter for porting pi-mono extensions.
Extensions declare tool parameters as JSON Schema literals; the adapter
compiles them to Zod at runtime.

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
