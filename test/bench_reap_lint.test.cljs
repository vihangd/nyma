(ns bench-reap-lint-test
  "Every exit path in `bench/run.mjs`'s `run()` reaps the process group.

   A source scan rather than a behavioural test because `bench/run.mjs` is
   plain ESM that is never compiled into `dist/`, and `run()` is not exported —
   importing it would execute the harness.

   The bug this guards: the harness spawns each task `detached`, so the agent
   and everything it starts share a process group, and the TIMEOUT path killed
   that group. Normal completion did not. A solution the agent writes can spawn
   workers that outlive the test runner — they release the inherited stdout
   pipe, so `'close'` fires and the task is scored a clean pass, while the
   workers keep running. The harness then deletes the task's temp directory and
   moves on.

   Measured once: a single `parallel-letter-frequency` run left a `jest` parent
   and 86 node workers alive for seven days, ~359 MB, pointing at a temp
   directory that no longer existed. They also load the machine every later run
   is timed on, which is the quiet part — a leaked run makes the NEXT run's
   numbers worse."
  (:require ["bun:test" :refer [test expect describe]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private src
  (fs/readFileSync (path/join (js/process.cwd) "bench" "run.mjs") "utf8"))

(defn- run-body
  "The text of `function run(...)`, up to the next top-level `function`."
  []
  (let [start (.indexOf src "function run(")
        rest* (.slice src (+ start 1))
        next* (.indexOf rest* "\nfunction ")]
    (if (neg? next*) (.slice src start) (.slice src start (+ start 1 next*)))))

(describe "bench harness reaps the process group"
          (fn []
            (test "run() is still spawning detached — the whole premise"
                  (fn []
                    (-> (expect (run-body)) (.toContain "detached: true"))))

            (test "a reap helper exists"
                  (fn []
                    (-> (expect (run-body)) (.toContain "const reap ="))))

            (test "the close handler reaps before resolving"
                  (fn []
        ;; The path that actually leaked.
                    (let [b (run-body)
                          close-idx (.indexOf b "child.on(\"close\"")
                          seg (.slice b close-idx (+ close-idx 400))]
                      (-> (expect seg) (.toContain "reap()"))
                      (-> (expect (< (.indexOf seg "reap()") (.indexOf seg "resolve("))) (.toBe true)))))

            (test "the error handler reaps too"
                  (fn []
                    (let [b (run-body)
                          err-idx (.indexOf b "child.on(\"error\"")
                          seg (.slice b err-idx (+ err-idx 400))]
                      (-> (expect seg) (.toContain "reap()")))))

            (test "every resolve inside run() is preceded by a reap or is the timeout path"
                  (fn []
        ;; Catches a NEW exit path added without reaping, which is how this
        ;; regressed in the first place: the timeout branch was written with a
        ;; kill and the completion branch without one.
                    (let [b (run-body)
                          lines (str/split-lines b)
                          resolves (keep-indexed
                                    (fn [i l] (when (.includes l "resolve({") i))
                                    lines)
              ;; `reap();` — the CALL, with its semicolon. Matching `reap()`
              ;; loosely also matched the `const reap = …` DEFINITION, whose
              ;; body contains the killTree text, so this assertion passed
              ;; against the very regression it exists to catch. Found by
              ;; deleting the fix and seeing only the targeted test go red.
                          reaped? (fn [i]
                                    (let [window (str/join "\n" (subvec (vec lines)
                                                                        (max 0 (- i 8)) i))]
                                      (or (.includes window "reap();")
                            ;; the timeout branch kills the group itself and
                            ;; marks its resolve
                                          (.includes (nth lines i "") "timedOut: true"))))]
                      (-> (expect (pos? (count resolves))) (.toBe true))
                      (doseq [i resolves]
                        (-> (expect #js [i (reaped? i)]) (.toEqual #js [i true]))))))))
