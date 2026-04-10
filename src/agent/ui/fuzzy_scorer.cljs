(ns agent.ui.fuzzy-scorer
  "Pure fuzzy-matching helpers used by the autocomplete provider.

   The matcher uses a 4-tier scoring scheme (lower is better, -1 means no
   match):

     0. empty query            → 0 (everything matches)
     1. exact (lowercase) match → 10
     2. prefix match           → 100 + distance-from-start
     3. contains substring     → 200 + substring-index
     4. subsequence match      → 400 + gap-penalty + boundary-penalty
     -. otherwise              → nil (excluded)

   Subsequence scoring rewards consecutive characters (good for
   acronym-style queries like 'tre' → 'tool-renderer') and penalizes gaps
   so that spread-out matches sink below tighter ones."
  (:require [clojure.string :as str]))

;;; ─── Single-match logic ─────────────────────────────────

(defn- subsequence-score
  "Return [matches? score] where `matches?` is true when every character
   of q appears in t in order. Score is a penalty-based number, lower is
   better. Returns nil when there's no match."
  [q t]
  (let [qn (count q)
        tn (count t)]
    (loop [qi 0
           ti 0
           last-match -1
           score 0]
      (cond
        (= qi qn) (+ score (* 2 (- tn qi)))  ;; small penalty for unmatched tail
        (>= ti tn) nil
        :else
        (let [qc (nth q qi)
              tc (nth t ti)]
          (if (= qc tc)
            (let [gap (if (= last-match -1) 0 (- ti last-match 1))
                  ;; Consecutive characters are cheap; gaps add penalty.
                  consec? (zero? gap)
                  ;; Matching at a word boundary (start or after separator)
                  ;; is a small bonus (negative penalty).
                  prev    (if (zero? ti) \space (nth t (dec ti)))
                  boundary? (or (zero? ti)
                                (= prev \space)
                                (= prev \/)
                                (= prev \_)
                                (= prev \-)
                                (= prev \.))
                  step (cond
                         consec? 1
                         boundary? (+ 3 gap)
                         :else (+ 5 gap))]
              (recur (inc qi) (inc ti) ti (+ score step)))
            (recur qi (inc ti) last-match score)))))))

(defn fuzzy-match
  "Score a single (query, text) pair.
   Returns {:matches bool :score number} — score is nil when matches is false.
   An empty query always returns {:matches true :score 0}."
  [query text]
  (cond
    (or (nil? query) (= query ""))
    {:matches true :score 0}

    (or (nil? text) (= text ""))
    {:matches false :score nil}

    :else
    (let [q (.toLowerCase (str query))
          t (.toLowerCase (str text))
          contain-idx (.indexOf t q)]
      (cond
        ;; Tier 1: exact
        (= q t)
        {:matches true :score 10}

        ;; Tier 2: prefix
        (.startsWith t q)
        {:matches true :score 100}

        ;; Tier 3: contains (at any position)
        (>= contain-idx 0)
        {:matches true :score (+ 200 contain-idx)}

        ;; Tier 4: subsequence fallback
        :else
        (if-let [sub (subsequence-score q t)]
          {:matches true :score (+ 400 sub)}
          {:matches false :score nil})))))

;;; ─── Multi-token filtering ──────────────────────────────

(defn- tokenize [query]
  (let [s (str/trim (or query ""))]
    (if (= s "")
      []
      (->> (str/split s #"\s+")
           (remove str/blank?)
           vec))))

(defn fuzzy-filter
  "Score every item in `items` against `query` and return the subset that
   matches, sorted by score (best first).

   get-text-fn is called on each item to extract the text to match
   against. When `query` is empty, all items are returned unchanged
   (same order).

   Multi-token queries (whitespace-separated) require that every token
   matches. The aggregated score is the sum of individual token scores."
  [items query get-text-fn]
  (let [tokens (tokenize query)]
    (if (empty? tokens)
      (vec items)
      (let [get-fn (or get-text-fn str)]
        (->> items
             (keep (fn [item]
                     (let [text (get-fn item)
                           scores (map #(fuzzy-match % text) tokens)]
                       (when (every? :matches scores)
                         {:item  item
                          :score (reduce + (map :score scores))}))))
             (sort-by :score)
             (mapv :item))))))
