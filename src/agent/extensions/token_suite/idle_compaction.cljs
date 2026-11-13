(ns agent.extensions.token-suite.idle-compaction
  "Compact an idle conversation on the provider's warm prompt cache, and apply
   the summary only once that cache has expired.

   A user who walks away from a long session comes back to a cold cache: the
   next request re-sends the whole prefix at full price, or 1.25-2x on a
   provider that charges for cache writes. Summarising BEFORE expiry reads that
   prefix at the cache-read price instead, so returning costs a much smaller
   prefix. Claude Code does this for its 1h cache; nyma's own sessions put it
   at ~16% of Anthropic spend on a 5m cache.

   Two timers, both cancelled the moment the session does anything (a run
   starting, or any request):
     1. at `fire-at` x the time the cache is known to stay warm, after the last
        request: build the summary on the request prefix (compact
        :summarize-only?), which is a cache read. Nothing changes yet.
     2. once the cache is known to have expired, counted from that summary
        call (a read refreshes the cache): apply it. The conversation ends on
        the assistant at that point, the shape post-turn compaction handles.
   A user back before (2) keeps the full conversation on a warm cache; the
   summary is dropped.

   How long a cache stays warm is per provider, held as data:
     - Anthropic-dialect requests (kv_cache places their breakpoints): the
       configured `kv-cache.cache-ttl`, 5m by default.
     - a settings profile for the provider: `{\"ttl-s\": n}`, or
       `{\"class\": \"none\"}` to never run (DeepSeek and Groq keep caches for
       hours, so the defaults say none).
     - anything else priced: learned. The first request of each run records
       its gap since the last cache touch and whether it read the cache; the
       longest gap that hit says warm, the shortest that missed says cold.
       Nothing runs until both have been seen, so a provider that never
       reports cache reads (local servers) is never compacted for it."
  (:require [agent.extensions.token-suite.shared :as shared]
            [agent.pricing :as pricing]
            [agent.debug :as d]))

(def ^:private margin-ms
  "Past the expiry before applying: clocks and the provider's own accounting
   are not exact, and applying onto a still-warm cache costs fidelity for
   nothing."
  5000)

(def ^:private min-learn-input
  "Requests smaller than this tell us little about a cache (and fall under
   most providers' minimum cacheable prefix)."
  2048)

(defn learn
  "Fold one first-request observation into `l` ({:hit-max s :miss-min s}).
   A read of at least half the input is a hit; zero is a miss. A miss is
   counted only past a minute, and only if it lies beyond every hit, since a
   miss inside a known-warm gap means the prefix changed, not that it expired."
  [l gap-s read input]
  (let [l (or l {})]
    (cond
      (or (nil? gap-s) (< (or input 0) min-learn-input)) l
      (>= (or read 0) (* 0.5 input))
      (let [hm (max (or (:hit-max l) 0) gap-s)]
        (cond-> (assoc l :hit-max hm)
          (and (:miss-min l) (<= (:miss-min l) hm)) (dissoc :miss-min)))
      (and (zero? (or read 0)) (> gap-s 60) (> gap-s (or (:hit-max l) 0)))
      (assoc l :miss-min (min (or (:miss-min l) gap-s) gap-s))
      :else l)))

(defn ttl-of
  "`kv-cache.cache-ttl` in seconds; Anthropic's default is 5 minutes."
  [kv-ttl]
  (if (= "1h" (str kv-ttl)) 3600 300))

