(ns claude-hook-bridge-http.test
  "HTTP handler tests. The actual fetch path is verified end-to-end
   via Bun.serve in one test; the env-var substitution logic (the
   hairier piece) is tested in isolation."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [agent.extensions.claude-hook-bridge.handlers.http :refer [run-http]]))

;;; ─── Round-trip via a localhost server ───────────────────────────────────

(def server (atom nil))
(def port (atom nil))

(beforeEach (fn []
              (let [s (js/Bun.serve
                       #js {:port 0
                            :fetch (fn [req]
                                     (-> (.text req)
                                         (.then (fn [body]
                                                  (js/Response.
                                                   (js/JSON.stringify
                                                    #js {:received body
                                                         :auth (or (.get (.-headers req) "authorization") "")}))))))})]
                (reset! server s)
                (reset! port (.-port s)))))

(afterEach (fn []
             (when @server
               (try (.stop @server) (catch :default _e nil)))))

(defn ^:async test-http-roundtrip []
  (let [r (js-await (run-http {:url        (str "http://localhost:" @port)
                               :stdin-json {:hello "world"}}))
        parsed (js/JSON.parse (:stdout r))]
    (-> (expect (:exit-code r)) (.toBe 0))
    (-> (expect (.includes (.-received parsed) "hello")) (.toBe true))))

(defn ^:async test-http-env-allowlist []
  ;; Set a test env var, allow it, verify it gets substituted.
  (aset js/process.env "TEST_HOOK_TOKEN" "secret-abc123")
  (let [r (js-await (run-http {:url         (str "http://localhost:" @port)
                               :headers     #js {:Authorization "Bearer $TEST_HOOK_TOKEN"}
                               :allowed-env #js ["TEST_HOOK_TOKEN"]
                               :stdin-json  {}}))
        parsed (js/JSON.parse (:stdout r))]
    (-> (expect (:exit-code r)) (.toBe 0))
    (-> (expect (.-auth parsed)) (.toBe "Bearer secret-abc123"))))

(defn ^:async test-http-env-not-allowlisted-passes-through []
  ;; If the env var isn't in the allowlist, $VAR stays literal —
  ;; this prevents arbitrary env exfiltration via a hostile hook config.
  (aset js/process.env "TEST_HOOK_LEAK" "should-not-appear")
  (let [r (js-await (run-http {:url         (str "http://localhost:" @port)
                               :headers     #js {:Authorization "Bearer $TEST_HOOK_LEAK"}
                               :allowed-env #js []
                               :stdin-json  {}}))
        parsed (js/JSON.parse (:stdout r))]
    (-> (expect (:exit-code r)) (.toBe 0))
    (-> (expect (.-auth parsed)) (.toBe "Bearer $TEST_HOOK_LEAK"))
    (-> (expect (.includes (.-auth parsed) "should-not-appear")) (.toBe false))))

(defn ^:async test-http-non-2xx []
  ;; Stop the server first so we get a connection error.
  (.stop @server)
  (let [r (js-await (run-http {:url         (str "http://localhost:" @port)
                               :stdin-json  {}
                               :timeout-ms  500}))]
    (-> (expect (not= 0 (:exit-code r))) (.toBe true))))

(describe "http/round-trip" (fn []
                              (it "POSTs JSON and reads the body back" test-http-roundtrip)))

(describe "http/env-substitution" (fn []
                                    (it "substitutes $VAR when name is in allowedEnvVars" test-http-env-allowlist)
                                    (it "leaves $VAR literal when not in allowlist" test-http-env-not-allowlisted-passes-through)))

(describe "http/errors" (fn []
                          (it "non-blocking error on connection failure" test-http-non-2xx)))
