(ns all-tools-schema-validation.test
  "Sweep every tool registered on a freshly-created agent through
   AI-SDK's asSchema. If any tool's inputSchema fails to normalize,
   the LLM will crash on the first call to that tool with
   `schema is not a function (...) instance of Object`. This test
   surfaces the same condition synchronously so a broken tool
   def is caught at build time, not first-tool-call time."
  (:require ["bun:test" :refer [describe it expect]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            [agent.core :refer [create-agent]]))

(describe "tools/schema-validation"
          (fn []
            (it "every native tool's inputSchema normalizes via asSchema"
                (fn []
                  (let [agent (create-agent {:model #js {:modelId "test"}
                                             :system-prompt "test"})
                        all   (.all (:tool-registry agent))
                        bad   (atom [])]
                    (doseq [n (js-keys all)]
                      (let [td (aget all n)
                            sch (when td (.-inputSchema td))]
                        (when (some? sch)
                          (try
                            (asSchema sch)
                            (catch :default e
                              (swap! bad conj
                                     {:name n
                                      :err  (str (.-message e))}))))))
                    (when (seq @bad)
                      (doseq [b @bad]
                        (js/console.error
                         (str "[schema-sweep] BAD: " (:name b) " — " (:err b)))))
                    (-> (expect (count @bad)) (.toBe 0)))))))
