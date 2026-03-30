;; Compile-time macros — executed by SCI at squint compile time
(ns macros.tool-dsl)

(defmacro deftool
  "Define an AI SDK tool with Zod schema.

   (deftool web-search
     \"Search the web for information\"
     {:query [:string \"The search query\"]
      :limit [:number \"Max results\" {:optional true}]}
     [params]
     (let [res (js-await (js/fetch (str api-url (:query params))))]
       (js-await (.json res))))"
  [name description schema-map args & body]
  (let [zod-fields (into {}
                     (map (fn [[k [type desc opts]]]
                            [k `(-> (. z ~(symbol type))
                                    (.describe ~desc)
                                    ~@(when (:optional opts)
                                        ['(.optional)]))]))
                     schema-map)]
    `(def ~name
       (tool #js {:description ~description
                  :parameters  (.object z (clj->js ~zod-fields))
                  :execute     (fn ^:async ~args ~@body)}))))

(defmacro defcommand
  "Register a slash command.

   (defcommand deploy-status
     \"Show current deployment status\"
     [args ctx]
     (let [output (js-await (run-bash \"kubectl get pods\"))]
       (.showOverlay (.-ui ctx) output)))"
  [name description args & body]
  `(do
     (defn ~(symbol (str name "-handler")) ^:async ~args ~@body)
     {:name        ~(str name)
      :description ~description
      :handler     ~(symbol (str name "-handler"))}))
