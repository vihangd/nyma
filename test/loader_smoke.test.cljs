(ns loader-smoke.test
  "Smoke test for the built-in extension loader.

   Calls `discover-and-load` with the statically compiled builtin registry —
   the path production takes — and asserts every expected built-in extension
   activates under the expected kebab-case namespace.

   It used to scan `dist/agent/extensions/` instead. That directory exists in a
   source tree and not inside a single-file binary, which is exactly how a
   bundled nyma came to load 2 of 40 extensions with this test green.

   This is the test that would have caught the recent loader bug where four
   manifest-less directory extensions (model_roles,
   prompt_history, stats_dashboard) all derived the namespace `\"index\"`
   from `derive-namespace`, collided in topo-sort's by-ns map, and silently
   dropped 3 of 4 from the load list."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.extension-scope :refer [create-scoped-api dispose-scope!]]
            [agent.ui.status-line-segments :as status-segments]
            [agent.pricing :as pricing]
            [agent.builtin-extensions :refer [registry]]))

;; The full set of built-in extension namespaces shipped under
;; src/agent/extensions/. If you ADD a new built-in, add it here.
(def expected-builtin-namespaces
  #{"advisor"
    "agent-runner-claude-sdk"
    "agent-state"
    "agent-shell"
    "ast-tools"
    "bash-suite"
    "claude-hook-bridge"
    "custom-provider-claude-native"
    "custom-provider-deepseek"
    "custom-provider-groq"
    "custom-provider-kimi"
    "custom-provider-minimax"
    "custom-provider-opencode-zen"
    "custom-provider-openrouter"
    "custom-provider-qwen-cli"
    "custom-provider-relay"
    "desktop-notify"
    "lsp-suite"
    "mcp-client"
    "model-roles"
    "refine"
    "prompt-history"
    "questionnaire"
    "spec-driven"
    "stats-dashboard"
    "token-suite"
    "workspace-config"
    "small-model"
    "local"
    "headroom"
    "subagent"
    "openwiki"
    "memory"
    "todos"
    "add-dir"
    "verify-gate"
    "checkpoints"
    "handoff"
    "budget"
    "thinking-renderer"})

(defn ^:async test-all-builtins-load-with-correct-namespaces []
  (let [agent  (create-agent {:model "test" :system-prompt "smoke"})
        api    (create-extension-api agent)
        loaded (js-await (discover-and-load [] api registry))
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
        loaded (js-await (discover-and-load [] api registry))
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
        loaded (js-await (discover-and-load [] api registry))]
    (try
      (doseq [ext loaded]
        (let [d (:deactivate ext)]
          (-> (expect (or (nil? d) (fn? d))) (.toBe true))))
      (finally
        (deactivate-all loaded)))))

;;; ─── Residue: every registration is gone after deactivate ─────────
;;
;; One case per builtin, loaded ALONE, so a failure names the extension.
;; Snapshot, don't assert zero: segment and pricing registries are
;; module-global atoms shared by every test in the process.

(defn- residue-snapshot [agent]
  {:handlers  ((:handler-total (:events agent)))
   :inter     ((:handler-total (:inter-events agent)))
   :tools     (vec (sort (keys ((:all (:tool-registry agent))))))
   :commands  (vec (sort (keys @(:commands agent))))
   :shortcuts (vec (sort (keys @(:shortcuts agent))))
   :flags     (vec (sort (keys @(:flags agent))))
   :mw        (count ((:chain (:middleware agent))))
   :providers (vec (sort (keys ((:list (:provider-registry agent))))))
   :segments  (vec (sort (keys (status-segments/segment-registry))))
   :pricing   (count @pricing/token-costs)
   ;; squint-internal: watches live in a plain object on the atom.
   :watches   (count (js/Object.keys (.-_watches (:state agent))))})

(defn ^:async test-builtin-leaves-no-residue [entry]
  (let [agent  (create-agent {:model "test" :system-prompt "leak"})
        api    (create-extension-api agent)
        before (residue-snapshot agent)
        loaded (js-await (discover-and-load [] api [entry]))]
    (js-await (deactivate-all loaded))
    (-> (expect (clj->js (residue-snapshot agent)))
        (.toEqual (clj->js before)))))

(defn test-two-scopes-overriding-one-native-tool []
  ;; The one regression a sweep can introduce: two extensions override the
  ;; same native name; disposing both must restore the original, not delete it.
  (let [agent (create-agent {:model "test" :system-prompt "leak"})
        api   (create-extension-api agent)
        a     (create-scoped-api api "a" #{:tools-override})
        b     (create-scoped-api api "b" #{:tools-override})
        orig  (.getTool api "read")]
    (.overrideTool a "read" #js {:description "a" :execute (fn [_] "a")})
    (.overrideTool b "read" #js {:description "b" :execute (fn [_] "b")})
    (dispose-scope! b)
    (dispose-scope! a)
    (-> (expect (.getTool api "read")) (.toBe orig))))

(describe "loader smoke — no residue after deactivate"
          (fn []
            (doseq [entry registry]
              (it (str (:namespace entry) " restores every registry to baseline")
                  (fn [] (test-builtin-leaves-no-residue entry))))
            (it "two scopes overriding one native tool restore the original"
                test-two-scopes-overriding-one-native-tool)))

(describe "loader smoke — built-in extensions"
          (fn []
            (it "all 30 expected namespaces load and none collide on 'index'"
                test-all-builtins-load-with-correct-namespaces)
            (it "no duplicate namespaces in the loaded list"
                test-no-duplicate-namespaces)
            (it "each entry's :deactivate is nil or a function"
                test-each-loaded-extension-has-deactivator-or-nil)))
