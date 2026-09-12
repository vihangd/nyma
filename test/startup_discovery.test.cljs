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

;; Captured before any test writes them. `bun test` runs every file in ONE
;; process, so an env var left mutated here is left mutated for whatever file
;; loads next — and relay_provider.test reads NYMA_NO_MODEL_DISCOVERY at module
;; load to prove the suite's network guard is in place. This file used to leave
;; it as "", so on a machine whose file order put relay second, the guard test
;; failed with `Received: ""`. That order is not alphabetical everywhere: it
;; passed on macOS for months and failed on the first CI run.
(def ^:private saved
  #js {"NYMA_ONE_SHOT"            (aget js/process.env "NYMA_ONE_SHOT")
       "NYMA_NO_MODEL_DISCOVERY"  (aget js/process.env "NYMA_NO_MODEL_DISCOVERY")})

(defn- clear!
  "Zero both switches so a test starts from a known state."
  []
  (aset js/process.env "NYMA_ONE_SHOT" "")
  (aset js/process.env "NYMA_NO_MODEL_DISCOVERY" ""))

(defn- restore!
  "Put the process environment back the way this file found it."
  []
  (doseq [k ["NYMA_ONE_SHOT" "NYMA_NO_MODEL_DISCOVERY"]]
    (if (undefined? (aget saved k))
      (js-delete js/process.env k)
      (aset js/process.env k (aget saved k)))))

(describe "startup discovery gating"
  (fn []
    (afterEach restore!)

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
