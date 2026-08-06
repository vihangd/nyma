(ns model-namespacing.test
  "Covers provider-qualified model keys.

   Two distinct bugs live here:
     1. `cost-model-id` stringified the resolved AI SDK model OBJECT, yielding
        \"[object Object]\", so every registry-resolved model reported $0.
     2. Context windows and prices were keyed by bare model id in a global map,
        so a relay carrying `claude-opus-5` overwrote anthropic's entry."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.model-info :as model-info :refer [create-model-registry]]
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

                                              (it "does not leak one gateway model's window to its siblings"
                                                  (fn []
        ;; The prefix match compares the first 15 chars, and "yunwu-claude/" is
        ;; 13 of them — so qualified keys under one provider differ in as little
        ;; as two characters. Matching those would make a single declared
        ;; contextWindow silently apply to every unrecognized sibling.
                                                    (let [reg (create-model-registry)]
                                                      ((:register reg) {"yunwu-claude/claude-opus-5-thinking" {:context-window 200000}
                                                                        "yunwu-claude/claude-unknown-model"   {:context-window nil}})
                                                      (-> (expect ((:context-window reg) "yunwu-claude/claude-unknown-model"))
                                                          (.toBe 100000)))))

                                              (it "still fuzzy-matches an unrecognized dated id to its family"
                                                  (fn []
                                                    (let [reg (create-model-registry)]
        ;; Bare-to-bare matching is what this was written for, and it keeps
        ;; working through a provider prefix.
                                                      (-> (expect ((:context-window reg) "claude-sonnet-4-20250514-preview"))
                                                          (.toBe 200000))
                                                      (-> (expect ((:context-window reg) "relay/claude-sonnet-4-20250514-preview"))
                                                          (.toBe 200000)))))

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

;;; ─── One context-window answer, not four ────────────────────────
;;; The /model picker resolved the provider-qualified key while the loop,
;;; compaction, headroom and pi-rpc all resolved the bare model id. Model ids
;;; are not unique across providers, so the two disagreed — the picker showed
;;; 262144 for kimi/kimi-k2.5 while the loop planned against 100000.

(defn- registry-in-load-order
  "kimi registers before opencode-zen (extensions load alphabetically). Zen
   carries the same kimi-k2.5 id and declares NO window for it."
  []
  (let [reg (create-model-registry)]
    ((:register reg) {"kimi/kimi-k2.5" {:context-window 262144}
                      "kimi-k2.5"      {:context-window 262144}})
    ((:register reg) {"opencode-zen/kimi-k2.5" {:context-window nil}
                      "kimi-k2.5"              {:context-window nil}})
    reg))

(describe "context window has one answer" (fn []
  (it "the picker and the runtime resolve the same number"
      (fn []
        (let [reg   (registry-in-load-order)
              cw    (:context-window reg)
              ;; What catalog/entry-models passes for the picker.
              picker (cw "kimi/kimi-k2.5")
              ;; What loop / compaction / headroom pass now.
              runtime (cw (model-info/model-key "kimi" #js {:modelId "kimi-k2.5"}))]
          (-> (expect runtime) (.toBe picker))
          (-> (expect runtime) (.toBe 262144)))))

  (it "a provider declaring no window cannot clobber one that does"
      (fn []
        (let [cw (:context-window (registry-in-load-order))]
          (-> (expect (cw "kimi-k2.5")) (.toBe 262144)))))

  (it "the guard holds in the opposite registration order too"
      (fn []
        (let [reg (create-model-registry)]
          ((:register reg) {"kimi-k2.5" {:context-window nil}})
          ((:register reg) {"kimi-k2.5" {:context-window 262144}})
          (-> (expect ((:context-window reg) "kimi-k2.5")) (.toBe 262144)))))

  (it "a real window still replaces another real window"
      (fn []
        ;; The guard must not freeze the first value in place — only reject
        ;; entries that carry nothing.
        (let [reg (create-model-registry)]
          ((:register reg) {"m" {:context-window 1000}})
          ((:register reg) {"m" {:context-window 2000}})
          (-> (expect ((:context-window reg) "m")) (.toBe 2000)))))

  (it "an undeclared model still falls back to the vendor's own entry"
      (fn []
        ;; opencode-zen carries kimi-k2.5 without a window; the qualified lookup
        ;; falls through to the bare id, which kimi declared.
        (let [cw (:context-window (registry-in-load-order))]
          (-> (expect (cw "opencode-zen/kimi-k2.5")) (.toBe 262144)))))))

(describe "model-info/model-key" (fn []
  (it "qualifies a resolved model object"
      (fn []
        (-> (expect (model-info/model-key "kimi" #js {:modelId "kimi-k2.5"}))
            (.toBe "kimi/kimi-k2.5"))))

  (it "never stringifies the object"
      (fn []
        (-> (expect (model-info/model-key "kimi" #js {:modelId "kimi-k2.5"}))
            (.not.toContain "object Object"))))

  (it "accepts a plain string on the unknown-provider path"
      (fn []
        (-> (expect (model-info/model-key "p" "some-model")) (.toBe "p/some-model"))))

  (it "falls back to the bare id when no provider is recorded"
      (fn []
        (-> (expect (model-info/model-key "" #js {:modelId "gpt-4o"})) (.toBe "gpt-4o"))))

  (it "returns a lookup-safe value for a missing model"
      (fn []
        (-> (expect (model-info/model-key "p" nil)) (.toBe "unknown"))))))
