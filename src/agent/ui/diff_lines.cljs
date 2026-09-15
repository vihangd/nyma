(ns agent.ui.diff-lines
  "Line diff for the expanded `edit` view. Pure, no deps.

   `diff-lines` returns a vector of `[:- line]`, `[:+ line]` and `[:= line]`
   in transcript order. Plain LCS over lines — an edit tool call is a snippet,
   not a file, so the quadratic table is cheap. Above `lcs-cap` total lines it
   degrades to all-removed-then-all-added rather than allocating a table that
   could not be scrolled anyway.")

(def lcs-cap
  "Total (old + new) line count above which the LCS is skipped."
  2000)

(defn- split-lines
  "CRLF is normalised first: an editor that writes \\r\\n against a model that
   sends \\n would otherwise diff as every line replaced."
  [s]
  (if (or (nil? s) (= s ""))
    []
    (vec (.split (.replace (str s) (js/RegExp. "\\r\\n" "g") "\n") "\n"))))

(defn diff-lines
  "[:- s] / [:+ s] / [:= s] rows describing how `old` becomes `new`.
   Deletions are listed before the additions that replace them, which is the
   order every diff viewer uses."
  [old-text new-text]
  ;; Not `old`/`new`: a param named `new` shadows squint's `new` special form
  ;; and the typed-array construction below compiles to a call on a string.
  (let [a (split-lines old-text)
        b (split-lines new-text)
        n (count a)
        m (count b)]
    (if (> (+ n m) lcs-cap)
      (into (mapv (fn [s] [:- s]) a) (mapv (fn [s] [:+ s]) b))
      ;; t[i][j] = LCS length of a[i..] and b[j..], row-major with a sentinel
      ;; row and column so the boundary needs no branches.
      (let [w   (inc m)
            t   (new js/Uint32Array (* (inc n) w))
            at  (fn [i j] (aget t (+ (* i w) j)))]
        (loop [i (dec n)]
          (when (>= i 0)
            (loop [j (dec m)]
              (when (>= j 0)
                (aset t (+ (* i w) j)
                      (if (= (nth a i) (nth b j))
                        (inc (at (inc i) (inc j)))
                        (max (at (inc i) j) (at i (inc j)))))
                (recur (dec j))))
            (recur (dec i))))
        (loop [i 0 j 0 out []]
          (cond
            (and (< i n) (< j m) (= (nth a i) (nth b j)))
            (recur (inc i) (inc j) (conj out [:= (nth a i)]))

            (and (< i n) (or (>= j m) (>= (at (inc i) j) (at i (inc j)))))
            (recur (inc i) j (conj out [:- (nth a i)]))

            (< j m)
            (recur i (inc j) (conj out [:+ (nth b j)]))

            :else out))))))
