(ns agent.model-info)

;; Built-in model context windows
(def ^:private default-models
  {"claude-sonnet-4-20250514" {:context-window 200000}
   "claude-opus-4-20250514"   {:context-window 200000}
   "claude-haiku-4-20250901"  {:context-window 200000}
   "gpt-4o"                   {:context-window 128000}
   "gpt-4o-mini"              {:context-window 128000}
   "gemini-2.0-flash"         {:context-window 1000000}})

(defn create-model-registry
  "Creates a model registry with context window info.
   Returns {:get fn, :register fn, :context-window fn}."
  []
  (let [models (atom default-models)]
    {:get (fn [model-id]
            (let [id (str model-id)]
              (or (get @models id)
                  ;; Fuzzy match: try prefix matching
                  (some (fn [[k v]]
                          (let [prefix (subs k 0 (min 15 (count k)))]
                            (when (.startsWith id prefix) v)))
                        @models)
                  {:context-window 100000})))
     :register (fn [entries]
                 (swap! models merge entries))
     :context-window (fn [model-id]
                       (let [id (str model-id)]
                         (:context-window
                           (or (get @models id)
                               {:context-window 100000}))))}))
