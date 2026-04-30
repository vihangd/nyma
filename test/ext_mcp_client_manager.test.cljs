(ns ext-mcp-client-manager.test
  "Manager-level orchestration tests. We don't need real MCP servers
   here — we exercise start-all! / stop-all! / accessor flow against
   `client.cljs`'s state atoms directly. The integration test layer
   covers the actual SDK round-trip."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]))

;;; ─── Pure accessors ──────────────────────────────────────────────

(describe "manager/create"
          (fn []
            (it "starts empty"
                (fn []
                  (let [m (mgr/create)]
                    (-> (expect (count (mgr/server-names m))) (.toBe 0))
                    (-> (expect (count (mgr/all-clients m))) (.toBe 0)))))

            (it "summary reports zero across the board"
                (fn []
                  (let [m (mgr/create)
                        s (mgr/summary m)]
                    (-> (expect (:total s)) (.toBe 0))
                    (-> (expect (:running s)) (.toBe 0))
                    (-> (expect (:error s)) (.toBe 0)))))))

;;; ─── start-all! / stop-all! against fakes ────────────────────────

;; We use a config pointing at /usr/bin/false (always exits non-zero)
;; for "fail" servers and a fake client install for "success" servers.
;; Since start-all! constructs clients via `client/create` then calls
;; `client/start!`, we pre-create fakes and stuff them in via the
;; clients atom AFTER start-all! kicks off. Cleaner: drive lower-level
;; APIs directly.

(defn ^:async test-stop-all-clears-registry []
  (let [m (mgr/create)]
    ;; Manually install two stopped-state clients
    (let [c1 (client/create {:name "a" :command "echo"})
          c2 (client/create {:name "b" :command "echo"})]
      (reset! (:clients m) {"a" c1 "b" c2})
      (-> (expect (count (mgr/server-names m))) (.toBe 2))
      (js-await (mgr/stop-all! m))
      (-> (expect (count (mgr/server-names m))) (.toBe 0)))))

(defn ^:async test-summary-counts-states-correctly []
  (let [m (mgr/create)
        c1 (client/create {:name "running"  :command "echo"})
        c2 (client/create {:name "starting" :command "echo"})
        c3 (client/create {:name "errored"  :command "echo"})
        c4 (client/create {:name "idle"     :command "echo"})]
    (reset! (:state c1) :running)
    (reset! (:state c2) :starting)
    (reset! (:state c3) :stopped-error)
    (reset! (:state c4) :stopped)
    (reset! (:clients m) {"a" c1 "b" c2 "c" c3 "d" c4})
    (let [s (mgr/summary m)]
      (-> (expect (:total s)) (.toBe 4))
      (-> (expect (:running s)) (.toBe 1))
      (-> (expect (:starting s)) (.toBe 1))
      (-> (expect (:error s)) (.toBe 1))
      (-> (expect (:stopped s)) (.toBe 1)))))

(defn ^:async test-all-clients-deterministic-order []
  ;; Order must be sorted by name so the status line / /mcp status
  ;; output is stable across runs.
  (let [m (mgr/create)
        c1 (client/create {:name "zebra" :command "echo"})
        c2 (client/create {:name "alpha" :command "echo"})
        c3 (client/create {:name "mango" :command "echo"})]
    (reset! (:clients m) {"zebra" c1 "alpha" c2 "mango" c3})
    (let [names (mapv :name (mgr/all-clients m))]
      (-> (expect (first names)) (.toBe "alpha"))
      (-> (expect (second names)) (.toBe "mango"))
      (-> (expect (last names)) (.toBe "zebra")))))

(describe "manager/state"
          (fn []
            (it "stop-all! empties the registry"
                test-stop-all-clears-registry)
            (it "summary counts each state correctly"
                test-summary-counts-states-correctly)
            (it "all-clients returns a deterministic, name-sorted order"
                test-all-clients-deterministic-order)))

;;; ─── start-all! against a failing config ─────────────────────────

;; Use a real binary that fails fast: /bin/false (or echo on stderr).
;; The bridge's command handler tests use this pattern reliably.

(defn ^:async test-start-all-failure-doesnt-block []
  (let [m (mgr/create)
        ;; Two configs: one that fails fast, one that fails on connect
        ;; due to non-MCP behavior. Both should reach :stopped-error
        ;; without throwing from start-all!.
        configs [{:name    "fail-1"
                  :command "/bin/false"
                  :args    []
                  :startup-timeout-ms 2000
                  :max-restarts 0}
                 {:name    "fail-2"
                  :command "/bin/false"
                  :args    []
                  :startup-timeout-ms 2000
                  :max-restarts 0}]
        result (js-await (mgr/start-all! m configs))]
    ;; Both should be :failed (or at least not :ok). What matters
    ;; is that start-all! returned a result without throwing.
    (-> (expect (= (count (:ok result)) 0)) (.toBe true))
    (-> (expect (= (count (:failed result)) 2)) (.toBe true))
    ;; The clients are still in the registry so /mcp status can list them.
    (-> (expect (count (mgr/server-names m))) (.toBe 2))
    (js-await (mgr/stop-all! m))))

(describe "manager/start-all!"
          (fn []
            (it "doesn't block other servers when one fails to start"
                test-start-all-failure-doesnt-block)))
