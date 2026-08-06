(ns agent.providers.model-fetch
  "Discover a gateway's model list from its OpenAI-shaped `/v1/models` endpoint,
   with an on-disk cache.

   A relay's catalogue is large, changes without notice, and is scoped to the
   caller's key — so a hardcoded list is stale on arrival and wrong for anyone
   whose key has different access. Shaped after Claude Code's gateway model
   discovery (docs: llm-gateway-protocol), including the parts that exist for
   safety rather than convenience:

     - a short timeout, because this must never delay startup;
     - redirects treated as failure, so the credential cannot leak to a
       redirect target;
     - exactly one credential header;
     - a cache that survives a failed refresh, so a flaky gateway degrades to
       the previous list rather than to nothing."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.debug :as d]
            [clojure.string :as str]))

(def ^:private ttl-ms (* 24 60 60 1000))
(def ^:private timeout-ms 3000)

;; ── Cache ────────────────────────────────────────────────────

(defn cache-dir []
  (when-let [home (.. js/process -env -HOME)]
    (path/join home ".nyma" "cache")))

(defn cache-path [provider-name]
  (when-let [dir (cache-dir)]
    (path/join dir (str "models-" provider-name ".json"))))

(defn read-cache
  "{:models [...] :fetched-at ms} or nil when absent/unreadable/malformed."
  [provider-name]
  (when-let [p (cache-path provider-name)]
    (when (fs/existsSync p)
      (try
        (let [parsed (js/JSON.parse (fs/readFileSync p "utf8"))
              models (.-models parsed)]
          (when (js/Array.isArray models)
            ;; Normalize to the same {:id :name} shape refresh! produces, so
            ;; callers never have to care which source a list came from.
            {:models     (->> (vec models)
                              (keep (fn [m]
                                      (let [id (or (.-id m) (aget m "id"))]
                                        (when (and (string? id) (seq id))
                                          {:id id :name (or (.-name m) id)}))))
                              vec)
             :fetched-at (or (.-fetchedAt parsed) 0)}))
        (catch :default _ nil)))))

(defn cache-fresh?
  "True when `entry` was fetched inside the TTL."
  ([entry] (cache-fresh? entry (js/Date.now)))
  ([entry now]
   (boolean (and entry
                 (< (- now (or (:fetched-at entry) 0)) ttl-ms)))))

(defn write-cache!
  "Persist `models`. Best-effort: a cache we can't write is not an error worth
   surfacing, since the list is still usable in memory for this session."
  [provider-name models]
  (try
    (when-let [p (cache-path provider-name)]
      (fs/mkdirSync (cache-dir) #js {:recursive true})
      (fs/writeFileSync p (js/JSON.stringify
                           #js {:fetchedAt (js/Date.now)
                                :models    (clj->js models)}))
      true)
    (catch :default e
      (d/warn "model-fetch" (str "could not write cache for " provider-name ": " (.-message e)))
      false)))

;; ── Filtering ────────────────────────────────────────────────

(defn- pattern->pred
  "A pattern is a plain substring, or `/re/` for a regex."
  [pattern]
  (let [p (str pattern)]
    (if (and (> (count p) 1) (.startsWith p "/") (.endsWith p "/"))
      (let [re (js/RegExp. (subs p 1 (dec (count p))) "i")]
        (fn [s] (.test re s)))
      (let [needle (str/lower-case p)]
        (fn [s] (str/includes? (str/lower-case s) needle))))))

(defn make-filter
  "Predicate over model ids. `include` (when non-empty) is an allow-list;
   `exclude` always subtracts. Both are vectors of substrings or `/regex/`.

   Filtering is not cosmetic at relay scale: a gateway can expose hundreds of
   models, which drowns the picker and buries the handful anyone actually uses."
  [include exclude]
  (let [inc-preds (mapv pattern->pred (or include []))
        exc-preds (mapv pattern->pred (or exclude []))]
    (fn [id]
      (let [s (str id)]
        (boolean
         (and (or (empty? inc-preds) (some (fn [p] (p s)) inc-preds))
              (not (some (fn [p] (p s)) exc-preds))))))))

;; ── Fetch ────────────────────────────────────────────────────

(defn parse-models
  "Extract [{:id :name}] from an OpenAI-shaped `/v1/models` payload.

   New API returns `{id, object, owned_by}` — no context window and no pricing —
   so those stay nil here and are supplied by the settings entry or resolved
   from the model registry by the vendor's own id."
  [payload]
  (let [data (when payload (.-data payload))]
    (when (js/Array.isArray data)
      (->> (vec data)
           (keep (fn [m]
                   (let [id (.-id m)]
                     (when (and (string? id) (seq id))
                       {:id   id
                        :name (or (.-display_name m) (.-name m) id)}))))
           vec))))

(defn ^:async fetch-models
  "GET <base-url>/models?limit=1000. Returns [{:id :name}] or nil.

   Never throws: every failure path returns nil so a caller can fall back to
   its cache. Also never retries — gateways commonly throttle repeated auth
   failures (yunwu answers a bad key with a 120-second 429), so a retry loop
   turns one bad key into a two-minute outage."
  [base-url api-key]
  (try
    (let [url  (str (str/replace (str base-url) #"/+$" "") "/models?limit=1000")
          resp (js-await
                (js/fetch url
                          #js {:method   "GET"
                               :redirect "manual"
                               :signal   (js/AbortSignal.timeout timeout-ms)
                               :headers  #js {"Authorization" (str "Bearer " api-key)}}))]
      (cond
        ;; A redirect would carry the credential to wherever it points.
        (or (zero? (.-status resp)) (and (>= (.-status resp) 300) (< (.-status resp) 400)))
        (do (d/warn "model-fetch" (str "refusing to follow a redirect from " url)) nil)

        (not (.-ok resp))
        (do (d/warn "model-fetch" (str url " returned " (.-status resp))) nil)

        :else
        (parse-models (js-await (.json resp)))))
    (catch :default e
      (d/warn "model-fetch" (str "discovery failed for " base-url ": " (.-message e)))
      nil)))

;; ── Orchestration ────────────────────────────────────────────

(defn ^:async refresh!
  "Fetch, filter, cache. Returns the new model list, or nil when unavailable.
   Callers use nil to mean \"keep whatever you already registered\"."
  [provider-name base-url api-key pred]
  (let [fetched (js-await (fetch-models base-url api-key))]
    (when (seq fetched)
      (let [kept (filterv (fn [m] (pred (:id m))) fetched)]
        (write-cache! provider-name kept)
        kept))))

(defn cached-models
  "Filtered models from the cache, or nil. Synchronous, so a provider can be
   registered with a real list before the first request rather than after the
   first refresh."
  [provider-name pred]
  (when-let [entry (read-cache provider-name)]
    (let [kept (filterv (fn [m] (pred (:id m))) (:models entry))]
      (when (seq kept)
        {:models kept :fresh? (cache-fresh? entry)}))))
