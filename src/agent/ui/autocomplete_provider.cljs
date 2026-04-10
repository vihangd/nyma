(ns agent.ui.autocomplete-provider
  "Combined autocomplete provider registry.

   A provider is a map:
     {:id       string
      :trigger  :slash | :at | :path | :any
      :priority number (higher runs first — used only for display order)
      :complete fn [context] → Promise<vec items>}

   Each item is a map {:label :value :description? :replace-range?}.

   The trigger decides when a provider is invoked:
     :slash  — text starts with '/'
     :at     — text ends with '@' or contains '@<query>' at cursor
     :path   — text contains a path-like token at cursor (ends with '/'
               or '.' or starts with './')
     :any    — always invoked

   `complete-all` runs every provider whose trigger matches the text,
   merges the results, scores them with the fuzzy scorer, and returns a
   flat list of items sorted best-first."
  (:require [agent.ui.fuzzy-scorer :refer [fuzzy-filter]]
            ["node:fs/promises" :as fsp]))

;;; ─── Trigger detection ──────────────────────────────────

(defn detect-trigger
  "Inspect the editor text (and cursor position when available) and
   return a vector of trigger keywords that apply right now.
   Pure function — used by complete-all and by the app-side effect."
  [text]
  (let [s (or text "")]
    (cond-> #{}
      (.startsWith s "/") (conj :slash)
      (.endsWith s "@") (conj :at)
      (or (.endsWith s "/") (.startsWith s "./")) (conj :path)
      true (conj :any))))

(defn- extract-query
  "Extract the token the user is currently typing. For '/' triggers the
   token is everything after the slash up to the first space. For '@'
   it's everything after the last '@'. For path it's the last path
   segment. For :any the provider's own results are authoritative, so
   the fuzzy filter should be a no-op (empty query)."
  [trigger text]
  (let [s (or text "")]
    (case trigger
      :slash (let [rest-text (.slice s 1)
                   space-idx (.indexOf rest-text " ")]
               (if (neg? space-idx) rest-text (.slice rest-text 0 space-idx)))
      :at    (let [idx (.lastIndexOf s "@")]
               (if (>= idx 0) (.slice s (inc idx)) s))
      :path  (let [idx (.lastIndexOf s "/")]
               (if (>= idx 0) (.slice s (inc idx)) s))
      :any   ""
      s)))

;;; ─── Registry ──────────────────────────────────────────

(defn create-provider-registry
  "Build a fresh provider registry. Returns
     {:providers (atom {id → provider})
      :register fn [id provider]
      :unregister fn [id]
      :get fn [id]
      :list fn []}"
  []
  (let [providers (atom {})]
    {:providers providers
     :register  (fn [id provider]
                  (swap! providers assoc (str id)
                         (assoc provider :id (str id))))
     :unregister (fn [id] (swap! providers dissoc (str id)))
     :get       (fn [id] (get @providers (str id)))
     :list      (fn [] (vec (vals @providers)))}))

;;; ─── Aggregation ───────────────────────────────────────

(defn ^:async complete-all
  "Run every provider whose :trigger matches the current text. Each
   provider is invoked with a context map {:text :trigger :query}.
   Results from all providers are concatenated and fuzzy-filtered by
   the extracted query."
  [registry text]
  (let [triggers (detect-trigger text)
        providers (filter (fn [p] (contains? triggers (:trigger p)))
                          ((:list registry)))
        ;; Use the first non-:any trigger when extracting the query.
        primary  (or (first (disj triggers :any)) :any)
        query    (extract-query primary text)
        promises (mapv (fn [p]
                         (let [ctx {:text text :trigger primary :query query}
                               ;; Catch both synchronous and async errors so a
                               ;; single flaky provider can't tank the whole
                               ;; completion step.
                               p-promise (try
                                           ((:complete p) ctx)
                                           (catch :default _ (js/Promise.resolve [])))]
                           (.catch p-promise (fn [_] []))))
                       providers)
        all-raw  (js-await (js/Promise.all (clj->js promises)))
        merged   (reduce (fn [acc items]
                           (into acc (or items [])))
                         []
                         all-raw)]
    (fuzzy-filter merged query
                  (fn [item]
                    (or (get item :label) (get item "label") "")))))

;;; ─── readdir cache ─────────────────────────────────────

(def ^:private readdir-cache
  "{path → {:entries :expires-at}} — process-wide TTL cache used by the
   built-in path-completion provider."
  (atom {}))

(def ^:private TTL-MS 2000)

(defn clear-readdir-cache!
  "Test helper."
  []
  (reset! readdir-cache {}))

(defn ^:async readdir-cached
  "Return a vector of entries for `path`, cached for TTL-MS milliseconds.
   Silently returns [] on any filesystem error."
  [path]
  (let [now   (js/Date.now)
        entry (get @readdir-cache path)]
    (if (and entry (< now (:expires-at entry)))
      (:entries entry)
      (let [entries (try
                      (js-await (fsp/readdir path))
                      (catch :default _ #js []))
            v       (vec entries)]
        (swap! readdir-cache assoc path
               {:entries v :expires-at (+ now TTL-MS)})
        v))))
