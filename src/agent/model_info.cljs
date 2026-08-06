(ns agent.model-info)

;; Built-in model context windows
(def ^:private default-models
  {"claude-opus-5"            {:context-window 1000000}
   "claude-opus-4-8"          {:context-window 200000}
   "claude-sonnet-5"          {:context-window 200000}
   "claude-haiku-4-5-20251001" {:context-window 200000}
   "claude-sonnet-4-20250514" {:context-window 200000}
   "claude-opus-4-20250514"   {:context-window 200000}
   "claude-haiku-4-20250901"  {:context-window 200000}
   "gpt-4o"                   {:context-window 128000}
   "gpt-4o-mini"              {:context-window 128000}
   "gemini-2.0-flash"         {:context-window 1000000}})

;; Provider/model splitting mirrors registry/split-model-spec: FIRST slash only,
;; since model ids carry slashes of their own (meta-llama/llama-3.3-70b).
(defn- bare-id [id]
  (let [i (.indexOf id "/")]
    (when-not (neg? i) (.slice id (inc i)))))

(defn- resolve-entry
  "Look up `id` in `models`: exact, then — for a `provider/id` spec — the bare
   id, then a prefix match. Nil when nothing matches.

   The bare-id step is what makes relayed models report a real context window:
   a gateway's /v1/models response carries no context field, but the relayed id
   is the vendor's own, so the first-party entry is the right answer."
  [models id]
  (or (get models id)
      (when-let [bare (bare-id id)] (get models bare))
      ;; Fuzzy match: try prefix matching
      (some (fn [[k v]]
              (let [prefix (subs k 0 (min 15 (count k)))]
                (when (.startsWith id prefix) v)))
            models)))

(defn create-model-registry
  "Creates a model registry with context window info.
   Returns {:get fn, :register fn, :context-window fn}."
  []
  (let [models (atom default-models)]
    {:get (fn [model-id]
            (or (resolve-entry @models (str model-id))
                {:context-window 100000}))
     :register (fn [entries]
                 (swap! models merge entries))
     :context-window (fn [model-id]
                       (:context-window
                        (or (resolve-entry @models (str model-id))
                            {:context-window 100000})))}))
