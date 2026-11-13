(ns idle-compaction.test
  "Idle compaction: summarise on the warm cache, apply once it has expired,
   drop it if the user comes back first."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.pricing :as pricing]
            [agent.extensions.token-suite.idle-compaction :as ic]))

(def anthropic-model #js {:modelId "claude-x" :provider "anthropic.messages"})
(def openai-model #js {:modelId "glm" :provider "zai.chat"})

(describe "learn" (fn []
                    (it "a hit raises the warm bound, a later miss sets the cold one"
                        (fn []
                          (let [l (-> {}
                                      (ic/learn 30 9000 10000)
                                      (ic/learn 200 6000 10000)
                                      (ic/learn 900 0 10000))]
                            (-> (expect (:hit-max l)) (.toBe 200))
                            (-> (expect (:miss-min l)) (.toBe 900)))))

                    (it "ignores small requests, short gaps, and misses inside a known-warm gap"
                        (fn []
                          (-> (expect (ic/learn {} 600 0 100)) (.toEqual {}))
                          (-> (expect (ic/learn {} 30 0 10000)) (.toEqual {}))
                          (-> (expect (:miss-min (ic/learn {:hit-max 500} 400 0 10000))) (.toBeUndefined))))

                    (it "a hit beyond the cold bound discards it (the miss was a prefix change)"
                        (fn []
                          (-> (expect (:miss-min (ic/learn {:hit-max 100 :miss-min 300} 400 9000 10000)))
                              (.toBeUndefined))))))

(describe "window" (fn []
                     (it "Anthropic dialect: the configured cache ttl"
                         (fn []
                           (-> (expect (ic/window {:spec "anthropic/claude-x" :model anthropic-model}))
                               (.toEqual {:warm-s 300 :cold-s 300}))
                           (-> (expect (ic/window {:spec "anthropic/claude-x" :model anthropic-model :kv-ttl "1h"}))
                               (.toEqual {:warm-s 3600 :cold-s 3600}))))

                     (it "a profile fixes or disables it"
                         (fn []
                           (-> (expect (ic/window {:spec "zai/glm" :model openai-model :profiles {"zai" {"ttl-s" 600}}}))
                               (.toEqual {:warm-s 600 :cold-s 600}))
                           (-> (expect (ic/window {:spec "anthropic/claude-x" :model anthropic-model
                                                   :profiles {"anthropic" {"class" "none"}}}))
                               (.toBeFalsy))))

                     (it "anything else needs a price and both learned bounds"
                         (fn []
                           (swap! pricing/token-costs assoc "zai/glm" [1 1])
                           (try
                             (-> (expect (ic/window {:spec "zai/glm" :model openai-model :learned {:hit-max 200}}))
                                 (.toBeFalsy))
                             (-> (expect (ic/window {:spec "zai/glm" :model openai-model
                                                     :learned {:hit-max 200 :miss-min 900}}))
                                 (.toEqual {:warm-s 550 :cold-s 900}))
          ;; Unpriced (a local server): never.
                             (-> (expect (ic/window {:spec "omlx/q" :model openai-model
                                                     :learned {:hit-max 200 :miss-min 900}}))
                                 (.toBeFalsy))
                             (finally (swap! pricing/token-costs dissoc "zai/glm")))))))

;;; ─── the timers ───────────────────────────────────────────────────────────

(defn- rig
  "Handlers over a fake clock. `state` is what api.getState returns."
  [state]
  (let [clock  (atom 1000000)
        timers (atom {})
        ids    (atom 0)
        calls  (atom [])
        saved  (atom nil)
        api    #js {:getState (fn [] @state)
                    :state    #js {:get (fn [_] nil) :set (fn [_ v] (reset! saved v))}}
        ctx    #js {:ui #js {:available false}
                    :compact (fn [opts]
                               (swap! calls conj opts)
                               (js/Promise.resolve
                                (if (aget opts "summarize-only?")
                                  {:summary "SUM" :before 1}
                                  {:before 200000 :after 20000})))}
        {:keys [handlers]}
        (ic/create api {:min-tokens 100000 :fire-at 0.9 :profiles {}}
                   {:now           (fn [] @clock)
                    :set-timeout   (fn [ms f] (let [id (swap! ids inc)]
                                                (swap! timers assoc id {:at (+ @clock ms) :f f})
                                                id))
                    :clear-timeout (fn [id] (swap! timers dissoc id))})
        fire!  (fn []
                 ;; Run the earliest timer, advancing the clock to it.
                 (let [[id {:keys [at f]}] (first (sort-by (comp :at val) @timers))]
                   (swap! timers dissoc id)
                   (reset! clock at)
                   (f)))]
    {:h handlers :ctx ctx :calls calls :clock clock :timers timers :fire! fire!
     :api api :saved saved}))

(defn- run-turn!
  "One run: request, one step, usage, end."
  [{:keys [h ctx clock]} spec]
  ((get h "before_provider_request") #js {} ctx)
  (swap! clock + 2000)
  ((get h "turn_end") #js {:usage #js {:inputTokens 150000}} ctx)
  ((get h "after_provider_request") #js {:model spec :cachedTokens 120000} ctx)
  ((get h "agent_end") #js {} ctx))

(defn- state-for [model]
  (let [msgs [{:role "user" :content "q"} {:role "assistant" :content "a"}]]
    (atom {:messages msgs :last-input-tokens 150000
           :last-request {:base #js {:model model}}})))

(describe "idle timers" (fn []
                          (it "summarises before expiry, applies after it"
                              ^:async (fn []
                                        (let [r (rig (state-for anthropic-model))]
                                          (run-turn! r "anthropic/claude-x")
                                          (-> (expect (count @(:timers r))) (.toBe 1))
          ;; 0.9 x 300s after the last request started
                                          (let [at (:at (first (vals @(:timers r))))]
                                            (-> (expect (- at 1000000)) (.toBe 270000)))
                                          (js-await ((:fire! r)))
                                          (-> (expect (aget (first @(:calls r)) "summarize-only?")) (.toBe true))
          ;; apply: 300s after the summary call (a read refreshes the cache) + margin
                                          (let [at (:at (first (vals @(:timers r))))]
                                            (-> (expect (- at @(:clock r))) (.toBe 305000)))
                                          (js-await ((:fire! r)))
                                          (let [applied (second @(:calls r))]
                                            (-> (expect (aget applied "summary")) (.toBe "SUM"))
                                            (-> (expect (aget applied "trigger")) (.toBe "idle"))))))

                          (it "a returning user cancels it, and the summary is never applied"
                              ^:async (fn []
                                        (let [r (rig (state-for anthropic-model))]
                                          (run-turn! r "anthropic/claude-x")
                                          (js-await ((:fire! r)))
                                          ((get (:h r) "agent_start") #js {} (:ctx r))
                                          (-> (expect (count @(:timers r))) (.toBe 0))
                                          (-> (expect (count @(:calls r))) (.toBe 1)))))

                          (it "a conversation that changed in the meantime is not touched"
                              ^:async (fn []
                                        (let [st (state-for anthropic-model)
                                              r  (rig st)]
                                          (run-turn! r "anthropic/claude-x")
                                          (js-await ((:fire! r)))
                                          (swap! st update :messages conj {:role "user" :content "more"})
                                          (js-await ((:fire! r)))
                                          (-> (expect (count @(:calls r))) (.toBe 1)))))

                          (it "small contexts and unknown providers never arm"
                              (fn []
                                (let [st (state-for anthropic-model)
                                      r  (rig st)]
                                  (swap! st assoc :last-input-tokens 5000)
                                  (run-turn! r "anthropic/claude-x")
                                  (-> (expect (count @(:timers r))) (.toBe 0)))
                                (let [r (rig (state-for openai-model))]
                                  (run-turn! r "omlx/q")
                                  (-> (expect (count @(:timers r))) (.toBe 0)))))))

(describe "idle timers need a cache that serves" (fn []
  (it "a model that never read from its cache is not armed"
      (fn []
        (let [{:keys [h ctx clock timers]} (rig (state-for anthropic-model))]
          ((get h "before_provider_request") #js {} ctx)
          (swap! clock + 2000)
          ((get h "turn_end") #js {:usage #js {:inputTokens 150000}} ctx)
          ((get h "after_provider_request") #js {:model "openlux-kiro/claude-x" :cachedTokens 0} ctx)
          ((get h "agent_end") #js {} ctx)
          (-> (expect (count @timers)) (.toBe 0)))))))

(describe "a prefix that does not survive" (fn []
  (it "stops arming on a model whose summary barely read the cache"
      ^:async (fn []
        (let [r (rig (state-for anthropic-model))]
          (aset (:ctx r) "compact" (fn [_] (js/Promise.resolve {:summary "SUM" :prefix-read 0.47})))
          (run-turn! r "anthropic/claude-x")
          (js-await ((:fire! r)))
          (reset! (:timers r) {})
          (run-turn! r "anthropic/claude-x")
          (-> (expect (count @(:timers r))) (.toBe 0)))))))

(defn- first-step!
  "A run whose first request goes to `model-id` and reads `read` of 10k."
  [{:keys [h ctx clock]} model-id read]
  ((get h "before_provider_request") #js {:model #js {:modelId model-id}} ctx)
  (swap! clock + 1000)
  ((get h "turn_end") #js {:usage #js {:inputTokens 10000
                                       :inputTokenDetails #js {:cacheReadTokens read}}} ctx))

(describe "review fixes" (fn []
  (it "learning is filed under the model the request went to, not the last run's"
      (fn []
        (let [r (rig (state-for openai-model))]
          (first-step! r "model-a" 0)
          ((get (:h r) "after_provider_request") #js {:model "p/model-a"} (:ctx r))
          (swap! (:clock r) + 120000)
          (first-step! r "model-b" 9000)
          (-> (expect (get-in @(:saved r) ["model-b" "hit-max"])) (.toBe 121))
          (-> (expect (aget @(:saved r) "model-a")) (.toBeUndefined)))))

  (it "compaction.enabled false stops it arming"
      (fn []
        (let [r (rig (state-for anthropic-model))]
          (aset (:api r) "settings" (fn [sec] (if (= sec "compaction") {"enabled" false} {})))
          (run-turn! r "anthropic/claude-x")
          (-> (expect (count @(:timers r))) (.toBe 0)))))

  (it "the summary call restarts the cache clock when it starts, even if cancelled"
      ^:async (fn []
        (let [r    (rig (state-for anthropic-model))
              done (atom nil)]
          (aset (:ctx r) "compact" (fn [_] (js/Promise. (fn [res] (reset! done res)))))
          (run-turn! r "anthropic/claude-x")
          (let [p ((:fire! r))]
            ;; the user is back 10s into the summary call; it reads the cache
            (swap! (:clock r) + 10000)
            ((get (:h r) "agent_start") #js {} (:ctx r))
            (first-step! r "claude-x" 9000)
            (@done {:summary "S"})
            (js-await p)
            ;; the gap is measured from the summary call, not the turn before it
            (-> (expect (get-in @(:saved r) ["claude-x" "hit-max"])) (.toBe 10))))))))
