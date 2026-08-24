(ns local-provider-key.test
  "The local provider was the only one that never consulted credentials.json.
   relay and opencode-zen both call credentials/read-credential, which is what
   makes /login work for them. Here the key came from an env var or nowhere, so
   an alias carrying OMLX_API_KEY=abcd worked while a plain
   `nyma --model omlx/...` returned a bare `Invalid API key` — naming neither
   the provider nor the variable to set."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-local.index :as local]))

(describe "local provider key resolution"
  (fn []
    (it "prefers the env var when it is set"
        (fn []
          (do (aset js/process.env "NYMA_TEST_LOCAL_KEY" "from-env")
              (let [k (local/resolve-key {:name "omlx" :api-key-env "NYMA_TEST_LOCAL_KEY"})]
                (aset js/process.env "NYMA_TEST_LOCAL_KEY" "")
                (-> (expect k) (.toBe "from-env"))))))

    (it "falls back to the placeholder when nothing is configured"
        ;; most local servers ignore the key entirely — this must keep working
        (fn []
          (-> (expect (local/resolve-key {:name "definitely-not-a-saved-provider"
                                          :api-key-env "NYMA_TEST_UNSET_VAR"}))
              (.toBe "local-no-key"))))

    (it "does not throw when the entry has neither name nor env var"
        (fn []
          (-> (expect (local/resolve-key {})) (.toBe "local-no-key"))))))
