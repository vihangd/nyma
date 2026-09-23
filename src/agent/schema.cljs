(ns agent.schema
  "One data form for a tool's parameters, rendered three ways.

   Tools were declared in two incompatible dialects — zod via interop in the
   core tools and nine extension files, raw JSON Schema maps in eleven others
   — and every description, default and required flag was typed again for
   the README. A schema here is data:

     {:path  [:string \"File path to read\"]
      :range [:array :number {:length 2 :optional true
                              :doc \"Line range [start, end], 1-based inclusive\"}]
      :mode  [:enum [\"text\" \"markdown\"] {:optional true :doc \"Output format\"}]}

   A field is `[type doc-or-opts? opts?]`; types are :string :number :integer
   :boolean :enum :array :object. Opts: :doc, :optional, :length (arrays),
   :items (array element type, or as the second element), :values (enum).

   `->zod` is what the AI SDK's `tool()` consumes; `->json-schema` is for
   tools that hand a JSON Schema straight to the model (extension tools,
   MCP-shaped registrations); `->doc-rows` is the README's parameter list.
   `test/all_tools_schema_validation.test.cljs` sweeps every registered tool
   through the SDK's `asSchema`, so a renderer that regresses fails there.

   Plain functions, not a macro: the runtime extension loader's
   `compileString` ignores `:require-macros`, so a macro DSL would exist for
   core only (roadmap ruling)."
  (:require ["zod" :as z]
            [clojure.string :as str]))

(defn parse-field
  "Normalise a field spec into {:type :doc :optional? :values :items :length}."
  [spec]
  (let [[t a b] (if (vector? spec) spec [spec])
        opts   (cond (map? a) a (map? b) b :else {})
        ;; squint keywords ARE strings, so `[:array :number …]` cannot tell
        ;; its item type from a doc by type — by membership in the type set.
        type-name? (fn [x] (contains? #{"string" "number" "integer" "boolean" "object" "enum" "array"} (str x)))
        items  (cond (and (= t :array) (type-name? a)) a :else (:items opts))
        values (cond (and (= t :enum) (vector? a)) a :else (:values opts))
        doc    (cond (and (string? a) (not (and (= t :array) (type-name? a)))) a
                     (string? b) b
                     :else (:doc opts))]
    {:type      t
     :doc       doc
     :optional? (boolean (:optional opts))
     :values    values
     :items     items
     :length    (:length opts)}))

(defn- zod-type [{:keys [type values items length]}]
  (case type
    :string  (.string z)
    :number  (.number z)
    :integer (.int (.number z))
    :boolean (.boolean z)
    :enum    (.enum z (clj->js (mapv str values)))
    :array   (cond-> (.array z (zod-type (parse-field (or items :string))))
               length (.length length))
    :object  (.object z #js {})
    (.any z)))

(defn ->zod
  "A zod object schema for `fields`."
  [fields]
  (let [o #js {}]
    (doseq [[k spec] fields]
      (let [f (parse-field spec)
            t (cond-> (zod-type f)
                (:optional? f) (.optional)
                (:doc f)       (.describe (:doc f)))]
        (aset o (name k) t)))
    (.object z o)))

(defn- json-type [{:keys [type values items length doc]}]
  (cond-> (case type
            :string  {:type "string"}
            :number  {:type "number"}
            :integer {:type "integer"}
            :boolean {:type "boolean"}
            :enum    {:type "string" :enum (mapv str values)}
            :array   (cond-> {:type "array" :items (json-type (parse-field (or items :string)))}
                       length (assoc :minItems length :maxItems length))
            :object  {:type "object" :properties {}}
            {})
    doc (assoc :description doc)))

(defn ->json-schema
  "A JSON Schema object (CLJS map) for `fields`; `clj->js` it for the wire."
  [fields]
  (let [parsed (map (fn [[k spec]] [(name k) (parse-field spec)]) fields)]
    {:type       "object"
     :properties (into {} (map (fn [[k f]] [k (json-type f)]) parsed))
     :required   (vec (keep (fn [[k f]] (when-not (:optional? f) k)) parsed))}))

(defn ->doc-rows
  "Markdown bullet per field: `- name (type, optional): doc`."
  [fields]
  (str/join "\n"
            (map (fn [[k spec]]
                   (let [f (parse-field spec)]
                     (str "- `" (name k) "` (" (name (:type f))
                          (when (= :enum (:type f)) (str ": " (str/join " | " (:values f))))
                          (when (:optional? f) ", optional") ")"
                          (when (:doc f) (str ": " (:doc f))))))
                 fields)))
