(ns agent.ui.diff-renderer
  "Pure logic for rendering unified diffs with:
     * per-line type (added / removed / context)
     * optional intra-line word-level highlight when exactly one removed line
       is followed by exactly one added line
     * indent visualization so whitespace changes are not invisible

   Rendered output uses ANSI escape codes directly in the strings that
   diff_view.cljs then drops into <Text>. This keeps the core logic
   testable without React/Ink."
  (:require ["diff" :refer [diffWords]]))

;;; ─── ANSI helpers ─────────────────────────────────────────────

(def ^:private ESC "\u001b[")
(def ^:private INVERSE (str ESC "7m"))
(def ^:private NO-INVERSE (str ESC "27m"))
(def ^:private DIM (str ESC "2m"))
(def ^:private NO-DIM (str ESC "22m"))

;;; ─── Indent visualization ─────────────────────────────────────

(defn- split-leading-ws
  "Return [leading-ws rest] for the given string."
  [s]
  (if (or (nil? s) (= s ""))
    ["" ""]
    (let [m (.match s (js/RegExp. "^([ \\t]*)(.*)$"))]
      (if m
        [(aget m 1) (aget m 2)]
        ["" s]))))

(defn visualize-indent
  "Render leading whitespace visibly:
     spaces → dim '·'
     tabs   → dim ' →' (two chars so the indent width still matches 4 spaces)

   Tabs in the non-leading part of the line are expanded to 4 spaces."
  [line]
  (if (or (nil? line) (= line ""))
    (or line "")
    (let [[lead rest] (split-leading-ws line)
          vis-lead    (when (seq lead)
                        (str DIM
                             (-> lead
                                 (.replace (js/RegExp. " " "g") "\u00b7")
                                 (.replace (js/RegExp. "\\t" "g") " \u2192"))
                             NO-DIM))
          expanded    (.replace (or rest "") (js/RegExp. "\\t" "g") "    ")]
      (str (or vis-lead "") expanded))))

;;; ─── Intra-line word diff ─────────────────────────────────────

(defn- wrap-change
  "Wrap `s` in ANSI inverse so the change pops against the surrounding
   added/removed line color."
  [s]
  (str INVERSE s NO-INVERSE))

(defn render-intra-line-diff
  "Given the old and new content of a 1:1 replaced line, return
   {:removed-line :added-line} where each contains ANSI-inverse marks on
   the words that actually changed. Whitespace-only changes are visualized
   via visualize-indent instead of inverse.

   diffWords produces a sequence of change objects with
   {value, added, removed}. We walk the sequence twice — once to build the
   removed line (kept + removed), once to build the added line (kept + added)."
  [old-content new-content]
  (let [parts   (diffWords (or old-content "") (or new-content ""))
        removed (reduce
                  (fn [acc p]
                    (cond
                      (.-added p)    acc
                      (.-removed p)  (str acc (wrap-change (.-value p)))
                      :else          (str acc (.-value p))))
                  "" parts)
        added   (reduce
                  (fn [acc p]
                    (cond
                      (.-removed p)  acc
                      (.-added p)    (str acc (wrap-change (.-value p)))
                      :else          (str acc (.-value p))))
                  "" parts)]
    {:removed-line (visualize-indent removed)
     :added-line   (visualize-indent added)}))

;;; ─── Parsing one-line diff rows ───────────────────────────────

(def ^:private line-re
  (js/RegExp. "^([-+ ])\\s*(\\d+)\\s*\\|(.*)$"))

(defn parse-diff-line
  "Parse a single diff row of the form '+123| content', '-123| content',
   or ' 123| content'. Returns {:type :line-num :content} or nil when the
   line does not match."
  [line]
  (when (and line (not= line ""))
    (let [m (.match line line-re)]
      (when m
        (let [prefix (aget m 1)
              num    (js/parseInt (aget m 2) 10)
              content (aget m 3)]
          {:type    (case prefix
                      "+" :added
                      "-" :removed
                      " " :context
                      :context)
           :line-num num
           :content  content})))))

;;; ─── Block rendering ──────────────────────────────────────────

(defn- parse-all [diff-text]
  (->> (.split (or diff-text "") "\n")
       (keep parse-diff-line)
       vec))

(defn- run-length
  "Count how many consecutive rows starting at index i have the given type."
  [rows i type]
  (let [n (count rows)]
    (loop [j i
           cnt 0]
      (if (or (>= j n) (not= (:type (get rows j)) type))
        cnt
        (recur (inc j) (inc cnt))))))

(defn- apply-intra-line
  "Walk the parsed rows and, whenever a removed run of length 1 is
   immediately followed by an added run of length 1, rewrite both rows'
   :content to the intra-line highlighted form. N:M cases where either
   run has length ≠ 1 are left alone.

   We only consider position i as a candidate when it starts a removed
   run (its previous row is not removed), to avoid treating the tail of
   a 2:1 block as its own 1:1 pair."
  [rows]
  (let [v (vec rows)
        n (count v)]
    (loop [i 0
           out []]
      (if (>= i n)
        out
        (let [cur (get v i)
              prev (when (pos? i) (get v (dec i)))
              starts-removed-run? (and (= (:type cur) :removed)
                                       (not= (:type prev) :removed))]
          (if starts-removed-run?
            (let [rem-run (run-length v i :removed)
                  next-i  (+ i rem-run)
                  add-run (run-length v next-i :added)]
              (if (and (= rem-run 1) (= add-run 1))
                (let [nxt (get v next-i)
                      {:keys [removed-line added-line]}
                      (render-intra-line-diff (:content cur) (:content nxt))]
                  (recur (+ next-i 1)
                         (conj out
                               (assoc cur :content removed-line)
                               (assoc nxt :content added-line))))
                ;; N:M — pass the current row through.
                (recur (inc i) (conj out cur))))
            (recur (inc i) (conj out cur))))))))

(defn render-diff-block
  "Parse diff-text and return a vector of rows {:type :line-num :content}
   where adjacent 1:1 removed/added pairs have word-level intra-line marks
   and every :content has been run through visualize-indent."
  [diff-text]
  (let [rows (parse-all diff-text)
        marked (apply-intra-line rows)]
    (mapv (fn [row]
            ;; If row already had intra-line processing, its :content is
            ;; already visualized. Otherwise we still want indent hints.
            (update row :content
                    (fn [c]
                      (if (or (.includes (str c) INVERSE)
                              (.includes (str c) DIM))
                        c
                        (visualize-indent c)))))
          marked)))
