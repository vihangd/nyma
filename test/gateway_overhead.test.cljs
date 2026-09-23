(ns gateway-overhead.test
  "A relay that injects its own system prompt makes our token estimate silently
   low, and the only signal is that the provider bills more input than our
   content should cost. `:overhead-tokens` was the manual compensation for that
   and no shipped preset set it, so it was 0 everywhere.

   Separately, a gateway that never serves the cache reads we paid to write is
   charging a write premium for nothing — on the openlux-kiro preset a write is
   0.221 per million against 0.018 for a read.

   Both behaviours are limited to GATEWAY providers, which `pricing/unpriced-providers`
   already marks; a first-party provider must be untouched by either."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.core :refer [create-agent]]
            [agent.pricing :as pricing]
            [agent.token-estimation :as te]
            [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.kv-cache :as kv-cache]
            [agent.extensions :refer [create-extension-api]]))

(beforeEach (fn []
              (te/reset-overhead!)
              (reset! pricing/unpriced-providers #{})
              (swap! shared/suite-stats assoc :kv-cache
                     {:turns 0 :cache-hits 0 :cached-tokens 0})
              nil))

;;; ─── the observed residual ────────────────────────────────────

(describe "gateway overhead: observation" (fn []

                                            (it "needs several samples before it will say anything"
                                                (fn []
                                                  (te/record-overhead! "relay/m" 5000 1000)
                                                  (-> (expect (te/overhead-for "relay/m")) (.toBeUndefined))
                                                  (te/record-overhead! "relay/m" 5000 1000)
                                                  (te/record-overhead! "relay/m" 5000 1000)
                                                  (-> (expect (te/overhead-for "relay/m")) (.toBe 4000))))

                                            (it "is the mean of the residuals, not the last one"
                                                (fn []
                                                  (doseq [[reported content] [[5000 1000] [7000 1000] [6000 1000]]]
                                                    (te/record-overhead! "relay/m" reported content))
        ;; residuals 4000 / 6000 / 5000
                                                  (-> (expect (te/overhead-for "relay/m")) (.toBe 5000))))

                                            (it "clamps a turn where our estimate came out high, and still counts it"
                                                (fn []
        ;; the content estimate is a heuristic and lands on both sides of the
        ;; truth; keeping only the turns where it undershot could only ever
        ;; drift the figure up
                                                  (te/record-overhead! "relay/m" 900 1000)
                                                  (te/record-overhead! "relay/m" 1000 1000)
                                                  (te/record-overhead! "relay/m" 3000 1000)
        ;; residuals 0 / 0 / 2000
                                                  (-> (expect (te/overhead-for "relay/m")) (.toBe 667))))

                                            (it "a provider that injects nothing converges on nothing"
                                                (fn []
                                                  (dotimes [_ 5] (te/record-overhead! "clean/m" 1000 1000))
                                                  (-> (expect (te/overhead-for "clean/m")) (.toBe 0))))

                                            (it "keys per provider-qualified model, so two relays do not blend"
                                                (fn []
                                                  (dotimes [_ 3] (te/record-overhead! "kiro/claude" 5000 1000))
                                                  (dotimes [_ 3] (te/record-overhead! "codex/gpt" 3000 1000))
                                                  (-> (expect (te/overhead-for "kiro/claude")) (.toBe 4000))
                                                  (-> (expect (te/overhead-for "codex/gpt")) (.toBe 2000))))

                                            (it "a missing or malformed key records nothing"
                                                (fn []
                                                  (te/record-overhead! nil 5000 1000)
                                                  (-> (expect (js/Object.keys @te/observed-overhead)) (.toEqual (clj->js [])))))))

;;; ─── the cache give-up ────────────────────────────────────────

(defn- make-config [model-id]
  #js {:model           #js {:modelId model-id}
       ;; long enough to clear min-system-tokens so layer 1 annotates
       :system          (.repeat "You are a careful assistant. " 400)
       :messages        #js []
       :tools           #js {}
       :providerOptions #js {}})

(defn- annotated? [config]
  (js/Array.isArray (.-system config)))

(defn ^:async drive
  "Activate kv-cache, then run `turns` of: annotate, then report a turn that
   served no cache read. Returns the final config so the caller can see whether
   annotation still happens."
  [provider-name turns & [model-id]]
  (let [mid    (or model-id "claude-sonnet-4-6")
        agent  (create-agent {:model #js {:modelId mid}
                              :system-prompt "s"})
        api    (create-extension-api agent)
        _d     (kv-cache/activate api)
        events (:events agent)
        emit-c (:emit-collect events)
        emit   (:emit events)]
    (loop [i 0 cfg nil]
      (if (>= i turns)
        cfg
        (let [c (make-config mid)]
          (js-await (emit-c "before_provider_request" c))
          (js-await (emit "after_provider_request"
                          #js {:model (str provider-name "/" mid)
                               :cachedTokens 0
                               :cacheWriteTokens 900
                               :inputTokens 1000
                               :turnCount (inc i)}))
          (recur (inc i) c))))))

(describe "gateway overhead: cache give-up" (fn []

                                              (it "stops annotating a gateway that never serves a read"
                                                  (fn []
                                                    (reset! pricing/unpriced-providers #{"openlux-kiro"})
            ;; well past the threshold; the first turn cannot count as a miss
            ;; because there is no previous hash to match yet
                                                    (-> (drive "openlux-kiro" (+ 3 kv-cache/max-unserved))
                                                        (.then (fn [cfg]
                                                                 (-> (expect (annotated? cfg)) (.toBe false))
                                                                 (-> (expect (:abandoned? (:kv-cache @shared/suite-stats))) (.toBe true)))))))

                                              (it "a FIRST-PARTY provider is never given up on, however bad it looks"
                                                  (fn []
            ;; unpriced-providers is empty, so anthropic is not a gateway
                                                    (-> (drive "anthropic" (+ 3 kv-cache/max-unserved))
                                                        (.then (fn [cfg]
                                                                 (-> (expect (annotated? cfg)) (.toBe true))
                                                                 (-> (expect (:abandoned? (:kv-cache @shared/suite-stats))) (.toBeFalsy)))))))

                                              (it "works for a relayed id that contains slashes of its own"
                                                  (fn []
            ;; "<provider>/<id>" where the id is itself "anthropic/claude-sonnet-5".
            ;; Splitting on the LAST slash gave "claude-sonnet-5", which never
            ;; matched the model the annotate side sees, so the warning fired and
            ;; annotation carried on at full write cost.
                                                    (reset! pricing/unpriced-providers #{"openlux"})
                                                    (-> (drive "openlux" (+ 3 kv-cache/max-unserved) "anthropic/claude-sonnet-5")
                                                        (.then (fn [cfg]
                                                                 (-> (expect (annotated? cfg)) (.toBe false)))))))

                                              (it "records the write cost and the read ratio"
                                                  (fn []
                                                    (reset! pricing/unpriced-providers #{"openlux-kiro"})
                                                    (-> (drive "openlux-kiro" 1)
                                                        (.then (fn [_]
                                                                 (let [kv (:kv-cache @shared/suite-stats)]
                ;; cache writes used to be dropped before the event, so
                ;; nothing downstream could see what a miss cost
                                                                   (-> (expect (:written-tokens kv)) (.toBe 900))
                                                                   (-> (expect (:input-tokens kv)) (.toBe 1000))
                                                                   (-> (expect (:cached-tokens kv)) (.toBe 0))))))))))
