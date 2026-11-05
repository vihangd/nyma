(ns agent.utils.stall
  "Is a run repeating itself? Pure.

   A repeat is the same call with the same result. Arguments alone are not
   enough: fix-then-verify re-runs one test command byte for byte, which is the
   work and not a loop.")

(defn result-fingerprint
  "Cheap identity for a tool result — length plus a bounded head/tail slice.
   Enough to tell \"the same command returned the same thing\" from \"the same
   command returned something new\" without holding whole outputs. Pure."
  [result]
  (let [s (str (or result ""))
        n (count s)]
    (if (<= n 240)
      (str n ":" s)
      (str n ":" (subs s 0 120) "…" (subs s (- n 120))))))

(defn tool-call-sig
  "Simple signature for a tool call — used to detect exact repeats."
  [tool-name args]
  (str tool-name ":" (try (js/JSON.stringify args)
                          (catch :default _ (str args)))))

(def max-cycle
  "Longest repeating sequence looked for, as Gemini CLI does: one call, a
   ping-pong pair, up to a five-call round."
  5)

(defn repeat-cycle
  "The length of the cycle that fills the tail of `sigs` `reps` times, or nil."
  [sigs reps]
  (let [v (vec sigs)
        n (count v)]
    (some (fn [len]
            (when (and (pos? reps) (>= n (* len reps)))
              (let [tail (subvec v (- n (* len reps)))]
                (when (every? (fn [i] (= (nth tail i) (nth tail (mod i len))))
                              (range (count tail)))
                  len))))
          (range 1 (inc max-cycle)))))
