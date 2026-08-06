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

(defn- usable
  "An entry with no context window is not an answer — it's a placeholder a
   gateway registered for a model whose /v1/models response said nothing about
   size. Treat it as a miss so the lookup falls through to the vendor's own
   entry, rather than reporting a default that compaction would plan against."
  [entry]
  (when (:context-window entry) entry))

(defn- resolve-entry
  "Look up `id` in `models`: exact, then — for a `provider/id` spec — the bare
   id, then a prefix match. Nil when nothing matches.

   The bare-id step is what makes relayed models report a real context window:
   a gateway's /v1/models response carries no context field, but the relayed id
   is the vendor's own, so the first-party entry is the right answer."
  [models id]
  (or (usable (get models id))
      (when-let [bare (bare-id id)] (usable (get models bare)))
      ;; Fuzzy match, over BARE keys only, against the bare id.
      ;;
      ;; Qualified keys must be excluded. The 15-char prefix is shorter than
      ;; many provider names — "yunwu-claude/" alone is 13 — so two models under
      ;; one provider differ in as little as two characters of the prefix, and
      ;; any model whose own window is unknown would inherit whichever sibling
      ;; `some` reached first. Matching bare-to-bare keeps this doing what it was
      ;; written for: letting an unrecognised dated id find its family.
      (let [target (or (bare-id id) id)]
        (some (fn [[k v]]
                (when-not (.includes k "/")
                  (let [prefix (subs k 0 (min 15 (count k)))]
                    (when (.startsWith target prefix) (usable v)))))
              models))))

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