(defn window
  "{:warm-s :cold-s} for this request's model, or nil to never run.
   `spec` is the provider-qualified key (`zai/glm-5.3`), `model` the SDK model."
  [{:keys [spec model profiles kv-ttl learned extra-providers]}]
  (let [pname   (first (.split (str spec) "/"))
        profile (or (get profiles (str spec)) (get profiles pname))
        fixed   (get profile "ttl-s")]
    (cond
      (= "none" (get profile "class")) nil
      (number? fixed)                  {:warm-s fixed :cold-s fixed}
      (= :anthropic (shared/detect-cache-provider-for-model model extra-providers))
      (let [t (ttl-of kv-ttl)] {:warm-s t :cold-s t})
      ;; The longest hit is only a lower bound on the lifetime; firing there
      ;; spends a summary on every ordinary reading pause. The midpoint of the
      ;; two bounds is the estimate; the shortest miss is when it is cold.
      (and (pricing/priced? spec) (:hit-max learned) (:miss-min learned))
      {:warm-s (/ (+ (:hit-max learned) (:miss-min learned)) 2)
       :cold-s (:miss-min learned)})))

(def default-profiles
  "DeepSeek and Groq keep caches for hours: the gap this exists for rarely
   comes, and a summary would cost fidelity to save little."
  {"deepseek" {"class" "none"}
   "groq"     {"class" "none"}})

