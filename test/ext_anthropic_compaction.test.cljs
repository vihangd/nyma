(ns ext-anthropic-compaction.test
  "Tests for the anthropic_compaction extension — beta header,
   context_management, model gating, fallback for unsupported models."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.core :refer [create-agent]]
            [agent.extensions.token-suite.shared :as shared]
            [agent.extensions.token-suite.anthropic-compaction :as ac]
            [agent.extensions :refer [create-extension-api]]))

(defn- reset-stats! []
  (swap! shared/suite-stats assoc :anthropic-compaction
         {:turns 0 :requests-with-context-mgmt 0 :compactions-observed 0}))

(beforeEach reset-stats!)

(defn- make-agent [model-id]
  (create-agent {:model #js {:modelId model-id}
                 :system-prompt "You are a test agent."}))

(defn- make-config [model-id]
  #js {:model           #js {:modelId model-id}
       :system          "system prompt"
       :messages        #js []
       :tools           #js {}
       :providerOptions #js {}})

(defn ^:async fire-bpr [model-id config]
  (let [agent  (make-agent model-id)
        api    (create-extension-api agent)
        _deact (ac/activate api)
        events (:events agent)]
    (js-await ((:emit-collect events) "before_provider_request" config))
    config))

;;; ─── Model gating ─────────────────────────────────────────────────────────

(defn ^:async test-applies-to-sonnet-46 []
  (let [cfg (make-config "claude-sonnet-4-6")]
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (let [hdrs (.. cfg -providerOptions -anthropic -headers)]
      (-> (expect (.includes (or (aget hdrs "anthropic-beta") "") "compact-2026-01-12"))
          (.toBe true)))))

(defn ^:async test-applies-to-opus-46 []
  (let [cfg (make-config "claude-opus-4-6")]
    (js-await (fire-bpr "claude-opus-4-6" cfg))
    (let [hdrs (.. cfg -providerOptions -anthropic -headers)]
      (-> (expect (.includes (or (aget hdrs "anthropic-beta") "") "compact-2026-01-12"))
          (.toBe true)))))

(defn ^:async test-applies-to-opus-47 []
  (let [cfg (make-config "claude-opus-4-7")]
    (js-await (fire-bpr "claude-opus-4-7" cfg))
    (let [hdrs (.. cfg -providerOptions -anthropic -headers)]
      (-> (expect (.includes (or (aget hdrs "anthropic-beta") "") "compact-2026-01-12"))
          (.toBe true)))))

(defn ^:async test-applies-to-mythos-preview []
  (let [cfg (make-config "claude-mythos-preview")]
    (js-await (fire-bpr "claude-mythos-preview" cfg))
    (let [hdrs (.. cfg -providerOptions -anthropic -headers)]
      (-> (expect (.includes (or (aget hdrs "anthropic-beta") "") "compact-2026-01-12"))
          (.toBe true)))))

(defn ^:async test-tolerates-date-suffix []
  ;; supported-compaction-models matches by prefix so dated variants work
  (let [cfg (make-config "claude-sonnet-4-6-20260601")]
    (js-await (fire-bpr "claude-sonnet-4-6-20260601" cfg))
    (let [hdrs (.. cfg -providerOptions -anthropic -headers)]
      (-> (expect (.includes (or (aget hdrs "anthropic-beta") "") "compact-2026-01-12"))
          (.toBe true)))))

(defn ^:async test-skips-unsupported-claude []
  ;; Sonnet 4.5 and earlier are NOT in the supported list
  (let [cfg (make-config "claude-sonnet-4-5")]
    (js-await (fire-bpr "claude-sonnet-4-5" cfg))
    (-> (expect (.. cfg -providerOptions -anthropic)) (.toBeUndefined))))

(defn ^:async test-skips-non-claude []
  (let [cfg (make-config "gpt-4o")]
    (js-await (fire-bpr "gpt-4o" cfg))
    (-> (expect (.. cfg -providerOptions -anthropic)) (.toBeUndefined))))

(describe "anthropic-compaction/model-gating" (fn []
                                                (it "applies to claude-sonnet-4-6" test-applies-to-sonnet-46)
                                                (it "applies to claude-opus-4-6" test-applies-to-opus-46)
                                                (it "applies to claude-opus-4-7" test-applies-to-opus-47)
                                                (it "applies to claude-mythos-preview" test-applies-to-mythos-preview)
                                                (it "tolerates date-suffix model ids" test-tolerates-date-suffix)
                                                (it "skips older claude variants" test-skips-unsupported-claude)
                                                (it "skips non-claude providers" test-skips-non-claude)))

;;; ─── Context management shape ────────────────────────────────────────────

(defn ^:async test-context-management-shape []
  (let [cfg (make-config "claude-sonnet-4-6")]
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (let [cm   (.. cfg -providerOptions -anthropic -contextManagement)
          edit (aget (.-edits cm) 0)
          trig (.-trigger edit)]
      (-> (expect (.-type edit)) (.toBe "compact_20260112"))
      (-> (expect (.-type trig)) (.toBe "input_tokens"))
      (-> (expect (number? (.-value trig))) (.toBe true))
      (-> (expect (>= (.-value trig) 50000)) (.toBe true)))))

(defn ^:async test-pause-after-compaction-default-false []
  (let [cfg (make-config "claude-sonnet-4-6")]
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (-> (expect (.. cfg -providerOptions -anthropic -contextManagement -pauseAfterCompaction))
        (.toBe false))))

(describe "anthropic-compaction/context-management" (fn []
                                                      (it "produces a valid compact_20260112 edit" test-context-management-shape)
                                                      (it "pauseAfterCompaction defaults to false" test-pause-after-compaction-default-false)))

;;; ─── Beta header merge ────────────────────────────────────────────────────

(defn ^:async test-beta-header-merge []
  ;; If something else already added an anthropic-beta header, append rather
  ;; than overwrite.
  (let [cfg (make-config "claude-sonnet-4-6")]
    (let [pre-anth #js {}
          pre-hdrs #js {}]
      (aset pre-hdrs "anthropic-beta" "interleaved-thinking-2025-05-14")
      (aset pre-anth "headers" pre-hdrs)
      (aset (.-providerOptions cfg) "anthropic" pre-anth))
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (let [hdr (aget (.. cfg -providerOptions -anthropic -headers) "anthropic-beta")]
      (-> (expect (.includes hdr "interleaved-thinking-2025-05-14")) (.toBe true))
      (-> (expect (.includes hdr "compact-2026-01-12")) (.toBe true)))))

(defn ^:async test-beta-header-no-duplicate []
  ;; Re-running the hook on the same config (e.g. retry) should not stack
  ;; the header.
  (let [cfg (make-config "claude-sonnet-4-6")]
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (let [hdr (aget (.. cfg -providerOptions -anthropic -headers) "anthropic-beta")
          first-idx  (.indexOf hdr "compact-2026-01-12")
          second-idx (.indexOf hdr "compact-2026-01-12" (inc first-idx))]
      (-> (expect (>= first-idx 0)) (.toBe true))
      (-> (expect (= second-idx -1)) (.toBe true)))))

(describe "anthropic-compaction/headers" (fn []
                                           (it "appends to existing anthropic-beta headers" test-beta-header-merge)
                                           (it "does not duplicate the beta on retry" test-beta-header-no-duplicate)))

;;; ─── Stats ────────────────────────────────────────────────────────────────

(defn ^:async test-stats-incremented []
  (let [cfg (make-config "claude-sonnet-4-6")]
    (js-await (fire-bpr "claude-sonnet-4-6" cfg))
    (-> (expect (:requests-with-context-mgmt
                 (:anthropic-compaction @shared/suite-stats)))
        (.toBe 1))))

(defn ^:async test-stats-not-incremented-for-unsupported []
  (let [cfg (make-config "gpt-4o")]
    (js-await (fire-bpr "gpt-4o" cfg))
    (-> (expect (:requests-with-context-mgmt
                 (:anthropic-compaction @shared/suite-stats)))
        (.toBe 0))))

(describe "anthropic-compaction/stats" (fn []
                                         (it "increments requests-with-context-mgmt on supported models" test-stats-incremented)
                                         (it "does not increment for unsupported models" test-stats-not-incremented-for-unsupported)))

;;; ─── shared/model-supports-compaction? ───────────────────────────────────

(describe "shared/model-supports-compaction?" (fn []
                                                (it "true for sonnet-4-6"
                                                    (fn []
                                                      (-> (expect (shared/model-supports-compaction? "claude-sonnet-4-6"))
                                                          (.toBe true))))
                                                (it "true for opus-4-7 with date suffix"
                                                    (fn []
                                                      (-> (expect (shared/model-supports-compaction? "claude-opus-4-7-20260201"))
                                                          (.toBe true))))
                                                (it "false for sonnet-4-5"
                                                    (fn []
                                                      (-> (expect (shared/model-supports-compaction? "claude-sonnet-4-5"))
                                                          (.toBe false))))
                                                (it "false for gpt-4o"
                                                    (fn []
                                                      (-> (expect (shared/model-supports-compaction? "gpt-4o"))
                                                          (.toBe false))))
                                                (it "false for nil"
                                                    (fn []
                                                      (-> (expect (shared/model-supports-compaction? nil))
                                                          (.toBe false))))))
