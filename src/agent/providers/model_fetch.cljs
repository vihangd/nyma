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
  (:require [agent.utils.data :as data]
            ["node:fs" :as fs]
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
    (let [i  (aget c "input") o (aget c "output")
          cr (data/key-get c "cache-read" "cacheRead")
          cw (data/key-get c "cache-write" "cacheWrite")]
      (when (and (number? i) (number? o))
        (cond-> {:input i :output o}
          (number? cr) (assoc :cache-read cr)
          (number? cw) (assoc :cache-write cw))))))

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
     :available-only  drop models the catalog itself flags unavailable.
                      Defaults ON, and unlike the options above that is safe:
                      the same declare-it-first rule applies, so a gateway that
                      never reports `available` is untouched. Only an endpoint
                      that explicitly says it will not run is hidden, and a
                      picker entry that always errors helps nobody. FreeLLMAPI
                      flags 207 of its 248 models `available: false` with
                      reason `no_key` — models it lists but has no upstream
                      credential for.

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
  [{:keys [include exclude endpoint-types types paid-only available-only]
    :or   {available-only true}}]
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
              (or (not available-only) (not (false? (:available m))))
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
         ;; A null row is not a model, and `.-id` on one throws — which,
         ;; uncaught, would abort the whole entry's discovery.
         (filter object?)
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
                       cst                           (assoc :cost cst)
                       ;; `available` is a FreeLLMAPI extension, not standard
                       ;; OpenAI. Carried only when the gateway states it, same
                       ;; as `supported_endpoint_types` above, so the filter can
                       ;; drop what the gateway itself says will not run.
                       (boolean? (.-available m))    (assoc :available (.-available m)))))))
         vec)))

(defn same-origin?
  "True when both URLs parse and share an origin. Anything unparseable is not
   the same origin — a malformed override must not be treated as trusted."
  [a b]
  (try
    (let [x (js/URL. (str a)) y (js/URL. (str b))]
      (= (.-origin x) (.-origin y)))
    (catch :default _ false)))

