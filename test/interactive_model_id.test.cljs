(ns interactive-model-id.test
  "The status line crashed the whole interactive session when model resolution
   failed:

     TypeError: null is not an object (evaluating '...modelId')
       at model_id (interactive.mjs:146)
       at sync_status! (interactive.mjs:266)

   model-id read `.-modelId` off the resolved model without checking anything
   was there. It is nil whenever resolution fails — an unknown provider, a
   provider that was renamed out from under a saved role, a missing credential.
   nyma had already printed a perfectly good warning naming every registered
   provider; then it threw and took the session with it.

   A provider that cannot be resolved is a message, not a crash."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.interactive :as ia]))

(defn- agent-with
  "An agent whose config carries `cfg-model`. There is deliberately no second
   model slot: this used to pass a `:runtime-model` state key too, and that key
   has no writer anywhere in the repo — the test was pinning a branch production
   could never take."
  [cfg-model]
  {:config (when cfg-model #js {:model cfg-model})
   :state  (atom {})})

(describe "interactive/model-id"
  (fn []
    (it "renders a dash instead of throwing when nothing resolved"
        ;; the reported crash: unknown provider -> null model -> dead session
        (fn []
          (-> (expect (ia/model-id (agent-with nil))) (.toBe "–"))))

    (it "survives a config with no model on it"
        (fn []
          (-> (expect (ia/model-id {:config #js {} :state (atom {})})) (.toBe "–"))))

    (it "reads the model id from the config"
        (fn []
          (-> (expect (ia/model-id (agent-with #js {:modelId "m-1"}))) (.toBe "m-1"))))

    (it "follows a mid-session /model switch"
        ;; The real mechanism, and the one this used to miss: /model goes
        ;; through setModel, which mutates config.model in place. The previous
        ;; version asserted a `:runtime-model` state key took precedence — a key
        ;; nothing writes, so it certified a branch that never runs while the
        ;; behaviour it claimed to protect went untested.
        (fn []
          (let [agent (agent-with #js {:modelId "m-1"})]
            (-> (expect (ia/model-id agent)) (.toBe "m-1"))
            (aset (:config agent) "model" #js {:modelId "m-2"})
            (-> (expect (ia/model-id agent)) (.toBe "m-2")))))

    (it "accepts a bare string model, which relays hand back verbatim"
        (fn []
          (-> (expect (ia/model-id (agent-with "plain-id"))) (.toBe "plain-id"))))))
