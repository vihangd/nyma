(ns interactive-model-id.test
  "The status line crashed the whole interactive session when model resolution
   failed:

     TypeError: null is not an object (evaluating '...modelId')
       at model_id (interactive.mjs:146)
       at sync_status! (interactive.mjs:266)

   model-id read `.-modelId` off `(or runtime m)` without checking anything was
   there. Both are nil whenever resolution fails — an unknown provider, a
   provider that was renamed out from under a saved role, a missing credential.
   nyma had already printed a perfectly good warning naming every registered
   provider; then it threw and took the session with it.

   A provider that cannot be resolved is a message, not a crash."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.interactive :as ia]))

(defn- agent-with [cfg-model runtime]
  {:config (when cfg-model #js {:model cfg-model})
   :state  (atom {:runtime-model runtime})})

(describe "interactive/model-id"
  (fn []
    (it "renders a dash instead of throwing when nothing resolved"
        ;; the reported crash: unknown provider -> null model -> dead session
        (fn []
          (-> (expect (ia/model-id (agent-with nil nil))) (.toBe "–"))))

    (it "survives a config with no model on it"
        (fn []
          (-> (expect (ia/model-id {:config #js {} :state (atom {})})) (.toBe "–"))))

    (it "reads the model id from the config"
        (fn []
          (-> (expect (ia/model-id (agent-with #js {:modelId "m-1"} nil))) (.toBe "m-1"))))

    (it "prefers a runtime model over the configured one"
        ;; /model swaps mid-session; the status bar must follow
        (fn []
          (-> (expect (ia/model-id (agent-with #js {:modelId "m-1"} #js {:modelId "rt"})))
              (.toBe "rt"))))

    (it "accepts a bare string model, which relays hand back verbatim"
        (fn []
          (-> (expect (ia/model-id (agent-with "plain-id" nil))) (.toBe "plain-id"))))))
