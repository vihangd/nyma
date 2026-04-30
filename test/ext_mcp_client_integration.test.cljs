(ns ext-mcp-client-integration.test
  "Real subprocess MCP integration tests. Gated by
   MCP_INTEGRATION_TESTS=1 (mirrors LSP's pattern at
   ext_lsp_suite_integration.test.cljs:22-24) so CI/dev
   doesn't pay the spawn cost on every run.

   When enabled, exercises the full client lifecycle against:
     1. lean-ctx mcp                  — already on PATH
     2. @modelcontextprotocol/server-everything — via npx -y

   Verifies: spawn → connect → list-tools → call-tool → close.
   These are the canonical MCP server hello-worlds; if they
   work end-to-end, the SDK wiring is correct."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:child_process" :as cp]
            [agent.extensions.mcp-client.client :as client]))

(defn- enabled? []
  (= "1" (.-MCP_INTEGRATION_TESTS js/process.env)))

(defn- on-path? [cmd]
  (try
    (= 0 (.-status (.spawnSync cp "which" #js [cmd] #js {:encoding "utf8"})))
    (catch :default _ false)))

;;; ─── lean-ctx ────────────────────────────────────────────────────

(defn ^:async test-lean-ctx-roundtrip []
  (when (and (enabled?) (on-path? "lean-ctx"))
    (let [c (client/create
             {:name    "lean-ctx"
              :command "lean-ctx"
              :args    ["mcp"]
              :startup-timeout-ms 10000})]
      (try
        (js-await (client/start! c))
        (-> (expect (client/state c)) (.toBe :running))
        (let [tools (client/list-tools c)]
          (-> (expect (pos? (count tools))) (.toBe true))
          ;; lean-ctx exposes ctx_tree as a low-cost call. lean-ctx
          ;; refuses paths outside its project root, so use cwd
          ;; explicitly rather than /tmp.
          (when (some #(= "ctx_tree" (:name %)) tools)
            (let [r (js-await (client/call-tool!
                               c
                               {:tool-name "ctx_tree"
                                :arguments #js {:path (js/process.cwd) :depth 1}}))]
              (-> (expect (:is-error? r)) (.toBe false))
              (-> (expect (pos? (count (:content r)))) (.toBe true)))))
        (finally
          (js-await (client/stop! c)))))))

;;; ─── server-everything (official reference) ─────────────────────

(defn ^:async test-server-everything-roundtrip []
  (when (and (enabled?) (on-path? "npx"))
    (let [c (client/create
             {:name    "everything"
              :command "npx"
              :args    ["-y" "@modelcontextprotocol/server-everything"]
              :startup-timeout-ms 30000})]
      (try
        (js-await (client/start! c))
        (-> (expect (client/state c)) (.toBe :running))
        (let [tools (client/list-tools c)]
          (-> (expect (pos? (count tools))) (.toBe true))
          ;; The canonical 'echo' tool returns structuredContent in
          ;; some SDK versions, which trips an SDK validation bug
          ;; (typescript-sdk #654). 'add' returns a plain text
          ;; content block and is more stable across SDK versions.
          (when-let [_t (first (filter #(= "add" (:name %)) tools))]
            (let [r (js-await (client/call-tool!
                               c
                               {:tool-name "add"
                                :arguments #js {:a 2 :b 3}}))]
              (-> (expect (:is-error? r)) (.toBe false))
              (-> (expect (pos? (count (:content r)))) (.toBe true)))))
        (finally
          (js-await (client/stop! c)))))))

;;; ─── stop! is idempotent ────────────────────────────────────────

(defn ^:async test-stop-is-idempotent []
  (when (and (enabled?) (on-path? "lean-ctx"))
    (let [c (client/create
             {:name "lean-ctx"
              :command "lean-ctx"
              :args    ["mcp"]
              :startup-timeout-ms 10000})]
      (js-await (client/start! c))
      (js-await (client/stop! c))
      (-> (expect (client/state c)) (.toBe :stopped))
      ;; Calling stop! again must not throw.
      (js-await (client/stop! c))
      (-> (expect (client/state c)) (.toBe :stopped)))))

;;; ─── Test gate notice ───────────────────────────────────────────

(defn ^:async test-gate-notice []
  ;; Always passes — but logs a hint when the gate is closed so
  ;; people aren't confused why "tests pass" without verifying
  ;; actual MCP behavior.
  (when-not (enabled?)
    (js/console.log
     "[mcp-integration] MCP_INTEGRATION_TESTS unset — skipping real subprocess tests")))

(describe "mcp-client/integration"
          (fn []
            (it "MCP_INTEGRATION_TESTS gate notice" test-gate-notice)
            (it "lean-ctx mcp: connect → list → call → close (gated)"
                test-lean-ctx-roundtrip)
            (it "server-everything: connect → list → call → close (gated)"
                test-server-everything-roundtrip)
            (it "stop! is idempotent (gated)" test-stop-is-idempotent)))
