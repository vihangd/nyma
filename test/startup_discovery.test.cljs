(ns startup-discovery.test
  "`nyma --model vllm/...` logged `[model-fetch] https://yunwu.ai/v1/models
   returned 403` — twice, for a provider that invocation has nothing to do with.
   Startup registered EVERY configured provider and fired discovery for each,
   without consulting the model the user asked for.

   A one-shot run does not need a catalogue: it resolves the single model named
   on the command line, which the seed lists already carry. There is no picker
   and no /model autocomplete to populate. Measured against a non-existent model
   so startup runs and generation does not: ~5.5s with discovery, ~2.6s without,
   and worse per provider holding a stale credential, since those 401 every
   launch. Over a 113-task benchmark that is minutes of pure startup.

   Interactive sessions still discover — they genuinely want the catalogue
   before the user has chosen anything."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.extensions.custom-provider-local.index :as local]))

(defn- clear! []
  (aset js/process.env "NYMA_ONE_SHOT" "")
  (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" ""))

(describe "startup discovery gating"
  (fn []
    (afterEach clear!)

    (it "discovers by default"
        (fn []
          (clear!)
          (-> (expect (local/discovery-disabled?)) (.toBe false))))

    (it "skips discovery in a one-shot run"
        (fn []
          (clear!)
          (aset js/process.env "NYMA_ONE_SHOT" "1")
          (-> (expect (local/discovery-disabled?)) (.toBe true))))

    (it "still honours the explicit opt-out"
        (fn []
          (clear!)
          (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" "1")
          (-> (expect (local/discovery-disabled?)) (.toBe true))))

    (it "treats 0 and false as off, not as truthy strings"
        (fn []
          (clear!)
          (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" "0")
          (-> (expect (local/discovery-disabled?)) (.toBe false))
          (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" "false")
          (-> (expect (local/discovery-disabled?)) (.toBe false))))))
