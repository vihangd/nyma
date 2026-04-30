(ns ext-mcp-client-status-command.test
  "Regression tests for the /mcp-status command path.
   Bug being defended against: format-status-table called
   `clojure.string/join` without the namespace being required, so
   the SECOND any user invoked the command in a real session it
   crashed with `clojure is not defined`. Squint doesn't auto-
   require clojure.string — every namespace that uses str/join
   needs an explicit (:require [clojure.string :as str])."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]
            [agent.extensions.mcp-client.index :refer [format-status-table]]))

(defn- fake-client
  ([name state] (fake-client name state nil))
  ([name state last-error]
   (let [c (client/create {:name name :command "echo"})]
     (reset! (:state c) state)
     (when last-error (reset! (:last-error c) last-error))
     c)))

;;; ─── Empty state ────────────────────────────────────────────────

(describe "/mcp-status command/empty"
          (fn []
            (it "produces a friendly message when no servers configured"
                (fn []
                  (let [m (mgr/create)
                        out (format-status-table m)]
                    (-> (expect (.includes out "No MCP servers")) (.toBe true)))))))

;;; ─── Populated state — exercises the str/join path ──────────────

(describe "/mcp-status command/populated"
          (fn []
            (it "renders one row per configured server"
                (fn []
                  (let [m (mgr/create)
                        c1 (fake-client "lean-ctx" :running)
                        c2 (fake-client "memory"   :starting)]
                    (reset! (:tools c1) [{:name "ctx_read"} {:name "ctx_tree"}])
                    (reset! (:tools c2) [])
                    (reset! (:clients m) {"lean-ctx" c1 "memory" c2})
                    (let [out (format-status-table m)]
                      ;; Header line counts running / total.
                      (-> (expect (.includes out "1/2 connected")) (.toBe true))
                      ;; Both server rows present.
                      (-> (expect (.includes out "lean-ctx")) (.toBe true))
                      (-> (expect (.includes out "memory")) (.toBe true))
                      ;; Tool counts come through.
                      (-> (expect (.includes out "(2 tools)")) (.toBe true))
                      (-> (expect (.includes out "(0 tools)")) (.toBe true))))))

            (it "includes last-error text when a server is errored"
                (fn []
                  (let [m  (mgr/create)
                        c  (fake-client "broken" :stopped-error "spawn ENOENT")]
                    (reset! (:clients m) {"broken" c})
                    (let [out (format-status-table m)]
                      (-> (expect (.includes out "broken")) (.toBe true))
                      (-> (expect (.includes out "spawn ENOENT")) (.toBe true))))))

            (it "doesn't throw for a single-server registry (the squint clojure.string regression)"
                (fn []
                  ;; The original bug surfaced only when there was at least
                  ;; one row to format (the rows path called clojure.string/
                  ;; join without the namespace required). Lock the contract.
                  (let [m (mgr/create)]
                    (reset! (:clients m) {"x" (fake-client "x" :running)})
                    ;; Direct call must not throw. If clojure.string/join
                    ;; isn't properly required, this call throws synchronously.
                    (-> (expect (string? (format-status-table m))) (.toBe true)))))))
