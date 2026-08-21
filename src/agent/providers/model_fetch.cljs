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

(defn- cached-window
  "The window a cached entry carries. Read tolerantly: `write-cache!` runs the
   model maps through `clj->js`, and the exact spelling a keyword key lands on
   is squint's business, not ours."
  [m]
  (let [n (or (aget m "context-window") (aget m "contextWindow") (aget m "context_window"))]
    (when (and (number? n) (pos? n)) n)))

(defn- cached-cost [m]
  (when-let [c (aget m "cost")]
    (let [i (aget c "input") o (aget c "output")]
      (when (and (number? i) (number? o)) {:input i :output o}))))

(defn- cached-type [m]
  (let [t (aget m "type")]
    (when (and (string? t) (seq t)) t)))

(defn read-cache
  "{:models [...] :fetched-at ms} or nil when absent/unreadable/malformed."
  [provider-name]
  (when-let [p (cache-path provider-name)]
    (when (fs/existsSync p)
      (try
        (let [parsed (js/JSON.parse (fs/readFileSync p "utf8"))
              models (.-models parsed)]
          (when (js/Array.isArray models)
            ;; Normalize to the same shape refresh! produces, so callers never
            ;; have to care which source a list came from.
            ;;
            ;; This is a WHITELIST, and every field discovery learns to carry
            ;; has to be added here too. A field written but not read back is
            ;; right on the first run and wrong on every run after, silently:
            ;; the cache is what a session reads before any refresh lands.
            {:models     (->> (vec models)
                              (keep (fn [m]
                                      (let [id (or (.-id m) (aget m "id"))]
                                        (when (and (string? id) (seq id))
                                          (let [w (cached-window m)
                                                c (cached-cost m)
                                                t (cached-type m)]
                                            (cond-> {:id id :name (or (.-name m) id)}
                                              (js/Array.isArray (aget m "endpoints"))
                                              (assoc :endpoints (vec (aget m "endpoints")))
                                              w (assoc :context-window w)
                                              c (assoc :cost c)
                                              t (assoc :type t)))))))
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
  "Predicate over a model map `{:id :endpoints}`.

   Filtering is not cosmetic at relay scale: a gateway can expose hundreds of
   models — many of them image, audio and rerank endpoints nyma cannot drive —
   which drowns the picker and buries the handful anyone uses.

     :endpoint-types  required `supported_endpoint_types`, matched as ANY-of.
                      Applied only to models that declare the field, so a
                      gateway that doesn't report it isn't filtered to nothing.
     :types           required `type`, matched as ANY-of. Same declare-it-first
                      rule as above, and same reason: a gateway that
                      sells image and embedding models alongside chat ones
                      (Velona) states which is which, and nyma can only drive
                      the text ones.
     :paid-only       drop models the catalog prices at zero on both sides.
                      A free TIER is often not the same product as the paid
                      one: Velona serves its free models only from the native
                      /gateway/v1/inference/run surface and answers the
                      OpenAI-compatible /v1 with `model_not_supported`, so
                      listing them in the picker offers models that cannot
                      run. Applied only to models whose price is known, so a
                      gateway that reports no pricing keeps everything.
     :include         allow-list over the id; substrings or `/regex/`
     :exclude         subtracted from the above

   Endpoint types beat id patterns where available: they're the gateway's own
   statement of what it will serve, rather than a guess from the name."
  [{:keys [include exclude endpoint-types types paid-only]}]
  (let [inc-preds (mapv pattern->pred (or include []))
        exc-preds (mapv pattern->pred (or exclude []))
        wanted    (set (map str (or endpoint-types [])))
        want-type (set (map str (or types [])))]
    (fn [m]
      (let [m   (if (map? m) m {:id m})
            s   (str (:id m))
            eps (:endpoints m)
            typ (:type m)
            c   (:cost m)
            free? (and c
                       (= 0 (:input c))
                       (= 0 (:output c)))]
        (boolean
         (and (or (empty? wanted)
                  (nil? eps)
                  (some (fn [e] (contains? wanted (str e))) eps))
              (or (empty? want-type)
                  (nil? typ)
                  (contains? want-type (str typ)))
              (or (not paid-only) (not free?))
              (or (empty? inc-preds) (some (fn [p] (p s)) inc-preds))
              (not (some (fn [p] (p s)) exc-preds))))))))

;; ── Fetch ────────────────────────────────────────────────────

(defn- payload-models
  "The model array inside a catalog payload, in either shape that occurs:
   `data` as an array (OpenAI `/v1/models`, New API), or `data.models`
   (Velona's `/gateway/v1/models`)."
  [payload]
  (let [data (when payload (.-data payload))]
    (cond
      (js/Array.isArray data)                            data
      (and data (js/Array.isArray (aget data "models"))) (aget data "models")
      :else                                              nil)))

(defn entry-window
  "The context window a catalog entry declares, or nil. Three spellings occur:
   `context_window` (Velona), `context_length` (OpenRouter), `max_model_len`
   (vLLM).

   Public and single-sourced on purpose — `custom_provider_local/model-window`
   used to carry its own copy of the same list, so a gateway adding a fourth
   spelling got fixed in one place and not the other."
  [m]
  (let [n (or (aget m "context_window") (aget m "context_length") (aget m "max_model_len"))]
    (when (and (number? n) (pos? n)) n)))

(defn- entry-cost
  "{:input :output} in USD per 1M tokens from an entry's `pricing` block, or nil.

   Two dialects occur, and the KEY NAMES disambiguate them — never the
   magnitude, which overlaps:

     input_per_1m_usd / output_per_1m_usd   already USD per 1M (Velona, numbers)
     prompt / completion                    USD per TOKEN, as strings
                                            (OpenRouter, \"0.0000015\") — parse
                                            and scale by 1e6.

   Getting that backwards misreports cost by a factor of a million, in a number
   the user reads as money, so both halves must come from the same dialect."
  [m]
  (when-let [p (aget m "pricing")]
    ;; Non-negative only. OpenRouter publishes "-1" as a sentinel for
    ;; variable/auto-routed pricing; scaled up that becomes -$1,000,000 per 1M,
    ;; which now BEATS a hand-declared cost under merge-declared and shows the
    ;; user a large negative rate while cost accounting subtracts money.
    (let [ok      (fn [n] (when (and (number? n) (js/isFinite n) (>= n 0)) n))
          per-1m  (fn [v] (ok v))
          per-tok (fn [v] (when (or (string? v) (number? v))
                            (ok (* (js/parseFloat v) 1e6))))
          i (or (per-1m (aget p "input_per_1m_usd")) (per-tok (aget p "prompt")))
          o (or (per-1m (aget p "output_per_1m_usd")) (per-tok (aget p "completion")))]
      ;; Both or neither: half a price is worse than no price.
      (when (and (number? i) (number? o))
        {:input i :output o}))))

(defn parse-models
  "Extract [{:id :name :endpoints :context-window :cost :type}] from a catalog
   payload.

   Only `:id` and `:name` are guaranteed. Everything else is present exactly
   when the gateway declared it, which is what lets one parser serve endpoints
   that disagree about how much they say:

     - New API's `/v1/models` and Velona's own `/v1/models` carry an id and
       nothing else, so a window has to come from the settings entry or from
       the vendor's id in the model registry;
     - Velona's `/gateway/v1/models` and OpenRouter's `/api/v1/models` carry
       real windows and real prices, and a gateway's prices are its own — no
       other source has them.

   `supported_endpoint_types` is a New API extension, not standard OpenAI, and
   it is the honest way to tell a chat model from an image or rerank endpoint.
   `type` plays the same role on Velona. Both are absent on most gateways, so
   `make-filter` only applies them to models that declare them."
  [payload]
  (when-let [data (payload-models payload)]
    (->> (vec data)
         (keep (fn [m]
                 (let [id  (.-id m)
                       eps (aget m "supported_endpoint_types")
                       typ (aget m "type")
                       win (entry-window m)
                       cst (entry-cost m)]
                   (when (and (string? id) (seq id))
                     (cond-> {:id   id
                              :name (or (.-display_name m) (.-name m) id)}
                       (js/Array.isArray eps)        (assoc :endpoints (vec eps))
                       (and (string? typ) (seq typ)) (assoc :type typ)
                       win                           (assoc :context-window win)
                       cst                           (assoc :cost cst))))))
         vec)))

(defn same-origin?
  "True when both URLs parse and share an origin. Anything unparseable is not
   the same origin — a malformed override must not be treated as trusted."
  [a b]
  (try
    (let [x (js/URL. (str a)) y (js/URL. (str b))]
      (= (.-origin x) (.-origin y)))
    (catch :default _ false)))

(defn ^:async fetch-models
  "GET the gateway's catalog. Returns [{:id :name …}] or nil.

   `catalog-url`, when non-blank, is fetched verbatim instead of
   `<base-url>/models?limit=1000`. Some gateways publish a richer catalog at a
   different path than their OpenAI surface — Velona's `/v1/models` carries only
   ids while `/gateway/v1/models` carries real windows and prices — and a window
   nyma has to invent is one compaction plans against.

   An override must share an origin with `base-url`, and is refused otherwise.
   Two reasons, and the second is the one that is easy to miss: a `catalogUrl`
   naming another host would be handed this provider's credential (the leak the
   redirect refusal below exists to prevent, reached directly instead of through
   a 302) — and, key or no key, whatever that host returns would be registered
   as this provider's context windows and prices. A 4096-token window makes
   compaction thrash every turn; a fabricated rate makes cost accounting lie.
   Withholding the key alone closes the first hole and leaves the second.

   Never throws: every failure path returns nil so a caller can fall back to
   its cache. Also never retries — gateways commonly throttle repeated auth
   failures (yunwu answers a bad key with a 120-second 429), so a retry loop
   turns one bad key into a two-minute outage."
  [base-url api-key catalog-url]
  (try
    (let [override  (str (or catalog-url ""))
          override? (seq override)
          url       (if override?
                      override
                      (str (str/replace (str base-url) #"/+$" "") "/models?limit=1000"))
          send-key? (seq (str (or api-key "")))
          resp (when (and override? (not (same-origin? url base-url)))
                 (d/warn "model-fetch"
                         (str "refusing an off-origin catalogUrl: " url
                              " does not share an origin with " base-url))
                 :refused)
          resp (if (= :refused resp)
                 resp
                 (js-await
                (js/fetch url
                          #js {:method   "GET"
                               :redirect "manual"
                               :signal   (js/AbortSignal.timeout timeout-ms)
                               :headers  (if send-key?
                                           #js {"Authorization" (str "Bearer " api-key)}
                                           #js {})})))]
      (cond
        (= :refused resp) nil

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
  [provider-name base-url api-key pred catalog-url]
  (let [fetched (js-await (fetch-models base-url api-key catalog-url))]
    (when (seq fetched)
      (let [kept (filterv pred fetched)]
        (write-cache! provider-name kept)
        kept))))

(defn cached-models
  "Filtered models from the cache, or nil. Synchronous, so a provider can be
   registered with a real list before the first request rather than after the
   first refresh."
  [provider-name pred]
  (when-let [entry (read-cache provider-name)]
    (let [kept (filterv pred (:models entry))]
      (when (seq kept)
        {:models kept :fresh? (cache-fresh? entry)}))))
