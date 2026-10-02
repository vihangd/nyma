(ns bench-reap-test
  "The benchmark harness must not leak a task's processes.

   This replaces `bench_reap_lint.test.cljs`, which scanned `bench/run.mjs` as
   TEXT for the string `reap();`. That lint was written on a false premise —
   that bench ESM cannot be imported from `dist/` — which
   `test/bench_scoring.test.cljs` disproves by importing
   `\"../bench/scoring.mjs\"` and asserting against it. The only real obstacle
   was that `run()` was not exported and `main()` ran unconditionally on
   import. Both are fixed, so the behaviour can be tested directly.

   Which matters, because the lint was weak in three ways and I only found out
   by planting violations: its first version cleared a file because a COMMENT
   mentioned the matcher; its second missed the named-set form; and even after
   both fixes its 8-line lookback could borrow a sibling branch's `reap();`,
   its `timedOut: true` exemption whitelisted the exact asymmetry it guarded,
   and it matched only the literal `resolve({`.

   The bug it was written for: the harness spawns each task `detached` so one
   kill reaches the whole tree, and the TIMEOUT path did that. Normal
   completion did not. A solution the agent writes can spawn workers that
   outlive the test runner — they release the inherited stdout pipe, so
   `'close'` fires and the task is scored a clean pass, while the workers keep
   running. Measured once: a `jest` parent and 86 node workers alive for seven
   days, ~359 MB, pointing at a temp directory the harness had already
   deleted."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["../bench/run.mjs" :as harness]))

(defn- alive?
  "Is `pid` still running? `kill -0` signals nothing and only tests existence."
  [pid]
  (try (js/process.kill pid 0) true (catch :default _e false)))

(describe "run() reaps the task's process group"
          (fn []
            (it "a grandchild that outlives the child is killed when run() resolves"
                (^:async fn []
                  (let [pidfile (path/join (os/tmpdir) (str "nyma-reap-" (js/Date.now)))
              ;; The shape that leaked: the direct child exits immediately
              ;; (so `'close'` fires and the task is scored), leaving a
              ;; grandchild running with no hold on the stdout pipe.
                        ;; `>/dev/null 2>&1` matters: the grandchild must RELEASE the
              ;; inherited stdout pipe, or `'close'` waits on it and the task
              ;; is not scored as complete at all — which is the separate
              ;; failure the timeout path's comment describes. The real case
              ;; behaved this way (jest workers with their own stdio), and it
              ;; is what makes the leak invisible: the run looks clean.
              script  (str "( sleep 30 >/dev/null 2>&1 & echo $! > " pidfile " ) ; exit 0")
                        _       (js-await (.run harness "bash" #js ["-c" script]
                                                #js {:timeoutMs 10000}))
              ;; The pid file is written by the subshell; give it a beat.
                        _       (js-await (js/Promise. (fn [r] (js/setTimeout r 300))))
                        pid     (js/parseInt (.trim (fs/readFileSync pidfile "utf8")) 10)]
                    (-> (expect (js/Number.isNaN pid)) (.toBe false))
          ;; THE assertion. Before the fix this grandchild survived the run.
                    (-> (expect #js [pid (alive? pid)]) (.toEqual #js [pid false]))
                    (try (fs/unlinkSync pidfile) (catch :default _e nil))))
      15000)

            (it "still returns the child's exit code and output"
                (^:async fn []
        ;; Reaping must not cost the harness its result — the whole point of
        ;; the SIGTERM-before-SIGKILL dance on the timeout path is that a
        ;; killed task still reports what it had.
                  (let [r (js-await (.run harness "bash" #js ["-c" "echo hello; exit 3"]
                                          #js {:timeoutMs 10000}))]
                    (-> (expect (.-exitCode r)) (.toBe 3))
                    (-> (expect (.-stdout r)) (.toContain "hello"))
                    (-> (expect (.-timedOut r)) (.toBeFalsy)))))

            (it "reports a timeout as a timeout, not as a failure"
                (^:async fn []
                  (let [r (js-await (.run harness "bash" #js ["-c" "sleep 30"]
                                          #js {:timeoutMs 700}))]
                    (-> (expect (.-timedOut r)) (.toBe true)))))

            (it "an already-dead group is not an error"
                (^:async fn []
        ;; `killTree` swallows ESRCH, which is the usual case: by the time the
        ;; group is reaped it is normally already empty.
                  (let [r (js-await (.run harness "bash" #js ["-c" "exit 0"]
                                          #js {:timeoutMs 5000}))]
                    (-> (expect (.-exitCode r)) (.toBe 0)))))))
