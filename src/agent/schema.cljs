(ns agent.schema
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            [clojure.string :as str]))

(defn compile-type
  "Compile a type specifier into a Zod schema.
   Supported types:
     :string, :number, :boolean
     [:tuple & types]
     [:enum & values]
     [:array type]
     [:object schema-map]"
  [type-spec]
  (cond
    (= :string type-spec)  (.string z)
    (= :number type-spec)  (.number z)
    (= :boolean type-spec) (.boolean z)

    (vector? type-spec)
    (let [kind (first type-spec)]
      (case kind
        :tuple (let [items (mapv compile-type (rest type-spec))]
                 (.tuple z (clj->js items)))
        :enum  (.enum z (clj->js (vec (rest type-spec))))
        :array (.array (.lazy z (fn [] (compile-type (second type-spec)))))
        :object (compile-schema (second type-spec))))

    :else (throw (js/Error. (str "Unknown schema type: " type-spec)))))

(defn compile-field
  "Compile a field spec {:type :string :description \"...\" :optional true :default val}
   into a Zod schema."
  [{:keys [type description optional default]}]
  (let [base      (compile-type type)
        with-desc (if description (.describe base description) base)
        ;; Apply default before optional; default implies optional input
        with-def  (if (some? default) (.default with-desc default) with-desc)
        with-opt  (if (and optional (nil? default)) (.optional with-def) with-def)]
    with-opt))

(defn compile-schema
  "Compile a schema map into a Zod object schema.
   Each key maps to a field spec: {:type :string :description \"...\"}
   Or shorthand: just a type keyword :string."
  [schema-map]
  (let [;; In Squint, keywords compile to strings — just use str
        key-name (fn [k] (str k))
        fields (into {}
                 (map (fn [[k v]]
                        [(key-name k)
                         (if (map? v)
                           (compile-field v)
                           (compile-type v))]))
                 schema-map)]
    (.object z (clj->js fields))))

(defn data-tool
  "Create an AI SDK tool from a data-driven definition.
   {:name \"read\" :description \"...\" :schema {:path {:type :string}} :execute fn}"
  [{:keys [description schema execute]}]
  (tool #js {:description description
             :parameters  (compile-schema schema)
             :execute     execute}))
