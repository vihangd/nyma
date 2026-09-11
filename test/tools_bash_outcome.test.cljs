(ns tools-bash-outcome.test
  "A command can be several things at once. Reporting only the exit code let a
   run that was cut short at its timeout read to the model as a clean success —
   `trap \"exit 0\" TERM` in a Makefile or a test runner's cleanup is all it
   takes. Borrowed from deepseek-harness's defensive-patterns rule."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tools :refer [bash-execute]]))

(defn- run [args]
  (-> (bash-execute args) (.then (fn [s] (js/JSON.parse s)))))

(defn ^:async t-plain-timeout []
  (let [r (js-await (run {:command "echo partial; sleep 1" :timeout 200}))]
    (-> (expect (.-timedOut r)) (.toBe true))
    ;; The partial output is still handed over — a cut-short run is not an empty
    ;; one, and the head of it is often the useful part.
    (-> (expect (.-stdout r)) (.toContain "partial"))))

(defn ^:async t-trapped-timeout-is-not-success []
  ;; The case that read as a clean success before: the command traps SIGTERM and
  ;; exits 0, so exitCode alone says everything went fine.
  (let [r (js-await (run {:command "trap 'exit 0' TERM; sleep 1" :timeout 200}))]
    (-> (expect (.-exitCode r)) (.toBe 0))
    (-> (expect (.-timedOut r)) (.toBe true))))

(defn ^:async t-normal-run-is-not-flagged []
  (let [r (js-await (run {:command "echo hi" :timeout 5000}))]
    (-> (expect (.-timedOut r)) (.toBe false))
    (-> (expect (.-exitCode r)) (.toBe 0))))

(defn ^:async t-nonzero-exit-is-not-a-timeout []
  (let [r (js-await (run {:command "exit 3" :timeout 5000}))]
    (-> (expect (.-exitCode r)) (.toBe 3))
    (-> (expect (.-timedOut r)) (.toBe false))))

(describe "bash reports orthogonal outcomes independently"
          (fn []
            (it "a plain timeout is named, not just exit 143" t-plain-timeout)
            (it "a trapped timeout is NOT reported as success" t-trapped-timeout-is-not-success)
            (it "a normal run is not flagged" t-normal-run-is-not-flagged)
            (it "a non-zero exit is not a timeout" t-nonzero-exit-is-not-a-timeout)))