(defn create
  "The extension's handlers over an injected clock and timers (tests drive
   them by hand). Returns {:handlers {event fn} :stop fn}."
  [api cfg {:keys [now set-timeout clear-timeout]}]
  (let [min-tokens  (:min-tokens cfg)
        fire-at     (:fire-at cfg)
        ;; A user's profile adds to the defaults, rather than replacing them.
        profiles    (merge default-profiles (:profiles cfg))
        kv-cfg      (:kv-cache (shared/load-config))
        store       (.-state api)
        learned     (atom (or (try (some-> store (.get "learned")) (catch :default _ nil)) {}))
        epoch       (atom 0)
        timers      (atom [])
        step-start  (atom nil)
        last-touch  (atom nil)
        first-gap   (atom nil)
        ;; The model THIS run's request went to, for learning. `spec` is only
        ;; known at the run's end, so it still names the previous run's model
        ;; when the first step (the one that measures the gap) finishes.
        req-model   (atom nil)
        model-key   (fn [m] (str (if (string? m) m (some-> m .-modelId))))
        compacted?  (atom false)
        spec        (atom nil)
        ;; Models whose cache has served a read this session. The dialect alone
        ;; is not proof: a gateway can accept breakpoints and never serve them
        ;; (kv_cache gives up on those), and there a summary pays full price.
        served      (atom #{})
        ;; Models whose prefix did NOT survive the summary turn (see
        ;; compaction/last-prefix-read): there a summary costs the prefix.
        unkept      (atom #{})
        cancel!     (fn []
                      (swap! epoch inc)
                      (doseq [t @timers] (clear-timeout t))
                      (reset! timers []))
        later!      (fn [ms f]
                      (let [t (set-timeout (max 0 ms) f)]
                        (when (and t (.-unref t)) (.unref t))
                        (swap! timers conj t)))
        same-conv?  (fn [{:keys [n last-msg]}]
                      (let [msgs (:messages (.getState api))]
                        (and (= n (count msgs)) (identical? last-msg (last msgs)))))
        apply!      (fn [ctx ep conv summary]
                      ^:async
                      (fn []
                        (when (and (= ep @epoch) (same-conv? conv))
                          (try
                            (let [r (js-await (.compact ctx #js {"force?" true "summary" summary
                                                                 "trigger" "idle"}))]
                              (when (and r (some-> ctx .-ui .-available))
                                (.notify (.-ui ctx)
                                         (str "Compacted while idle — the prompt cache had expired"
                                              " (~" (:before r) " → ~" (:after r) " tokens)")
                                         "info")))
                            (catch :default e
                              (d/warn "idle-compaction" (str "apply failed: " (.-message e))))))))
        precompute! (fn [ctx ep win]
                      ^:async
                      (fn []
                        (when (= ep @epoch)
                          (let [msgs    (:messages (.getState api))
                                conv    {:n (count msgs) :last-msg (last msgs)}
                                started (now)
                                ;; The request reads the cache as it starts, cancelled
                                ;; or not, so the clock restarts here.
                                _       (reset! last-touch started)
                                r       (try
                                          (js-await (.compact ctx #js {"force?" true "summarize-only?" true
                                                                       "trigger" "idle"}))
                                          (catch :default e
                                            (d/warn "idle-compaction" (str "summary failed: " (.-message e)))
                                            nil))]
                            (when (some-> (:prefix-read r) (< 0.5))
                              (d/info "idle-compaction"
                                      (str "summary read " (js/Math.round (* 100 (:prefix-read r)))
                                           "% from cache on " @spec " — not arming there again"))
                              (swap! unkept conj (str @spec)))
                            (when (and (= ep @epoch) (:summary r) (same-conv? conv))
                              (later! (- (+ started (* 1000 (:cold-s win)) margin-ms) (now))
                                      (apply! ctx ep conv (:summary r))))))))
        handlers
        {"before_provider_request"
         (fn [cfg _ctx]
           (cancel!)
           (reset! req-model (model-key (some-> cfg .-model)))
           (let [t (now)]
             (reset! first-gap (when @last-touch (/ (- t @last-touch) 1000)))
             (reset! step-start t))
           nil)

         "turn_end"
         (fn [step _ctx]
           (when-let [gap @first-gap]
             (reset! first-gap nil)
             ;; After a compaction the prefix is new: a miss says nothing
             ;; about how long the cache lives.
             (if @compacted?
               (reset! compacted? false)
               (when-let [u (some-> step .-usage)]
                 (let [read  (or (some-> u .-inputTokenDetails .-cacheReadTokens)
                                 (.-cachedInputTokens u) 0)
                       k     (str @req-model)
                       old   (get @learned k)
                       new   (learn old gap read (or (.-inputTokens u) 0))]
                   (when (and (seq k) (not= old new))
                     (swap! learned assoc k new)
                     (try (some-> store (.set "learned" (clj->js @learned)))
                          (catch :default _ nil)))))))
           (reset! last-touch @step-start)
           (reset! step-start (now))
           nil)

         "after_provider_request"
         (fn [evt _ctx]
           (reset! spec (.-model evt))
           (when (pos? (or (.-cachedTokens evt) 0))
             (swap! served conj (str (.-model evt))))
           nil)

         "compact"
         (fn [_ _ctx] (reset! compacted? true) nil)

         "agent_start" (fn [_ _ctx] (cancel!) nil)

         "agent_end"
         (fn [_ ctx]
           (cancel!)
           (let [st   (.getState api)
                 snap (:last-request st)
                 ;; compaction.enabled false turns this off too.
                 on?  (not (false? (get (when (.-settings api) (.settings api "compaction")) "enabled")))
                 win  (when (and on? snap @last-touch @spec (contains? @served (str @spec))
                                 (not (contains? @unkept (str @spec)))
                                 (>= (or (:last-input-tokens st) 0) min-tokens))
                        (window {:spec            @spec
                                 :model           (.-model (:base snap))
                                 :profiles        profiles
                                 :kv-ttl          (:cache-ttl kv-cfg)
                                 :extra-providers (:extra-providers kv-cfg)
                                 :learned         (get @learned (model-key (.-model (:base snap))))}))]
             (when win
               (later! (- (+ @last-touch (* 1000 fire-at (:warm-s win))) (now))
                       (precompute! ctx @epoch win))))
           nil)}]
    {:handlers handlers :stop cancel!}))

(defn activate [api]
  (let [cfg (:idle-compaction (shared/load-config))]
    (if-not (:enabled cfg)
      (fn [] nil)
      (let [{:keys [handlers stop]}
            (create api cfg {:now           #(js/Date.now)
                             :set-timeout   (fn [ms f] (js/setTimeout f ms))
                             :clear-timeout js/clearTimeout})]
        (doseq [[ev h] handlers] (.on api ev h))
        (fn []
          (stop)
          (doseq [[ev h] handlers] (.off api ev h)))))))
