(ns ext-lsp-suite-integration.test
  "Integration tests for lsp_suite — require clojure-lsp on PATH.
   Run with: LSP_INTEGRATION_TESTS=1 bun test test/ext_lsp_suite_integration.test.cljs

   Gated with `describe.skipIf`, so with the env var unset these report as
   SKIPPED. They previously wrapped each body in `(when run-tests? …)`, which
   meant the tests ran, asserted nothing, and reported PASS — five green
   results standing in for the only coverage `lsp_client` and `lsp_manager`
   have. A test that cannot fail is worse than a missing one: it hides the
   absence. `ext_agent_shell_e2e` had the convention right."
  (:require ["bun:test" :refer [describe it expect beforeAll afterAll]]
            ["node:child_process" :as cp]
            ["node:path" :as node-path]
            [agent.extensions.lsp-suite.lsp_manager :as mgr]
            [agent.extensions.lsp-suite.lsp_client :as lsp-client]
            [agent.extensions.lsp-suite.lsp_diagnostics :as diags]
            [agent.extensions.lsp-suite.lsp_formatters :as fmt]))

;; ── Guards ────────────────────────────────────────────────────────

(defn- lsp-available? []
  (try
    (= 0 (.-status (.spawnSync cp "clojure-lsp"
                               #js ["--version"]
                               #js {:encoding "utf8" :timeout 5000})))
    (catch :default _ false)))

(def ^:private run-tests?
  (and (some? (.-LSP_INTEGRATION_TESTS js/process.env))
       (lsp-available?)))

;; ── Fixtures ──────────────────────────────────────────────────────

(def ^:private project-root
  (node-path/resolve (js* "import.meta.dir") ".."))

(def ^:private test-file
  (str project-root "/src/agent/core.cljs"))

(def ^:private clj-lsp-cfg
  {:command     ["clojure-lsp" "--stdio"]
   :extensions  [".clj" ".cljs" ".cljc" ".edn"]
   :disabled?   false
   :env         nil
   :initOptions nil
   :startupTimeout 45000
   :maxRestarts 0})

(def ^:private mgr-ref (atom nil))

;; ── Lifecycle callbacks (named so ^:async compiles correctly) ─────

(defn ^:async setup! []
  (when run-tests?
    (let [m (mgr/create-manager)]
      (mgr/init! m {"clojure-lsp" clj-lsp-cfg} project-root)
      (diags/clear!)
      (mgr/set-diagnostic-handler! m
                                   (fn [uri arr] (diags/register! uri arr (fmt/uri->path uri))))
      (reset! mgr-ref m)
      (js-await (mgr/ensure-open! m test-file)))))

(defn ^:async teardown! []
  (when-let [m @mgr-ref]
    (js-await (mgr/stop-all! m))
    (diags/clear!)))

;; ── Individual test bodies ────────────────────────────────────────

(defn ^:async test-client-running []
  (let [client (js-await (mgr/get-client-for! @mgr-ref test-file))]
    (-> (expect (some? client)) (.toBe true))
    (-> (expect (lsp-client/running? client)) (.toBe true))))

(defn ^:async test-hover []
  (let [client (js-await (mgr/get-client-for! @mgr-ref test-file))
        result (js-await
                (lsp-client/request! client
                                     "textDocument/hover"
                                     #js {:textDocument #js {:uri (fmt/path->uri test-file)}
                                          :position     #js {:line 0 :character 4}}))]
    ;; clojure-lsp may return nil for some positions; any non-throw is valid
    (-> (expect (not (= result js/undefined))) (.toBe true))))

(defn ^:async test-document-symbols []
  (let [client (js-await (mgr/get-client-for! @mgr-ref test-file))
        result (js-await
                (lsp-client/request! client
                                     "textDocument/documentSymbol"
                                     #js {:textDocument #js {:uri (fmt/path->uri test-file)}}))]
    (-> (expect (some? result)) (.toBe true))
    (-> (expect (pos? (.-length result))) (.toBe true))))

(defn ^:async test-find-references []
  (let [loop-file (str project-root "/src/agent/loop.cljs")
        _         (js-await (mgr/ensure-open! @mgr-ref loop-file))
        client    (js-await (mgr/get-client-for! @mgr-ref loop-file))
        result    (js-await
                   (lsp-client/request! client
                                        "textDocument/references"
                                        #js {:textDocument #js {:uri (fmt/path->uri loop-file)}
                                             :position     #js {:line 0 :character 5}
                                             :context      #js {:includeDeclaration true}}))]
    ;; Result may be nil or an array; just verify no exception thrown
    (-> (expect true) (.toBe true))))

(defn ^:async test-did-change []
  (let [client (js-await (mgr/get-client-for! @mgr-ref test-file))]
    (mgr/did-change! @mgr-ref test-file "(ns agent.core)")
    (let [fs      (js/require "node:fs")
          content (.readFileSync fs test-file "utf8")]
      (mgr/did-change! @mgr-ref test-file content))
    (-> (expect (lsp-client/running? client)) (.toBe true))))

;; ── Suite ─────────────────────────────────────────────────────────

((.skipIf describe (not run-tests?)) "LSP integration — clojure-lsp"
          (fn []
            (beforeAll setup! 60000)
            (afterAll teardown!)

            (it "clojure-lsp client is running after start"
                test-client-running)

            (it "hover returns without error for a known position"
                test-hover)

            (it "document-symbols returns at least one symbol"
                test-document-symbols)

            (it "find-references does not throw"
                test-find-references)

            (it "did-change! notifies server without crashing"
                test-did-change)))
