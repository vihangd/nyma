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
    (let [exec-name (symbol (str name "-execute"))]
      `(do
         (defn ~exec-name ^:async ~args ~@body)
         (def ~name
           (tool #js {:description ~description
                      :parameters  (.object z (clj->js ~zod-fields))
                      :execute     ~exec-name}))))))

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

(defmacro definterceptor
  "Define an interceptor with optional :enter, :leave, :error stages.

   (definterceptor logging
     {:enter (fn [ctx] (js/console.log (:tool-name ctx)) ctx)
      :leave (fn [ctx] (js/console.log \"done\") ctx)})"
  [name stages]
  `(def ~name
     (merge {:name ~(str name)} ~stages)))

(defmacro defmiddleware
  "Define a middleware interceptor — sugar for definterceptor.

   (defmiddleware rate-limit
     :enter (fn [ctx] (check-rate-limit ctx)))"
  [name & {:as stages}]
  `(def ~name
     (merge {:name ~(str name)}
            ~(select-keys stages [:enter :leave :error]))))

(defmacro defreducer
  "Define a state reducer for the event-sourced store.

   (defreducer add-message :message-added [state data]
     (update state :messages conj (:message data)))"
  [name event-type args & body]
  `(def ~name
     {:event-type ~event-type
      :reducer    (fn ~args ~@body)}))

(defmacro defextension
  "Define an extension with metadata and activation function.
   Supports :capabilities, :flags, :providers, :widgets declarations.

   (defextension git-tools
     {:capabilities #{:tools :events}}
     [api]
     (.registerTool api \"status\" ...))"
  [name opts args & body]
  `(do
     (def ~(symbol (str name "-metadata"))
       {:namespace    ~(str name)
        :capabilities ~(or (:capabilities opts) #{:all})
        :flags        ~(or (:flags opts) {})})
     (defn ~(symbol (str name "-activate")) ^:async ~args ~@body)
     (set! (.-default js/module) ~(symbol (str name "-activate")))))

(defmacro defevent
  "Define a named event handler.

   (defevent guard-bash \"tool_call\" [event ctx]
     (when (= (.-toolName event) \"bash\")
       #js {:block true :reason \"Blocked\"}))"
  [name event-type args & body]
  `(def ~name
     {:event   ~event-type
      :handler (fn ~args ~@body)}))

(defmacro defwidget
  "Define a UI widget with position and render function.

   (defwidget git-branch {:position \"below\"} [state]
     [(str \"Branch: \" (:branch state))])"
  [name opts args & body]
  `(def ~name
     {:widget-name ~(str name)
      :position    ~(or (:position opts) "below")
      :render      (fn ~args ~@body)}))

(defmacro defprovider
  "Define an LLM provider for the provider registry.

   (defprovider my-provider
     {:create-model (fn [id] (my-sdk id))})"
  [name config]
  `(def ~name
     (merge {:name ~(str name)} ~config)))
