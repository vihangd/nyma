(ns agent.extensions.codemode.openapi
  "An OpenAPI 3 spec → one tool per operation.

   Each operation becomes an ordinary registered tool, so a call goes through
   the same middleware pipeline, permission gate and tracking as any other —
   code mode has nothing API-specific past this file. The tool is named
   `<api>__<operationId>`, which scripts read as `<api>/<operationId>`.

   Inputs: path and query parameters by name, the JSON request body as `body`.
   Auth lives in settings headers with `${ENV}` expansion; it is applied here,
   at call time, and never appears in a schema, a description or a result.

   GET/HEAD are read-only for the gate; everything else is destructive.
   Skipped: non-JSON bodies, SSE, `$ref` outside `#/components`."
  (:require [clojure.string :as str]))

(def ^:private methods ["get" "put" "post" "delete" "patch" "head"])

(defn expand-env
  "`${VAR}` → process.env.VAR (empty when unset)."
  [s env]
  (if (string? s)
    (.replace s (js/RegExp. "\\$\\{([^}]+)\\}" "g")
              (fn [_m v] (or (aget env v) "")))
    s))

(defn- resolve-refs
  "Inline local `$ref`s. `hops` counts refs followed, not nesting, so a deep
   but finite schema resolves fully and a self-referential one stops as `{}`."
  [spec node hops]
  (cond
    (array? node) (.map node #(resolve-refs spec % hops))
    (and node (object? node))
    (if-let [ref (aget node "$ref")]
      (let [target (reduce (fn [o k] (when o (aget o k)))
                           spec
                           (rest (str/split (str ref) #"/")))]
        (if (and target (< hops 8)) (resolve-refs spec target (inc hops)) #js {}))
      (let [out #js {}]
        (doseq [k (js/Object.keys node)]
          (aset out k (resolve-refs spec (aget node k) hops)))
        out))
    :else node))

(defn op-name
  "operationId, or `<method>_<path>` made identifier-safe."
  [method path op]
  (or (aget op "operationId")
      (-> (str method "_" path)
          (.replace (js/RegExp. "[{}]" "g") "")
          (.replace (js/RegExp. "[^A-Za-z0-9_]+" "g") "_")
          (.replace (js/RegExp. "_+$") ""))))

(defn operations
  "Every operation as {:name :method :path :description :schema :params
   :body?}. `:params` is [{:name :in}] for path/query parameters. Pure."
  [spec]
  (let [paths (or (aget spec "paths") #js {})]
    (vec
     (for [p (js/Object.keys paths)
           :let [item   (aget paths p)
                 shared (or (aget item "parameters") #js [])]
           m methods
           :let [op (aget item m)]
           :when op
           :let [params (->> (concat shared (or (aget op "parameters") #js []))
                             (map #(resolve-refs spec % 0))
                             (filter #(contains? #{"path" "query"} (aget % "in"))))
                 body   (some-> op (aget "requestBody") (#(resolve-refs spec % 0))
                                (aget "content") (aget "application/json") (aget "schema"))
                 props  (into {} (map (fn [prm] [(aget prm "name")
                                                 (let [s (or (aget prm "schema") #js {:type "string"})
                                                       d (aget prm "description")]
                                                   (if d (js/Object.assign #js {} s #js {:description d}) s))])
                                      params))
                 props  (cond-> props body (assoc "body" body))
                 ;; the first 2xx JSON response, for the catalog's `→ {…}`
                 output (let [rs (or (aget op "responses") #js {})]
                          (some (fn [code]
                                  (when (re-find #"^2" code)
                                    (some-> (resolve-refs spec (aget rs code) 0)
                                            (aget "content") (aget "application/json") (aget "schema"))))
                                (sort (js/Object.keys rs))))
                 req    (vec (concat (keep #(when (or (= "path" (aget % "in")) (aget % "required")) (aget % "name")) params)
                                     (when (and body (some-> op (aget "requestBody") (aget "required"))) ["body"])))]]
       {:name        (op-name m p op)
        :method      (str/upper-case m)
        :path        p
        :description (or (aget op "summary") (aget op "description") "")
        :params      (mapv (fn [prm] {:name (aget prm "name") :in (aget prm "in")}) params)
        :body?       (some? body)
        :output      output
        :schema      (clj->js {:type "object" :properties props :required req})}))))

(defn request
  "{:url :init} for calling `op` with `args`. Pure (env passed in)."
  [{:keys [base-url headers]} op args env]
  (let [path (reduce (fn [p {:keys [name in]}]
                       (if (= "path" in)
                         (.replace p (str "{" name "}") (js/encodeURIComponent (str (aget args name))))
                         p))
                     (:path op) (:params op))
        qs   (js/URLSearchParams.)]
    (doseq [{:keys [name in]} (:params op)]
      (when (and (= "query" in) (some? (aget args name)))
        (.append qs name (str (aget args name)))))
    (let [q    (str qs)
          hdrs (let [h #js {}]
                 (doseq [k (js/Object.keys (or headers #js {}))]
                   (aset h k (expand-env (aget headers k) env)))
                 (when (:body? op) (aset h "content-type" "application/json"))
                 h)]
      {:url  (str (.replace (str base-url) (js/RegExp. "/+$") "") path (when (seq q) (str "?" q)))
       :init (cond-> {:method (:method op) :headers hdrs}
               (and (:body? op) (some? (aget args "body")))
               (assoc :body (js/JSON.stringify (aget args "body"))))})))

(defn server-url
  "The spec's first server URL, resolved against the spec's own URL: the
   Petstore v3 spec says `/api/v3`, which fetch cannot use as written."
  [spec src]
  (when-let [u (some-> spec (aget "servers") (aget 0) (aget "url"))]
    (if (re-find #"^https?://" (str src)) (str (js/URL. u src)) u)))

(defn ^:async load-spec
  "Read a spec from a URL or a file path; JSON or YAML."
  [src]
  (let [text (if (re-find #"^https?://" (str src))
               (let [r (js-await (js/fetch src #js {:signal (js/AbortSignal.timeout 30000)}))]
                 (when-not (.-ok r) (throw (js/Error. (str "HTTP " (.-status r) " for " src))))
                 (js-await (.text r)))
               (js-await (.text (js/Bun.file src))))]
    (if (re-find #"^\s*[{\[]" text)
      (js/JSON.parse text)
      (js/Bun.YAML.parse text))))

(defn tool-def
  "A registerable tool for `op` of the API configured by `cfg`."
  [cfg op]
  (let [read? (contains? #{"GET" "HEAD"} (:method op))]
    #js {:description (str (:description op) " [" (:method op) " " (:path op) "]")
         :inputSchema (:schema op)
         :nymaOutputSchema (:output op)
         :safety      (if read?
                        #js {:read-only? true :network? true :capabilities #js ["network"] :category "network"}
                        #js {:destructive? true :network? true :requires-confirmation? true
                             :capabilities #js ["network"] :category "network"})
         :execute
         (^:async fn [args ctx]
           (let [{:keys [url init]} (request cfg op (or args #js {}) (.-env js/process))
                 sigs (cond-> [(js/AbortSignal.timeout 30000)]
                        (some-> ctx .-abortSignal) (conj (.-abortSignal ctx)))
                 r    (js-await (js/fetch url (js/Object.assign (clj->js init)
                                                                #js {:signal (js/AbortSignal.any (clj->js sigs))})))
                 text (js-await (.text r))]
             (when-not (.-ok r)
               (throw (js/Error. (str "HTTP " (.-status r) ": " (subs text 0 (min 500 (count text)))))))
             text))}))
