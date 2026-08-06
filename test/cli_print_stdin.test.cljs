(ns cli-print-stdin.test
  "Print mode must survive piped stdin.

   `resolve-one-shot-prompt` bound a local named `pos?`, which shadows core/pos?
   for every later binding — `let` binds sequentially. The stdin branch then
   called a boolean and print mode died with
   `TypeError: pos_QMARK_2 is not a function`, for EVERY non-TTY invocation:
   pipes, scripts, CI. It compiled cleanly and no unit test reached it, because
   the path needs real piped input.

   So this drives the actual CLI as a subprocess with stdin piped. It uses a
   deliberately unresolvable model so the run stops at model resolution and
   never makes a network call — the assertion is about HOW it fails."
  (:require ["bun:test" :refer [describe it expect]]))

(defn ^:async run-print
  "Run the CLI in print mode with `input` on stdin. Returns combined output."
  [input args]
  (let [proc (js/Bun.spawn
              (clj->js (concat ["bun" "dist/agent/cli.mjs" "-p" "--no-session"] args))
              #js {:stdin "pipe" :stdout "pipe" :stderr "pipe"
                   :env (js/Object.assign
                         #js {} js/process.env
                         ;; Never touch the network from the test suite.
                         #js {"NYMA_NO_MODEL_DISCOVERY" "1"})})]
    (.write (.-stdin proc) input)
    (.end (.-stdin proc))
    (let [out (js-await (js/Bun.readableStreamToText (.-stdout proc)))
          err (js-await (js/Bun.readableStreamToText (.-stderr proc)))]
      (js-await (.-exited proc))
      (str out "\n" err))))

(defn ^:async test-piped-stdin []
  ;; One spawn, both halves — starting a bun subprocess is slow enough that a
  ;; second identical run risks the suite timeout under load.
  (let [output (js-await (run-print "some piped text\n" ["--model" "nyma-test/unresolvable"]))]
    ;; The regression, stated exactly: a shadowed core fn surfaces as a
    ;; TypeError naming the munged symbol.
    (-> (expect (.includes output "is not a function")) (.toBe false))
    (-> (expect (.includes output "pos_QMARK")) (.toBe false))
    ;; Positive half: it must get PAST prompt assembly. Failing on the bogus
    ;; provider proves the stdin branch ran and produced a prompt.
    (-> (expect (or (.includes output "nyma-test")
                    (.includes output "No model configured")))
        (.toBe true))))

(defn ^:async test-positional-plus-stdin []
  ;; Both sources present — the branch that reads the shadowed binding twice.
  (let [output (js-await (run-print "piped tail\n"
                                    ["positional head" "--model" "nyma-test/unresolvable"]))]
    (-> (expect (.includes output "is not a function")) (.toBe false))))

(describe "cli print mode with piped stdin"
          (fn []
            ;; Explicit timeouts: each case spawns a bun subprocess, which
            ;; exceeds the 5s default when the whole suite is running.
            (it "assembles a prompt from piped stdin without crashing"
                test-piped-stdin 30000)
            (it "handles a positional argument combined with stdin"
                test-positional-plus-stdin 30000)))
