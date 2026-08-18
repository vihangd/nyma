(ns agent.extensions.refine.mining
  "Deterministic session mining — pure functions over session entries.

   Every signal here is one that found a real bug by hand: a session that ran
   1188 tool calls across 61 turns while 56% of its turns did no work at all,
   compactions that reduced nothing, commands re-run verbatim, one file read 14
   times. The material was on disk the whole time and nothing read it.

   Deliberately model-free. These cost nothing, cannot hallucinate, and are what
   actually surfaced the problems; a model is only asked to NAME the lesson
   afterwards, and only when there is something to name.

   Not reusing small_model/quality_monitor: its predicates are private and shaped
   for live turn events (stream deltas, tool-call names as they happen), not for
   a JSONL tree read after the fact."
  (:require [clojure.string :as str]))

;; ── Shaping ──────────────────────────────────────────────────────

(defn- entry-role [e] (str (or (:role e) (get e "role") "")))
(defn- entry-content [e] (str (or (:content e) (get e "content") "")))

(defn- tool-name [e]
  (let [m (or (:metadata e) (get e "metadata"))]
    (when m (str (or (:tool-name m) (get m "tool-name") "")))))

(defn- tool-args [e]
  (let [m (or (:metadata e) (get e "metadata"))]
    (when m (or (:args m) (get m "args")))))

(defn- arg-get [args k]
  (when args (or (get args k) (get args (str k)))))

(defn- canonical-command
  "A bash command with the wrappers stripped, so identical work collapses.

   Both prefixes are added by nyma, not typed by the model: the env scrub from
   bash_suite/env_filter and the cwd prefix from cwd_manager. Comparing raw
   strings would make every command look unique."
  [cmd]
  (-> (str cmd)
      (str/replace #"^unset [^;]*;\s*" "")
      (str/replace #"(cd '[^']*' && )+" "")
      str/trim))

;; ── Signals ──────────────────────────────────────────────────────

(defn turn-tool-counts
  "Tool calls per user-delimited turn, in order."
  [entries]
  (let [counts (atom []) cur (atom 0) started? (atom false)]
    (doseq [e entries]
      (let [r (entry-role e)]
        (cond
          (= r "user") (do (when @started? (swap! counts conj @cur))
                           (reset! started? true) (reset! cur 0))
          (= r "tool_call") (swap! cur inc)
          :else nil)))
    (when @started? (swap! counts conj @cur))
    @counts))

(defn no-op-turns
  "Turns that ran no tools, plus the longest consecutive run.

   One is normal — an answer or a question. A RUN of them is the signature of a
   context-rot collapse: the model still talks but stops acting."
  [entries]
  (let [counts (turn-tool-counts entries)
        total  (count counts)
        zeros  (count (filter zero? counts))
        longest (:best (reduce (fn [acc n]
                                 (if (zero? n)
                                   (let [c (inc (:cur acc))]
                                     {:cur c :best (max c (:best acc))})
                                   (assoc acc :cur 0)))
                               {:cur 0 :best 0} counts))]
    (when (and (pos? total) (>= longest 3))
      {:kind :no-op-turns
       :turns total
       :zero-turns zeros
       :longest-run longest
       :detail (str zeros " of " total " turns ran no tools; longest run "
                    longest " in a row")})))

(defn repeated-commands
  "Identical bash commands run more than twice, wrappers stripped."
  [entries]
  (let [freq (->> entries
                  (filter #(= "bash" (tool-name %)))
                  (keep #(let [c (canonical-command (arg-get (tool-args %) :command))]
                           (when (seq c) c)))
                  frequencies)
        repeats (->> freq (filter (fn [[_ n]] (>= n 3))) (sort-by (fn [[_ n]] (- n))))]
    (when (seq repeats)
      {:kind :repeated-commands
       :items (mapv (fn [[c n]] {:count n :command (subs c 0 (min 120 (count c)))})
                    (take 5 repeats))
       :detail (str (count repeats) " command(s) run 3+ times identically")})))

(defn re-read-files
  "Files read repeatedly. Re-reading is a named contributor to context rot —
   and it means the earlier read stopped being findable."
  [entries]
  (let [freq (->> entries
                  (filter #(= "read" (tool-name %)))
                  (keep #(arg-get (tool-args %) :path))
                  frequencies)
        heavy (->> freq (filter (fn [[_ n]] (>= n 4))) (sort-by (fn [[_ n]] (- n))))
        total (reduce + 0 (vals freq))
        uniq  (count freq)]
    (when (seq heavy)
      {:kind :re-read-files
       :items (mapv (fn [[p n]] {:count n :path p}) (take 5 heavy))
       :redundant (- total uniq)
       :detail (str (- total uniq) " of " total " reads were repeats")})))

(defn correction-messages
  "User messages that read as corrections — the cheapest ground truth there is
   for 'the agent did the wrong thing'."
  [entries]
  (let [pat #"(?i)\b(didn'?t|did not|you (?:still )?(?:haven'?t|have not)|why (?:didn'?t|not)|wrong|instead|i said|as i said|actually|no,)\b"
        hits (->> entries
                  (filter #(= "user" (entry-role %)))
                  (map entry-content)
                  (filter #(re-find pat %)))]
    (when (seq hits)
      {:kind :corrections
       :items (mapv #(let [t (str/join " " (str/split % #"\s+"))]
                       (subs t 0 (min 140 (count t))))
                    (take 5 hits))
       :detail (str (count hits) " user message(s) read as corrections")})))

(defn mine
  "All signals present in a session. Empty vector for a clean session — the
   caller must treat that as 'say nothing and stop', not 'ask a model anyway'."
  [entries]
  (let [es (vec entries)]
    (vec (keep identity
               [(no-op-turns es)
                (repeated-commands es)
                (re-read-files es)
                (correction-messages es)]))))
