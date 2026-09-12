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

(defn- outcome
  "exit code plus stderr. run-command returns exit-code 1 both for a command
   that exited 1 and for a spawn that threw, so an assertion on the bare number
   cannot say which — and on CI it was the second."
  [r]
  (str "exit=" (:exit-code r) " stderr=" (:stderr r)))

(defn ^:async test-exit-zero-plain-stdout []
  (let [r (js-await (run-command {:command "echo hello"
                                  :stdin-json {}}))]
    (-> (expect (outcome r)) (.toBe "exit=0 stderr="))
    (-> (expect (.includes (:stdout r) "hello")) (.toBe true))))

(defn ^:async test-stdin-ignored-by-the-command []
  ;; The command never reads stdin and exits at once, so the pipe's reader can
  ;; be gone before the payload is written: EPIPE, which Bun delivers as a
  ;; rejected promise nobody awaits.
  ;;
  ;; Honest about what this does and does not prove: it is a smoke test, not a
  ;; mutation-verified guard. Removing the fix does not make it fail on macOS or
  ;; in an arm64 Linux container — the race does not reproduce through
  ;; run-command there, while a direct Bun.spawn of the same shape reproduces it
  ;; every time. What is pinned here is the observable rule (a command that
  ;; ignores stdin must produce no unhandled rejection and exit 0); the
  ;; behaviour it guards was measured with that direct probe.
  (let [seen (atom [])
        on-rej (fn [e] (swap! seen conj (or (.-code e) (str e))))]
    (.on js/process "unhandledRejection" on-rej)
    (let [big (.repeat "x" 200000)
          r   (js-await (run-command {:command "true"
                                      :stdin-json {:blob big}}))]
      ;; Give a rejection a turn of the loop to surface before we look.
      (js-await (js/Promise. (fn [res] (js/setTimeout res 100))))
      (.off js/process "unhandledRejection" on-rej)
      (-> (expect (vec @seen)) (.toEqual #js []))
      (-> (expect (outcome r)) (.toBe "exit=0 stderr=")))))

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
    (-> (expect (str "exit=" (:exit-code r))) (.toBe "exit=2"))
    (-> (expect (:stderr r)) (.not.toContain "spawn error"))
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
                                 (it "captures exit 2 with stderr (blocking)" test-exit-2-blocking)
                                 (it "survives a command that never reads stdin"
                                     test-stdin-ignored-by-the-command)))

(defn ^:async test-timeout-returns-when-the-shell-forked []
  ;; `sleep 5` alone is exec'd by bash, so killing the shell kills sleep and the
  ;; pipes close. `sleep 5; true` makes bash fork instead — the shell dies, the
  ;; orphan keeps stdout open, and an unbounded drain waits the full 5s for a
  ;; process we did not spawn. That is the Linux default for far more command
  ;; shapes than macOS, and it timed out three CI tests at exactly 5000ms while
  ;; passing here.
  (let [start (js/Date.now)
        r     (js-await (run-command {:command "echo hi; sleep 5; true"
                                      :timeout-ms 100
                                      :stdin-json {}}))]
    (-> (expect (- (js/Date.now) start)) (.toBeLessThan 2000))
    (-> (expect (:timed-out? r)) (.toBe true))
    ;; Partial output survives the kill.
    (-> (expect (:stdout r)) (.toContain "hi"))))

(describe "command/timeout-and-abort" (fn []
                                        (it "kills a hung process on timeout" test-timeout-kills-process)
                                        (it "kills a process when abort signal fires" test-abort-signal-kills-process)
                                        (it "returns promptly when the shell forked and the child outlives it"
                                            test-timeout-returns-when-the-shell-forked)))

(describe "command/json-roundtrip" (fn []
                                     (it "passes through JSON in both directions" test-json-stdout-roundtrip)))
