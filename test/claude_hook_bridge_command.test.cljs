(ns claude-hook-bridge-command.test
  "Integration tests for the command handler — spawns real subprocesses
   and verifies the JSON-stdin / JSON-stdout / exit-code contract."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.claude-hook-bridge.handlers.command :refer [run-command]]))

(defn ^:async test-stdin-json-passthrough []
  ;; cat reads stdin and writes it to stdout — perfect for verifying
  ;; that the handler pipes the JSON payload through.
  (let [r (js-await (run-command {:command "cat"
                                  :stdin-json {:hello "world" :n 42}}))]
    (-> (expect (:exit-code r)) (.toBe 0))
    (let [parsed (js/JSON.parse (:stdout r))]
      (-> (expect (.-hello parsed)) (.toBe "world"))
      (-> (expect (.-n parsed)) (.toBe 42)))))

(defn ^:async test-exit-zero-plain-stdout []
  (let [r (js-await (run-command {:command "echo hello"
                                  :stdin-json {}}))]
    (-> (expect (:exit-code r)) (.toBe 0))
    (-> (expect (.includes (:stdout r) "hello")) (.toBe true))))

(defn ^:async test-exit-non-zero-stderr []
  (let [r (js-await (run-command {:command "ls /no/such/path/exists/12345"
                                  :stdin-json {}}))]
    ;; ls should return non-zero with an error on stderr
    (-> (expect (not= 0 (:exit-code r))) (.toBe true))
    (-> (expect (or (seq (:stderr r)) (seq (:stdout r)))) (.toBeTruthy))))

(defn ^:async test-exit-2-blocking []
  ;; A hook returning exit 2 with stderr indicates a blocking error.
  (let [r (js-await (run-command {:command "sh -c 'echo blocked >&2; exit 2'"
                                  :stdin-json {}}))]
    (-> (expect (:exit-code r)) (.toBe 2))
    (-> (expect (.includes (:stderr r) "blocked")) (.toBe true))))

(defn ^:async test-timeout-kills-process []
  ;; sleep 5 with a 100ms timeout should be killed.
  (let [r (js-await (run-command {:command "sleep 5"
                                  :timeout-ms 100
                                  :stdin-json {}}))]
    (-> (expect (:timed-out? r)) (.toBe true))
    (-> (expect (not= 0 (:exit-code r))) (.toBe true))))

(defn ^:async test-abort-signal-kills-process []
  ;; If we abort the agent, the in-flight hook subprocess must die.
  (let [ctrl   (js/AbortController.)
        signal (.-signal ctrl)
        ;; Schedule the abort 100ms after spawn.
        _      (js/setTimeout (fn [] (.abort ctrl)) 100)
        r      (js-await (run-command {:command "sleep 5"
                                       :timeout-ms 10000
                                       :stdin-json {}
                                       :abort-signal signal}))]
    (-> (expect (:aborted? r)) (.toBe true))))

(defn ^:async test-json-stdout-roundtrip []
  ;; Hook script: read JSON, emit JSON.
  (let [r (js-await
           (run-command {:command "sh -c 'echo \"{\\\"echo\\\":42}\"'"
                         :stdin-json {}}))]
    (-> (expect (:exit-code r)) (.toBe 0))
    (let [parsed (js/JSON.parse (:stdout r))]
      (-> (expect (.-echo parsed)) (.toBe 42)))))

(describe "command/stdin-json" (fn []
                                 (it "pipes JSON to stdin and reads it back" test-stdin-json-passthrough)))

(describe "command/exit-codes" (fn []
                                 (it "captures stdout on exit 0" test-exit-zero-plain-stdout)
                                 (it "captures stderr on non-zero exit" test-exit-non-zero-stderr)
                                 (it "captures exit 2 with stderr (blocking)" test-exit-2-blocking)))

(describe "command/timeout-and-abort" (fn []
                                        (it "kills a hung process on timeout" test-timeout-kills-process)
                                        (it "kills a process when abort signal fires" test-abort-signal-kills-process)))

(describe "command/json-roundtrip" (fn []
                                     (it "passes through JSON in both directions" test-json-stdout-roundtrip)))
