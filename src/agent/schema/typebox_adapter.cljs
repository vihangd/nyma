(ns agent.schema.typebox-adapter
  (:require ["zod" :as z]))

(defn json-schema->zod
  "Convert a JSON Schema (as produced by TypeBox) to a Zod schema.
   Supports the subset used by pi-mono: string, number, integer, boolean,
   enum, array, object, optional fields with default values."
  [schema]
  (let [type-val (.-type schema)]
    (cond
      ;; Enum — must come before plain type checks to avoid dead code.
      ;; Handles both string enums (z.enum) and numeric enums (z.union of literals).
      (.-enum schema)
      (let [vals (.-enum schema)]
        (if (= type-val "string")
          (cond-> (.enum z vals)
            (.-description schema) (.describe (.-description schema)))
          ;; Numeric or mixed enums: z.union([z.literal(1), z.literal(2), ...])
          (cond-> (.union z (.map vals (fn [v] (.literal z v))))
            (.-description schema) (.describe (.-description schema)))))

      (= type-val "string")
      (cond-> (.string z)
        (.-description schema) (.describe (.-description schema))
        (.-default schema)     (.default (.-default schema)))

      (= type-val "number")
      (cond-> (.number z)
        (.-description schema) (.describe (.-description schema))
        (.-default schema)     (.default (.-default schema)))

      (= type-val "integer")
      (cond-> (.number z)
        true                   (.int)
        (.-description schema) (.describe (.-description schema))
        (.-default schema)     (.default (.-default schema)))

      (= type-val "boolean")
      (cond-> (.boolean z)
        (.-description schema) (.describe (.-description schema))
        (.-default schema)     (.default (.-default schema)))

      ;; Array: {type: "array", items: {...}}
      (= type-val "array")
      (cond-> (.array z (json-schema->zod (.-items schema)))
        (.-description schema) (.describe (.-description schema)))

      ;; Object: {type: "object", properties: {...}, required: [...]}
      (= type-val "object")
      (let [props     (.-properties schema)
            required  (set (or (when (.-required schema) (js/Array.from (.-required schema))) []))
            entries   (when props (js/Object.entries props))
            zod-props (when entries
                        (reduce
                          (fn [acc entry]
                            (let [key   (aget entry 0)
                                  val   (aget entry 1)
                                  field (json-schema->zod val)]
                              (aset acc key
                                (if (contains? required key) field (.optional field)))
                              acc))
                          #js {}
                          entries))]
        (cond-> (.object z (or zod-props #js {}))
          (.-description schema) (.describe (.-description schema))))

      :else
      ;; Fallback: treat as any
      (.any z))))

(defn typebox-tool->zod
  "Convert a pi-mono tool definition with TypeBox parameters to a Zod-based one.
   Returns a new tool def with :parameters as a Zod schema."
  [tool-def]
  (let [params (.-parameters tool-def)]
    (if (and params (.-type params) (= (.-type params) "object"))
      ;; TypeBox schema — convert to Zod
      (let [zod-schema (json-schema->zod params)]
        (js/Object.assign #js {} tool-def #js {:parameters zod-schema}))
      ;; Already Zod or unknown — pass through
      tool-def)))
