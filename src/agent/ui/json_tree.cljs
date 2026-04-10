(ns agent.ui.json-tree
  "Minimal collapsible JSON tree renderer — used as the fallback body for
   tools that don't have a dedicated renderer registered."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]))

(def ^:private MAX-DEPTH 4)
(def ^:private MAX-SCALAR 120)
(def ^:private MAX-ARRAY-ITEMS 20)
(def ^:private MAX-OBJECT-KEYS 20)

(defn- scalar-str
  "Produce a short human string for a scalar value."
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (if v "true" "false")
    (number? v) (str v)
    (string? v) (let [s (str "\"" v "\"")]
                  (if (> (count s) MAX-SCALAR)
                    (str (.slice s 0 (- MAX-SCALAR 4)) "…\"")
                    s))
    :else (str v)))

(defn- scalar? [v]
  (or (nil? v) (boolean? v) (number? v) (string? v)))

(defn- plain-js-object? [v]
  (and (some? v)
       (not (scalar? v))
       (not (js/Array.isArray v))
       (not (map? v))
       (= (js/Object.getPrototypeOf v) js/Object.prototype)))

(defn JsonNode
  "Render a single node in the tree. Props: :value :name :depth :theme"
  [{:keys [value name depth theme]}]
  (let [muted     (get-in theme [:colors :muted] "#565f89")
        primary   (get-in theme [:colors :primary] "#7aa2f7")
        secondary (get-in theme [:colors :secondary] "#9ece6a")
        prefix    (when name (str name ": "))]
    (cond
      (>= depth MAX-DEPTH)
      #jsx [Text {:color muted} (str prefix "…")]

      (scalar? value)
      #jsx [Box {:flexDirection "row"}
            (when name #jsx [Text {:color primary} prefix])
            [Text {:color secondary} (scalar-str value)]]

      (or (array? value) (js/Array.isArray value))
      (let [items (.slice value 0 MAX-ARRAY-ITEMS)
            extra (max 0 (- (.-length value) MAX-ARRAY-ITEMS))]
        #jsx [Box {:flexDirection "column"}
              (when name #jsx [Text {:color primary} (str name ":")])
              [Box {:flexDirection "column" :marginLeft 2}
               (for [[i item] (map-indexed vector items)]
                 #jsx [JsonNode {:key i
                                 :value item
                                 :depth (inc depth)
                                 :theme theme}])
               (when (pos? extra)
                 #jsx [Text {:color muted} (str "… " extra " more")])]])

      (map? value)
      (let [entries (take MAX-OBJECT-KEYS (seq value))
            extra   (max 0 (- (count value) MAX-OBJECT-KEYS))]
        #jsx [Box {:flexDirection "column"}
              (when name #jsx [Text {:color primary} (str name ":")])
              [Box {:flexDirection "column" :marginLeft 2}
               (for [[k v] entries]
                 #jsx [JsonNode {:key (str k)
                                 :name (str k)
                                 :value v
                                 :depth (inc depth)
                                 :theme theme}])
               (when (pos? extra)
                 #jsx [Text {:color muted} (str "… " extra " more")])]])

      ;; Plain JS object — walk enumerable keys.
      (plain-js-object? value)
      (let [keys (.slice (js/Object.keys value) 0 MAX-OBJECT-KEYS)
            extra (max 0 (- (.-length (js/Object.keys value)) MAX-OBJECT-KEYS))]
        #jsx [Box {:flexDirection "column"}
              (when name #jsx [Text {:color primary} (str name ":")])
              [Box {:flexDirection "column" :marginLeft 2}
               (for [k keys]
                 #jsx [JsonNode {:key k
                                 :name k
                                 :value (aget value k)
                                 :depth (inc depth)
                                 :theme theme}])
               (when (pos? extra)
                 #jsx [Text {:color muted} (str "… " extra " more")])]])

      :else
      #jsx [Text {:color muted} (str prefix (str value))])))

(defn JsonTree
  "Root tree component. Props: :data :theme"
  [{:keys [data theme]}]
  #jsx [JsonNode {:value data :depth 0 :theme theme}])
