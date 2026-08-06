(ns model-namespacing.test
  "Covers provider-qualified model keys.

   Two distinct bugs live here:
     1. `cost-model-id` stringified the resolved AI SDK model OBJECT, yielding
        \"[object Object]\", so every registry-resolved model reported $0.
     2. Context windows and prices were keyed by bare model id in a global map,
        so a relay carrying `claude-opus-5` overwrote anthropic's entry."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.model-info :refer [create-model-registry]]
            [agent.pricing :as pricing]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]))

;; ── model-cost-key ───────────────────────────────────────────

(describe "pricing/model-cost-key" (fn []
                                     (it "qualifies a resolved model object with the active provider"
                                         (fn []
                                           (-> (expect (pricing/model-cost-key
                                                        #js {"model" #js {:modelId "claude-opus-5"}
                                                             "active-provider-name" "yunwu"}))
                                               (.toBe "yunwu/claude-opus-5"))))

                                     (it "does not stringify the model object"
                                         (fn []
      ;; The whole bug: `(str model-object)` is "[object Object]".
                                           (-> (expect (pricing/model-cost-key
                                                        #js {"model" #js {:modelId "claude-opus-5"}
                                                             "active-provider-name" "yunwu"}))
                                               (.not.toContain "object Object"))))

                                     (it "falls back to the bare id when no provider is recorded"
                                         (fn []
                                           (-> (expect (pricing/model-cost-key
                                                        #js {"model" #js {:modelId "gpt-4o"}
                                                             "active-provider-name" ""}))
                                               (.toBe "gpt-4o"))))

                                     (it "passes a raw string spec through — the unknown-provider path"
                                         (fn []
                                           (-> (expect (pricing/model-cost-key #js {"model" "some/unknown-model"}))
                                               (.toBe "some/unknown-model"))))

                                     (it "returns an empty string rather than throwing on an empty config"
                                         (fn []
                                           (-> (expect (pricing/model-cost-key nil)) (.toBe ""))))))

;; ── calculate-cost end to end ────────────────────────────────

(describe "pricing/calculate-cost with qualified keys" (fn []
                                                         (it "prices a registry-resolved model — previously always $0"
                                                             (fn []
                                                               (let [key  (pricing/model-cost-key
                                                                           #js {"model" #js {:modelId "claude-opus-5"}
                                                                                "active-provider-name" "anthropic"})
                                                                     cost (pricing/calculate-cost key 1000000 0)]
        ;; claude-opus-5 input is $5/1M; resolution goes qualified -> bare.
                                                                 (-> (expect cost) (.toBe 5.0)))))

                                                         (it "still prices a bare id"
                                                             (fn []
                                                               (-> (expect (pricing/calculate-cost "claude-opus-5" 1000000 0)) (.toBe 5.0))))

                                                         (it "returns 0 for an unknown model"
                                                             (fn []
                                                               (-> (expect (pricing/calculate-cost "nope/nothing" 1000000 0)) (.toBe 0))))))

;; ── unpriced providers ───────────────────────────────────────

