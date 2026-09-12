(ns stream-drain.test
  "A killed command must not make us wait for the orphan it left behind.

   The shape, reproduced exactly: spawn `bash -c \"sleep 5; true\"` (the
   trailing command stops bash optimising itself into an exec, which is what
   Linux does for far more command shapes than macOS), kill the shell after
   100 ms, then read its stdout. The shell is gone in ~100 ms but the pipe stays
   open for the full 5 s, because `sleep` inherited it.

   Three tests timed out at exactly 5000 ms on CI for this reason and passed on
   macOS, where `bash -c \"sleep 5\"` execs."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.stream-drain :refer [read-text-until stop-signal]]))

(defn- readable-of
  "A ReadableStream over `strs`, emitted immediately."
  [strs]
  (js/ReadableStream.
   #js {:start (fn [ctrl]
                 (let [enc (js/TextEncoder.)]
                   (doseq [s strs] (.enqueue ctrl (.encode enc s)))
                   (.close ctrl)))}))

(defn ^:async t-drains-to-eof []
  (-> (expect (js-await (read-text-until (readable-of ["a" "b" "c"]) nil)))
      (.toBe "abc")))

(defn ^:async t-nil-stream-is-empty []
  (-> (expect (js-await (read-text-until nil (stop-signal 10)))) (.toBe "")))

(defn ^:async t-gives-up-on-an-orphan-held-pipe []
  ;; The real thing, not a mock: bash forks, we kill bash, sleep keeps the pipe.
  (let [proc  (js/Bun.spawn #js ["bash" "-c" "echo hello; sleep 5; true"]
                            #js {:stdout "pipe" :stderr "pipe"})
        start (js/Date.now)]
    (js/setTimeout (fn [] (try (.kill proc) (catch :default _ nil))) 100)
    (js-await (.-exited proc))
    (let [text    (js-await (read-text-until (.-stdout proc) (stop-signal 300)))
          elapsed (- (js/Date.now) start)]
      ;; The point: back well under the 5 s the orphan would have cost.
      (-> (expect elapsed) (.toBeLessThan 2000))
      ;; And what the command did manage to say is not thrown away.
      (-> (expect text) (.toContain "hello")))))

(defn ^:async t-unbounded-read-would-have-hung []
  ;; Guard the guard. If this ever stops taking ~5 s, the platform changed and
  ;; the test above is no longer proving anything — better to hear it here than
  ;; to keep a green test that guards nothing.
  (let [proc  (js/Bun.spawn #js ["bash" "-c" "sleep 2; true"]
                            #js {:stdout "pipe" :stderr "pipe"})
        start (js/Date.now)]
    (js/setTimeout (fn [] (try (.kill proc) (catch :default _ nil))) 100)
    (js-await (.-exited proc))
    (js-await (.text (js/Response. (.-stdout proc))))
    (-> (expect (- (js/Date.now) start)) (.toBeGreaterThan 1500))))

(describe "read-text-until"
          (fn []
            (it "drains a finished stream to EOF" t-drains-to-eof)
            (it "treats a missing stream as empty" t-nil-stream-is-empty)
            (it "stops waiting on a pipe an orphan still holds"
                t-gives-up-on-an-orphan-held-pipe)
            (it "an unbounded read really does hang on that pipe"
                t-unbounded-read-would-have-hung)))
