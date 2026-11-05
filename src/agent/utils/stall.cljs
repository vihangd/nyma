(ns agent.utils.stall
  "Is a run repeating itself? Pure.

   A repeat is the same call with the same result. Arguments alone are not
   enough: fix-then-verify re-runs one test command byte for byte, which is the
   work and not a loop."
  (:require [clojure.string :as str]
            [agent.tool-metadata :as tool-metadata]))

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

;; ── what a pass did ──────────────────────────────────────────────

(def ^:private digest-steps
  "Steps a digest shows in full. Bounded because it reaches the transcript as
   part of a user message."
  30)

(defn- clip [x n]
  (let [s (if (string? x) x (or (js/JSON.stringify x) ""))
        s (str/replace s #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn step-record
  "Pure: an AI SDK StepResult as `{:lines [...] :files [...]}`: one short line per
   tool call and the paths its edit tools wrote."
  [step]
  (let [results (or (.-toolResults step) #js [])
        said    (str/trim (str (or (.-text step) "")))]
    {:lines (cond-> []
              (seq said) (conj (str "said: " (clip said 160)))
              :always    (into (map (fn [r]
                                      (str (.-toolName r) " " (clip (.-input r) 100)
                                           " -> " (clip (.-output r) 120)))
                                    results)))
     :files (vec (keep (fn [r]
                         (when (tool-metadata/matches-any-tool-name?
                                (.-toolName r) #{"edit" "write" "multi_edit"})
                           (when-let [in (.-input r)] (or (.-path in) (.-file_path in)))))
                       results))}))

(defn add-step
  "Fold one step into a run's `{:lines :files :dropped}`, keeping the latest."
  [digest step]
  (let [{:keys [lines files]} (step-record step)
        all  (into (vec (:lines digest)) lines)
        over (max 0 (- (count all) digest-steps))]
    {:lines   (subvec all over)
     :files   (into (or (:files digest) #{}) files)
     :dropped (+ (or (:dropped digest) 0) over)}))

(defn digest-text
  "The digest as text, or \"\" when the pass did nothing."
  [{:keys [lines files dropped]}]
  (if (empty? lines)
    ""
    (str (when (seq files)
           (str "Files edited: " (str/join ", " (sort files)) "\n"))
         (when (pos? (or dropped 0))
           (str "(" dropped " earlier entries not shown)\n"))
         (str/join "\n" lines))))
