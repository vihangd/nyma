(ns cli-stdin-timeout-test
  "`-p \"prompt\"` must not block on a pipe nobody closes.

   `resolve-one-shot-prompt` binds sequentially, and the stdin branch ran
   whenever stdin was not a TTY — regardless of whether a positional prompt
   had been supplied. `read-stdin-string` resolved only on `\"end\"`, with no
   timeout and no race. So with stdin an open pipe (a wrapper, a supervisor, an
   editor task runner — all completely normal), `nyma -p \"prompt\"` blocked
   forever before the model was ever contacted.

   Measured both ways against a FIFO held open by a sleeping writer: the old
   build was still running after 20 seconds; the fixed build exits in 4.

   The suite could not reach this. Every existing spawn test
   (`cli_e2e`, `cli_oneshot_exit`, `cli_print_stdin`) calls `.end(stdin)`, so
   the open-pipe case was structurally excluded — which is why a hang this
   total went unnoticed. This file is that missing case.

   Note the sibling trap recorded at the same call site: a local named `pos?`
   once shadowed `core/pos?` for every later binding in the same `let`. Two
   separate defects in one `let`, both from sequential binding."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:stream" :refer [PassThrough]]
            [agent.cli :as cli]))

(defn ^:async test-silent-pipe []
  (let [warned (atom [])
        s      (js-await (cli/read-stream-string (new PassThrough) 10 50 #(swap! warned conj %)))]
    (-> (expect s) (.toBe ""))
    (-> (expect (count @warned)) (.toBe 1))
    (-> (expect (first @warned)) (.toContain "running without it"))))

(defn ^:async test-started-pipe []
  (let [p      (new PassThrough)
        warned (atom [])
        r      (cli/read-stream-string p 20 80 #(swap! warned conj %))]
    (.write p "first ")
    ;; past the first timeout, inside the idle one
    (js/setTimeout #(.write p "second") 50)
    (-> (expect (js-await r)) (.toBe "first second"))
    (-> (expect (first @warned)) (.toContain "using what arrived"))))

(defn ^:async test-closed-pipe []
  (let [p      (new PassThrough)
        warned (atom [])
        r      (cli/read-stream-string p 20 80 #(swap! warned conj %))]
    (.end p "all of it")
    (-> (expect (js-await r)) (.toBe "all of it"))
    (-> (expect (count @warned)) (.toBe 0))))

(describe "reading a pipe beside a prompt"
          (fn []
            (it "gives up on silence, and says so" test-silent-pipe)
            (it "keeps reading a pipe that has started" test-started-pipe)
            (it "says nothing when the pipe closes" test-closed-pipe)))

(describe "combine-prompt"
          (fn []
            (it "positional only"
                (fn []
                  (-> (expect (cli/combine-prompt "do the thing" nil)) (.toBe "do the thing"))))

            (it "stdin only"
                (fn []
                  (-> (expect (cli/combine-prompt nil "piped text")) (.toBe "piped text"))))

            (it "both — positional first, blank line, then stdin"
                (fn []
                  (-> (expect (cli/combine-prompt "summarise" "file contents"))
                      (.toBe "summarise\n\nfile contents"))))

            (it "neither is nil, so the caller can exit non-zero"
                (fn []
                  (-> (expect (cli/combine-prompt nil nil)) (.toBeNil))
                  (-> (expect (cli/combine-prompt "" "")) (.toBeNil))))

            (it "whitespace-only stdin counts as no stdin"
                (fn []
        ;; An open pipe that gets flushed with a newline must not turn into a
        ;; prompt of its own.
                  (-> (expect (cli/combine-prompt "do it" "   \n  ")) (.toBe "do it"))
                  (-> (expect (cli/combine-prompt nil "   \n  ")) (.toBeNil))))

            (it "trims stdin but not the positional"
                (fn []
                  (-> (expect (cli/combine-prompt nil "  padded  ")) (.toBe "padded"))))))

(describe "the timeout constant"
          (fn []
            (it "is short enough not to be felt, and non-zero"
                (fn []
        ;; Zero would make the 'both' case drop stdin that is mid-write; a
        ;; large value reintroduces a stall the user would notice.
                  (-> (expect (> cli/stdin-with-prompt-timeout-ms 0)) (.toBe true))
                  (-> (expect (< cli/stdin-with-prompt-timeout-ms 1000)) (.toBe true))))))

(def ^:private cli-entry
  (path/join (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn- built? [] (fs/existsSync cli-entry))

;; Spawned, because the defect is in process I/O and nothing smaller can see
;; it. skipIf rather than a body guard — a test that cannot run must report
;; SKIPPED, not PASS (see ext_lsp_suite_integration, which got that wrong for
;; five tests).
((.skipIf describe (not (built?))) "spawned: an open pipe cannot stall -p"
                                   (fn []
                                     (it "exits promptly with a positional prompt and a stdin nobody closes"
                                         (^:async fn []
                                           (let [;; A scratch HOME. This passed `(os/tmpdir)` as HOME,
                                        ;; which is the REAL temp dir, so every run wrote
                                        ;; `$TMPDIR/.nyma/{debug.log,nyma.db,-shm,-wal}` and left
                                        ;; them there — and under `bun test --parallel` that would
                                        ;; be one SQLite file shared by every worker.
                                                 home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-stdin-home-"))
                                                 fifo (path/join (os/tmpdir) (str "nyma-stdin-" (js/Date.now)))
                                                 _    (js/Bun.spawnSync #js ["mkfifo" fifo])
             ;; A writer that holds the pipe open without ever closing it.
                                                 holder (js/Bun.spawn #js ["bash" "-c" (str "sleep 30 > " fifo)])
                                                 started (js/Date.now)
                                                 proc (js/Bun.spawn
                                                       #js ["bun" cli-entry "-p" "hi"
                                                            "--model" "nope/nope" "--output-format" "json"]
                                                       #js {:stdin (js/Bun.file fifo)
                                                            :cwd home
                                                            :stdout "pipe" :stderr "pipe"
                                                            :env #js {"NYMA_NO_MODEL_DISCOVERY" "1"
                                                                      "HOME" home
                                                                      "PATH" (.-PATH js/process.env) "TMPDIR" (os/tmpdir)}})
                                                 code (js-await (.-exited proc))
                                                 ms   (- (js/Date.now) started)]
                                             (try (.kill holder) (catch :default _e nil))
                                             (try (fs/unlinkSync fifo) (catch :default _e nil))
                                             (try (fs/rmSync home #js {:recursive true :force true})
                                                  (catch :default _e nil))
         ;; It must have EXITED. Before the fix this never returned at all.
                                             (-> (expect (number? code)) (.toBe true))
         ;; Generously bounded: the measured fixed run is ~4s, the broken one
         ;; was unbounded. Anything under 20s proves it is not waiting on EOF.
                                             (-> (expect #js [ms (< ms 20000)]) (.toEqual #js [ms true]))))
                                         30000)))
