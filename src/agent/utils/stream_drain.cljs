(ns agent.utils.stream-drain
  "Draining a subprocess pipe without waiting on processes we do not own.

   Killing a shell does not kill what the shell forked. `bash -c \"sleep 5\"`
   execs sleep on macOS, so killing the shell kills sleep too — but on Linux,
   and for any command bash does not optimise into an exec (`sleep 5; true`),
   the shell forks and the grandchild survives. It inherits stdout and stderr,
   so the pipe never reaches EOF, and a plain `.text()` on it blocks for as long
   as the orphan lives.

   Measured locally, killing the shell after 100 ms:

     bash -c \"sleep 5\"        exited@106ms  streams-drained@107ms
     bash -c \"sleep 5; true\"  exited@102ms  streams-drained@5005ms

   That second line is why three kill-related tests timed out at exactly 5000 ms
   on CI while passing on macOS.

   So: read incrementally, and stop when told to. The process is gone; whatever
   is still holding the pipe is not ours to wait for, and partial output beats
   hanging. Reading must also start BEFORE the process is awaited — a command
   that fills the 64 KB pipe buffer blocks on write until someone reads, so
   draining only after `exited` deadlocks."
  (:require [clojure.string :as str]))

;; aget/aset with a plain string key, never `.-__drain-stop`: squint compiles a
;; hyphenated field access to an underscored one (`.__drain_stop`) while the
;; object literal keeps the hyphen, so the check silently never matches — which
;; here meant racing forever instead of stopping.
(def ^:private stop-key "__drainStop")
(def ^:private stop-sentinel (doto #js {} (aset stop-key true)))

(defn stop-signal
  "A promise that resolves `ms` from now, for passing to `read-text-until`."
  [ms]
  (js/Promise. (fn [res] (js/setTimeout (fn [] (res stop-sentinel)) ms))))

(defn deferred-stop
  "{:promise p :stop-in! (fn [ms])} — a stop signal whose countdown starts when
   the caller says so.

   Draining has to START before the process is awaited (pipe-buffer deadlock),
   but the grace period only makes sense AFTER it exits. This separates the two:
   hand `:promise` to `read-text-until` up front, call `:stop-in!` once the
   process is gone."
  []
  (let [resolve-fn (atom nil)
        p (js/Promise. (fn [res] (reset! resolve-fn res)))]
    {:promise  p
     :stop-in! (fn [ms] (js/setTimeout (fn [] (@resolve-fn stop-sentinel)) ms))}))

(defn ^:async read-text-until
  "Drain `stream` into a string, giving up when `stop` resolves.

   Returns whatever arrived before that — a killed command's partial output is
   worth more than a hang. `stop` may be nil to drain to EOF."
  [stream stop]
  (if (or (nil? stream) (false? stream))
    ""
    (let [reader  (.getReader stream)
          decoder (js/TextDecoder.)
          chunks  (atom [])]
      (try
        (loop []
          (let [next-read (.read reader)
                r         (js-await (if stop
                                      (js/Promise.race #js [next-read stop])
                                      next-read))]
            (cond
              ;; The stop signal won the race. Leave the read in flight; the
              ;; reader is cancelled below, which settles it.
              (and r (aget r stop-key)) nil
              (or (nil? r) (.-done r))   nil
              :else (do (swap! chunks conj
                               (.decode decoder (.-value r) #js {:stream true}))
                        (recur)))))
        (catch :default _e nil))
      ;; Release the pipe either way, so a still-running orphan's writes go
      ;; nowhere instead of holding a reader we abandoned.
      (try (.cancel reader) (catch :default _e nil))
      (str/join @chunks))))
