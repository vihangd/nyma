(ns agent.extensions.token-suite.anthropic-compaction
  "Server-side context compaction via Anthropic's `compact-2026-01-12`
   beta. When the model is one of the supported Claude variants
   (Mythos preview, Opus 4.7/4.6, Sonnet 4.6), nyma sets a beta
   header and a context_management edit on the request. Anthropic
   then handles compaction transparently when the conversation
   crosses the trigger threshold — no client-side LLM call needed.

   Activation gate (all must hold):
     - settings :token-suite :anthropic-compaction :enabled (default true)
     - shared/model-supports-compaction? on the request model
     - trigger-tokens >= 50_000 (Anthropic's enforced minimum)

   Falls back silently for any other model — workstreams C/D
   (anchored iterative + cascade) take over for those.

   Spec reference: https://platform.claude.com/docs/en/build-with-claude/compaction"
  (:require [agent.extensions.token-suite.shared :as shared]))

(def ^:private MIN-TRIGGER-TOKENS
  "Anthropic's enforced minimum for `trigger.value`. Lower values
   return an API error, so we clamp before sending."
  50000)

(defn- effective-trigger
  "Compute the trigger threshold honoring the API minimum."
  [config-value]
  (max MIN-TRIGGER-TOKENS
       (or (when (number? config-value) config-value) 150000)))

(defn- get-or-create
  "Get a JS subobject under `key`, creating an empty one if absent.
   Mutates `obj` to install the new subobject when created."
  [obj key]
  (let [existing (aget obj key)]
    (if existing
      existing
      (let [created #js {}]
        (aset obj key created)
        created))))

(defn- apply-compaction-providers!
  "Mutate st-config in place to add the beta header and the
   context_management edit for Anthropic. Returns true if applied."
  [config-obj trigger pause?]
  (let [popts (or (.-providerOptions config-obj)
                  (let [p #js {}]
                    (aset config-obj "providerOptions" p)
                    p))
        anth  (get-or-create popts "anthropic")
        hdrs  (get-or-create anth "headers")]
    ;; Beta header — append to any existing list of betas.
    (let [existing (aget hdrs "anthropic-beta")
          desired  "compact-2026-01-12"
          new-val  (cond
                     (nil? existing) desired
                     (.includes existing desired) existing
                     :else (str existing "," desired))]
      (aset hdrs "anthropic-beta" new-val))

    ;; context_management — single compact_20260112 edit on input_tokens trigger.
    (aset anth "contextManagement"
          #js {:edits #js [#js {:type    "compact_20260112"
                                :trigger #js {:type "input_tokens"
                                              :value trigger}}]
               :pauseAfterCompaction (boolean pause?)})
    true))

(defn activate [api]
  (let [config   (shared/load-config)
        ac-cfg   (:anthropic-compaction config)
        enabled? (boolean (:enabled ac-cfg))
        trigger  (effective-trigger (:trigger-tokens ac-cfg))
        pause?   (boolean (:pause-after-compaction ac-cfg))]

    ;; before_provider_request — priority 90.
    ;; Runs BEFORE kv-cache (priority 100) so kv-cache can still see and
    ;; mutate the config; the beta header / context_management we add
    ;; here are independent of cacheControl breakpoints and coexist fine.
    (.on api "before_provider_request"
         (fn [config-obj _ctx]
           (let [model    (.-model config-obj)
                 model-id (str (or (when model (.-modelId model)) model ""))]
             (when (and enabled? (shared/model-supports-compaction? model-id))
               (apply-compaction-providers! config-obj trigger pause?)
               (swap! shared/suite-stats update :anthropic-compaction
                      (fn [s] (-> s
                                  (update :requests-with-context-mgmt inc))))))
           nil)
         90)

    ;; after_provider_request — track turns + observe compaction events.
    (.on api "after_provider_request"
         (fn [event _ctx]
           (let [model    (.-model event)
                 model-id (str (or model ""))]
             (when (and enabled? (shared/model-supports-compaction? model-id))
               (swap! shared/suite-stats update :anthropic-compaction
                      (fn [s] (update s :turns inc)))
               ;; If the response carries a context_management indicator
               ;; (shape TBD pending live verification against the API),
               ;; bump the compactions-observed counter. Until then this
               ;; remains zero and is safe — the field name is unknown.
               (when-let [cm (or (.-contextManagement event)
                                 (.-context_management event))]
                 (when cm
                   (swap! shared/suite-stats update :anthropic-compaction
                          (fn [s] (update s :compactions-observed inc)))))))))

    ;; deactivate
    (fn [] nil)))
