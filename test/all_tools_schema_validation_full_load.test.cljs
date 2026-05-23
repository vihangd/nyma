(ns all-tools-schema-validation-full-load.test
  "Production-shaped sweep: load every extension via the real
   discover-and-load path (same as cli.cljs), then walk every
   registered tool through asSchema. Catches extensions that
   register tools with raw JSON Schema objects (no `~standard`
   marker) — those crash the LLM on first tool call with
   `schema is not a function (...) instance of Object`.

   The narrow test in all_tools_schema_validation only covers
   native tools and missed 9 broken tools across ast_tools,
   lsp_suite, and questionnaire because those only register
   their schemas after activation."
  (:require ["bun:test" :refer [describe it expect]]
            ["@ai-sdk/provider-utils" :refer [asSchema]]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load]]))

(def ^:private builtin-dir
  "/Users/vihangd/projects/pers/nyma/dist/agent/extensions")

(defn ^:async test-every-loaded-tool-validates []
  (let [agent  (create-agent {:model #js {:modelId "test"}
                              :system-prompt "test"
                              :settings #js {}})
        api    (create-extension-api agent)
        _      (set! (.-extension-api agent) api)
        dirs   [builtin-dir
                (path/join (os/homedir) ".nyma" "extensions")
                (path/join (js/process.cwd) ".nyma" "extensions")]
        loaded (js-await (discover-and-load dirs api))
        all    (.all (:tool-registry agent))
        bad    (atom [])]
    (doseq [n (js-keys all)]
      (let [td  (aget all n)
            sch (when td (or (.-inputSchema td) (.-parameters td)))]
        (when (some? sch)
          (try (asSchema sch)
               (catch :default e
                 (swap! bad conj {:name n :err (str (.-message e))}))))))
    (doseq [b @bad]
      (js/console.error (str "[full-load-sweep] BAD: " (:name b) " — " (:err b))))
    (-> (expect (count @bad)) (.toBe 0))
    (-> (expect (pos? (count loaded))) (.toBe true))
    (doseq [e loaded]
      (when-let [d (:deactivate e)]
        (try (d) (catch :default _e nil))))))

(describe "tools/schema-validation/full-load"
          (fn []
            (it "every tool registered after full extension load validates"
                test-every-loaded-tool-validates)))
