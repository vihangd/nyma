(ns loader-smoke.test
  "Smoke test for the built-in extension loader.

   Single test that calls `discover-and-load` against the actual
   `dist/agent/extensions/` tree and asserts every expected built-in
   extension loads with the expected kebab-case namespace.

   This is the test that would have caught the recent loader bug where four
   manifest-less directory extensions (mention_files, model_roles,
   prompt_history, stats_dashboard) all derived the namespace `\"index\"`
   from `derive-namespace`, collided in topo-sort's by-ns map, and silently
   dropped 3 of 4 from the load list."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]))

;; The full set of built-in extension namespaces shipped under
;; src/agent/extensions/. If you ADD a new built-in, add it here.
(def expected-builtin-namespaces
  #{"agent-shell"
    "ast-tools"
    "bash-suite"
    "custom-provider-claude-native"
    "custom-provider-minimax"
    "custom-provider-qwen-cli"
    "desktop-notify"
    "mention-files"
    "model-roles"
    "prompt-history"
    "questionnaire"
    "rtk-compression"
    "stats-dashboard"
    "token-suite"
    "workspace-config"
    "lsp-suite"})

(defn- builtin-dir []
  ;; This test file compiles to dist/loader_smoke.test.mjs, so
  ;; agent extensions sit at dist/agent/extensions/ — one level down.
  (path/resolve (js* "import.meta.dir") "agent" "extensions"))

(defn ^:async test-all-builtins-load-with-correct-namespaces []
  (let [agent  (create-agent {:model "test" :system-prompt "smoke"})
        api    (create-extension-api agent)
        loaded (js-await (discover-and-load [(builtin-dir)] api))
        nses   (set (map :namespace loaded))]
    (try
      ;; All expected namespaces present
      (doseq [expected expected-builtin-namespaces]
        (-> (expect (contains? nses expected)) (.toBe true)))
      ;; Nothing showed up under the placeholder "index" — the loader bug
      ;; that motivated this test.
      (-> (expect (contains? nses "index")) (.toBe false))
      ;; Loaded count matches expected count exactly. If this fails, either
      ;; an extension was added without updating expected-builtin-namespaces
      ;; or the loader is loading the same extension multiple times.
      (-> (expect (count loaded)) (.toBe (count expected-builtin-namespaces)))
      (finally
        (deactivate-all loaded)))))

(defn ^:async test-no-duplicate-namespaces []
  (let [agent  (create-agent {:model "test" :system-prompt "smoke"})
        api    (create-extension-api agent)
        loaded (js-await (discover-and-load [(builtin-dir)] api))
        nses   (map :namespace loaded)]
    (try
      ;; Set count must equal vec count — duplicates would shrink the set.
      (-> (expect (count (set nses))) (.toBe (count nses)))
      (finally
        (deactivate-all loaded)))))

(defn ^:async test-each-loaded-extension-has-deactivator-or-nil []
  ;; Either nil (extension returned no cleanup fn) or a function — never
  ;; some other value. Catches accidental return-shape regressions.
  (let [agent  (create-agent {:model "test" :system-prompt "smoke"})
        api    (create-extension-api agent)
        loaded (js-await (discover-and-load [(builtin-dir)] api))]
    (try
      (doseq [ext loaded]
        (let [d (:deactivate ext)]
          (-> (expect (or (nil? d) (fn? d))) (.toBe true))))
      (finally
        (deactivate-all loaded)))))

(describe "loader smoke — built-in extensions"
          (fn []
            (it "all 15 expected namespaces load and none collide on 'index'"
                test-all-builtins-load-with-correct-namespaces)
            (it "no duplicate namespaces in the loaded list"
                test-no-duplicate-namespaces)
            (it "each entry's :deactivate is nil or a function"
                test-each-loaded-extension-has-deactivator-or-nil)))