(describe "pricing/lookup-cost and unpriced providers" (fn []
                                                         (beforeEach (fn [] (reset! pricing/unpriced-providers #{}) nil))

                                                         (it "falls back to the bare id for an ordinary provider"
                                                             (fn []
                                                               (-> (expect (pricing/lookup-cost "anthropic/claude-opus-5"))
                                                                   (.toEqual #js [5.0 25.0]))))

                                                         (it "does NOT fall back for a provider marked unpriced"
                                                             (fn []
      ;; A relay charges its own rate; showing Anthropic's is worse than
      ;; showing nothing.
                                                               (swap! pricing/unpriced-providers conj "yunwu")
                                                               (-> (expect (pricing/lookup-cost "yunwu/claude-opus-5")) (.toBeNil))))

                                                         (it "still honours an explicitly registered rate for an unpriced provider"
                                                             (fn []
                                                               (swap! pricing/unpriced-providers conj "yunwu")
                                                               (swap! pricing/token-costs assoc "yunwu/some-model" [1.0 2.0])
                                                               (-> (expect (pricing/lookup-cost "yunwu/some-model"))
                                                                   (.toEqual #js [1.0 2.0]))))

                                                         (it "splits on the first slash only, so ids keep their own slashes"
                                                             (fn []
                                                               (swap! pricing/token-costs assoc "meta-llama/llama-3.3-70b" [0.1 0.2])
                                                               (-> (expect (pricing/lookup-cost "openrouter/meta-llama/llama-3.3-70b"))
                                                                   (.toEqual #js [0.1 0.2]))))))

;; ── model registry ───────────────────────────────────────────

(describe "model registry qualified lookup" (fn []
                                              (it "resolves a provider/id key via the bare entry"
                                                  (fn []
                                                    (let [reg (create-model-registry)]
                                                      (-> (expect ((:context-window reg) "anthropic/claude-opus-5"))
                                                          (.toBe 1000000)))))

                                              (it "prefers an exact qualified entry over the bare one"
                                                  (fn []
                                                    (let [reg (create-model-registry)]
                                                      ((:register reg) {"relay/claude-opus-5" {:context-window 128000}})
                                                      (-> (expect ((:context-window reg) "relay/claude-opus-5")) (.toBe 128000))
        ;; The bare entry is untouched.
                                                      (-> (expect ((:context-window reg) "claude-opus-5")) (.toBe 1000000)))))

                                              (it "keeps two providers' entries for the same model id distinct"
                                                  (fn []
                                                    (let [reg (create-model-registry)]
                                                      ((:register reg) {"a/shared-model" {:context-window 111}
                                                                        "b/shared-model" {:context-window 222}})
                                                      (-> (expect ((:context-window reg) "a/shared-model")) (.toBe 111))
                                                      (-> (expect ((:context-window reg) "b/shared-model")) (.toBe 222)))))))

;; ── registerProvider wiring ──────────────────────────────────

(defn- make-api []
  (let [agent (create-agent #js {})]
    {:agent agent :api (create-extension-api agent "test-ext")}))

(describe "registerProvider model/pricing registration" (fn []
                                                          (beforeEach (fn [] (reset! pricing/unpriced-providers #{}) nil))

                                                          (it "registers an ordinary provider under both bare and qualified keys"
                                                              (fn []
                                                                (let [{:keys [agent api]} (make-api)]
                                                                  (.registerProvider api "plainco"
                                                                                     #js {:createModel (fn [id] id)
                                                                                          :models #js [#js {:id "m-1" :contextWindow 4242
                                                                                                            :cost #js {:input 1 :output 2}}]})
                                                                  (let [cw (:context-window (:model-registry agent))]
                                                                    (-> (expect (cw "m-1")) (.toBe 4242))
                                                                    (-> (expect (cw "plainco/m-1")) (.toBe 4242)))
                                                                  (-> (expect (get @pricing/token-costs "m-1")) (.toEqual #js [1 2]))
                                                                  (-> (expect (get @pricing/token-costs "plainco/m-1")) (.toEqual #js [1 2])))))

                                                          (it "a gateway registers ONLY qualified keys, so it can't clobber a vendor"
                                                              (fn []
                                                                (let [{:keys [agent api]} (make-api)
                                                                      cw (:context-window (:model-registry agent))
                                                                      before (cw "claude-opus-5")]
                                                                  (.registerProvider api "relayco"
                                                                                     #js {:createModel (fn [id] id)
                                                                                          :unpriced    true
                                                                                          :models #js [#js {:id "claude-opus-5" :contextWindow 8000}]})
        ;; The vendor's own entry survives.
                                                                  (-> (expect (cw "claude-opus-5")) (.toBe before))
                                                                  (-> (expect (cw "relayco/claude-opus-5")) (.toBe 8000))
                                                                  (-> (expect (contains? @pricing/unpriced-providers "relayco")) (.toBe true))
        ;; And the relayed model reports no price rather than the vendor's.
                                                                  (-> (expect (pricing/lookup-cost "relayco/claude-opus-5")) (.toBeNil)))))))