(defn ^:async fetch-json
  "GET `url`, optionally with one bearer header, and return the parsed JSON
   body or nil. The safety half of discovery lives here, shared by the model
   catalogue and the pricing sheet:

     - short timeout, so it never delays startup;
     - redirects treated as failure — one would carry the credential to
       wherever it points;
     - exactly one credential header, sent only when `api-key` is non-blank;
     - never throws, never retries (gateways throttle repeated auth failures:
       yunwu answers a bad key with a 120-second 429)."
  [url api-key]
  (try
    (let [send-key? (seq (str (or api-key "")))
          resp (js-await
                (js/fetch url
                          #js {:method   "GET"
                               :redirect "manual"
                               :signal   (js/AbortSignal.timeout timeout-ms)
                               :headers  (if send-key?
                                           #js {"Authorization" (str "Bearer " api-key)}
                                           #js {})}))]
      (cond
        (or (zero? (.-status resp)) (and (>= (.-status resp) 300) (< (.-status resp) 400)))
        (do (d/warn "model-fetch" (str "refusing to follow a redirect from " url)) nil)

        (not (.-ok resp))
        ;; The status alone is not actionable. yunwu answered every request with
        ;; 403 and a body reading "Your account has been migrated to OpenLux.
        ;; Please sign in at https://api.openlux.ai" — the fix, discarded. On a
        ;; 4xx the provider is usually telling you exactly what is wrong, so say
        ;; it. Capped, because a body can be an HTML error page. 4xx only: a
        ;; 5xx is the gateway being unwell and the body is usually an HTML
        ;; page, so skip the extra read there — this runs on every startup.
        (do (let [msg (try
                        (when (< (.-status resp) 500)
                          (let [t (js-await (.text resp))]
                            (when (seq t)
                              (let [parsed (data/parse-json t)
                                    m (some-> parsed .-error .-message)]
                                (str " — " (subs (str (or m t)) 0 300))))))
                        (catch :default _ nil))]
              (d/warn "model-fetch" (str url " returned " (.-status resp) (or msg ""))))
            nil)

        :else
        (js-await (.json resp))))
    (catch :default e
      (d/warn "model-fetch" (str "fetch failed for " url ": " (.-message e)))
      nil)))

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
   redirect refusal in fetch-json exists to prevent, reached directly instead
   of through a 302) — and, key or no key, whatever that host returns would be
   registered as this provider's context windows and prices. A 4096-token
   window makes compaction thrash every turn; a fabricated rate makes cost
   accounting lie. Withholding the key alone closes the first hole and leaves
   the second.

   Never throws: every failure path returns nil so a caller can fall back to
   its cache."
  [base-url api-key catalog-url]
  (let [override  (str (or catalog-url ""))
        override? (seq override)
        url       (if override?
                    override
                    (str (str/replace (str base-url) #"/+$" "") "/models?limit=1000"))]
    (if (and override? (not (same-origin? url base-url)))
      (do (d/warn "model-fetch"
                  (str "refusing an off-origin catalogUrl: " url
                       " does not share an origin with " base-url))
          nil)
      (when-let [payload (js-await (fetch-json url api-key))]
        (parse-models payload)))))

;; ── New API pricing sheet ────────────────────────────────────
;;
;; New API relays (openlux, ex-yunwu) publish `GET /api/pricing` with no auth:
;; every model's ratios and the multiplier of every billing GROUP. A group is
;; an upstream route with its own price — `Kiro-Claude-1` is the same
;; claude-sonnet-5 as `Anthropic-Claude-1` at a twelfth of the rate — and it
;; is a property of the TOKEN, chosen when the token is minted, so nyma cannot
;; read it off the wire; the settings entry has to name it. Given the group,
;; the sheet is exact: USD per 1M input = model_ratio × group_ratio × 2 (one
;; ratio unit is $0.002 per 1K tokens), output = input × completion_ratio,
;; cache read = input × cache_ratio, cache write = input ×
;; cache_creation_5m_ratio. Per-call rows (quota_type 1, image and video) are
;; not token-priced and are skipped, as is the tiered `step_ratios` surcharge
;; past a model's base window.

(defn pricing-url
  "`/api/pricing` next to a New API base URL: the API surface lives under
   `/v1`, the sheet at the app root beside it — so the trailing version
   segment comes off and the rest of the path stays, for a relay mounted
   under a prefix. Same origin by construction, so no credential question
   arises — and none is sent."
  [base-url]
  (try
    (let [u    (js/URL. (str base-url))
          root (-> (.-pathname u)
                   (str/replace #"/+$" "")
                   (str/replace #"/v\d+$" ""))]
      (str (.-origin u) root "/api/pricing"))
    (catch :default _ nil)))

(defn ^:async fetch-pricing
  "The parsed pricing sheet, or nil."
  [base-url]
  (when-let [url (pricing-url base-url)]
    (js-await (fetch-json url nil))))

(defn group-costs
  "{model-id {:input :output :cache-read? :cache-write?}} in USD per 1M for the
   token-priced chat rows a billing `group` serves, or nil when the sheet does
   not know the group. Keys are exactly the models the group can run — a group
   token's `/v1/models` may list the whole catalogue, and the sheet is the only
   statement of which of those the token will actually be served."
  [pricing group]
  (let [ratios (when pricing (aget pricing "group_ratio"))
        gr     (when ratios (aget ratios (str group)))
        rows   (when pricing (aget pricing "data"))
        num    (fn [v] (when (and (number? v) (js/isFinite v) (>= v 0)) v))]
    (when (and (num gr) (pos? gr) (js/Array.isArray rows))
      (into {}
            (keep (fn [r]
                    (let [id     (when (object? r) (aget r "model_name"))
                          groups (aget r "enable_groups")
                          mr     (num (aget r "model_ratio"))
                          cr     (num (aget r "completion_ratio"))]
                      (when (and (string? id) (seq id)
                                 (js/Array.isArray groups)
                                 (some #(= (str %) (str group)) groups)
                                 (= 0 (or (aget r "quota_type") 0))
                                 (not (false? (aget r "available")))
                                 mr cr)
                        (let [in    (* mr gr 2)
                              cache (num (aget r "cache_ratio"))
                              write (num (or (aget r "cache_creation_5m_ratio")
                                             (aget r "cache_creation_ratio")))]
                          [id (cond-> {:input in :output (* in cr)}
                                cache (assoc :cache-read (* in cache))
                                write (assoc :cache-write (* in write)))]))))
                  ;; A null row would throw on the first aget and abort
                  ;; the whole entry's discovery; skip it like parse-models.
                  (filter object? rows))))))

;; ── Orchestration ────────────────────────────────────────────

(defn ^:async refresh!
  "Fetch, filter, decorate, cache. Returns the new model list, or nil when
   unavailable. Callers use nil to mean \"keep whatever you already registered\".

   `decorate`, when given, maps over the kept list BEFORE the cache write, so
   whatever it adds (a billing group's prices) is what the next start reads."
  [provider-name base-url api-key pred catalog-url & [decorate]]
  (let [fetched (js-await (fetch-models base-url api-key catalog-url))]
    (when (seq fetched)
      (let [kept (cond-> (filterv pred fetched)
                   decorate (->> (mapv decorate)))]
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
