(ns agent.providers.catalog
  "Enumerate every model nyma knows about, across all registered providers.

   nyma had no central model catalogue — the provider registry's `:list`
   returns *providers*, and `pi_rpc/available-models` returned a single-element
   vector holding the current model. That left `/model` with nothing to show,
   so it was text-only.

   Each provider entry may declare `:models` (extension providers already do;
   built-ins now do too). Entries carry ids and display names; the numbers are
   joined at read time from `agent.model-info` (context window) and
   `agent.pricing` (rates) so there's one source of truth for each."
  (:require [agent.pricing :as pricing]
            [clojure.string :as str]))

(defn- model-cost
  "[input-rate output-rate] per 1M tokens, or nil when unpriced (local models).

   Looks up by full `provider/id` spec, not the bare id: two providers can carry
   the same model id, and for a relay the bare id would resolve to the vendor's
   first-party rate rather than what the relay charges."
  [spec declared]
  (or (when-let [c declared]
        (when (or (:input c) (:output c))
          [(or (:input c) 0) (or (:output c) 0)]))
      (pricing/lookup-cost (str spec))))

(defn- entry-models [provider-name entry context-window-fn]
  (let [models (or (:models entry) [])]
    (keep (fn [m]
            (let [m    (if (map? m) m (js->clj m :keywordize-keys true))
                  id   (or (:id m) (get m "id"))
                  ;; What the user types / what setModel expects.
                  spec (str provider-name "/" id)]
              (when (seq (str id))
                {:provider       provider-name
                 :id             (str id)
                 :spec           spec
                 :name           (or (:name m) (str id))
                 :context-window (or (:context-window m)
                                     (when context-window-fn (context-window-fn spec)))
                 :cost           (model-cost spec (:cost m))
                 :reasoning      (:reasoning m)})))
          models)))

(defn list-all-models
  "All known models as a vector of maps:
   {:provider :id :spec :name :context-window :cost [in out] :reasoning}.

   `providers` is the map returned by the provider registry's `:list`.
   `context-window-fn` is the model registry's `:context-window` (optional)."
  [providers context-window-fn]
  (->> (or providers {})
       (mapcat (fn [[pname entry]] (entry-models pname entry context-window-fn)))
       (sort-by (fn [m] [(:provider m) (:id m)]))
       vec))

(defn format-context
  "Context window as a compact string, e.g. 200000 -> \"200.0k\"."
  [n]
  (if (and n (pos? n)) (pricing/format-tokens n) ""))

(defn format-price
  "Per-1M input/output rates, e.g. \"$3/$15\". Empty when unpriced."
  [cost]
  (if-let [[in out] cost]
    (str "$" in "/$" out)
    ""))

(defn rank-by-recent
  "Stable-sort `models` so the most recently used come first.
   `recent` is a vector of model specs, newest first (MRU) — borrowed from
   oh-my-pi's `getModelUsageOrder`, which ranks its picker the same way.
   Models absent from `recent` keep their existing relative order."
  [models recent]
  (let [idx (into {} (map-indexed (fn [i s] [(str s) i]) (or recent [])))
        big (+ (count idx) (count models) 1)]
    (vec (sort-by (fn [m] (get idx (:spec m) big)) models))))

(defn search
  "Case-insensitive substring match over spec + display name."
  [models query]
  (if (str/blank? (str query))
    (vec models)
    (let [q (str/lower-case (str query))]
      (filterv (fn [m]
                 (str/includes?
                  (str/lower-case (str (:spec m) " " (:name m)))
                  q))
               models))))
