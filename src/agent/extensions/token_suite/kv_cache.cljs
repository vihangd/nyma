(ns agent.extensions.token-suite.kv-cache
  "Prompt caching via cache_control breakpoints.

   Two layers:

   1. System-prompt split — splits the system at a stable/dynamic
      boundary (last ## heading or --- in the first 80%) and wraps the
      stable section with provider-specific cacheControl.

   2. Message-level breakpoints — places cache_control on the last
      complete assistant turn (anchor) and optionally every K turns
      back (checkpoint). This keeps multi-turn sessions inside
      Anthropic's 20-block lookback window so cache hits compound
      instead of degrading.

   Provider routing via shared/detect-cache-provider-for-model, which keys off
   the AI SDK model's `provider` tag rather than its id:
     - anthropic.messages, claude-native → providerOptions.anthropic.cacheControl
     - google.generative-ai              → providerOptions.google.cacheControl
     - everything else                   → no breakpoints
     - extras via settings               → e.g. minimax via Anthropic-compat

   Keying off the id was wrong for any Claude model served over an
   OpenAI-compatible endpoint (a relay, an Anthropic-compat shim): the id starts
   `claude`, so it looked cacheable, but @ai-sdk/openai drops
   providerOptions.anthropic, so the breakpoints were spent and never sent.

   Anthropic enforces a 4-breakpoint limit per request:
     slot 1: system stable section
     slot 2: last stable assistant turn (anchor)
     slot 3: N-back checkpoint
     slot 4: reserved for future (tool-block caching)"
  (:require [agent.debug :as d]
            [agent.pricing :as pricing]
            [agent.extensions.token-suite.shared :as shared]))

(def max-unserved
  "Consecutive turns of \"we annotated breakpoints and got no read back\" before
   we stop annotating for that provider+model.

   A cache write costs more than an ordinary input token — on the openlux-kiro
   preset, 0.221 per million against 0.018 for a read — so annotating into a
   cache that never serves is a recurring premium for nothing. Kiro-style relays
   prepend a per-request timestamp to the block they forward, which invalidates
   the prefix every turn, and no amount of retrying fixes it from our side.

   Session-scoped and gateway-only: a relay that stops doing this starts working
   again on the next run, and a first-party provider is never second-guessed."
  3)

(defn- split-at-stable-boundary
  "Split system prompt into stable (cacheable) and dynamic sections.
   Heuristic: look for the last major separator (---  or ## heading)
   in the first 80% of the text to find the stable/dynamic boundary."
  [system-text]
  (let [len         (count system-text)
        ;; The loop marks its per-turn tail with an explicit `---` boundary
        ;; (agent.loop/volatile-boundary). When present, that is THE split:
        ;; everything before it is stable by construction, however long the
        ;; tail is — the 80% heuristic below would put the breakpoint too
        ;; early for a short tail and too late for a long one.
        explicit    (.lastIndexOf system-text "\n\n---\n\n")
        boundary    (js/Math.floor (* len 0.8))
        search-text (.substring system-text 0 boundary)
        last-sep    (max (.lastIndexOf search-text "\n---\n")
                         (.lastIndexOf search-text "\n## "))
        split-idx   (cond
                      (> explicit 100)  (+ explicit 2)
                      (> last-sep 100)  (+ last-sep 1)
                      :else             boundary)]
    {:stable  (.substring system-text 0 split-idx)
     :dynamic (.substring system-text split-idx)}))

(defn- provider-key
  "Returns the JS key for providerOptions for the given provider keyword."
  [provider]
  (case provider :anthropic "anthropic" :google "google" nil))

(defn- annotate-message!
  "Mutate a JS message in place: add providerOptions.<provider>.cacheControl
   = {type: 'ephemeral'}. Preserves any existing providerOptions for other
   providers."
  [msg provider]
  (when-let [pkey (provider-key provider)]
    (let [existing (or (.-providerOptions msg) #js {})
          ppart    (or (aget existing pkey) #js {})]
      (aset ppart "cacheControl" #js {:type "ephemeral"})
      (aset existing pkey ppart)
      (aset msg "providerOptions" existing))))

(defn- assistant-msg?
  "True when the JS message is an assistant turn with non-empty content.
   Excludes streaming-empty turns and orphaned tool_use messages."
  [msg]
  (and (= "assistant" (.-role msg))
       (let [c (.-content msg)]
         (cond
           (string? c)            (pos? (count c))
           (js/Array.isArray c)   (pos? (.-length c))
           :else                  (some? c)))))

(defn- find-last-stable-anchor
  "Walk messages from the end, return index of the last stable assistant
   turn. Skips empty/streaming messages. Returns nil if none found."
  [messages]
  (let [n (.-length messages)]
    (loop [i (dec n)]
      (cond
        (< i 0)                          nil
        (assistant-msg? (aget messages i)) i
        :else                            (recur (dec i))))))

(defn- find-checkpoint-indices
  "From the anchor going back, every `every-turns` assistant turns place
   a checkpoint. Returns up to `max-checkpoints` indices, ordered nearest-to-anchor first."
  [messages anchor-idx every-turns max-checkpoints]
  (if (or (nil? anchor-idx) (nil? every-turns) (<= every-turns 0) (<= max-checkpoints 0))
    []
    (loop [acc        []
           i          (dec anchor-idx)
           since-last 1]
      (cond
        (or (< i 0) (>= (count acc) max-checkpoints)) acc
        (assistant-msg? (aget messages i))
        (if (>= since-last every-turns)
          (recur (conj acc i) (dec i) 1)
          (recur acc (dec i) (inc since-last)))
        :else (recur acc (dec i) since-last)))))

(defn- annotate-messages!
  "Walk st-config.messages, place cache_control on anchor + checkpoints.
   Mutates the messages array in place. Returns the count of breakpoints
   placed (for stats / testing)."
  [config-obj provider every-turns max-message-breakpoints]
  (let [messages (.-messages config-obj)]
    (if (or (nil? messages) (zero? (.-length messages)))
      0
      (let [anchor (find-last-stable-anchor messages)
            ;; Reserve 1 slot for anchor; remainder for checkpoints.
            ckpt-budget (max 0 (dec max-message-breakpoints))
            checkpoints (find-checkpoint-indices messages anchor every-turns ckpt-budget)
            indices     (cond-> [] anchor (conj anchor) :always (into checkpoints))]
        (doseq [i indices]
          (annotate-message! (aget messages i) provider))
        (count indices)))))

(defn activate [api]
  (let [config            (shared/load-config)
        kv-config         (:kv-cache config)
        prev-hash         (atom nil)
        ;; consecutive annotated-but-unserved turns, and the model we gave up on.
        ;; Keyed by model id because that is all the annotate side has; the
        ;; gateway test uses the provider-qualified key from the usage event,
        ;; which is the only place the provider name is available without the
        ;; `model` capability (that one also grants setModel — too much for a
        ;; token-accounting extension).
        ;; Per model, both of them. One counter and one flag for the whole
        ;; session meant switching models carried the previous one's misses
        ;; over, and — because the give-up guard tested that single flag — once
        ;; anything was abandoned nothing else ever could be.
        unserved          (atom {})
        given-up          (atom #{})
        gateway-spec?     (fn [spec]
                            (let [pname (first (.split (str spec) "/"))]
                              (contains? @pricing/unpriced-providers (str pname))))
        ;; Did the LAST provider request place breakpoints the cache should
        ;; serve — a Layer-1 prefix stable vs the previous request, or Layer-2
        ;; message breakpoints on a request that ALSO annotated previously?
        ;; Only then is a zero-cache-read response a real miss worth surfacing.
        expected-hit?     (atom false)
        ;; Did the previous request place ANY breakpoints (either layer)?
        prev-annotated?   (atom false)
        extra-providers   (or (:extra-providers kv-config) {})
        cache-messages?   (boolean (:cache-messages kv-config))
        every-turns       (or (:checkpoint-every-turns kv-config) 4)
        max-msg-breaks    (or (:max-message-breakpoints kv-config) 2)

        on-before
        (fn [config-obj _ctx]
          ;; Reset FIRST: a stale true must not survive a request that skips
          ;; annotation (provider switch, shrunk system prompt) or a blocked
          ;; request whose after_provider_request never fired.
          (reset! expected-hit? false)
          (let [model     (.-model config-obj)
                model-id  (str (some-> model .-modelId))
                ;; Stop annotating once this model has shown, repeatedly, that our
                ;; breakpoints are written and never served. Switching models
                ;; clears it: the next one has not earned the doubt.
                give-up?  (contains? @given-up model-id)
                ;; Route on the model's PROVIDER, not its id: a `claude-*` model
                ;; reached over an OpenAI-compatible endpoint cannot carry
                ;; cache_control at all, and annotating it burned breakpoints
                ;; that never reached the wire.
                provider  (shared/detect-cache-provider-for-model model extra-providers)
                system    (.-system config-obj)
                annotated (atom false)]

            (when (and provider (:enabled kv-config) (not give-up?))

              ;; Layer 1: system prompt split (only when string + above threshold)
              (when (and (string? system)
                         (> (.estimateTokens api system) (:min-system-tokens kv-config)))
                (let [{:keys [stable dynamic]} (split-at-stable-boundary system)
                      hash         (js/Bun.hash stable)
                      pkey         (provider-key provider)]
                  (when (= hash @prev-hash) (reset! expected-hit? true))
                  (reset! prev-hash hash)
                  (reset! annotated true)
                  (aset config-obj "system"
                        #js [#js {:role "system"
                                  :content stable
                                  :providerOptions
                                  (doto #js {}
                                    (aset pkey #js {:cacheControl #js {:type "ephemeral"}}))}
                             #js {:role "system" :content dynamic}])))

              ;; Layer 2: message-level breakpoints (anchor + N-back checkpoint).
              ;; Breakpoints on consecutive requests should also serve reads —
              ;; without this, Layer-2-only configs pay cache-write premiums
              ;; forever with zero reads and no miss ever surfaces.
              (when cache-messages?
                (when (pos? (annotate-messages! config-obj provider every-turns max-msg-breaks))
                  (when @prev-annotated? (reset! expected-hit? true))
                  (reset! annotated true))))

            (reset! prev-annotated? @annotated))

          ;; Mutations in place — no return value
          nil)

        on-compact
        (fn [_data _ctx]
          (reset! expected-hit? false)
          (reset! prev-annotated? false)
          (reset! prev-hash nil)
          nil)

        on-after
        (fn [event _ctx]
          (let [cached (.-cachedTokens event)
                turn   (or (.-turnCount event) 0)
                miss?  (and @expected-hit? (or (nil? cached) (zero? cached)))]
            (reset! expected-hit? false)
            (when miss?
              (d/info "kv-cache" (str "cache miss on turn " turn
                                      " — breakpoints annotated but not served")))
            ;; A gateway that never serves what we wrote is charging us a write
            ;; premium for nothing. Count consecutive occurrences and stop.
            ;; Split on the FIRST slash only: the key is "<provider>/<id>" and a
            ;; relayed id carries slashes of its own ("anthropic/claude-sonnet-5",
            ;; "qwen/qwen3.8-27b" both ship in presets). Taking the last segment
            ;; produced a model id that never matched the one the annotate side
            ;; sees, so the warning fired and annotation carried on at full
            ;; write cost.
            (let [spec  (str (.-model event))
                  slash (.indexOf spec "/")
                  mid   (if (neg? slash) spec (.slice spec (inc slash)))]
              (when (gateway-spec? spec)
                (if miss?
                  (let [n (inc (get @unserved mid 0))]
                    (swap! unserved assoc mid n)
                    (when (and (>= n max-unserved) (not (contains? @given-up mid)))
                      (swap! given-up conj mid)
                      (d/warn-quiet
                       "kv-cache"
                       (str "no cache read served on " spec " after " n
                            " annotated turns — writing breakpoints there costs more than"
                            " it saves, so they are off for the rest of this session"))))
                  (swap! unserved dissoc mid))))
            (swap! shared/suite-stats update :kv-cache
                   (fn [s] (-> s
                               (update :turns inc)
                               (update :cached-tokens + (or cached 0))
                               ;; Read tokens against total input is what "the
                               ;; cache is working" actually means; hit/miss
                               ;; turn counts say nothing about proportion.
                               (update :input-tokens (fnil + 0) (or (.-inputTokens event) 0))
                               (update :written-tokens (fnil + 0) (or (.-cacheWriteTokens event) 0))
                               (assoc :abandoned? (boolean (seq @given-up)))
                               (update :cache-misses (fnil + 0) (if miss? 1 0))
                               (update :cache-hits + (if (and cached (pos? cached)) 1 0)))))))]

    ;; Compaction replaces early history, and the cache is a PREFIX match — so
    ;; everything after the change is invalidated and the next request is a
    ;; guaranteed full miss. Expected, not anomalous. Clearing the expectation
    ;; here keeps that miss out of :cache-misses, which otherwise reports a
    ;; problem that is really just the cost of compacting. A diagnostic that
    ;; cries wolf is worse than none.
    (.on api "compact" on-compact)

    ;; before_provider_request — priority 100 (runs last so we see the
    ;; final form of messages from any earlier mutators).
    (.on api "before_provider_request" on-before 100)

    ;; after_provider_request — track cache metrics + surface misses. Only a
    ;; request where WE placed breakpoints the cache should serve counts as a
    ;; miss; everything else misses by design and stays quiet — a per-turn
    ;; false alarm trains users to ignore the one notice that matters.
    (.on api "after_provider_request" on-after)

    ;; Deactivate: unregister the handlers. (Resetting the closure atoms was
    ;; dead code — a fresh activate creates fresh atoms — while a leaked
    ;; handler double-annotates prompts and double-counts stats on reload.)
    (fn []
      (.off api "before_provider_request" on-before)
      (.off api "after_provider_request" on-after)
      (.off api "compact" on-compact))))
